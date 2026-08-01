package com.example.paymentsmstracker.export

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MoneyManagerRow(
    val date: String,
    val account: String,
    val category: String,
    val subcategory: String,
    val note: String,
    val amount: String,
    val incomeExpense: String,
    val description: String
)

object MoneyManagerExporter {

    val HEADERS = listOf(
        "Date",
        "Account",
        "Category",
        "Subcategory",
        "Note",
        "Amount",
        "Income/Expense",
        "Description"
    )

    fun expandTransaction(transaction: Transaction, config: AppConfig): List<MoneyManagerRow> {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
            .format(Date(transaction.timestamp))
        val type = transaction.transactionType.ifBlank { "Expense" }
        val note = transaction.notes.orEmpty()
        val description = transaction.merchantName
        val account = transaction.account.ifBlank { "Cash" }
        val amount = BigDecimal.valueOf(transaction.amount).setScale(2, RoundingMode.HALF_UP)

        if (type != "Expense") {
            return listOf(
                MoneyManagerRow(
                    date = date,
                    account = account,
                    category = if (type == "Transfer-Out") {
                        transaction.category.ifBlank { description }
                    } else {
                        transaction.category
                    },
                    subcategory = if (type == "Transfer-Out") "" else transaction.subcategory,
                    note = note,
                    amount = amount.toPlainString(),
                    incomeExpense = type,
                    description = description
                )
            )
        }

        val participants = transaction.participantList()
        if (participants.isEmpty() || (participants.size == 1 && participants.first().equals("ME", true))) {
            return listOf(
                MoneyManagerRow(
                    date = date,
                    account = account,
                    category = transaction.category.ifBlank { "Other" },
                    subcategory = transaction.subcategory,
                    note = note,
                    amount = amount.toPlainString(),
                    incomeExpense = "Expense",
                    description = description
                )
            )
        }

        // Full amount as own MM expense; split metadata still used by Splitwise / split table.
        if (config.mmFullSplitAsOwnExpense) {
            return listOf(
                MoneyManagerRow(
                    date = date,
                    account = account,
                    category = transaction.category.ifBlank { "Other" },
                    subcategory = transaction.subcategory,
                    note = buildString {
                        append(note)
                        if (isNotEmpty()) append(" | ")
                        append("Split: ${participants.joinToString(",")}")
                    },
                    amount = amount.toPlainString(),
                    incomeExpense = "Expense",
                    description = description
                )
            )
        }

        val split = SplitCalculator.resolve(transaction)
        val personalShare = if (split.isValid) split.myShare else {
            // Fall back to equal if stored allocations are invalid.
            amount.divide(BigDecimal(participants.size), 2, RoundingMode.HALF_UP)
                .takeIf { participants.any { it.equals("ME", true) } }
                ?: BigDecimal.ZERO.setScale(2)
        }
        val debtShare = amount.subtract(personalShare)

        val rows = mutableListOf<MoneyManagerRow>()
        if (personalShare > BigDecimal.ZERO) {
            rows.add(
                MoneyManagerRow(
                    date = date,
                    account = account,
                    category = transaction.category.ifBlank { "Other" },
                    subcategory = transaction.subcategory,
                    note = note,
                    amount = personalShare.toPlainString(),
                    incomeExpense = "Expense",
                    description = description
                )
            )
        }
        if (debtShare > BigDecimal.ZERO) {
            rows.add(
                MoneyManagerRow(
                    date = date,
                    account = account,
                    category = config.debtCategory,
                    subcategory = config.debtSubcategory,
                    note = buildString {
                        append(note)
                        if (isNotEmpty()) append(" | ")
                        append("Split: ${participants.joinToString(",")}")
                    },
                    amount = debtShare.toPlainString(),
                    incomeExpense = "Expense",
                    description = description
                )
            )
        }
        return rows
    }

    fun toTsv(transactions: List<Transaction>, config: AppConfig): String {
        val rows = transactions.flatMap { expandTransaction(it, config) }
            .sortedWith(
                compareBy(
                    { it.date },
                    { it.account },
                    { it.description },
                    { it.category }
                )
            )
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString("\t")).append('\n')
        rows.forEach { row ->
            sb.append(
                listOf(
                    row.date,
                    row.account,
                    row.category,
                    row.subcategory,
                    escape(row.note),
                    row.amount,
                    row.incomeExpense,
                    escape(row.description)
                ).joinToString("\t")
            ).append('\n')
        }
        return sb.toString()
    }

    fun splitPreview(amount: Double, participants: List<String>, config: AppConfig): String {
        if (participants.isEmpty()) return "Full ₹${fmt(amount)} as your expense"
        val total = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
        if (config.mmFullSplitAsOwnExpense) {
            return "MM: full ₹${total.toPlainString()} as your expense (split kept for ledger)"
        }
        val equal = SplitCalculator.resolve(amount, participants, "equal", emptyMap())
        return splitPreviewFromShares(equal.myShare, equal.friendsOwe, config)
    }

    fun splitPreviewFromShares(
        personal: BigDecimal,
        debt: BigDecimal,
        config: AppConfig
    ): String {
        if (config.mmFullSplitAsOwnExpense) {
            val total = personal.add(debt)
            return "MM: full ₹${total.toPlainString()} as your expense (split kept for ledger)"
        }
        return buildString {
            if (personal > BigDecimal.ZERO) {
                append("You: ₹${personal.toPlainString()}")
            }
            if (debt > BigDecimal.ZERO) {
                if (isNotEmpty()) append(" · ")
                append("Dues (${config.debtCategory}/${config.debtSubcategory}): ₹${debt.toPlainString()}")
            }
            if (isEmpty()) append("No rows")
        }
    }

    private fun escape(value: String): String =
        value.replace("\t", " ").replace("\n", " ").replace("\r", " ")

    private fun fmt(amount: Double): String =
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString()
}
