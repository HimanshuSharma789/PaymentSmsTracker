package com.example.paymentsmstracker.sms

import com.example.paymentsmstracker.data.Transaction
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Duplicate rules (one set, used by the live receiver and inbox backfill alike):
 * 1. Identical SMS body (whitespace/case-insensitive) → duplicate
 * 2. Same bank reference (6+ chars) and same transaction type → duplicate
 *    (a reversal can reuse the debit's reference; it is not a duplicate)
 * 3. If the new SMS has a reference and it is new → keep it (even if amount/merchant match)
 * 4. Only when reference is missing: amount + merchant within the window, measured between
 *    transaction times → duplicate. Skipped for an "Unknown" merchant, where two ₹20
 *    payments in half an hour are just as likely real.
 */
object SmsDedupe {

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
                it.referenceNumber?.trim()?.equals(reference, ignoreCase = true) == true &&
                    it.transactionType.equals(parsed.transactionType, ignoreCase = true)
            }
        }

        if (parsed.merchantName.equals(UNKNOWN_MERCHANT, ignoreCase = true)) return false
        val windowMillis = TimeUnit.MINUTES.toMillis(windowMinutes.coerceAtLeast(0).toLong())
        return existing.any {
            abs(it.amount - parsed.amount) < 0.005 &&
                normalizeSms(it.merchantName) == normalizeSms(parsed.merchantName) &&
                abs(it.timestamp - parsed.timestamp) <= windowMillis
        }
    }

    /** Bank references are long; a short one ("No", "und") is a mis-capture and must not dedupe. */
    fun usableReference(parsed: ParsedSms): String =
        parsed.referenceNumber?.trim()?.takeIf { it.length >= MIN_REFERENCE_LENGTH }.orEmpty()

    private const val MIN_REFERENCE_LENGTH = 6
    private const val UNKNOWN_MERCHANT = "Unknown"

    fun normalizeSms(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ENGLISH)
}
