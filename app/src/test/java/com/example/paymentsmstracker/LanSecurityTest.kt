package com.example.paymentsmstracker

import com.example.paymentsmstracker.server.LanServerState
import com.example.paymentsmstracker.splitwise.SplitwiseAuthStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSecurityTest {

    @After
    fun stop() = LanServerState.markStopped()

    private fun start(code: String = "123456") =
        LanServerState.markStarting(30, code, "192.168.1.5", 8080, accessCodeRequired = true)

    @Test
    fun wrongCodesLockOutEvenTheRightOne() {
        start()
        repeat(LanServerState.MAX_FAILED_LOGINS) { assertNull(LanServerState.login("000000")) }
        assertTrue(LanServerState.isLockedOut())
        assertNull(LanServerState.login("123456"))

        start() // restart issues a fresh window
        val token = LanServerState.login("123456")
        assertNotNull(token)
        assertTrue(LanServerState.isSessionValid(token))
    }

    @Test
    fun expiredServerRejectsEverything() {
        start()
        val token = LanServerState.login("123456")
        assertTrue(LanServerState.isLive())
        assertFalse(LanServerState.isLive(nowMs = System.currentTimeMillis() + 31 * 60_000L))
        LanServerState.markStopped()
        assertFalse(LanServerState.isSessionValid(token))
    }

    @Test
    fun onlyTheBoundHostIsAccepted() {
        start()
        assertTrue(LanServerState.isExpectedHost("192.168.1.5:8080"))
        assertFalse(LanServerState.isExpectedHost("evil.example:8080"))
        assertFalse(LanServerState.isExpectedHost(null))
    }

    @Test
    fun oauthStateFailsClosed() {
        assertTrue(SplitwiseAuthStore.isValidOAuthState("s1", "s1"))
        assertFalse(SplitwiseAuthStore.isValidOAuthState(null, "attacker"))
        assertFalse(SplitwiseAuthStore.isValidOAuthState("s1", "s2"))
    }
}
