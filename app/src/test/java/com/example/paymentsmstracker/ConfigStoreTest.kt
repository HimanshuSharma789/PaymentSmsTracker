package com.example.paymentsmstracker

import com.example.paymentsmstracker.config.ConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStoreTest {

    private val customRule =
        """{"id":"mine","name":"Mine","pattern":"Rs (?<amount>\\d+)"}"""

    @Test
    fun defaultsMergeOnlyIntoUnversionedConfig() {
        val legacy = ConfigStore.parse("""{"accounts":["Cash"],"smsParseRules":[$customRule]}""")
        assertTrue("Paytm" in legacy.accounts)
        assertTrue(legacy.smsParseRules.size > 1)

        // Once saved at the current version, deletions stick.
        val current = ConfigStore.parse(
            """{"configVersion":${ConfigStore.CONFIG_VERSION},"accounts":["Cash"],"smsParseRules":[$customRule]}"""
        )
        assertEquals(listOf("Cash"), current.accounts)
        assertEquals(listOf("mine"), current.smsParseRules.map { it.id })
    }

    @Test
    fun brokenRuleIsSkippedNotWholeConfigReset() {
        val config = ConfigStore.parse(
            """{"configVersion":1,"accounts":["Cash"],"dedupeWindowMinutes":5,
               "smsParseRules":[{"id":"broken"},$customRule]}"""
        )
        assertEquals(5, config.dedupeWindowMinutes)
        assertEquals(listOf("mine"), config.smsParseRules.map { it.id })
        assertFalse(config.smsParseRules.any { it.id == "broken" })
    }
}
