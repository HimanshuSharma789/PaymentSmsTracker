package com.example.paymentsmstracker.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

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

    fun updateHost(host: String) {
        snapshot.updateAndGet { it.copy(hostAddress = host) }
    }

    fun isAccessCodeValid(code: String): Boolean {
        val current = snapshot.get()
        if (!current.running) return false
        if (!current.accessCodeRequired) return true
        return code.trim() == current.accessCode
    }

    fun isOpenAccess(): Boolean {
        val current = snapshot.get()
        return current.running && !current.accessCodeRequired
    }

    fun createSession(): String {
        val token = buildString {
            repeat(32) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) }
        }
        sessions[token] = System.currentTimeMillis()
        return token
    }

    fun isSessionValid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val current = snapshot.get()
        if (!current.running) return false
        return sessions.containsKey(token)
    }

    fun generateAccessCode(): String =
        Random.nextInt(100000, 1000000).toString()
}
