package com.example.paymentsmstracker.agent

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.SplitwiseExpenseIds
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.merchant.MerchantMemory
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.splitwise.SplitwiseAuthState
import com.example.paymentsmstracker.splitwise.SplitwiseMemberLabels
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** One diff card. [tx] is the snapshot it was built on; Apply refuses if the row changed since. */
data class Proposal(
    val key: Long,
    val tx: Transaction,
    val suggestion: Suggestion,
    val markReviewed: Boolean,
    val groupId: Long,
    val labelToId: Map<String, Long>
)

/** Read-only tools plus `propose_update`, which only creates cards. Nothing here writes transactions. */
class AgentTools(
    private val db: AppDatabase,
    private val config: AppConfig,
    private val privacy: AgentPrivacy,
    private val auth: SplitwiseAuthState
) {
    /** Cards created so far in this request, in order. */
    val proposals = mutableListOf<Proposal>()

    val specs: List<ToolSpec> = buildList {
        val p = privacy
        if (p.history) {
            add(ToolSpec("search_transactions", "Find transactions, newest first.",
                obj(filterProps().put("limit", int("max 30, default 20")))))
            add(ToolSpec("get_transaction", "One transaction by id.",
                obj(JSONObject().put("id", int(null)), "id")))
        }
        if (p.merchantMemory) {
            add(ToolSpec("merchant_hint", "Category/account the user last chose for this merchant.",
                obj(JSONObject().put("merchant", str(null)), "merchant")))
        }
        if (p.amounts) {
            add(ToolSpec("summarize", "Count and total of matching transactions, computed by the app. Use this for any money totals.",
                obj(filterProps().put("group_by", enum("category", "merchant", "account", "none")))))
        }
        if (p.friends && p.amounts) {
            add(ToolSpec("friend_shares", "Per-person amounts friends owe the user from split transactions, computed by the app.",
                obj(dateProps().put("pending_splitwise_only", JSONObject().put("type", "boolean")))))
        }
        add(ToolSpec("propose_update",
            "Propose changes to one or more transactions. Shows the user a diff card per id to Apply or Skip; never writes by itself. " +
                "changes: only fields to change. mark_reviewed: also mark them reviewed.",
            obj(JSONObject()
                .put("ids", JSONObject().put("type", "array").put("items", int(null)))
                .put("changes", JSONObject().put("type", "object").put("properties", AgentClient.PROPOSE_FIELDS_SCHEMA))
                .put("mark_reviewed", JSONObject().put("type", "boolean"))
                .put("reason", str("one short sentence")),
                "ids", "changes", "reason")))
    }

    suspend fun execute(call: ToolCall): String = runCatching {
        if (specs.none { it.name == call.name }) error("unknown or disabled tool ${call.name}")
        val input = call.input
        when (call.name) {
            "search_transactions" -> {
                val matched = matching(input)
                val limit = input.optInt("limit", 20).coerceIn(1, 30)
                JSONObject()
                    .put("matched", matched.size)
                    .put("transactions", JSONArray(matched.take(limit).map { AgentData.txJson(it, privacy) }))
                    .toString()
            }
            "get_transaction" -> db.transactionDao().getTransactionById(input.optLong("id"))
                ?.let { AgentData.txJson(it, privacy).toString() } ?: "not found"
            "merchant_hint" -> MerchantMemory.lookup(db, input.optString("merchant"))?.let {
                JSONObject().put("category", it.category).put("subcategory", it.subcategory)
                    .put("account", it.account).put("transactionType", it.transactionType).toString()
            } ?: "no memory for this merchant"
            "summarize" -> summarize(matching(input), input.optString("group_by"))
            "friend_shares" -> friendShares(input)
            "propose_update" -> proposeUpdate(input)
            else -> error("unknown tool ${call.name}")
        }
    }.getOrElse { "error: ${it.message}" }

    private suspend fun matching(input: JSONObject): List<Transaction> {
        val f = Filter(input, privacy)
        return db.transactionDao().getAllTransactionsList().filter(f::matches)
    }

    private fun summarize(txs: List<Transaction>, groupBy: String): String {
        val key: (Transaction) -> String = when {
            groupBy == "category" && privacy.categories -> { t -> t.category.ifBlank { "(none)" } }
            groupBy == "account" && privacy.categories -> { t -> t.account.ifBlank { "(none)" } }
            groupBy == "merchant" && privacy.merchants -> { t -> t.merchantName }
            else -> { _ -> "" }
        }
        fun total(list: List<Transaction>) = money(list.fold(BigDecimal.ZERO) { a, t -> a + BigDecimal.valueOf(t.amount) })
        val out = JSONObject().put("count", txs.size).put("sum", total(txs))
        val groups = txs.groupBy(key)
        if (groups.keys != setOf("") && txs.isNotEmpty()) {
            out.put("groups", JSONArray(groups.entries.sortedByDescending { it.value.sumOf { t -> t.amount } }.take(40).map { (k, v) ->
                JSONObject().put("key", k).put("count", v.size).put("sum", total(v))
            }))
        }
        return out.toString()
    }

    private suspend fun friendShares(input: JSONObject): String {
        val f = Filter(input, privacy)
        val pendingOnly = input.optBoolean("pending_splitwise_only")
        val owed = linkedMapOf<String, BigDecimal>()
        var count = 0
        var invalid = 0
        db.transactionDao().getAllTransactionsList()
            .filter { f.matches(it) && it.participantList().any { p -> !p.equals("ME", ignoreCase = true) } }
            .filter { MoneyManagerExporter.normalizeType(it.transactionType) != "Transfer-Out" }
            .filter { !pendingOnly || !SplitwiseExpenseIds.isDone(it.splitwiseExpenseId) }
            .forEach { tx ->
                val split = SplitCalculator.resolve(tx)
                if (!split.isValid) { invalid++; return@forEach }
                count++
                split.amounts.filterKeys { !it.equals("ME", ignoreCase = true) }
                    .forEach { (name, amount) -> owed[name] = (owed[name] ?: BigDecimal.ZERO) + amount }
            }
        return JSONObject()
            .put("transactions", count)
            .put("skipped_invalid_splits", invalid)
            .put("owed_to_user", JSONObject(owed.mapValues { money(it.value) }))
            .put("total", money(owed.values.fold(BigDecimal.ZERO, BigDecimal::add)))
            .toString()
    }

    private suspend fun proposeUpdate(input: JSONObject): String {
        val ids = input.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty().distinct()
        if (ids.isEmpty()) return "error: ids is empty"
        val changes = input.optJSONObject("changes") ?: JSONObject()
        val reason = input.optString("reason").takeUnless { input.isNull("reason") }.orEmpty()
        val wantReviewed = input.optBoolean("mark_reviewed")
        val raw = AgentClient.parseProposal(changes).let { it.copy(reason = reason.ifBlank { it.reason }) }
        return ids.map { id ->
            if (proposals.size >= MAX_PROPOSALS) return@map "id $id: rejected, limit of $MAX_PROPOSALS cards per request"
            val tx = db.transactionDao().getTransactionById(id) ?: return@map "id $id: rejected, not found"
            val groupId = tx.splitwiseGroupId.takeIf { it > 0L } ?: auth.defaultGroupId
            val labels = auth.groupById(groupId)
                ?.let { SplitwiseMemberLabels.labelsFor(it.members, auth.currentUserId) }.orEmpty()
            var s = raw.validate(tx.toFormState(labels), config, labels.map { it.first }, tx.amount)
            var markReviewed = wantReviewed && tx.reviewStatus == ReviewStatus.NEEDS_REVIEW
            if (markReviewed && (s.after.category.isBlank() || s.after.account.isBlank())) {
                markReviewed = false
                s = s.copy(warnings = s.warnings + "Not marked reviewed: needs a category and an account")
            }
            val warnings = if (s.warnings.isEmpty()) "" else "; warnings: ${s.warnings.joinToString(" | ")}"
            if (!s.hasChanges && !markReviewed) return@map "id $id: rejected, nothing to change$warnings"
            proposals += Proposal(nextKey.incrementAndGet(), tx, s, markReviewed, groupId, labels.toMap())
            val what = s.diff().joinToString(", ") { "${it.first} ${it.second} -> ${it.third}" }
                .let { if (markReviewed) "$it, mark reviewed".trimStart(',', ' ') else it }
            "id $id: card shown ($what)$warnings"
        }.joinToString("\n")
    }

    private class Filter(o: JSONObject, p: AgentPrivacy) {
        private val status = o.text("status")
        private val type = o.text("transaction_type")?.let(MoneyManagerExporter::normalizeType)
        private val merchant = o.text("merchant_contains").takeIf { p.merchants }
        private val category = o.text("category").takeIf { p.categories }
        private val from = o.text("from_date")?.takeIf { p.dates }?.let { day(it) }
        private val to = o.text("to_date")?.takeIf { p.dates }?.let { day(it) }?.plus(DAY_MS)
        private val min = o.number("min_amount").takeIf { p.amounts }
        private val max = o.number("max_amount").takeIf { p.amounts }

        fun matches(t: Transaction): Boolean =
            (status == null || t.reviewStatus == status) &&
                (type == null || MoneyManagerExporter.normalizeType(t.transactionType) == type) &&
                (merchant == null || t.merchantName.contains(merchant, ignoreCase = true)) &&
                (category == null || t.category.equals(category, ignoreCase = true)) &&
                (from == null || t.timestamp >= from) &&
                (to == null || t.timestamp < to) &&
                (min == null || t.amount >= min) &&
                (max == null || t.amount <= max)

        private fun day(s: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)?.time
            ?: error("bad date $s, use yyyy-MM-dd")

        private fun JSONObject.text(k: String) = if (isNull(k)) null else optString(k).trim().ifEmpty { null }
        private fun JSONObject.number(k: String) = if (isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }
    }

    private fun filterProps(): JSONObject {
        val o = dateProps()
            .put("status", enum(ReviewStatus.NEEDS_REVIEW, ReviewStatus.REVIEWED, ReviewStatus.EXPORTED))
            .put("transaction_type", enum("Expense", "Income", "Transfer-Out"))
        if (privacy.merchants) o.put("merchant_contains", str(null))
        if (privacy.categories) o.put("category", str(null))
        if (privacy.amounts) o.put("min_amount", JSONObject().put("type", "number")).put("max_amount", JSONObject().put("type", "number"))
        return o
    }

    private fun dateProps(): JSONObject {
        val o = JSONObject()
        if (privacy.dates) o.put("from_date", str("yyyy-MM-dd, inclusive")).put("to_date", str("yyyy-MM-dd, inclusive"))
        return o
    }

    companion object {
        const val MAX_PROPOSALS = 50
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val nextKey = AtomicLong()

        private fun money(v: BigDecimal): Double = v.setScale(2, RoundingMode.HALF_UP).toDouble()
        private fun str(description: String?) = JSONObject().put("type", "string").also { o -> description?.let { o.put("description", it) } }
        private fun int(description: String?) = JSONObject().put("type", "integer").also { o -> description?.let { o.put("description", it) } }
        private fun enum(vararg values: String) = JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))
        private fun obj(props: JSONObject, vararg required: String) = JSONObject().put("type", "object").put("properties", props)
            .also { if (required.isNotEmpty()) it.put("required", JSONArray(required.toList())) }
    }
}
