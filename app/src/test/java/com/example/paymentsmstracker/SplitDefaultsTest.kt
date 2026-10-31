package com.example.paymentsmstracker

import com.example.paymentsmstracker.split.SplitDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitDefaultsTest {
    @Test fun closedSectionSavesNoParticipants() {
        assertEquals(emptyList<String>(), SplitDefaults.participantsToSave(false, false, listOf("ME", "A")))
    }
    @Test fun openSectionKeepsParticipants() {
        assertEquals(listOf("ME", "A"), SplitDefaults.participantsToSave(false, true, listOf("ME", "A")))
    }
    @Test fun transferNeverSplits() {
        assertEquals(emptyList<String>(), SplitDefaults.participantsToSave(true, true, listOf("ME")))
    }
    @Test fun groupOnOpen() {
        assertEquals(5L, SplitDefaults.groupOnOpen(5L, 9L, listOf(9L)))
        assertEquals(9L, SplitDefaults.groupOnOpen(0L, 9L, listOf(9L)))
        assertEquals(0L, SplitDefaults.groupOnOpen(0L, 9L, listOf(1L)))
        assertEquals(0L, SplitDefaults.groupOnOpen(0L, 0L, listOf(1L)))
    }
}
