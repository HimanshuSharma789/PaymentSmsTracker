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

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH)

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

    /**
     * Income/Expense column: Income, Expense, Transfer-Out.
     * "Expenses" (plural) is treated as Expense.
     */
    fun toMoneyManagerType(transactionType: String): String = when (normalizeType(transactionType)) {
        "Income" -> "Income"
        "Transfer-Out" -> "Transfer-Out"
        else -> "Expense"
    }

    /** Collapse aliases / blanks to the three internal types. */
    fun normalizeType(transactionType: String): String {
        val key = transactionType.trim().lowercase()
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
        return when (key) {
            "income" -> "Income"
            "transferout", "transfer" -> "Transfer-Out"
            else -> "Expense"
        }
    }

    fun formatDate(timestamp: Long): String =
        dateFormat.format(Date(timestamp))

    fun expandTransaction(transaction: Transaction, config: AppConfig): List<MoneyManagerRow> {
        val date = formatDate(transaction.timestamp)
        val type = normalizeType(transaction.transactionType)
        val note = transaction.notes.orEmpty()
        val description = transaction.merchantName
        val account = transaction.account.ifBlank { "Cash" }
        val amount = BigDecimal.valueOf(transaction.amount).setScale(2, RoundingMode.HALF_UP)

        if (type != "Expense") {
            // Transfer-Out: Account=From, Category=To (MM account name), Subcategory empty.
            val transferTo = if (type == "Transfer-Out") {
                transaction.category.ifBlank { description }
            } else {
                transaction.category
            }
            return listOf(
                MoneyManagerRow(
                    date = date,
                    account = account,
                    category = transferTo,
                    subcategory = if (type == "Transfer-Out") "" else transaction.subcategory,
                    note = note,
                    amount = amount.toPlainString(),
                    incomeExpense = toMoneyManagerType(type),
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
                    incomeExpense = toMoneyManagerType("Expense"),
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
                    incomeExpense = toMoneyManagerType("Expense"),
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
                    incomeExpense = toMoneyManagerType("Expense"),
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
                    incomeExpense = toMoneyManagerType("Expense"),
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
