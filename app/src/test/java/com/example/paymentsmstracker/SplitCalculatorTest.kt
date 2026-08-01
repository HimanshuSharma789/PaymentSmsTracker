package com.example.paymentsmstracker

import com.example.paymentsmstracker.adapter.SplitTableAdapter
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
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

    private fun bd(value: String): BigDecimal = BigDecimal(value).setScale(2)

    private fun sum(map: Map<String, BigDecimal>): BigDecimal =
        map.values.fold(BigDecimal.ZERO.setScale(2)) { a, b -> a.add(b) }
}
