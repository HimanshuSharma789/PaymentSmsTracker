package com.example.paymentsmstracker.sms

import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.data.TransactionDao
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Duplicate rules:
 * 1. Identical SMS body → duplicate
 * 2. Same bank reference (6+ chars) → duplicate
 * 3. If the new SMS has a reference and it is new → keep it (even if amount/merchant match)
 * 4. Only when reference is missing: amount + merchant within the dedupe window → duplicate
 */
object SmsDedupe {

    suspend fun isDuplicate(
        dao: TransactionDao,
        messageBody: String,
        parsed: ParsedSms,
        windowMinutes: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (dao.findByOriginalSms(messageBody) != null) return true

        val ref = usableReference(parsed)
        if (ref.isNotEmpty()) {
            return dao.findByReference(ref) != null
        }

        val since = nowMs - TimeUnit.MINUTES.toMillis(windowMinutes.coerceAtLeast(0).toLong())
        return dao.findRecentDuplicate(parsed.amount, parsed.merchantName, since) != null
    }

    fun isDuplicateAgainst(
        existing: List<Transaction>,
        messageBody: String,
        parsed: ParsedSms,
        windowMinutes: Int
    ): Boolean {
        val normalizedBody = normalizeSms(messageBody)
        if (existing.any { normalizeSms(it.originalSms.orEmpty()) == normalizedBody }) {
            return true
        }

        val reference = usableReference(parsed)
        if (reference.isNotEmpty()) {
            return existing.any {
                it.referenceNumber?.trim()?.equals(reference, ignoreCase = true) == true
            }
        }

        val windowMillis = TimeUnit.MINUTES.toMillis(windowMinutes.coerceAtLeast(0).toLong())
        return existing.any {
            abs(it.amount - parsed.amount) < 0.005 &&
                normalizeMerchant(it.merchantName) == normalizeMerchant(parsed.merchantName) &&
                abs(it.timestamp - parsed.timestamp) <= windowMillis
        }
    }

    /** Bank references are long; a short one ("No", "und") is a mis-capture and must not dedupe. */
    fun usableReference(parsed: ParsedSms): String =
        parsed.referenceNumber?.trim()?.takeIf { it.length >= MIN_REFERENCE_LENGTH }.orEmpty()

    private const val MIN_REFERENCE_LENGTH = 6

    fun normalizeSms(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ENGLISH)

    fun normalizeMerchant(value: String): String = normalizeSms(value)
}
