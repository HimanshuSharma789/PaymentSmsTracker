package com.example.paymentsmstracker

import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.home.HomeSummaryCalculator
import com.example.paymentsmstracker.home.QuickReview
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSummaryTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 16)

    private fun at(date: LocalDate) = date.atTime(12, 0).toInstant(zone).toEpochMilli()

    private fun tx(
        amount: Double,
        date: LocalDate,
        status: String = ReviewStatus.REVIEWED,
        type: String = "Expense",
        participants: String = "",
        category: String = "Food",
        account: String = "HDFC",
        groupId: Long = 0L
    ) = Transaction(
        amount = amount, merchantName = "M", category = category, account = account,
        timestamp = at(date), isExpense = type != "Income", reviewStatus = status,
        transactionType = type, participants = participants, splitwiseGroupId = groupId
    )

    @Test
    fun monthSpendCountsOnlyMyShareAndSkipsIncomeAndTransfers() {
        val s = HomeSummaryCalculator.summarize(
            listOf(
                tx(450.0, today),
                tx(3600.0, today.minusDays(1), participants = "ME,RAHUL,AISHA"),
                tx(1250.0, today, type = "Income"),
                tx(5000.0, today, type = "Transfer-Out"),
                tx(999.0, today.minusMonths(1).withDayOfMonth(20)) // after the cutoff
            ),
            today, zone
        )
        assertEquals(BigDecimal("1650.00"), s.monthSpent)
        assertEquals(0, s.lastMonthToDate.signum())
        assertEquals(7, s.lastSevenDays.size)
        assertEquals(today, s.lastSevenDays.last().date)
        assertEquals(BigDecimal("450.00"), s.lastSevenDays.last().amount)
        assertEquals(BigDecimal("1200.00"), s.lastSevenDays[5].amount)
    }

    @Test
    fun lastMonthCutoffClampsToShortMonth() {
        val march31 = LocalDate.of(2026, 3, 31)
        val s = HomeSummaryCalculator.summarize(
            listOf(tx(100.0, LocalDate.of(2026, 2, 28)), tx(50.0, march31)), march31, zone
        )
        assertEquals(BigDecimal("100.00"), s.lastMonthToDate)
        assertEquals(BigDecimal("50.00"), s.monthSpent)
    }

    @Test
    fun readyCountsReviewedOnly() {
        val s = HomeSummaryCalculator.summarize(
            listOf(
                tx(100.0, today),
                tx(200.0, today, status = ReviewStatus.NEEDS_REVIEW),
                tx(300.0, today, status = ReviewStatus.EXPORTED)
            ),
            today, zone
        )
        assertEquals(1, s.readyCount)
        assertEquals(BigDecimal("100.00"), s.readyTotal)
    }

    @Test
    fun quickConfirmNeedsWhatTheReviewScreenNeeds() {
        val draft = tx(450.0, today, status = ReviewStatus.NEEDS_REVIEW)
        assertTrue(QuickReview.canConfirm(draft))
        assertFalse(QuickReview.canConfirm(draft.copy(category = "")))
        assertFalse(QuickReview.canConfirm(draft.copy(account = "")))
        assertFalse(QuickReview.canConfirm(draft.copy(reviewStatus = ReviewStatus.REVIEWED)))
        assertFalse(QuickReview.canConfirm(draft.copy(transactionType = "Transfer-Out")))
        // A split needs a Splitwise group, like Save as reviewed.
        assertFalse(QuickReview.canConfirm(draft.copy(participants = "ME,RAHUL")))
        assertTrue(QuickReview.canConfirm(draft.copy(participants = "ME,RAHUL", splitwiseGroupId = 7L)))
        assertTrue(QuickReview.canConfirm(draft.copy(category = "", transactionType = "Income", isExpense = false)))
        assertEquals(ReviewStatus.REVIEWED, QuickReview.confirmed(draft, 1L).reviewStatus)
    }
}
