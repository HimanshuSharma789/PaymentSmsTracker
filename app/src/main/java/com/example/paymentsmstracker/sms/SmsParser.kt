package com.example.paymentsmstracker.sms

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.config.SmsParseRule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

data class ParsedSms(
    val amount: Double,
    val merchantName: String,
    val timestamp: Long,
    val referenceNumber: String?,
    val account: String,
    val isExpense: Boolean,
    val transactionType: String,
    val matchedRuleId: String?,
    val matchedRuleName: String?,
    /** True when calendar date (and optionally time) came from the SMS body. */
    val timestampFromMessage: Boolean = false
)

class SmsParser(private val config: AppConfig) {

    fun looksLikePayment(message: String): Boolean {
        val lower = message.lowercase(Locale.ENGLISH)
        return listOf(
            "debited",
            "spent",
            "paid",
            "payment of",
            "transaction of",
            "trf to",
            "transfer to",
            "thank you for using",
            "debit card",
            "credit card"
        ).any { lower.contains(it) }
    }

    /**
     * @param receivedAtMs SMS inbox/received time. Used when the body has no usable date,
     * or to supply time-of-day when the body has a date but no clock time.
     */
    fun parse(
        message: String,
        receivedAtMs: Long? = null,
        requirePaymentHeuristic: Boolean = true
    ): ParsedSms? {
        if (requirePaymentHeuristic && !looksLikePayment(message)) return null
        val kind = classify(message)
        if (kind == MessageKind.NOT_A_TRANSACTION) return null

        for (rule in config.smsParseRules.filter { it.enabled }) {
            val parsed = parseWithRule(message, rule, receivedAtMs) ?: continue
            // A credit/refund must never become a spend; only an Income rule may claim it.
            if (kind == MessageKind.CREDIT && parsed.transactionType != "Income") continue
            return parsed
        }
        // The fallback only knows how to read spends.
        return if (kind == MessageKind.CREDIT) null else fallbackParse(message, receivedAtMs)
    }

    enum class MessageKind { SPEND, CREDIT, NOT_A_TRANSACTION }

    /**
     * OTPs and reminders are not transactions. Money-in wording ("credited", "refund") with no
     * money-out wording is a credit; "debited … credited to NAME" (UPI P2P) stays a spend.
     */
    fun classify(message: String): MessageKind {
        val lower = message.lowercase(Locale.ENGLISH)
        if (NOT_A_TRANSACTION_WORDS.any { lower.contains(it) }) return MessageKind.NOT_A_TRANSACTION
        val moneyIn = CREDIT_WORDS.any { lower.contains(it) }
        val moneyOut = DEBIT_WORDS.any { Regex("\\b$it\\b").containsMatchIn(lower) }
        return if (moneyIn && !moneyOut) MessageKind.CREDIT else MessageKind.SPEND
    }

    /** Try a single rule (used by the SMS rule wizard verify step). */
    fun parseWithRulePublic(
        message: String,
        rule: SmsParseRule,
        receivedAtMs: Long? = null
    ): ParsedSms? = parseWithRule(message, rule, receivedAtMs)

    private fun parseWithRule(
        message: String,
        rule: SmsParseRule,
        receivedAtMs: Long?
    ): ParsedSms? {
        val pattern = try {
            Pattern.compile(rule.pattern)
        } catch (_: Exception) {
            return null
        }
        val matcher = pattern.matcher(message)
        if (!matcher.find()) return null

        val amount = namedGroup(matcher, "amount")
            ?.replace(",", "")
            ?.toDoubleOrNull()
            ?: return null

        val merchant = namedGroup(matcher, "merchant")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Unknown"
        val reference = namedGroup(matcher, "reference")?.trim()?.trimEnd('.', ',', ';')
        val dateRaw = namedGroup(matcher, "date")?.trim()
        val accountHint = namedGroup(matcher, "accountHint")?.trim().orEmpty()

        val resolved = resolveTimestamp(
            dateRaw = dateRaw,
            primaryFormat = rule.dateFormat,
            message = message,
            receivedAtMs = receivedAtMs
        )

        val account = when {
            rule.defaultAccount.isNotBlank() -> rule.defaultAccount
            accountHint.isNotBlank() -> accountHint
            else -> ""
        }

        val type = if (rule.isTransfer) "Transfer-Out" else if (rule.isExpense) "Expense" else "Income"
        val cleanedMerchant = cleanMerchant(merchant)

        // Credit-card bill style UPI: "trf to HDFC Bank Credit"
        val resolvedType = if (
            type == "Expense" &&
            cleanedMerchant.contains("credit", ignoreCase = true) &&
            (cleanedMerchant.contains("hdfc", ignoreCase = true) ||
                cleanedMerchant.contains("sbi", ignoreCase = true))
        ) {
            "Transfer-Out"
        } else {
            type
        }

        return ParsedSms(
            amount = amount,
            merchantName = cleanedMerchant,
            timestamp = resolved.timestamp,
            referenceNumber = reference,
            account = account,
            isExpense = resolvedType != "Income",
            transactionType = resolvedType,
            matchedRuleId = rule.id,
            matchedRuleName = rule.name,
            timestampFromMessage = resolved.fromMessage
        )
    }

