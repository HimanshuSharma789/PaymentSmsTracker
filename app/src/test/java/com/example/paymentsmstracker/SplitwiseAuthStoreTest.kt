package com.example.paymentsmstracker

import com.example.paymentsmstracker.splitwise.SplitwiseAuthHeaders
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitwiseAuthStoreTest {

    @Test
    fun cookieHeaderIncludesSessionAndCredentials() {
        val header = SplitwiseAuthHeaders.cookieHeader("sess-abc", "cred-xyz")
        assertEquals(
            "${SplitwiseAuthHeaders.COOKIE_SESSION}=sess-abc; ${SplitwiseAuthHeaders.COOKIE_USER_CREDENTIALS}=cred-xyz",
            header
        )
        assertEquals(
            "sess-abc",
            SplitwiseAuthHeaders.parseNamedCookie(header, SplitwiseAuthHeaders.COOKIE_SESSION)
        )
        assertEquals(
            "cred-xyz",
            SplitwiseAuthHeaders.parseNamedCookie(header, SplitwiseAuthHeaders.COOKIE_USER_CREDENTIALS)
        )
    }
}
