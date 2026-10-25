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

    // SimpleDateFormat is not thread-safe; exports run on IO threads.
    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH) }

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
        dateFormat.get()!!.format(Date(timestamp))

    /** Why [transaction] must not go to MM yet, or null when it can be exported. */
    fun exportBlocker(transaction: Transaction, config: AppConfig): String? {
        if (normalizeType(transaction.transactionType) != "Expense" || config.mmFullSplitAsOwnExpense) return null
        if (!SplitwiseExporter.isSplit(transaction)) return null
        return SplitCalculator.resolve(transaction).error
    }

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

        // MoneyManagerSync refuses invalid splits; the equal fallback is only a safety net.
        val personalShare = SplitCalculator.resolveOrEqual(transaction).myShare
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
        // Sort by real time: the MM/dd/yyyy string would put 01/02/2026 before 12/30/2025.
        val rows = transactions.sortedBy { it.timestamp }
            .flatMap { expandTransaction(it, config) }
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString("\t")).append('\n')
        rows.forEach { row ->
            sb.append(
                listOf(
                    row.date,
                    row.account,
                    row.category,
                    row.subcategory,
                    row.note,
                    row.amount,
                    row.incomeExpense,
                    row.description
                ).joinToString("\t") { escape(it) }
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
