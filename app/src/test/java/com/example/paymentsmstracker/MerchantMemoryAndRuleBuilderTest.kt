package com.example.paymentsmstracker

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.config.SmsParseRule
import com.example.paymentsmstracker.merchant.MerchantKeys
import com.example.paymentsmstracker.merchant.MerchantMemory
import com.example.paymentsmstracker.merchant.MerchantMemoryHint
import com.example.paymentsmstracker.sms.CaptureSpan
import com.example.paymentsmstracker.sms.SmsParser
import com.example.paymentsmstracker.sms.SmsRuleBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantMemoryAndRuleBuilderTest {

    @Test
    fun merchantKeyNormalizesPunctuationAndCase() {
        assertEquals(
            MerchantKeys.normalize("SWIGGY*"),
            MerchantKeys.normalize("  Swiggy ")
        )
        assertEquals("pyu instamart grocery", MerchantKeys.normalize("PYU*Instamart Grocery"))
        assertFalse(MerchantKeys.isLearnable("?"))
        assertFalse(MerchantKeys.isLearnable("Unknown"))
        assertTrue(MerchantKeys.isLearnable("Swiggy"))
    }

    @Test
    fun applyToBlankFieldsDoesNotOverwrite() {
        val hint = MerchantMemoryHint(
            merchantKey = "swiggy",
            category = "Food",
            subcategory = "Eating out",
            account = "SBI",
            transactionType = "Expense"
        )
        val filled = MerchantMemory.applyToBlankFields(
            category = "",
            subcategory = "",
            account = "HDFC credit card",
            transactionType = "Expense",
            hint = hint
        )
        assertEquals("Food", filled.category)
        assertEquals("Eating out", filled.subcategory)
        assertEquals("HDFC credit card", filled.account)
    }

    @Test
    fun applySkipsCategoryWhenTypeMismatch() {
        val hint = MerchantMemoryHint(
            merchantKey = "hdfc bank credit",
            category = "Food",
            subcategory = "",
            account = "SBI",
            transactionType = "Expense"
        )
        val filled = MerchantMemory.applyToBlankFields(
            category = "",
            subcategory = "",
            account = "",
            transactionType = "Transfer-Out",
            hint = hint
        )
        assertEquals("", filled.category)
        assertEquals("SBI", filled.account)
    }

    @Test
    fun ruleBuilderBuildsWorkingHdfcStylePattern() {
        val sms =
            "Spent Rs.159 On HDFC Bank Card 1974 At PYU*Instamart Grocery On 2026-07-26:21:11:36.Not You?"
        val (rule, error) = SmsRuleBuilder.buildRule(
            id = "user_test",
            name = "Test HDFC",
            sms = sms,
            amountText = "159",
            merchantText = "PYU*Instamart Grocery",
            dateText = "2026-07-26:21:11:36",
            dateFormat = "yyyy-MM-dd:HH:mm:ss",
            defaultAccount = "HDFC credit card"
        )
        assertNull(error)
        assertNotNull(rule)
        val parsed = SmsParser(AppConfig()).parseWithRulePublic(sms, rule!!)
        assertNotNull(parsed)
        assertEquals(159.0, parsed!!.amount, 0.001)
        assertTrue(parsed.merchantName.contains("Instamart", ignoreCase = true))
        assertEquals("HDFC credit card", parsed.account)
    }

    @Test
    fun ruleBuilderRejectsMissingAmountInSms() {
        val sms = "Hello world paid something"
        val (rule, error) = SmsRuleBuilder.buildRule(
            id = "user_bad",
            name = "Bad",
            sms = sms,
            amountText = "999",
            merchantText = "world"
        )
        assertNull(rule)
        assertNotNull(error)
        assertTrue(error!!.contains("amount", ignoreCase = true))
    }

    @Test
    fun escapeLiteralCollapsesWhitespace() {
        val escaped = SmsRuleBuilder.escapeLiteral("A   B")
        assertEquals("A\\s+B", escaped)
    }

    @Test
    fun buildPatternFromSpans() {
        val sms = "Paid Rs.40 to Cafe X Ref ABC"
        val spans = listOf(
            CaptureSpan("amount", sms.indexOf("40"), sms.indexOf("40") + 2),
            CaptureSpan("merchant", sms.indexOf("Cafe X"), sms.indexOf("Cafe X") + 6),
            CaptureSpan("reference", sms.indexOf("ABC"), sms.indexOf("ABC") + 3)
        )
        val built = SmsRuleBuilder.buildPattern(sms, spans)
        assertTrue(built.isOk)
        val rule = SmsParseRule(
            id = "t",
            name = "t",
            pattern = built.pattern,
            defaultAccount = "Cash"
        )
        val parsed = SmsParser(AppConfig()).parseWithRulePublic(sms, rule)
        assertNotNull(parsed)
        assertEquals(40.0, parsed!!.amount, 0.001)
        assertEquals("Cafe X", parsed.merchantName)
        assertEquals("ABC", parsed.referenceNumber)
    }
}
