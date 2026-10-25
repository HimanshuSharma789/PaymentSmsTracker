package com.example.paymentsmstracker.list

import com.example.paymentsmstracker.data.Transaction
import java.math.BigDecimal

enum class TransactionSort { NEWEST, OLDEST, AMOUNT_HIGH, AMOUNT_LOW, MERCHANT }

/** Pure filter + sort for the Transactions list. All sorts are stable (ties keep input order). */
object TransactionQuery {

    fun apply(list: List<Transaction>, query: String, sort: TransactionSort): List<Transaction> {
        val q = query.trim()
        val rows = if (q.isEmpty()) list else list.filter { matches(it, q) }
        return when (sort) {
            TransactionSort.NEWEST -> rows.sortedByDescending { it.timestamp }
            TransactionSort.OLDEST -> rows.sortedBy { it.timestamp }
            TransactionSort.AMOUNT_HIGH -> rows.sortedByDescending { it.amount }
            TransactionSort.AMOUNT_LOW -> rows.sortedBy { it.amount }
            TransactionSort.MERCHANT -> rows.sortedBy { it.merchantName.lowercase() }
        }
    }

    private fun matches(t: Transaction, q: String): Boolean {
        val text = listOf(t.merchantName, t.notes, t.category, t.subcategory, t.account, t.originalSms)
        if (text.any { it?.contains(q, ignoreCase = true) == true }) return true
        val digits = q.replace(",", "").replace("₹", "").trim()
        if (digits.isEmpty()) return false
        return amountText(t.amount).contains(digits)
    }

    private fun amountText(amount: Double): String =
        BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()
}
