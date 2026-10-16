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
    fun hero(amount: BigDecimal, rupeeScale: Float = 0.5f): CharSequence {
        val text = format(amount)
        return SpannableString(text).apply {
            setSpan(RelativeSizeSpan(rupeeScale), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
