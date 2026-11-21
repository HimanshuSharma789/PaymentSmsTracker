package com.example.paymentsmstracker.insights

import com.example.paymentsmstracker.data.Transaction
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightsCalculatorTest {
    private val utc = ZoneOffset.UTC
    private val oct = YearMonth.of(2026, 10)

    private fun tx(
        amount: Double, merchant: String, cat: String, ym: YearMonth,
        type: String = "Expense", participants: String = ""
    ) = Transaction(
        amount = amount, merchantName = merchant, category = cat,
        timestamp = ym.atDay(10).atStartOfDay(utc).toInstant().toEpochMilli(),
        transactionType = type, participants = participants
    )

    private fun calc(list: List<Transaction>) = InsightsCalculator.calculate(list, oct, utc)
    private fun bd(v: Int) = BigDecimal(v)

    @Test fun deltaMath() {
        val r = calc(listOf(
            tx(300.0, "A", "Food", oct), tx(100.0, "B", "Food", oct.minusMonths(1)),
            tx(100.0, "C", "Food", oct.minusMonths(1))
        ))
        assertEquals(0, bd(300).compareTo(r.total))
        assertEquals(0, bd(200).compareTo(r.previousTotal))
        assertEquals(0, bd(100).compareTo(r.delta))
        assertEquals(50, r.deltaPercent)
        assertEquals(0, bd(100).compareTo(r.categories[0].delta))
    }

    @Test fun categoriesSortedWithShare() {
        val r = calc(listOf(
            tx(100.0, "A", "Fuel", oct), tx(300.0, "B", "Food", oct), tx(100.0, "C", "", oct)
        ))
        assertEquals(listOf("Food", "Fuel", InsightsCalculator.UNCATEGORISED), r.categories.map { it.category })
        assertEquals(60, r.categories[0].sharePercent)
        assertEquals(3, r.rowCount)
    }

    @Test fun topMerchantsSumCountAndLimit() {
        val list = (1..7).map { tx(it * 10.0, "M$it", "Food", oct) } + tx(5.0, "M7", "Food", oct)
        val r = calc(list)
        assertEquals(5, r.topMerchants.size)
        assertEquals("M7", r.topMerchants[0].merchant)
        assertEquals(2, r.topMerchants[0].count)
        assertEquals(0, bd(75).compareTo(r.topMerchants[0].amount))
    }

    @Test fun emptyPreviousMonthNoDivideByZero() {
        val r = calc(listOf(tx(100.0, "A", "Food", oct)))
        assertNull(r.deltaPercent)
        assertEquals(0, bd(100).compareTo(r.delta))
    }

    @Test fun emptyMonth() {
        val r = calc(emptyList())
        assertEquals(0, r.rowCount)
        assertEquals(0, r.total.signum())
        assertEquals(emptyList<CategorySpend>(), r.categories)
    }

    @Test fun splitRowUsesMyShareAndIncomeIgnored() {
        val r = calc(listOf(
            tx(300.0, "A", "Food", oct, participants = "ME,X,Y"),
            tx(999.0, "Pay", "Salary", oct, type = "Income")
        ))
        assertEquals(0, bd(100).compareTo(r.total))
        assertEquals(1, r.rowCount)
    }
}
