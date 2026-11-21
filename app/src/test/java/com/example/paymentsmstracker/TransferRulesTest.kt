package com.example.paymentsmstracker

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.sms.SmsParser
import com.example.paymentsmstracker.sms.TransferRules
import org.junit.Assert.*
import org.junit.Test

class TransferRulesTest {
    private val cfg = AppConfig()

    @Test fun cardBillsAreTransfers() {
        assertEquals("HDFC credit card", TransferRules.detect("HDFC Bank Credit", cfg))
        assertEquals("ICICI credit card", TransferRules.detect("ICICI Bank Credit Card", cfg))
        assertEquals("SBI cashback credit card", TransferRules.detect("SBI Card Payment", cfg))
    }

    @Test fun otherMerchantsAndCuelessStayExpense() {
        assertNull(TransferRules.detect("Amazon", cfg))          // Amazon account defaults ON
        assertNull(TransferRules.detect("HDFC Life", cfg))       // no card cue
        assertNull(TransferRules.detect("Swiggy", cfg))
    }

    @Test fun flagOnAccountIsNotTransferred() {
        val on = cfg.copy(accountsNotSpending = emptyList())
        assertNull(TransferRules.detect("ICICI Bank Credit Card", on))
        // legacy hdfc/sbi fallback still applies when nothing in config names the merchant
        assertEquals("", TransferRules.detect("HDFC Bank Credit", on.copy(accounts = listOf("Cash"))))
        // ...but not when the user turned that exact account ON
        assertNull(TransferRules.detect("HDFC Bank Credit", on))
    }

    @Test fun nonCreditAccountFlaggedOffMatchesFullName() {
        val c = cfg.copy(accountsNotSpending = listOf("Mutual Funds"))
        assertEquals("Mutual Funds", TransferRules.detect("Mutual Funds SIP", c))
        assertNull(TransferRules.detect("Funds", c))
    }

    @Test fun firstAccountWinsTie() {
        val c = AppConfig(accounts = listOf("HDFC credit card", "HDFC platinum credit card"),
            accountsNotSpending = listOf("HDFC credit card", "HDFC platinum credit card"))
        assertEquals("HDFC credit card", TransferRules.detect("hdfc credit card", c))
    }

    @Test fun configDefaultsAndStoredField() {
        assertEquals(listOf("SBI cashback credit card", "HDFC credit card", "ICICI credit card"), cfg.accountsNotSpending)
        // missing field -> default derived from the stored accounts
        val old = ConfigStore.parse("""{"configVersion":2,"accounts":["Cash","My Credit Card"]}""")
        assertEquals(listOf("My Credit Card"), old.accountsNotSpending)
        val set = ConfigStore.parse("""{"configVersion":2,"accounts":["Cash","X"],"accountsNotSpending":["X"]}""")
        assertEquals(listOf("X"), set.accountsNotSpending)
    }

    @Test fun parserMakesTransferOut() {
        val sms = "A/C X1234 debited by 500.00 on date 12Nov26 trf to HDFC Bank Credit Refno 123456789"
        val p = SmsParser(cfg).parse(sms)!!
        assertEquals("Transfer-Out", p.transactionType)
        assertEquals("HDFC credit card", p.transferTo)
        val on = SmsParser(cfg.copy(accountsNotSpending = emptyList())).parse(sms)!!
        assertEquals("Expense", on.transactionType)
    }

    private fun tx(id: Long, m: String, status: String = ReviewStatus.REVIEWED, type: String = "Expense",
                   participants: String = "", account: String = "SBI") =
        Transaction(id = id, amount = 1.0, merchantName = m, timestamp = 0, reviewStatus = status,
            transactionType = type, participants = participants, account = account)

    @Test fun planSkipsExportedSplitIncomeAndSameAccount() {
        val h = "HDFC Bank Credit"
        val plan = TransferRules.plan(listOf(
            tx(1, h), tx(2, h, ReviewStatus.EXPORTED), tx(3, h, type = "Income"),
            tx(4, h, participants = "ME,A"), tx(5, h, type = "Transfer-Out"),
            tx(6, h, account = "HDFC credit card"), tx(7, "Amazon"), tx(8, h, ReviewStatus.NEEDS_REVIEW)
        ), cfg)
        assertEquals(listOf(1L, 8L), plan.changes.map { it.id })
        assertEquals(2, plan.skipped)
        assertTrue(plan.changes.all { it.transactionType == "Transfer-Out" && it.category == "HDFC credit card" })
    }
}