    private fun fallbackParse(message: String, receivedAtMs: Long?): ParsedSms? {
        val amountPattern = Pattern.compile(
            """(?i)(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)"""
        )
        val amountMatcher = amountPattern.matcher(message)
        if (!amountMatcher.find()) return null
        val amount = amountMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() ?: return null

        val merchant = extractFallbackMerchant(message)
        val reference = extractFallbackReference(message)
        val resolved = resolveTimestamp(
            dateRaw = null,
            primaryFormat = "",
            message = message,
            receivedAtMs = receivedAtMs
        )

        return ParsedSms(
            amount = amount,
            merchantName = merchant,
            timestamp = resolved.timestamp,
            referenceNumber = reference,
            account = "",
            isExpense = true,
            transactionType = "Expense",
            matchedRuleId = null,
            matchedRuleName = "fallback",
            timestampFromMessage = resolved.fromMessage
        )
    }

    private data class ResolvedTimestamp(
        val timestamp: Long,
        val fromMessage: Boolean
    )

    private fun resolveTimestamp(
        dateRaw: String?,
        primaryFormat: String,
        message: String,
        receivedAtMs: Long?
    ): ResolvedTimestamp {
        // 1) Prefer a full datetime embedded anywhere in the SMS body.
        findDateTimeInMessage(message)?.let {
            return ResolvedTimestamp(it, fromMessage = true)
        }

        // 2) Rule-captured date/time (date-only common for bank SMS).
        parseDate(dateRaw, primaryFormat, anchorMs = receivedAtMs ?: System.currentTimeMillis())?.let { parsed ->
            if (dateRawHasTime(dateRaw)) {
                return ResolvedTimestamp(parsed, fromMessage = true)
            }
            // Date-only: keep SMS calendar day, use received clock time when available.
            val combined = if (receivedAtMs != null) {
                combineDateWithTimeOfDay(parsed, receivedAtMs)
            } else {
                parsed
            }
            return ResolvedTimestamp(combined, fromMessage = true)
        }

        // 3) No usable date in SMS → SMS received time (or now).
        return ResolvedTimestamp(
            timestamp = receivedAtMs ?: System.currentTimeMillis(),
            fromMessage = false
        )
    }

    private fun dateRawHasTime(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return raw.contains(':') || raw.contains('T')
    }

    private fun combineDateWithTimeOfDay(dateMillis: Long, timeOfDayMillis: Long): Long {
        val dateCal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val timeCal = Calendar.getInstance().apply { timeInMillis = timeOfDayMillis }
        dateCal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
        dateCal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
        dateCal.set(Calendar.SECOND, timeCal.get(Calendar.SECOND))
        dateCal.set(Calendar.MILLISECOND, timeCal.get(Calendar.MILLISECOND))
        return dateCal.timeInMillis
    }

    private fun findDateTimeInMessage(message: String): Long? {
        val patterns = listOf(
            """\d{4}-\d{2}-\d{2}:\d{2}:\d{2}:\d{2}""" to "yyyy-MM-dd:HH:mm:ss",
            """\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}""" to "yyyy-MM-dd HH:mm:ss",
            """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""" to "yyyy-MM-dd'T'HH:mm:ss"
        )
        for ((regex, format) in patterns) {
            val matcher = Pattern.compile(regex).matcher(message)
            if (matcher.find()) {
                val raw = matcher.group().replace('T', ' ')
                val normalizedFormat =
                    if (format.contains('T')) "yyyy-MM-dd HH:mm:ss" else format
                parseDate(raw, normalizedFormat)?.let { return it }
            }
        }
        return null
    }

