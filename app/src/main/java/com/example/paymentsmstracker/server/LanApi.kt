package com.example.paymentsmstracker.server

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

class LanApi(
    private val db: AppDatabase,
    private val configStore: ConfigStore
) {
    private val dao get() = db.transactionDao()

    fun statusJson(): JSONObject {
        val s = LanServerState.get()
        return JSONObject()
            .put("running", s.running)
            .put("baseUrl", s.baseUrl)
            .put("port", s.port)
            .put("durationMinutes", s.durationMinutes)
            .put("expiresAtMs", s.expiresAtMs)
            .put("remainingMs", s.remainingMs)
            .put("accessCodeRequired", s.accessCodeRequired)
    }

    fun metaJson(): JSONObject {
        val config = configStore.load()
        val categories = JSONObject()
        config.expenseCategories.forEach { (k, v) ->
            categories.put(k, JSONArray(v))
        }
        return JSONObject()
            .put("accounts", JSONArray(config.accounts))
            .put("participants", JSONArray(config.participants))
            .put("expenseCategories", categories)
            .put("incomeCategories", JSONArray(config.incomeCategories))
            .put("splitModes", JSONArray(SplitMode.ALL))
    }

    suspend fun listTransactions(status: String?): JSONArray {
        val rows = when (status?.lowercase()) {
            "needs_review", "needs-review" ->
                dao.getTransactionsByStatusList(ReviewStatus.NEEDS_REVIEW)
            "reviewed" ->
                dao.getTransactionsByStatusList(ReviewStatus.REVIEWED)
            "exported", "mm_exported" ->
                dao.getTransactionsByStatusList(ReviewStatus.EXPORTED)
            else -> dao.getAllTransactionsList()
        }
        return JSONArray().also { arr ->
            rows.forEach { arr.put(toJson(it)) }
        }
    }

    suspend fun getTransaction(id: Long): JSONObject? {
        val tx = dao.getTransactionById(id) ?: return null
        return toJson(tx)
    }

    suspend fun createTransaction(body: JSONObject): Pair<Int, JSONObject> {
        val config = configStore.load()
        val parsed = parseBody(body, existing = null, config)
        if (parsed.error != null) {
            return 400 to JSONObject().put("error", parsed.error)
        }
        val id = dao.insertTransaction(parsed.transaction!!)
        val saved = dao.getTransactionById(id) ?: parsed.transaction
        return 201 to toJson(saved)
    }

    suspend fun updateTransaction(id: Long, body: JSONObject): Pair<Int, JSONObject> {
        val existing = dao.getTransactionById(id)
            ?: return 404 to JSONObject().put("error", "Not found")
        val config = configStore.load()
        val parsed = parseBody(body, existing = existing, config)
        if (parsed.error != null) {
            return 400 to JSONObject().put("error", parsed.error)
        }
        dao.updateTransaction(parsed.transaction!!.copy(id = id))
        val saved = dao.getTransactionById(id) ?: parsed.transaction
        return 200 to toJson(saved)
    }

    suspend fun markReviewed(id: Long): Pair<Int, JSONObject> {
        val existing = dao.getTransactionById(id)
            ?: return 404 to JSONObject().put("error", "Not found")
        val updated = existing.copy(
            reviewStatus = ReviewStatus.REVIEWED,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        dao.updateTransaction(updated)
        return 200 to toJson(updated)
    }

    suspend fun deleteTransaction(id: Long): Pair<Int, JSONObject> {
        val existing = dao.getTransactionById(id)
            ?: return 404 to JSONObject().put("error", "Not found")
        dao.deleteTransaction(existing)
        return 200 to JSONObject().put("ok", true)
    }

    suspend fun bulkAction(body: JSONObject): Pair<Int, JSONObject> {
        val action = body.optString("action").trim().lowercase()
        val idsJson = body.optJSONArray("ids")
            ?: return 400 to JSONObject().put("error", "ids required")
        val ids = buildList {
            for (i in 0 until idsJson.length()) {
                val id = idsJson.optLong(i, -1L)
                if (id > 0) add(id)
            }
        }.distinct()
        if (ids.isEmpty()) {
            return 400 to JSONObject().put("error", "No valid ids")
        }
        val now = System.currentTimeMillis()
        return when (action) {
            "review", "mark_reviewed", "reviewed" -> {
                dao.updateStatusForIds(ids, ReviewStatus.REVIEWED, now)
                200 to JSONObject().put("ok", true).put("count", ids.size).put("action", "review")
            }
            "delete" -> {
                val deleted = dao.deleteTransactionsByIds(ids)
                200 to JSONObject().put("ok", true).put("count", deleted).put("action", "delete")
            }
            else -> 400 to JSONObject().put("error", "Unknown action. Use review or delete.")
        }
    }

    private data class Parsed(
        val transaction: Transaction? = null,
        val error: String? = null
    )

    private fun parseBody(
        body: JSONObject,
        existing: Transaction?,
        config: AppConfig
    ): Parsed {
        val amount = body.optDouble("amount", existing?.amount ?: Double.NaN)
        if (amount.isNaN() || amount <= 0.0) {
            return Parsed(error = "Amount must be greater than 0")
        }
        val account = body.optString("account", existing?.account.orEmpty()).trim()
            .ifBlank { config.accounts.firstOrNull().orEmpty() }
        val category = body.optString("category", existing?.category.orEmpty()).trim()
        val notesRaw = if (body.has("notes")) body.optString("notes").trim() else existing?.notes
        val notes = notesRaw?.ifBlank { null }
        val timestamp = body.optLong("timestamp", existing?.timestamp ?: System.currentTimeMillis())
        val transactionType = body.optString(
            "transactionType",
            existing?.transactionType ?: "Expense"
        ).ifBlank { "Expense" }
        val isTransfer = transactionType == "Transfer-Out"
        val reviewStatus = body.optString(
            "reviewStatus",
            existing?.reviewStatus ?: ReviewStatus.NEEDS_REVIEW
        ).ifBlank { ReviewStatus.NEEDS_REVIEW }

        if (isTransfer) {
            if (account.isBlank()) {
                return Parsed(error = "Choose From account")
            }
            if (category.isBlank()) {
                return Parsed(error = "Choose To account")
            }
            if (account.equals(category, ignoreCase = true)) {
                return Parsed(error = "From and To must be different")
            }
        }

        var merchant = body.optString("merchantName", existing?.merchantName.orEmpty()).trim()
        if (merchant.isEmpty()) {
            if (isTransfer) {
                merchant = "Transfer to $category"
            } else {
                return Parsed(error = "Merchant is required")
            }
        }
        val subcategory = if (isTransfer) {
            ""
        } else {
            body.optString("subcategory", existing?.subcategory.orEmpty()).trim()
        }

        val participants = if (isTransfer) {
            emptyList()
        } else {
            when {
                body.has("participants") -> {
                    when (val raw = body.get("participants")) {
                        is JSONArray -> buildList {
                            for (i in 0 until raw.length()) {
                                val v = raw.optString(i).trim()
                                if (v.isNotEmpty()) add(v)
                            }
                        }
                        else -> body.optString("participants")
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    }
                }
                else -> existing?.participantList().orEmpty()
            }
        }

        val splitMode = if (isTransfer) {
            SplitMode.EQUAL
        } else {
            SplitMode.normalize(
                body.optString("splitMode", existing?.splitMode ?: SplitMode.EQUAL)
            )
        }
        val allocations = if (isTransfer) {
            emptyMap()
        } else {
            when {
                body.has("splitAllocations") -> parseAllocationMap(body.get("splitAllocations"))
                else -> SplitCalculator.parseAllocations(existing?.splitAllocations)
            }
        }

        if (participants.isNotEmpty() && splitMode != SplitMode.EQUAL) {
            val result = SplitCalculator.resolve(amount, participants, splitMode, allocations)
            if (!result.isValid) {
                return Parsed(error = result.error)
            }
        }

        val tx = Transaction(
            id = existing?.id ?: 0L,
            amount = amount,
            merchantName = merchant,
            category = category,
            subcategory = subcategory,
            account = account,
            notes = notes,
            timestamp = timestamp,
            originalSms = existing?.originalSms,
            isExpense = transactionType != "Income",
            referenceNumber = existing?.referenceNumber,
            participants = participants.joinToString(","),
            splitMode = if (participants.isEmpty()) SplitMode.EQUAL else splitMode,
            splitAllocations = if (participants.isEmpty() || splitMode == SplitMode.EQUAL) {
                ""
            } else {
                SplitCalculator.encodeAllocations(allocations)
            },
            reviewStatus = reviewStatus,
            transactionType = transactionType,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        return Parsed(transaction = tx)
    }

    private fun parseAllocationMap(raw: Any): Map<String, BigDecimal> {
        return when (raw) {
            is JSONObject -> buildMap {
                raw.keys().forEach { key ->
                    val value = raw.optString(key).toBigDecimalOrNull()
                        ?: raw.optDouble(key).takeIf { !it.isNaN() }?.toBigDecimal()
                    if (value != null) put(key, value)
                }
            }
            is String -> SplitCalculator.parseAllocations(raw)
            else -> emptyMap()
        }
    }

    private fun toJson(tx: Transaction): JSONObject {
        val split = SplitCalculator.resolve(tx)
        return JSONObject()
            .put("id", tx.id)
            .put("amount", tx.amount)
            .put("merchantName", tx.merchantName)
            .put("category", tx.category)
            .put("subcategory", tx.subcategory)
            .put("account", tx.account)
            .put("notes", tx.notes ?: "")
            .put("timestamp", tx.timestamp)
            .put("participants", JSONArray(tx.participantList()))
            .put("splitMode", tx.splitMode)
            .put("splitAllocations", tx.splitAllocations)
            .put("reviewStatus", tx.reviewStatus)
            .put("transactionType", tx.transactionType)
            .put("referenceNumber", tx.referenceNumber ?: "")
            .put("originalSms", tx.originalSms ?: "")
            .put(
                "splitPreview",
                if (split.isValid) {
                    JSONObject()
                        .put("myShare", split.myShare.toPlainString())
                        .put("friendsOwe", split.friendsOwe.toPlainString())
                } else {
                    JSONObject.NULL
                }
            )
    }
}
