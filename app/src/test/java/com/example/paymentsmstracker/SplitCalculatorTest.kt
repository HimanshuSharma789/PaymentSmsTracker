package com.example.paymentsmstracker

import com.example.paymentsmstracker.adapter.SplitTableAdapter
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import com.example.paymentsmstracker.splitwise.SplitwiseMemberLabels
import com.example.paymentsmstracker.splitwise.SplitwiseUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SplitCalculatorTest {

    @Test
    fun legacyEqualSplitWhenOnlyParticipantsSet() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Dinner",
            timestamp = 1L,
            participants = "ME,PUNEET,KUSHAGRA,MUKUL"
        )
        assertEquals(SplitMode.EQUAL, tx.splitMode)
        assertEquals("", tx.splitAllocations)
        val result = SplitCalculator.resolve(tx)
        assertTrue(result.isValid)
        assertEquals(bd("25.00"), result.myShare)
        assertEquals(bd("75.00"), result.friendsOwe)
        assertEquals(bd("25.00"), result.amounts["PUNEET"])
    }

    @Test
    fun equalSplitAssignsRoundingRemainderToMe() {
        val result = SplitCalculator.resolve(
            totalAmount = 100.0,
            participants = listOf("ME", "A", "B"),
            mode = SplitMode.EQUAL,
            allocationInputs = emptyMap()
        )
        assertTrue(result.isValid)
        assertEquals(bd("33.34"), result.myShare)
        assertEquals(bd("33.33"), result.amounts["A"])
        assertEquals(bd("33.33"), result.amounts["B"])
        assertEquals(bd("100.00"), sum(result.amounts))
    }

    @Test
    fun exactAmountsMustSumToTotal() {
        val people = listOf("ME", "PUNEET", "KUSHAGRA")
        val ok = SplitCalculator.resolve(
            100.0,
            people,
            SplitMode.EXACT,
            mapOf(
                "ME" to bd("40"),
                "PUNEET" to bd("35"),
                "KUSHAGRA" to bd("25")
            )
        )
        assertTrue(ok.isValid)
        assertEquals(bd("40.00"), ok.myShare)
        assertEquals(bd("60.00"), ok.friendsOwe)

        val bad = SplitCalculator.resolve(
            100.0,
            people,
            SplitMode.EXACT,
            mapOf(
                "ME" to bd("40"),
                "PUNEET" to bd("35"),
                "KUSHAGRA" to bd("20")
            )
        )
        assertFalse(bad.isValid)
        assertTrue(bad.error!!.contains("must add up"))
    }

    @Test
    fun exactMissingParticipantFails() {
        val result = SplitCalculator.resolve(
            50.0,
            listOf("ME", "PUNEET"),
            SplitMode.EXACT,
            mapOf("ME" to bd("25"))
        )
        assertFalse(result.isValid)
        assertTrue(result.error!!.contains("PUNEET"))
    }

    @Test
    fun percentMustSumTo100AndRoundsExactly() {
        val result = SplitCalculator.resolve(
            100.0,
            listOf("ME", "A", "B"),
            SplitMode.PERCENT,
            mapOf(
                "ME" to bd("33.33"),
                "A" to bd("33.33"),
                "B" to bd("33.34")
            )
        )
        assertTrue(result.isValid)
        assertEquals(bd("100.00"), sum(result.amounts))

        val bad = SplitCalculator.resolve(
            100.0,
            listOf("ME", "A"),
            SplitMode.PERCENT,
            mapOf("ME" to bd("50"), "A" to bd("40"))
        )
        assertFalse(bad.isValid)
        assertTrue(bad.error!!.contains("100"))
    }

    @Test
    fun weightSharesAllocateProportionally() {
        val result = SplitCalculator.resolve(
            90.0,
            listOf("ME", "A", "B"),
            SplitMode.WEIGHT,
            mapOf(
                "ME" to bd("1"),
                "A" to bd("2"),
                "B" to bd("1")
            )
        )
        assertTrue(result.isValid)
        assertEquals(bd("22.50"), result.myShare)
        assertEquals(bd("45.00"), result.amounts["A"])
        assertEquals(bd("22.50"), result.amounts["B"])
        assertEquals(bd("67.50"), result.friendsOwe)
    }

    @Test
    fun weightZeroIsInvalid() {
        val result = SplitCalculator.resolve(
            90.0,
            listOf("ME", "A"),
            SplitMode.WEIGHT,
            mapOf("ME" to bd("1"), "A" to bd("0"))
        )
        assertFalse(result.isValid)
        assertTrue(result.error!!.contains("greater than 0"))
    }

    @Test
    fun encodeAndParseAllocationsRoundTrip() {
        val encoded = SplitCalculator.encodeAllocations(
            mapOf("ME" to bd("25.50"), "PUNEET" to bd("74.50"))
        )
        val parsed = SplitCalculator.parseAllocations(encoded)
        assertEquals(0, bd("25.50").compareTo(parsed.getValue("ME")))
        assertEquals(0, bd("74.50").compareTo(parsed.getValue("PUNEET")))
        val alt = SplitCalculator.parseAllocations("ME=10|X=20")
        assertEquals(0, bd("10").compareTo(alt.getValue("ME")))
        assertEquals(0, bd("20").compareTo(alt.getValue("X")))
    }

    @Test
    fun mmExportUsesExactAllocations() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Trip",
            category = "Travel",
            subcategory = "Cab",
            account = "HSBC",
            timestamp = 1L,
            participants = "ME,PUNEET",
            splitMode = SplitMode.EXACT,
            splitAllocations = "ME:30,PUNEET:70",
            transactionType = "Expense"
        )
        val rows = MoneyManagerExporter.expandTransaction(tx, AppConfig())
        assertEquals(2, rows.size)
        assertEquals("30.00", rows[0].amount)
        assertEquals("Travel", rows[0].category)
        assertEquals("70.00", rows[1].amount)
        assertEquals("Social Life", rows[1].category)
    }

    @Test
    fun mmFullAsOwnIgnoresAllocationForMmRows() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Trip",
            category = "Travel",
            subcategory = "Cab",
            account = "HSBC",
            timestamp = 1L,
            participants = "ME,PUNEET",
            splitMode = SplitMode.EXACT,
            splitAllocations = "ME:30,PUNEET:70",
            transactionType = "Expense"
        )
        val rows = MoneyManagerExporter.expandTransaction(
            tx,
            AppConfig(mmFullSplitAsOwnExpense = true)
        )
        assertEquals(1, rows.size)
        assertEquals("100.00", rows[0].amount)
        assertEquals("Travel", rows[0].category)
    }

    @Test
    fun splitTableUsesCalculatedShares() {
        val tx = Transaction(
            id = 9L,
            amount = 100.0,
            merchantName = "Cafe",
            timestamp = 1_720_000_000_000L,
            participants = "ME,PUNEET",
            splitMode = SplitMode.PERCENT,
            splitAllocations = "ME:40,PUNEET:60"
        )
        val row = SplitTableAdapter.fromTransaction(tx)
        assertEquals("40.00", row.myShare)
        assertEquals("60.00", row.friendsOwe)
        assertTrue(row.people.contains("Percentage"))
        assertEquals("Pending", row.status)
        assertFalse(row.failed)
    }

    @Test
    fun splitwiseCsvUsesExactPersonColumns() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Movie",
            category = "Culture",
            subcategory = "Movie",
            account = "HSBC",
            timestamp = 1_720_000_000_000L,
            participants = "ME,PUNEET,KUSHAGRA",
            splitMode = SplitMode.EXACT,
            splitAllocations = "ME:40,PUNEET:35,KUSHAGRA:25",
            transactionType = "Expense"
        )
        val csv = SplitwiseExporter.toCsv(listOf(tx))
        assertTrue(csv.contains("40.00"))
        assertTrue(csv.contains("35.00"))
        assertTrue(csv.contains("25.00"))
        assertTrue(csv.contains("\"ME\""))
        val ledger = SplitwiseExporter.toSplitLedgerCsv(listOf(tx))
        assertTrue(ledger.contains("Uneven"))
        assertTrue(ledger.contains("40.00"))
        assertTrue(ledger.contains("60.00"))
    }

    @Test
    fun blankModeTreatedAsEqual() {
        val result = SplitCalculator.resolve(
            60.0,
            listOf("ME", "A"),
            mode = "",
            allocationInputs = emptyMap()
        )
        assertNull(result.error)
        assertEquals(bd("30.00"), result.myShare)
        assertEquals(bd("30.00"), result.friendsOwe)
    }

    @Test
    fun participantIdEncodeParseRoundTrip() {
        val encoded = Transaction.encodeParticipantIds(
            mapOf("ME" to 10L, "Puneet S" to 22L)
        )
        val parsed = Transaction.parseParticipantIds(encoded)
        assertEquals(10L, parsed["ME"])
        assertEquals(22L, parsed["Puneet S"])
        assertTrue(ReviewStatus.canPushToSplitwise(ReviewStatus.REVIEWED))
        assertTrue(ReviewStatus.canPushToSplitwise(ReviewStatus.EXPORTED))
        assertFalse(ReviewStatus.canPushToSplitwise(ReviewStatus.NEEDS_REVIEW))
    }

    @Test
    fun memberLabelsMarkCurrentUserAsMeAndDedup() {
        val labels = SplitwiseMemberLabels.labelsFor(
            members = listOf(
                SplitwiseUser(1, "Himanshu", "Sharma"),
                SplitwiseUser(2, "Alex", ""),
                SplitwiseUser(3, "Alex", "B")
            ),
            currentUserId = 1L
        )
        assertEquals("ME", labels[0].first)
        assertEquals(1L, labels[0].second)
        assertEquals("Alex", labels[1].first)
        assertTrue(labels[2].first.contains("Alex"))
        assertTrue(labels.map { it.first.lowercase() }.toSet().size == 3)
    }

    @Test
    fun deselectedMemberNotInEqualSplit() {
        val result = SplitCalculator.resolve(
            90.0,
            participants = listOf("ME", "PUNEET"),
            mode = SplitMode.EQUAL,
            allocationInputs = emptyMap()
        )
        assertTrue(result.isValid)
        assertEquals(bd("45.00"), result.myShare)
        assertEquals(bd("45.00"), result.amounts["PUNEET"])
        assertTrue("KUSHAGRA" !in result.amounts)
    }

    @Test
    fun remapAllocationsUsesSplitwiseIdsWhenLabelsChange() {
        val remapped = SplitCalculator.remapAllocationsToLabels(
            allocations = mapOf(
                "PUNEET" to bd("5"),
                "KUSHAGRA" to bd("2"),
                "ME" to bd("3")
            ),
            savedNameToId = mapOf(
                "PUNEET" to 101L,
                "KUSHAGRA" to 102L,
                "ME" to 10L
            ),
            savedParticipantOrder = listOf("PUNEET", "KUSHAGRA", "ME"),
            currentLabels = listOf(
                "Puneet Manghwani" to 101L,
                "Kushagra Mehta" to 102L,
                "ME" to 10L
            )
        )
        assertEquals(bd("5"), remapped["Puneet Manghwani"])
        assertEquals(bd("2"), remapped["Kushagra Mehta"])
        assertEquals(bd("3"), remapped["ME"])
    }

    @Test
    fun remapAllocationsFallsBackToSavedOrder() {
        val remapped = SplitCalculator.remapAllocationsToLabels(
            allocations = mapOf("A" to bd("5"), "B" to bd("2"), "C" to bd("3")),
            savedNameToId = emptyMap(),
            savedParticipantOrder = listOf("A", "B", "C"),
            currentLabels = listOf(
                "Puneet Manghwani" to 1L,
                "Kushagra Mehta" to 2L,
                "ME" to 3L
            )
        )
        assertEquals(bd("5"), remapped["Puneet Manghwani"])
        assertEquals(bd("2"), remapped["Kushagra Mehta"])
        assertEquals(bd("3"), remapped["ME"])
    }

    @Test
    fun remapAllocationsUsesNumericIdKeys() {
        val remapped = SplitCalculator.remapAllocationsToLabels(
            allocations = mapOf(
                "101" to bd("5"),
                "102" to bd("2"),
                "10" to bd("3")
            ),
            savedNameToId = emptyMap(),
            savedParticipantOrder = emptyList(),
            currentLabels = listOf(
                "Puneet Manghwani" to 101L,
                "Kushagra Mehta" to 102L,
                "ME" to 10L
            )
        )
        assertEquals(bd("5"), remapped["Puneet Manghwani"])
        assertEquals(bd("2"), remapped["Kushagra Mehta"])
        assertEquals(bd("3"), remapped["ME"])
    }

    @Test
    fun encodeAllocationsPrefersUserIds() {
        val encoded = SplitCalculator.encodeAllocations(
            values = mapOf("ME" to bd("3"), "Puneet" to bd("5")),
            nameToId = mapOf("ME" to 10L, "Puneet" to 101L)
        )
        assertTrue(encoded.contains("10:3"))
        assertTrue(encoded.contains("101:5"))
        val resolved = SplitCalculator.resolve(
            Transaction(
                amount = 8.0,
                merchantName = "Test",
                timestamp = 1L,
                participants = "ME,Puneet",
                splitMode = SplitMode.EXACT,
                splitAllocations = encoded,
                splitwiseParticipantIds = Transaction.encodeParticipantIds(
                    mapOf("ME" to 10L, "Puneet" to 101L)
                )
            )
        )
        assertTrue(resolved.isValid)
        assertEquals(bd("3.00"), resolved.myShare)
        assertEquals(bd("5.00"), resolved.amounts["Puneet"])
    }

    private fun bd(value: String): BigDecimal = BigDecimal(value).setScale(2)

    private fun sum(map: Map<String, BigDecimal>): BigDecimal =
        map.values.fold(BigDecimal.ZERO.setScale(2)) { a, b -> a.add(b) }
}
