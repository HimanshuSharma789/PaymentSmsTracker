package com.example.paymentsmstracker.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class LanServerSnapshot(
    val running: Boolean = false,
    val hostAddress: String = "",
    val port: Int = 0,
    val accessCode: String = "",
    val accessCodeRequired: Boolean = true,
    val startedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val durationMinutes: Int = 30
) {
    val baseUrl: String
        get() = if (running && hostAddress.isNotBlank() && port > 0) {
            "http://$hostAddress:$port"
        } else {
            ""
        }

    val remainingMs: Long
        get() = if (!running) 0L else (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

object LanServerState {
    private val snapshot = AtomicReference(LanServerSnapshot())
    private val sessions = ConcurrentHashMap<String, Long>()
    private val failedLogins = AtomicInteger(0)
    private val random = SecureRandom()

    /** A 6-digit code is guessable without a cap; after this many wrong codes every login
     *  fails until the server is restarted (which issues a new code). */
    const val MAX_FAILED_LOGINS = 5

    fun get(): LanServerSnapshot = snapshot.get()

    fun markStarting(
        durationMinutes: Int,
        accessCode: String,
        host: String,
        port: Int,
        accessCodeRequired: Boolean
    ) {
        val now = System.currentTimeMillis()
        sessions.clear()
        failedLogins.set(0)
        snapshot.set(
            LanServerSnapshot(
                running = true,
                hostAddress = host,
                port = port,
                accessCode = accessCode,
                accessCodeRequired = accessCodeRequired,
                startedAtMs = now,
                expiresAtMs = now + durationMinutes * 60_000L,
                durationMinutes = durationMinutes
            )
        )
    }

    fun markStopped() {
        sessions.clear()
        snapshot.set(LanServerSnapshot())
    }

    /** Running and not past its expiry. Checked per request: the service's stop timer
     *  pauses while the phone sleeps, so it alone cannot enforce the window. */
    fun isLive(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = snapshot.get()
        return current.running && nowMs <= current.expiresAtMs
    }

    /** Returns a session token for a correct code, or null (wrong code, locked out, or not live). */
    fun login(code: String): String? {
        if (!isLive()) return null
        val current = snapshot.get()
        if (current.accessCodeRequired) {
            if (failedLogins.get() >= MAX_FAILED_LOGINS) return null
            val ok = java.security.MessageDigest.isEqual(
                code.trim().toByteArray(),
                current.accessCode.toByteArray()
            )
            if (!ok) {
                failedLogins.incrementAndGet()
                return null
            }
        }
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        sessions[token] = System.currentTimeMillis()
        return token
    }

    fun isLockedOut(): Boolean = failedLogins.get() >= MAX_FAILED_LOGINS

    fun isOpenAccess(): Boolean = isLive() && !snapshot.get().accessCodeRequired

    fun isSessionValid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return isLive() && sessions.containsKey(token)
    }

    /** The only Host header a browser on the LAN should send; anything else is DNS rebinding. */
    fun isExpectedHost(hostHeader: String?): Boolean {
        val current = snapshot.get()
        return hostHeader != null && hostHeader == "${current.hostAddress}:${current.port}"
    }

    fun generateAccessCode(): String = (100000 + random.nextInt(900000)).toString()
}
