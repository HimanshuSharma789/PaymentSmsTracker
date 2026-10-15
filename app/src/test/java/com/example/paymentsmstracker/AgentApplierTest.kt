package com.example.paymentsmstracker

import com.example.paymentsmstracker.agent.FormState
import com.example.paymentsmstracker.agent.applyTo
import com.example.paymentsmstracker.agent.toFormState
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class AgentApplierTest {

    private val labels = listOf("ME" to 111L, "Puneet S" to 222L, "Mukul" to 333L)
    private val tx = Transaction(id = 7, amount = 840.0, merchantName = "SWIGGY", account = "SBI", timestamp = 1L,
        lastUpdatedTimestamp = 5L)
    private val split = FormState(
        merchantName = "Swiggy", account = "SBI", category = "Food", subcategory = "Delivery",
        participants = listOf("ME", "Puneet S"), splitMode = SplitMode.EXACT,
        splitAllocations = mapOf("ME" to BigDecimal("300"), "Puneet S" to BigDecimal("540"))
    )

    @Test
    fun splitIsEncodedLikeTheEntryScreen() {
        val out = split.applyTo(tx, 9L, labels.toMap(), markReviewed = true)
        // Entry screen: encodeAllocations(allocations, selectedParticipantIdMap()) with ids of the checked chips.
        val idMap = mapOf("ME" to 111L, "Puneet S" to 222L)
        assertEquals(SplitCalculator.encodeAllocations(split.splitAllocations, idMap), out.splitAllocations)
        assertEquals("111:300,222:540", out.splitAllocations)
        assertEquals("ME=111,Puneet S=222", out.splitwiseParticipantIds)
        assertEquals("ME,Puneet S", out.participants)
        assertEquals(SplitMode.EXACT, out.splitMode)
        assertEquals(9L, out.splitwiseGroupId)
        assertEquals(ReviewStatus.REVIEWED, out.reviewStatus)
        assertEquals(true, out.lastUpdatedTimestamp > tx.lastUpdatedTimestamp)
        // Round trip back into the form restores the same labels and inputs.
        assertEquals(split, out.toFormState(labels))
    }

    @Test
    fun exportedIsNeverDowngraded() {
        val exported = tx.copy(reviewStatus = ReviewStatus.EXPORTED)
        assertEquals(ReviewStatus.EXPORTED, split.applyTo(exported, 9L, labels.toMap(), markReviewed = true).reviewStatus)
        assertEquals(ReviewStatus.EXPORTED, split.applyTo(exported, 9L, labels.toMap(), markReviewed = false).reviewStatus)
        assertEquals(ReviewStatus.NEEDS_REVIEW, split.applyTo(tx, 9L, labels.toMap(), markReviewed = false).reviewStatus)
    }

    @Test
    fun transferClearsSplit() {
        val withSplit = split.applyTo(tx, 9L, labels.toMap(), markReviewed = false)
        val out = split.copy(transactionType = "transfer", merchantName = "", category = "HDFC credit card")
            .applyTo(withSplit, 9L, labels.toMap(), markReviewed = false)
        assertEquals("Transfer-Out", out.transactionType)
        assertEquals("", out.participants)
        assertEquals("", out.splitAllocations)
        assertEquals("", out.splitwiseParticipantIds)
        assertEquals(SplitMode.EQUAL, out.splitMode)
        assertEquals(0L, out.splitwiseGroupId)
        assertEquals("", out.subcategory)
        assertEquals("Transfer to HDFC credit card", out.merchantName)
        assertEquals(true, out.isExpense)
    }
}
