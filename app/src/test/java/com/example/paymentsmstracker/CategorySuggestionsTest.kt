package com.example.paymentsmstracker

import com.example.paymentsmstracker.review.CategorySuggestions.pick
import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySuggestionsTest {
    private val opts = listOf("Food", "Travel", "Bills", "Fun", "Health", "Gifts", "Misc")

    @Test fun countsOrder() {
        val c = mapOf("bills" to 9, "misc" to 5, "food" to 1)
        assertEquals(listOf("Bills", "Misc", "Food", "Travel", "Fun"), pick(opts, c, ""))
    }

    @Test fun configOrderFill() {
        assertEquals(listOf("Food", "Travel", "Bills", "Fun", "Health"), pick(opts, mapOf("food" to 2), ""))
        assertEquals(listOf("Food", "Travel", "Bills", "Fun", "Health"), pick(opts, emptyMap(), ""))
    }

    @Test fun selectedOutsideTopTakesSlotOne() {
        assertEquals(listOf("Misc", "Food", "Travel", "Bills", "Fun"), pick(opts, emptyMap(), "misc"))
    }

    @Test fun selectedInsideTopDoesNotMove() {
        assertEquals(listOf("Food", "Travel", "Bills", "Fun", "Health"), pick(opts, emptyMap(), "Bills"))
    }

    @Test fun shortListReturnsAll() {
        val six = opts.take(6)
        assertEquals(six, pick(six, mapOf("gifts" to 3), "Gifts"))
    }
}
