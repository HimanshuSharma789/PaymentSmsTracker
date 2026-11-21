package com.example.paymentsmstracker.sms

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction

/**
 * Payments to an account the user flagged "not spending" (card bills etc.) are Transfer-Out
 * (category = that account), not Expense.
 */
object TransferRules {
    private val FILLER = setOf("bank", "credit", "card", "cashback", "ac", "account")

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** True when [merchant] names [account]; credit-card accounts match on distinctive tokens + a card cue. */
    private fun names(merchant: String, account: String): Boolean {
        val m = words(merchant)
        val a = words(account)
        if (a.isEmpty()) return false
        if (!account.contains("credit", ignoreCase = true)) return m.contains(a)
        val tokens = a.split(" ").filter { it !in FILLER }
        val cue = m.contains("credit") || m.contains("card") || m.split(" ").contains("cc")
        return tokens.isNotEmpty() && cue && tokens.all { m.contains(it) }
    }

    /**
     * Destination account for a transfer, or null. Ties -> first account in list order.
     * Legacy fallback (kept from the old hardcoded rule): "credit" + hdfc/sbi in the merchant is a
     * transfer with a blank destination, unless the merchant names some configured account that
     * is flagged as spending (the user opted that one out).
     */
    fun detect(merchant: String, config: AppConfig): String? {
        val hits = config.accounts.filter { names(merchant, it) }
        hits.firstOrNull { a -> config.accountsNotSpending.any { it.equals(a, true) } }?.let { return it }
        if (hits.isEmpty() && merchant.contains("credit", true) &&
            (merchant.contains("hdfc", true) || merchant.contains("sbi", true))
        ) return ""
        return null
    }

    data class Plan(val changes: List<Transaction>, val skipped: Int)

    /**
     * Not-yet-exported Expense rows that [detect] turns into Transfer-Out (category = destination).
     * Rows with splits, or whose From account equals the destination, count as skipped.
     * Exported, Income and Transfer-Out rows are ignored silently.
     */
    fun plan(rows: List<Transaction>, config: AppConfig): Plan {
        val changes = mutableListOf<Transaction>()
        var skipped = 0
        for (t in rows) {
            if (t.reviewStatus == ReviewStatus.EXPORTED || t.transactionType != "Expense") continue
            val dest = detect(t.merchantName, config)?.ifEmpty { null } ?: continue
            if (t.participants.isNotBlank() || t.splitwiseGroupId > 0L || t.account.equals(dest, true)) {
                skipped++
                continue
            }
            changes += t.copy(transactionType = "Transfer-Out", isExpense = true, category = dest, subcategory = "")
        }
        return Plan(changes, skipped)
    }
}
