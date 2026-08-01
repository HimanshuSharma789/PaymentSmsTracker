package com.example.paymentsmstracker.export

import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Splitwise-friendly CSV: uses stored allocations when present; equal shares otherwise.
 * Paid by = ME.
 */
object SplitwiseExporter {

    fun isSplit(transaction: Transaction): Boolean {
        val people = transaction.participantList()
        return people.size >= 2 || (people.size == 1 && !people.first().equals("ME", true))
    }

    fun toCsv(transactions: List<Transaction>, onlySplits: Boolean = true): String {
        val rows = transactions.filter { !onlySplits || isSplit(it) }
        val people = linkedSetOf<String>()
        rows.forEach { people.addAll(it.participantList()) }
        if (people.isEmpty()) people.add("ME")

        val headers = mutableListOf(
            "Date", "Description", "Category", "Cost", "Currency", "Paid by"
        ) + people.toList()

        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { q(it) }).append('\n')

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        rows.forEach { tx ->
            val participants = tx.participantList().ifEmpty { listOf("ME") }
            val total = BigDecimal.valueOf(tx.amount).setScale(2, RoundingMode.HALF_UP)
            val split = SplitCalculator.resolve(tx)
            val amountMap = if (split.isValid && split.amounts.isNotEmpty()) {
                split.amounts
            } else {
                val share = total.divide(BigDecimal(participants.size), 2, RoundingMode.HALF_UP)
                participants.associateWith { share }
            }

            val cols = mutableListOf(
                dateFmt.format(Date(tx.timestamp)),
                tx.merchantName,
                listOf(tx.category, tx.subcategory).filter { it.isNotBlank() }.joinToString(" / "),
                total.toPlainString(),
                "INR",
                "ME"
            )
            people.forEach { name ->
                val amount = amountMap.entries
                    .firstOrNull { it.key.equals(name, true) }
                    ?.value
                    ?: BigDecimal.ZERO.setScale(2)
                cols.add(amount.toPlainString())
            }
            sb.append(cols.joinToString(",") { q(it) }).append('\n')
        }
        return sb.toString()
    }

    /** Human-readable split ledger for archive / review */
    fun toSplitLedgerCsv(transactions: List<Transaction>): String {
        val header =
            "Date,Merchant,Total,SplitMode,Participants,MyShare,FriendsOwe,Category,Account,Status,Notes,Reference\n"
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val body = transactions.filter { isSplit(it) }.joinToString("\n") { tx ->
            val people = tx.participantList()
            val total = BigDecimal.valueOf(tx.amount).setScale(2, RoundingMode.HALF_UP)
            val split = SplitCalculator.resolve(tx)
            val myShare = if (split.isValid) split.myShare else {
                val share = total.divide(BigDecimal(people.size.coerceAtLeast(1)), 2, RoundingMode.HALF_UP)
                if (people.any { it.equals("ME", true) }) share else BigDecimal.ZERO.setScale(2)
            }
            val friendsOwe = if (split.isValid) split.friendsOwe else total.subtract(myShare)
            listOf(
                dateFmt.format(Date(tx.timestamp)),
                q(tx.merchantName),
                total.toPlainString(),
                q(SplitMode.label(tx.splitMode)),
                q(people.joinToString("|")),
                myShare.toPlainString(),
                friendsOwe.toPlainString(),
                q(listOf(tx.category, tx.subcategory).filter { it.isNotBlank() }.joinToString(" / ")),
                q(tx.account),
                q(tx.reviewStatus),
                q(tx.notes.orEmpty()),
                q(tx.referenceNumber.orEmpty())
            ).joinToString(",")
        }
        return header + body + if (body.isNotEmpty()) "\n" else ""
    }

    private fun q(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
