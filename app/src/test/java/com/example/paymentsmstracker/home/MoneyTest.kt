package com.example.paymentsmstracker.home

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test fun plainFormatHasNoGap() {
        assertEquals("₹450", Money.format(BigDecimal("450")))
    }
}
