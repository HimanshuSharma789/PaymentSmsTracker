package com.example.paymentsmstracker.merchant

import java.util.Locale

object MerchantKeys {

    /**
     * Stable key for merchant memory: lowercase, collapse non-alphanumerics to spaces.
     * "SWIGGY*" / "Swiggy " / "PYU*Instamart" → comparable keys.
     */
    fun normalize(merchant: String): String =
        merchant
            .trim()
            .lowercase(Locale.ENGLISH)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    fun isLearnable(merchant: String): Boolean {
        val key = normalize(merchant)
        return key.length >= 2 && key != "unknown"
    }
}
