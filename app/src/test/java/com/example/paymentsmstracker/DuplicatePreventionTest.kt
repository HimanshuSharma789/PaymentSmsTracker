package com.example.paymentsmstracker

import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.splitwise.SplitwiseApi
import com.example.paymentsmstracker.splitwise.SplitwisePusher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuplicatePreventionTest {

    @Test
    fun retryAdoptsLiveExpenseCarryingThisRowsMarker() {
        val marker = SplitwisePusher.marker(Transaction(id = 7, amount = 1.0, merchantName = "x", timestamp = 1L))
        assertEquals("[pst:7]", marker)
        val json = JSONObject(
            """{"expenses":[
                {"id":11,"details":"Food · [pst:70]","deleted_at":null},
                {"id":12,"details":"Food · [pst:7]","deleted_at":"2026-10-01T00:00:00Z"},
                {"id":13,"details":"Food · [pst:7]","deleted_at":null}
            ]}"""
        )
        // [pst:70] must not match [pst:7]; a deleted expense must not be adopted.
        assertEquals(13L, SplitwiseApi.expenseIdWithMarker(json, marker))
        assertNull(SplitwiseApi.expenseIdWithMarker(JSONObject("""{"expenses":[]}"""), marker))
    }

    @Test
    fun savingAnExportedRowNeverDemotesIt() {
        assertEquals(ReviewStatus.EXPORTED, ReviewStatus.afterSave(ReviewStatus.EXPORTED, markReviewed = false))
        assertEquals(ReviewStatus.EXPORTED, ReviewStatus.afterSave(ReviewStatus.EXPORTED, markReviewed = true))
        assertEquals(ReviewStatus.NEEDS_REVIEW, ReviewStatus.afterSave(ReviewStatus.REVIEWED, markReviewed = false))
        assertEquals(ReviewStatus.REVIEWED, ReviewStatus.afterSave(null, markReviewed = true))
    }
}
