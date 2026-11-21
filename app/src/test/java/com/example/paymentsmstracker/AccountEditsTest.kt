package com.example.paymentsmstracker

import com.example.paymentsmstracker.config.AccountEdits
import com.example.paymentsmstracker.config.AppConfig
import org.junit.Assert.*
import org.junit.Test

class AccountEditsTest {
    private val c = AppConfig(accounts = listOf("Cash", "Visa"), accountsNotSpending = listOf("Visa"))

    @Test fun validateRejectsBlankAndDuplicates() {
        assertNotNull(AccountEdits.validate(c, "  "))
        assertNotNull(AccountEdits.validate(c, " cash "))
        assertNull(AccountEdits.validate(c, "Wallet"))
        assertNull(AccountEdits.validate(c, "CASH", except = "Cash")) // case-only rename
    }

    @Test fun addAppendsAndDefaultsCreditCardsOff() {
        val r = AccountEdits.add(AccountEdits.add(c, " Wallet "), "Amex credit card")
        assertEquals(listOf("Cash", "Visa", "Wallet", "Amex credit card"), r.accounts)
        assertEquals(listOf("Visa", "Amex credit card"), r.accountsNotSpending)
    }

    @Test fun renameKeepsFlagAndOrder() {
        val r = AccountEdits.rename(c, "Visa", "Visa Gold")
        assertEquals(listOf("Cash", "Visa Gold"), r.accounts)
        assertEquals(listOf("Visa Gold"), r.accountsNotSpending)
    }

    @Test fun removeDropsFlag() {
        val r = AccountEdits.remove(c, "visa")
        assertEquals(listOf("Cash"), r.accounts)
        assertTrue(r.accountsNotSpending.isEmpty())
        assertTrue(AccountEdits.remove(r, "Cash").accounts.isEmpty())
    }

    @Test fun toggle() {
        val off = AccountEdits.setCountsAsSpending(c, "Cash", false)
        assertFalse(AccountEdits.countsAsSpending(off, "Cash"))
        val on = AccountEdits.setCountsAsSpending(off, "cash", true)
        assertEquals(listOf("Visa"), on.accountsNotSpending)
    }
}