    private fun extractFallbackMerchant(message: String): String {
        val patterns = listOf(
            Pattern.compile("""(?i)(?:trf|transfer)\s+to\s+(.+?)(?:\s+Ref|\s+Ac\s|\.|$)"""),
            Pattern.compile("""(?i)\bto\s+(.+?)\s+on\b"""),
            Pattern.compile("""(?i)\bAt\s+(.+?)\s+On\b"""),
            Pattern.compile("""(?i)\btowards\s+(.+?)(?:\.|$)""")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                return cleanMerchant(matcher.group(1) ?: continue)
            }
        }
        return "Unknown"
    }

    private fun extractFallbackReference(message: String): String? {
        val pattern = Pattern.compile(
            // Whole word "Ref"/"Reference", optional "No."/"Number", then a token with a digit,
            // so "Ref No: 4123…" gives the number (not "No") and "Refund" matches nothing.
            """(?i)\bRef(?:erence)?(?:\.?\s*No\.?|\s*Number)?\s*[:#.]?\s*(?=[A-Za-z0-9-]*\d)([A-Za-z0-9-]{6,})"""
        )
        val matcher = pattern.matcher(message)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun namedGroup(matcher: java.util.regex.Matcher, name: String): String? {
        return try {
            matcher.group(name)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    /** [anchorMs] supplies the year for year-less formats: the SMS time, not "now", so an
     *  old SMS read by backfill keeps its own year. */
    private fun parseDate(
        raw: String?,
        primaryFormat: String,
        anchorMs: Long = System.currentTimeMillis()
    ): Long? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().replace('T', ' ')
        val formats = buildList {
            if (primaryFormat.isNotBlank()) add(primaryFormat.replace('T', ' '))
            addAll(
                listOf(
                    "yyyy-MM-dd:HH:mm:ss",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd HH:mm",
                    "yyyy-MM-dd",
                    "dd-MMM-yy",
                    "dd-MMM-yyyy",
                    "ddMMMyy",
                    "ddMMMyyyy",
                    "ddMMM",
                    "dd MMM yy",
                    "dd MMM yyyy",
                    "dd MMM",
                    "dd.MM.yy",
                    "dd.MM.yyyy",
                    "dd/MM/yy",
                    "dd/MM/yyyy"
                )
            )
        }.distinct()

        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.ENGLISH)
                sdf.isLenient = false
                val parsed = sdf.parse(normalized) ?: continue
                // Formats without year default to 1970 — pin to the anchor's year.
                if (!format.contains("y", ignoreCase = true)) {
                    val cal = Calendar.getInstance().apply {
                        time = parsed
                        set(Calendar.YEAR, Calendar.getInstance().apply { timeInMillis = anchorMs }.get(Calendar.YEAR))
                    }
                    // If that lands after the anchor by > few days (e.g. Dec SMS read in Jan), use previous year.
                    if (cal.timeInMillis > anchorMs + 2L * 24 * 60 * 60 * 1000) {
                        cal.add(Calendar.YEAR, -1)
                    }
                    return cal.timeInMillis
                }
                return parsed.time
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    private fun cleanMerchant(raw: String): String {
        return raw
            .replace(Regex("""\s+Ac\s+x?\w+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Ref\b(?:\.?\s*No\.?)?\s*[:#]?\s*[\w.-]+""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('.', ',', '-')
            .replace(Regex("""\s+"""), " ")
    }

    private companion object {
        val NOT_A_TRANSACTION_WORDS = listOf(
            "otp", "one time password", "verification code", "is due", "due on", "due by",
            "will be debited", "will be charged", "requested money", "collect request", "payment reminder"
        )
        val CREDIT_WORDS = listOf("credited to your", "credited in your", "has been credited", "is credited",
            "refund", "reversed", "reversal", "received rs", "received inr", "cashback")
        val DEBIT_WORDS = listOf("debited", "spent", "sent", "paid", "withdrawn", "purchase")
    }
}
