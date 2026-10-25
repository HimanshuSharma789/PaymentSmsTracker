package com.example.paymentsmstracker.sms

import com.example.paymentsmstracker.config.SmsParseRule
import java.util.regex.Pattern

/**
 * Named capture group to splice into an SMS body when building a [SmsParseRule] pattern.
 */
data class CaptureSpan(
    /** amount | merchant | date | reference | accountHint */
    val group: String,
    val start: Int,
    val end: Int
)

data class PatternBuildResult(
    val pattern: String = "",
    val error: String? = null
) {
    val isOk: Boolean get() = error == null && pattern.isNotBlank()
}

/**
 * Builds a Java regex with named groups from an SMS sample + selected capture spans.
 * Literals outside spans are regex-escaped; capture text is replaced by group patterns.
 */
object SmsRuleBuilder {

    private val GROUP_PATTERNS = mapOf(
        "amount" to """(?<amount>[\d,]+(?:\.\d{1,2})?)""",
        "merchant" to """(?<merchant>.+?)""",
        "date" to """(?<date>\S+)""",
        "reference" to """(?<reference>[\w.-]+)""",
        "accountHint" to """(?<accountHint>.+?)"""
    )

    private const val MAX_TAIL_ANCHOR = 24

    private val REGEX_META = setOf(
        '.', '^', '$', '*', '+', '?', '(', ')', '[', ']', '{', '}', '\\', '|'
    )

    /**
     * Locate the first occurrence of [text] in [sms] starting at [fromIndex].
     */
    fun findSpan(sms: String, group: String, text: String, fromIndex: Int = 0): CaptureSpan? {
        val needle = text.trim()
        if (needle.isEmpty()) return null
        val start = sms.indexOf(needle, fromIndex.coerceAtLeast(0))
        if (start < 0) return null
        return CaptureSpan(group = group, start = start, end = start + needle.length)
    }

    /**
     * Build spans from capture strings as they appear in [sms].
     * Amount and merchant search left-to-right; reference may appear anywhere.
     */
    fun spansFromCaptures(
        sms: String,
        amountText: String,
        merchantText: String,
        dateText: String = "",
        referenceText: String = ""
    ): Pair<List<CaptureSpan>, String?> {
        val spans = mutableListOf<CaptureSpan>()
        var cursor = 0

        fun takeInOrder(group: String, text: String): String? {
            if (text.isBlank()) return null
            val span = findSpan(sms, group, text, cursor)
                ?: return "Could not find $group text “${text.trim()}” in the SMS"
            if (spans.any { overlaps(it, span) }) {
                return "$group span overlaps another capture"
            }
            spans += span
            cursor = span.end
            return null
        }

        takeInOrder("amount", amountText)?.let { return emptyList<CaptureSpan>() to it }
        takeInOrder("merchant", merchantText)?.let { return emptyList<CaptureSpan>() to it }
        if (dateText.isNotBlank()) {
            takeInOrder("date", dateText)?.let { return emptyList<CaptureSpan>() to it }
        }
        if (referenceText.isNotBlank()) {
            val span = findSpan(sms, "reference", referenceText, cursor)
                ?: findSpan(sms, "reference", referenceText, 0)
                ?: return emptyList<CaptureSpan>() to
                "Could not find reference text “${referenceText.trim()}” in the SMS"
            if (spans.any { overlaps(it, span) }) {
                return emptyList<CaptureSpan>() to "reference span overlaps another capture"
            }
            spans += span
        }

        if (spans.none { it.group == "amount" }) {
            return emptyList<CaptureSpan>() to "Amount capture is required"
        }
        if (spans.none { it.group == "merchant" }) {
            return emptyList<CaptureSpan>() to "Merchant capture is required"
        }
        return spans.sortedBy { it.start } to null
    }

    fun buildPattern(sms: String, spans: List<CaptureSpan>): PatternBuildResult {
        if (sms.isBlank()) return PatternBuildResult(error = "SMS is empty")
        if (spans.isEmpty()) return PatternBuildResult(error = "No captures selected")

        val sorted = spans.sortedBy { it.start }
        for (i in sorted.indices) {
            val span = sorted[i]
            if (span.start < 0 || span.end > sms.length || span.start >= span.end) {
                return PatternBuildResult(error = "Invalid span for ${span.group}")
            }
            if (span.group !in GROUP_PATTERNS) {
                return PatternBuildResult(error = "Unknown group: ${span.group}")
            }
            if (i > 0 && span.start < sorted[i - 1].end) {
                return PatternBuildResult(error = "Overlapping captures")
            }
        }

        // Text after the last capture (balance, helpline, card digits) changes per SMS. Keep only
        // a short digit-free prefix of it as an anchor so the next SMS of this template matches.
        val tailAnchor = sms.substring(sorted.last().end)
            .takeWhile { !it.isDigit() }
            .take(MAX_TAIL_ANCHOR)

        val sb = StringBuilder("(?i)")
        var pos = 0
        for ((index, span) in sorted.withIndex()) {
            sb.append(escapeLiteral(sms.substring(pos, span.start)))
            val isLast = index == sorted.lastIndex
            val groupPat = when {
                // Lazy merchant with nothing after it would capture one character.
                span.group == "merchant" && isLast && span.end == sms.length -> """(?<merchant>.+)"""
                span.group == "merchant" && isLast && tailAnchor.isBlank() ->
                    """(?<merchant>.+?)(?=\s*\d|$)"""
                else -> GROUP_PATTERNS.getValue(span.group)
            }
            sb.append(groupPat)
            pos = span.end
        }
        sb.append(escapeLiteral(tailAnchor))

        val pattern = sb.toString()
        return try {
            Pattern.compile(pattern)
            PatternBuildResult(pattern = pattern)
        } catch (e: Exception) {
            PatternBuildResult(error = "Invalid pattern: ${e.message}")
        }
    }

    fun buildRule(
        id: String,
        name: String,
        sms: String,
        amountText: String,
        merchantText: String,
        dateText: String = "",
        referenceText: String = "",
        dateFormat: String = "",
        defaultAccount: String = "",
        isExpense: Boolean = true,
        isTransfer: Boolean = false
    ): Pair<SmsParseRule?, String?> {
        val (spans, spanError) = spansFromCaptures(
            sms,
            amountText,
            merchantText,
            dateText,
            referenceText
        )
        if (spanError != null) return null to spanError
        val built = buildPattern(sms, spans)
        if (!built.isOk) return null to built.error
        val rule = SmsParseRule(
            id = id,
            name = name.trim().ifBlank { "Custom rule" },
            enabled = true,
            pattern = built.pattern,
            dateFormat = dateFormat.trim(),
            defaultAccount = defaultAccount.trim(),
            isExpense = isExpense,
            isTransfer = isTransfer
        )
        return rule to null
    }

    private fun overlaps(a: CaptureSpan, b: CaptureSpan): Boolean =
        a.start < b.end && b.start < a.end

    /**
     * Escape regex metacharacters; collapse whitespace runs to \\s+; turn digit runs (card
     * last-4, times, balances) into a number pattern, since they differ between SMS.
     */
    fun escapeLiteral(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch.isDigit() -> {
                    sb.append("""[\d,.]*\d""")
                    while (i + 1 < raw.length && (raw[i + 1].isDigit() ||
                            (raw[i + 1] in ",." && i + 2 < raw.length && raw[i + 2].isDigit()))
                    ) i++
                }
                ch.isWhitespace() -> {
                    sb.append("\\s+")
                    while (i + 1 < raw.length && raw[i + 1].isWhitespace()) i++
                }
                ch in REGEX_META -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
