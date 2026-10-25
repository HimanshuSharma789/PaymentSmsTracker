package com.example.paymentsmstracker.home

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Indian grouping (₹1,14,790), whole rupees unless paise matter. */
object Money {
    private val india = Locale("en", "IN")

    fun format(amount: BigDecimal, withPaise: Boolean = false): String {
        val nf = NumberFormat.getNumberInstance(india).apply {
            minimumFractionDigits = if (withPaise) 2 else 0
            maximumFractionDigits = if (withPaise) 2 else 0
        }
        val scaled = amount.setScale(if (withPaise) 2 else 0, RoundingMode.HALF_UP)
        return "₹" + nf.format(scaled)
    }

    fun format(amount: Double): String = format(BigDecimal.valueOf(amount))

    /** "₹38,420" with the ₹ at [rupeeScale] of the number size, on the baseline. */
    fun hero(amount: BigDecimal, rupeeScale: Float = 0.5f): CharSequence = styled(format(amount), rupeeScale)

    /**
     * UI only: every "₹<digit>" in [text] becomes a smaller ₹, a thin space, then the number.
     * Never use for exports, notifications or anything sent out; use plain [format] there.
     */
    fun styled(text: CharSequence, rupeeScale: Float = 0.75f): CharSequence {
        val sb = StringBuilder()
        val marks = ArrayList<Int>()
        text.forEachIndexed { i, c ->
            sb.append(c)
            if (c == '₹' && i + 1 < text.length && text[i + 1].isDigit()) {
                marks.add(sb.length - 1)
                sb.append('\u2009')
            }
        }
        return SpannableString(sb).apply {
            marks.forEach { setSpan(RelativeSizeSpan(rupeeScale), it, it + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }
}
