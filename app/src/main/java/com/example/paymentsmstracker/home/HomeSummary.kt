package com.example.paymentsmstracker.home

import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.split.SplitCalculator
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Numbers for the Mono home screen. Spend counts only your share of a split bill, like Money Manager. */
data class HomeSummary(
    val monthSpent: BigDecimal,
    /** Spend in the previous month up to the same day of month. */
    val lastMonthToDate: BigDecimal,
    /** Oldest first; the last entry is today. */
    val lastSevenDays: List<DaySpend>,
    val readyCount: Int,
    val readyTotal: BigDecimal
)

data class DaySpend(val date: LocalDate, val amount: BigDecimal)

object HomeSummaryCalculator {

    fun summarize(
        transactions: List<Transaction>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): HomeSummary {
        val byDay = HashMap<LocalDate, BigDecimal>()
        var ready = 0
        var readyTotal = BigDecimal.ZERO
        for (tx in transactions) {
            if (tx.reviewStatus == ReviewStatus.REVIEWED) {
                ready++
                readyTotal += myShare(tx)
            }
            if (!isSpend(tx)) continue
            val day = Instant.ofEpochMilli(tx.timestamp).atZone(zone).toLocalDate()
            byDay[day] = (byDay[day] ?: BigDecimal.ZERO) + myShare(tx)
        }

        val monthStart = today.withDayOfMonth(1)
        val lastMonthStart = monthStart.minusMonths(1)
        // Same day last month, clamped (31 Mar -> 28/29 Feb).
        val lastMonthCutoff = lastMonthStart.withDayOfMonth(
            minOf(today.dayOfMonth, lastMonthStart.lengthOfMonth())
        )
        var month = BigDecimal.ZERO
        var lastMonth = BigDecimal.ZERO
        for ((day, amount) in byDay) {
            if (!day.isBefore(monthStart) && !day.isAfter(today)) month += amount
            if (!day.isBefore(lastMonthStart) && !day.isAfter(lastMonthCutoff)) lastMonth += amount
        }

        val week = (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            DaySpend(d, byDay[d] ?: BigDecimal.ZERO)
        }
        return HomeSummary(month, lastMonth, week, ready, readyTotal)
    }

    fun isSpend(tx: Transaction): Boolean =
        tx.isExpense && MoneyManagerExporter.normalizeType(tx.transactionType) == "Expense"

    private fun myShare(tx: Transaction): BigDecimal = SplitCalculator.resolveOrEqual(tx).myShare
}

/** One-tap "looks right" from Home: only when the draft would pass the full review screen's checks. */
object QuickReview {

    fun canConfirm(tx: Transaction): Boolean {
        if (tx.reviewStatus != ReviewStatus.NEEDS_REVIEW) return false
        if (tx.amount <= 0 || tx.account.isBlank() || tx.merchantName.isBlank()) return false
        val type = MoneyManagerExporter.normalizeType(tx.transactionType)
        if (type != "Expense" && type != "Income") return false
        if (type == "Expense" && tx.category.isBlank()) return false
        if (tx.participantList().isNotEmpty()) {
            if (tx.splitwiseGroupId <= 0L) return false
            if (!SplitCalculator.resolve(tx).isValid) return false
        }
        return true
    }

    fun confirmed(tx: Transaction, now: Long): Transaction =
        tx.copy(reviewStatus = ReviewStatus.REVIEWED, lastUpdatedTimestamp = now)
}
