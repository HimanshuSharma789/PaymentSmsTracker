package com.example.paymentsmstracker.insights

import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.home.HomeSummaryCalculator
import com.example.paymentsmstracker.split.SplitCalculator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class CategorySpend(
    val category: String,
    val amount: BigDecimal,
    /** Share of the month total, 0..100. */
    val sharePercent: Int,
    /** This month minus previous month for the category. */
    val delta: BigDecimal
)

data class MerchantSpend(val merchant: String, val amount: BigDecimal, val count: Int)

data class Insights(
    val month: YearMonth,
    val total: BigDecimal,
    val previousTotal: BigDecimal,
    val delta: BigDecimal,
    /** Null when the previous month has no spend (nothing to compare to). */
    val deltaPercent: Int?,
    val categories: List<CategorySpend>,
    val topMerchants: List<MerchantSpend>,
    val rowCount: Int
)

/** Read-only month analysis. Same spend rule as Home: Expense rows, your share of a split. */
object InsightsCalculator {

    const val UNCATEGORISED = "Uncategorised"

    fun calculate(
        transactions: List<Transaction>,
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault()
    ): Insights {
        val prev = month.minusMonths(1)
        val catNow = HashMap<String, BigDecimal>()
        val catPrev = HashMap<String, BigDecimal>()
        val merchants = HashMap<String, Pair<BigDecimal, Int>>()
        var total = BigDecimal.ZERO
        var previousTotal = BigDecimal.ZERO
        var rows = 0
        for (tx in transactions) {
            if (!HomeSummaryCalculator.isSpend(tx)) continue
            val ym = YearMonth.from(Instant.ofEpochMilli(tx.timestamp).atZone(zone))
            if (ym != month && ym != prev) continue
            val mine = SplitCalculator.resolveOrEqual(tx).myShare
            val cat = tx.category.trim().ifBlank { UNCATEGORISED }
            if (ym == prev) {
                previousTotal += mine
                catPrev.merge(cat, mine, BigDecimal::add)
            } else {
                total += mine
                rows++
                catNow.merge(cat, mine, BigDecimal::add)
                val m = tx.merchantName.trim().ifBlank { "Payment" }
                val old = merchants[m]
                merchants[m] = ((old?.first ?: BigDecimal.ZERO) + mine) to ((old?.second ?: 0) + 1)
            }
        }
        val delta = total - previousTotal
        val pct = if (previousTotal.signum() == 0) null
        else delta.multiply(BigDecimal(100)).divide(previousTotal, 0, RoundingMode.HALF_UP).toInt()
        val categories = catNow.entries
            .sortedWith(compareByDescending<Map.Entry<String, BigDecimal>> { it.value }.thenBy { it.key })
            .map { (c, a) ->
                val share = if (total.signum() == 0) 0
                else a.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP).toInt()
                CategorySpend(c, a, share, a - (catPrev[c] ?: BigDecimal.ZERO))
            }
        val top = merchants.entries
            .sortedWith(compareByDescending<Map.Entry<String, Pair<BigDecimal, Int>>> { it.value.first }.thenBy { it.key })
            .take(5)
            .map { (m, v) -> MerchantSpend(m, v.first, v.second) }
        return Insights(month, total, previousTotal, delta, pct, categories, top, rows)
    }

    /** Earliest month with any spend row, or null. */
    fun earliestMonth(transactions: List<Transaction>, zone: ZoneId = ZoneId.systemDefault()): YearMonth? =
        transactions.filter { HomeSummaryCalculator.isSpend(it) }
            .minOfOrNull { it.timestamp }
            ?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) }
}
