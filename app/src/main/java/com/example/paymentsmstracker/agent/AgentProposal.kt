package com.example.paymentsmstracker.agent

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.split.SplitAllocationResult
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import java.math.BigDecimal

/** The editable fields of the entry form. Amount, date and review status are never touched by AI. */
data class FormState(
    val merchantName: String = "",
    val transactionType: String = "Expense",
    val account: String = "",
    val category: String = "",
    val subcategory: String = "",
    val notes: String = "",
    val participants: List<String> = emptyList(),
    val splitMode: String = SplitMode.EQUAL,
    val splitAllocations: Map<String, BigDecimal> = emptyMap()
)

/** Model output as parsed. Untrusted: null = no change suggested. Only [validate] turns it into a [Suggestion]. */
data class RawProposal(
    val merchantName: String? = null,
    val transactionType: String? = null,
    val account: String? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val notes: String? = null,
    val participants: List<String>? = null,
    val splitMode: String? = null,
    val splitAllocations: Map<String, BigDecimal> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val needsAttention: Boolean = false,
    val reason: String = ""
)

data class Suggestion(
    val before: FormState,
    val after: FormState,
    /** Per-person shares from [SplitCalculator]; null when [after] has no split. */
    val split: SplitAllocationResult?,
    val warnings: List<String>,
    val needsAttention: Boolean,
    val reason: String
) {
    /** (label, before, after) for fields that change. */
    fun diff(): List<Triple<String, String, String>> = buildList {
        fun add(label: String, b: String, a: String) { if (b != a) add(Triple(label, b.ifBlank { "–" }, a.ifBlank { "–" })) }
        add("Merchant", before.merchantName, after.merchantName)
        add("Type", before.transactionType, after.transactionType)
        add("Account", before.account, after.account)
        add("Category", before.category, after.category)
        add("Subcategory", before.subcategory, after.subcategory)
        add("Notes", before.notes, after.notes)
        add("Split", splitLabel(before), splitLabel(after))
    }

    val hasChanges: Boolean get() = before != after

    /** Merchant/notes text written by the model: the one place SMS-borne prompt injection can land. */
    val changesFreeText: Boolean
        get() = before.merchantName != after.merchantName || before.notes != after.notes

    private fun splitLabel(s: FormState): String {
        if (s.participants.isEmpty()) return ""
        val people = s.participants.joinToString(", ") { name ->
            s.splitAllocations[name]?.let { "$name ${it.stripTrailingZeros().toPlainString()}" } ?: name
        }
        return "${SplitMode.label(s.splitMode)}: $people"
    }
}

private val TYPES = listOf("Expense", "Income", "Transfer-Out")

/**
 * Merge [this] onto [current], dropping anything not allowed by [config] or [memberLabels]
 * (the chip labels of the selected Splitwise group). Split math is checked by [SplitCalculator].
 */
fun RawProposal.validate(
    current: FormState,
    config: AppConfig,
    memberLabels: List<String>,
    amount: Double
): Suggestion {
    val warnings = warnings.take(5).map { it.take(160) }.toMutableList()
    fun pick(value: String?, allowed: Collection<String>, field: String): String? {
        if (value.isNullOrBlank()) return null
        return allowed.firstOrNull { it.equals(value.trim(), ignoreCase = true) }
            ?: null.also { warnings += "Ignored unknown $field \"$value\"" }
    }

    val type = pick(transactionType, TYPES, "type") ?: current.transactionType
    val transfer = type == "Transfer-Out"
    val account = pick(account, config.accounts, "account") ?: current.account

    val categoryOptions = when (type) {
        "Income" -> config.incomeCategories
        "Transfer-Out" -> config.accounts.filterNot { it.equals(account, ignoreCase = true) }
        else -> config.expenseCategories.keys
    }
    val category = (pick(category, categoryOptions, "category") ?: current.category)
        .takeIf { c -> categoryOptions.any { it.equals(c, ignoreCase = true) } }
        .orEmpty()
    val subOptions = if (type == "Expense") config.expenseCategories[category].orEmpty() else emptyList()
    val subcategory = (pick(subcategory, subOptions, "subcategory") ?: current.subcategory)
        .takeIf { it in subOptions }
        .orEmpty()

    var participants = current.participants
    var mode = current.splitMode
    var allocations = current.splitAllocations
    if (transfer) {
        participants = emptyList(); mode = SplitMode.EQUAL; allocations = emptyMap()
    } else if (this.participants != null) {
        val names = this.participants.map { it.trim() }.filter { it.isNotEmpty() }
        val unknown = names.filter { n -> memberLabels.none { it.equals(n, ignoreCase = true) } }
        val newMode = SplitMode.normalize(splitMode)
        if (unknown.isNotEmpty()) {
            warnings += if (memberLabels.isEmpty()) "Pick a Splitwise group before splitting"
            else "Split ignored, unknown: ${unknown.joinToString()}"
        } else if (newMode !in SplitMode.ALL) {
            warnings += "Split ignored, unknown mode \"$splitMode\""
        } else {
            val canonical = names.map { n -> memberLabels.first { it.equals(n, ignoreCase = true) } }.distinct()
            val canonicalAllocations = splitAllocations.mapNotNull { (k, v) ->
                canonical.firstOrNull { it.equals(k.trim(), ignoreCase = true) }?.let { it to v }
            }.toMap()
            val check = SplitCalculator.resolve(amount, canonical, newMode, canonicalAllocations)
            if (canonical.isNotEmpty() && !check.isValid) {
                warnings += "Split ignored: ${check.error}"
            } else {
                participants = canonical
                mode = if (canonical.isEmpty()) SplitMode.EQUAL else newMode
                allocations = if (mode == SplitMode.EQUAL) emptyMap() else canonicalAllocations
            }
        }
    }

    val after = FormState(
        merchantName = merchantName?.trim()?.take(80)?.ifBlank { null } ?: current.merchantName,
        transactionType = type,
        account = account,
        category = category,
        subcategory = subcategory,
        notes = notes?.trim()?.take(200) ?: current.notes,
        participants = participants,
        splitMode = mode,
        splitAllocations = allocations
    )
    val split = if (after.participants.isEmpty()) null
    else SplitCalculator.resolve(amount, after.participants, after.splitMode, after.splitAllocations)
    return Suggestion(current, after, split, warnings, needsAttention, reason.take(200))
}
