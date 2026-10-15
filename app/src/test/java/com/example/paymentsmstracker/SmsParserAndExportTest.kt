package com.example.paymentsmstracker

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.sms.SmsDedupe
import com.example.paymentsmstracker.sms.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val cal = Calendar.getInstance().apply { timeInMillis = parsed.timestamp }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, cal.get(Calendar.MONTH))
        assertEquals(26, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(21, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(11, cal.get(Calendar.MINUTE))
        assertEquals(36, cal.get(Calendar.SECOND))
        assertTrue(parsed.timestampFromMessage)
    }

    @Test
    fun parsesIciciCardSpend() {
        val sms =
            "INR 285.00 spent using ICICI Bank Card XX0004 on 19-Aug-26 on AMAZON PAY IN E. Avl Limit: INR 3,19,715.00. If not you, call 1800 2662/SMS BLOCK 0004 to 9215676766."
        val parsed = parser.parse(sms)
        assertNotNull(parsed)
        assertEquals(285.0, parsed!!.amount, 0.001)
        assertEquals("AMAZON PAY IN E", parsed.merchantName)
        assertEquals("ICICI credit card", parsed.account)
        assertEquals("icici_card_spend", parsed.matchedRuleId)
        val cal = Calendar.getInstance().apply { timeInMillis = parsed.timestamp }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
        assertTrue(parsed.timestampFromMessage)
    }

    @Test
    fun parseAirtelPaymentsBankDebit() {
        val body =
            "Rs. 2200.00 debited from Airtel Payments Bank a/c Txn ID 297805887122 Bal:6073.82 Call 180023400 for help"
        val parsed = parser.parse(body, receivedAtMs = 1_700_000_000_000L)
        assertNotNull(parsed)
        assertEquals(2200.0, parsed!!.amount, 0.001)
        assertEquals("Airtel Payments Bank", parsed.merchantName)
        assertEquals("297805887122", parsed.referenceNumber)
        assertEquals("Airtel", parsed.account)
        assertEquals("airtel_apb_debit", parsed.matchedRuleId)
    }

    @Test
    fun usesReceivedTimeWhenSmsHasNoDate() {
        val sms =
            "Payment of Rs.50 done successfully Refno ABC123XYZ. Thank you for using debit card."
        val receivedAt = java.util.GregorianCalendar(2026, Calendar.AUGUST, 1, 14, 30, 0).timeInMillis
        val parsed = parser.parse(sms, receivedAtMs = receivedAt)
        assertNotNull(parsed)
        assertEquals(receivedAt, parsed!!.timestamp)
        assertTrue(!parsed.timestampFromMessage)
    }

    @Test
    fun dateOnlySmsKeepsDayAndUsesReceivedClock() {
        val sms =
            "Dear UPI user A/C X0539 debited by 1173.0 on date 01Mar25 trf to LAXMI MANJUNATH Refno 506087389221. If not u? call 1800111109. -SBI"
        val receivedAt = java.util.GregorianCalendar(2025, Calendar.MARCH, 1, 18, 45, 12).timeInMillis
        val parsed = parser.parse(sms, receivedAtMs = receivedAt)
        assertNotNull(parsed)
        val cal = Calendar.getInstance().apply { timeInMillis = parsed!!.timestamp }
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(18, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, cal.get(Calendar.MINUTE))
        assertEquals(12, cal.get(Calendar.SECOND))
        assertTrue(parsed!!.timestampFromMessage)
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
    fun moneyManagerExportUsesSlashDateFormat() {
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
        assertEquals("12/30/2026", rows.first().date)
        assertEquals("Expense", rows.first().incomeExpense)
    }

    @Test
    fun transferOutExportsFromAccountAndToCategory() {
        val tx = Transaction(
            amount = 2500.0,
            merchantName = "Transfer to Cash",
            category = "Cash",
            subcategory = "should-be-cleared",
            account = "HSBC",
            timestamp = java.util.GregorianCalendar(2026, java.util.Calendar.AUGUST, 9).timeInMillis,
            participants = "ME,PUNEET",
            reviewStatus = "reviewed",
            transactionType = "Transfer-Out"
        )
        val rows = MoneyManagerExporter.expandTransaction(tx, AppConfig())
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("HSBC", row.account)
        assertEquals("Cash", row.category)
        assertEquals("", row.subcategory)
        assertEquals("Transfer-Out", row.incomeExpense)
        assertEquals("2500.00", row.amount)
        assertEquals("Transfer to Cash", row.description)
        assertEquals("08/09/2026", row.date)
    }

    @Test
    fun moneyManagerTsvUsesTabSeparatedColumnsAndExpenseLabel() {
        val tx = Transaction(
            amount = 42.50,
            merchantName = "Coffee Shop",
            category = "Food",
            subcategory = "Beverages",
            account = "Cash",
            timestamp = java.util.GregorianCalendar(2026, java.util.Calendar.NOVEMBER, 15).timeInMillis,
            reviewStatus = "reviewed",
            transactionType = "Expense"
        )
        val tsv = MoneyManagerExporter.toTsv(listOf(tx), AppConfig())
        val lines = tsv.lines()
        assertEquals(
            "Date\tAccount\tCategory\tSubcategory\tNote\tAmount\tIncome/Expense\tDescription",
            lines.first()
        )
        assertTrue(lines[1].contains("\t"))
        assertTrue(lines[1].contains("Expense"))
        assertTrue(lines[1].startsWith("11/15/2026"))
    }

    @Test
    fun moneyManagerIncomeExpenseColumnOnlyAllowsOfficialTypes() {
        assertEquals("Expense", MoneyManagerExporter.toMoneyManagerType("Expense"))
        assertEquals("Expense", MoneyManagerExporter.toMoneyManagerType("Expenses"))
        assertEquals("Expense", MoneyManagerExporter.toMoneyManagerType("unknown"))
        assertEquals("Income", MoneyManagerExporter.toMoneyManagerType("Income"))
        assertEquals("Transfer-Out", MoneyManagerExporter.toMoneyManagerType("Transfer-Out"))
        assertEquals("Transfer-Out", MoneyManagerExporter.toMoneyManagerType("transfer"))
        val allowed = setOf("Income", "Expense", "Transfer-Out")
        listOf("Expense", "Income", "Transfer-Out", "", "Transfer-In", "Debit").forEach { type ->
            assertTrue(MoneyManagerExporter.toMoneyManagerType(type) in allowed)
        }
    }

    @Test
    fun splitwiseCsvHasEqualSharesAndPaidByMe() {
        val tx = Transaction(
            amount = 100.0,
            merchantName = "Movie",
            category = "Culture",
            subcategory = "Movie",
            account = "HSBC",
            notes = "Birthday outing",
            timestamp = 1_720_000_000_000L,
            participants = "ME,PUNEET,KUSHAGRA,MUKUL",
            reviewStatus = "reviewed",
            transactionType = "Expense"
        )
        assertTrue(SplitwiseExporter.isSplit(tx))
        val csv = SplitwiseExporter.toCsv(listOf(tx), onlySplits = true)
        assertTrue(csv.contains("Paid by"))
        assertTrue(csv.contains("\"Notes\""))
        assertTrue(csv.contains("Birthday outing"))
        assertTrue(csv.contains("25.00"))
        assertTrue(csv.contains("Movie"))
        val ledger = SplitwiseExporter.toSplitLedgerCsv(listOf(tx))
        assertTrue(ledger.contains("FriendsOwe"))
        assertTrue(ledger.contains("75.00"))
        assertTrue(ledger.contains("Equal"))
    }

    @Test
    fun differentBankRefsAreNotDuplicatesEvenIfAmountAndMerchantMatch() {
        val first = parser.parse(
            "INR 1.00 is paid from HSBC account XXXXXX7006 to Kushagra  Mehta on 01-Aug-26 with ref 621372614292. If this is not done by you, call 18002673456 to report."
        )
        val second = parser.parse(
            "INR 1.00 is paid from HSBC account XXXXXX7006 to Kushagra  Mehta on 01-Aug-26 with ref 212133500185. If this is not done by you, call 18002673456 to report."
        )
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("621372614292", first!!.referenceNumber)
        assertEquals("212133500185", second!!.referenceNumber)

        val existing = listOf(
            Transaction(
                id = 1L,
                amount = first.amount,
                merchantName = first.merchantName,
                account = first.account,
                timestamp = first.timestamp,
                originalSms = "INR 1.00 is paid from HSBC account XXXXXX7006 to Kushagra  Mehta on 01-Aug-26 with ref 621372614292. If this is not done by you, call 18002673456 to report.",
                referenceNumber = first.referenceNumber,
                reviewStatus = "needs_review",
                transactionType = "Expense"
            )
        )
        assertFalse(
            SmsDedupe.isDuplicateAgainst(
                existing = existing,
                messageBody = "INR 1.00 is paid from HSBC account XXXXXX7006 to Kushagra  Mehta on 01-Aug-26 with ref 212133500185. If this is not done by you, call 18002673456 to report.",
                parsed = second,
                windowMinutes = 30
            )
        )
        assertTrue(
            SmsDedupe.isDuplicateAgainst(
                existing = existing,
                messageBody = existing.first().originalSms.orEmpty(),
                parsed = first,
                windowMinutes = 30
            )
        )
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

    @Test
    fun creditsOtpsAndRemindersAreNotSpends() {
        assertNull(parser.parse("Refund of Rs 500 credited to your HDFC Bank Credit Card XX12 on 15-Oct-26"))
        assertNull(parser.parse("OTP for transaction of INR 4,999.00 at AMAZON is 123456. Do not share."))
        assertNull(parser.parse("Your credit card payment of Rs 12,000 is due on 20-Oct-26"))
        // UPI P2P debit that mentions the payee being credited is still a spend.
        assertNotNull(parser.parse("ICICI Bank Acct XX12 debited for Rs 100.00 on 15-Oct-26; RAVI credited. UPI:412345678901"))
    }

    @Test
    fun genericRuleCapturesReference() {
        val parsed = parser.parse("Rs 250 paid to CHAI POINT on 15-Oct-26. UPI Ref no 412345678901")
        assertEquals("generic_paid_to_on", parsed?.matchedRuleId)
        assertEquals("412345678901", parsed?.referenceNumber)
    }

    @Test
    fun yearlessDateTakesTheSmsYearNotToday() {
        val dec2024 = Calendar.getInstance().apply { set(2024, Calendar.DECEMBER, 31, 12, 0) }.timeInMillis
        val parsed = parser.parse(
            "Thank you for using HSBC Debit Card XX12 for INR 100.00 on 30DEC at CAFE.",
            receivedAtMs = dec2024
        )
        val cal = Calendar.getInstance().apply { timeInMillis = parsed!!.timestamp }
        assertEquals(2024, cal.get(Calendar.YEAR))
    }

    @Test
    fun dedupeKeepsReversalsAndUnknownRepeats() {
        val debit = Transaction(
            id = 1, amount = 20.0, merchantName = "Unknown", timestamp = 1_000L,
            referenceNumber = "412345678901", transactionType = "Expense"
        )
        val reversal = parser.parse("Rs 20 debited. Ref no 412345678901", requirePaymentHeuristic = false)!!
            .copy(transactionType = "Income", isExpense = false)
        assertFalse(SmsDedupe.isDuplicateAgainst(listOf(debit), "reversal sms", reversal, 30))
        val repeat = parser.parse("Rs 20 debited at counter", requirePaymentHeuristic = false)!!
            .copy(timestamp = 2_000L)
        assertEquals("Unknown", repeat.merchantName)
        assertFalse(SmsDedupe.isDuplicateAgainst(listOf(debit.copy(referenceNumber = null)), "other sms", repeat, 30))
    }

    @Test
    fun tsvEscapesEveryColumnAndSortsAcrossYears() {
        val jan2026 = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 2, 10, 0) }.timeInMillis
        val dec2025 = Calendar.getInstance().apply { set(2025, Calendar.DECEMBER, 30, 10, 0) }.timeInMillis
        val txs = listOf(
            Transaction(id = 1, amount = 10.0, merchantName = "New", timestamp = jan2026,
                account = "My\tBank", category = "Food\nX", reviewStatus = "reviewed"),
            Transaction(id = 2, amount = 20.0, merchantName = "Old", timestamp = dec2025,
                account = "Cash", category = "Food", reviewStatus = "reviewed")
        )
        val lines = MoneyManagerExporter.toTsv(txs, AppConfig()).trimEnd().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("12/30/2025"))
        assertEquals(8, lines[2].split("\t").size)
    }

    @Test
    fun invalidSplitIsBlockedFromMoneyManager() {
        val broken = Transaction(
            amount = 300.0, merchantName = "Dinner", timestamp = 1L, participants = "ME,A",
            splitMode = "exact", splitAllocations = "ME:100,A:100", reviewStatus = "reviewed"
        )
        assertNotNull(MoneyManagerExporter.exportBlocker(broken, AppConfig()))
        assertNull(MoneyManagerExporter.exportBlocker(broken.copy(splitAllocations = "ME:100,A:200"), AppConfig()))
        assertNull(MoneyManagerExporter.exportBlocker(broken, AppConfig(mmFullSplitAsOwnExpense = true)))
    }

    @Test
    fun fallbackReferenceIsTheNumberNotAWord() {
        val fallbackOnly = SmsParser(AppConfig(smsParseRules = emptyList()))
        fun ref(sms: String) = fallbackOnly.parse(sms, requirePaymentHeuristic = false)?.referenceNumber
        assertEquals("412345678901", ref("Rs 50 paid to Chai. UPI Ref No: 412345678901"))
        assertEquals("998877", ref("Rs 50 debited. Ref no. 998877"))
        assertEquals("ABC123456", ref("Rs 50 debited. Ref.No.ABC123456 thanks"))
        assertEquals(null, ref("Refund of Rs 300 at Refreshments Corner"))
    }
}
