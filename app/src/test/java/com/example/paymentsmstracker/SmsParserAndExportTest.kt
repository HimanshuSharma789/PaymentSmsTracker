package com.example.paymentsmstracker

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.sms.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SmsParserAndExportTest {

    private val parser = SmsParser(AppConfig())

    @Test
    fun parsesHdfcCardSpend() {
        val sms =
            "Spent Rs.159 On HDFC Bank Card 1974 At PYU*Instamart Grocery On 2026-07-26:21:11:36.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 1974 to 7308080808"
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(159.0, parsed!!.amount, 0.001)
        assertTrue(parsed.merchantName.contains("Instamart", ignoreCase = true))
        assertEquals("HDFC credit card", parsed.account)
        assertEquals("hdfc_card_spend", parsed.matchedRuleId)
    }

    @Test
    fun parsesSbiUpiDebit() {
        val sms =
            "Dear UPI user A/C X0539 debited by 1173.0 on date 01Mar25 trf to LAXMI MANJUNATH Refno 506087389221. If not u? call 1800111109. -SBI"
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(1173.0, parsed!!.amount, 0.001)
        assertEquals("LAXMI MANJUNATH", parsed.merchantName)
        assertEquals("SBI", parsed.account)
        assertEquals("506087389221", parsed.referenceNumber)
    }

    @Test
    fun parsesSbiCreditCardBillAsTransfer() {
        val sms =
            "Dear UPI user A/C X0539 debited by 963.0 on date 28Feb25 trf to HDFC Bank Credit Refno 505977148455. If not u? call 1800111109. -SBI"
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(963.0, parsed!!.amount, 0.001)
        assertEquals("Transfer-Out", parsed.transactionType)
    }

    @Test
    fun parsesHsbcDebitedTowards() {
        val sms =
            "Your HSBC Acc XXXXXX7006 is debited for INR 6403.00 on 22-Jul-25 towards FLIPKART PAYMENTS. UPI Ref No 688130316524. If you have not initiated this transaction, please report this as a fraudulent transaction by calling us on 1800 267 3456 (if in India) or on 914065118001 (from overseas)."
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(6403.0, parsed!!.amount, 0.001)
        assertEquals("FLIPKART PAYMENTS", parsed.merchantName)
        assertEquals("HSBC", parsed.account)
        assertEquals("688130316524", parsed.referenceNumber)
    }

    @Test
    fun parsesHsbcPaidTo() {
        val sms =
            "INR 6.00 is paid from HSBC account XXXXXX7006 to NAMO TANDURI CHAI on 04-Dec-25 with ref 533839694684. If this is not done by you, call 18002673456 to report."
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(6.0, parsed!!.amount, 0.001)
        assertEquals("NAMO TANDURI CHAI", parsed.merchantName)
        assertEquals("533839694684", parsed.referenceNumber)
    }

    @Test
    fun parsesHsbcPaidToGamingArena() {
        val sms =
            "INR 300.00 is paid from HSBC account XXXXXX7006 to gaming arena on 04-Dec-25 with ref 296168247205. If this is not done by you, call 18002673456 to report."
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(300.0, parsed!!.amount, 0.001)
        assertEquals("gaming arena", parsed.merchantName)
    }

    @Test
    fun parsesSbiYonoTransfer() {
        val sms =
            "Your Ac x0539 debited Rs.100.00 for transfer to Neeru  Ac x7124 dt 18.07.26 Ref 619914971512. If not done by you, call 1800111109. YONO SBI"
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(100.0, parsed!!.amount, 0.001)
        assertTrue(parsed.merchantName.contains("Neeru", ignoreCase = true))
        assertEquals("619914971512", parsed.referenceNumber)
        val cal = Calendar.getInstance().apply { timeInMillis = parsed.timestamp }
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH))
    }

    @Test
    fun splitExportCreatesPersonalAndDuesRows() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Movie",
            category = "Culture",
            subcategory = "Movie",
            account = "HSBC",
            timestamp = System.currentTimeMillis(),
            participants = "ME,PUNEET,KUSHAGRA,MUKUL",
            reviewStatus = "reviewed",
            transactionType = "Expense"
        )
        val rows = MoneyManagerExporter.expandTransaction(tx, AppConfig())
        assertEquals(2, rows.size)
        assertEquals("25.00", rows[0].amount)
        assertEquals("Culture", rows[0].category)
        assertEquals("75.00", rows[1].amount)
        assertEquals("Social Life", rows[1].category)
        assertEquals("Dues", rows[1].subcategory)
    }

    @Test
    fun splitExportFullAsOwnCreatesSingleRow() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Movie",
            category = "Culture",
            subcategory = "Movie",
            account = "HSBC",
            timestamp = System.currentTimeMillis(),
            participants = "ME,PUNEET,KUSHAGRA,MUKUL",
            reviewStatus = "reviewed",
            transactionType = "Expense"
        )
        val rows = MoneyManagerExporter.expandTransaction(
            tx,
            AppConfig(mmFullSplitAsOwnExpense = true)
        )
        assertEquals(1, rows.size)
        assertEquals("100.00", rows[0].amount)
        assertEquals("Culture", rows[0].category)
        assertEquals("Movie", rows[0].subcategory)
        assertTrue(rows[0].note.contains("Split:"))
    }

    @Test
    fun parsesHsbcDebitCardThankYou() {
        val sms =
            "HSBC:Thank you for using HSBC Debit Card XXXXX3825 for INR 1,051.00 on 30JUL at ZEPTO MARKETPLACE PRIV .Your available bal is INR 9,01,706.23 .To report fraudulent transaction call 18002673456 (local) Or +914061268007 (overseas)."
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(1051.0, parsed!!.amount, 0.001)
        assertTrue(parsed.merchantName.contains("ZEPTO", ignoreCase = true))
        assertEquals("HSBC debit card", parsed.account)
        assertEquals("hsbc_debit_card", parsed.matchedRuleId)
        val cal = Calendar.getInstance().apply { timeInMillis = parsed.timestamp }
        assertEquals(30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH))
    }

    @Test
    fun moneyManagerExportUsesDotDateFormat() {
        val tx = Transaction(
            amount = 10.0,
            merchantName = "Tea",
            category = "Food",
            subcategory = "Beverages",
            account = "HSBC",
            timestamp = java.util.GregorianCalendar(2026, java.util.Calendar.DECEMBER, 30).timeInMillis,
            reviewStatus = "reviewed",
            transactionType = "Expense"
        )
        val rows = MoneyManagerExporter.expandTransaction(tx, AppConfig())
        assertEquals("2026-12-30", rows.first().date)
    }

    @Test
    fun splitwiseCsvHasEqualSharesAndPaidByMe() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Movie",
            category = "Culture",
            subcategory = "Movie",
            account = "HSBC",
            timestamp = 1_720_000_000_000L,
            participants = "ME,PUNEET,KUSHAGRA,MUKUL",
            reviewStatus = "reviewed",
            transactionType = "Expense"
        )
        assertTrue(SplitwiseExporter.isSplit(tx))
        val csv = SplitwiseExporter.toCsv(listOf(tx), onlySplits = true)
        assertTrue(csv.contains("Paid by"))
        assertTrue(csv.contains("25.00"))
        assertTrue(csv.contains("Movie"))
        val ledger = SplitwiseExporter.toSplitLedgerCsv(listOf(tx))
        assertTrue(ledger.contains("FriendsOwe"))
        assertTrue(ledger.contains("75.00"))
        assertTrue(ledger.contains("Equal"))
    }

    @Test
    fun weightSplitMmExportUsesMyCalculatedShare() {
        val tx = Transaction(
            amount = 90.0,
            merchantName = "Dinner",
            category = "Food",
            subcategory = "Dining",
            account = "HSBC",
            timestamp = 1L,
            participants = "ME,PUNEET,KUSHAGRA",
            splitMode = "weight",
            splitAllocations = "ME:1,PUNEET:2,KUSHAGRA:1",
            reviewStatus = "reviewed",
            transactionType = "Expense"
        )
        val rows = MoneyManagerExporter.expandTransaction(tx, AppConfig())
        assertEquals(2, rows.size)
        assertEquals("22.50", rows[0].amount)
        assertEquals("67.50", rows[1].amount)
    }
}
