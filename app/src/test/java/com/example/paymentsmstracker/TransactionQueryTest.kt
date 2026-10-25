package com.example.paymentsmstracker

import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.list.TransactionQuery
import com.example.paymentsmstracker.list.TransactionSort
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionQueryTest {
    private fun tx(id: Long, m: String, amt: Double, ts: Long, notes: String? = null) =
        Transaction(id = id, amount = amt, merchantName = m, timestamp = ts, notes = notes)

    private val a = tx(1, "Swiggy", 450.0, 10)
    private val b = tx(2, "dmart", 1280.0, 20)
    private val c = tx(3, "Uber", 450.0, 30, notes = "Airport")
    private val all = listOf(a, b, c)

    private fun ids(q: String, s: TransactionSort = TransactionSort.NEWEST) =
        TransactionQuery.apply(all, q, s).map { it.id }

    @Test fun amountMatchesWithCommasAndRupee() {
        assertEquals(listOf(3L, 1L), ids("450"))
        assertEquals(listOf(3L, 1L), ids("4,50"))
        assertEquals(listOf(3L, 1L), ids("₹450"))
        assertEquals(listOf(2L), ids("1,280"))
    }

    @Test fun textMatchesCaseInsensitive() {
        assertEquals(listOf(2L), ids("DMART"))
        assertEquals(listOf(3L), ids("airport"))
        assertEquals(emptyList<Long>(), ids("zzz"))
    }

    @Test fun sortOrders() {
        assertEquals(listOf(3L, 2L, 1L), ids("", TransactionSort.NEWEST))
        assertEquals(listOf(1L, 2L, 3L), ids("", TransactionSort.OLDEST))
        assertEquals(listOf(2L, 1L, 3L), ids("", TransactionSort.AMOUNT_HIGH))
        assertEquals(listOf(1L, 3L, 2L), ids("", TransactionSort.AMOUNT_LOW))
        assertEquals(listOf(2L, 1L, 3L), ids("", TransactionSort.MERCHANT))
    }

    @Test fun tiesKeepInputOrder() {
        assertEquals(listOf(1L, 3L), ids("", TransactionSort.AMOUNT_HIGH).filter { it != 2L })
        assertEquals(listOf(1L, 3L), ids("", TransactionSort.AMOUNT_LOW).filter { it != 2L })
        val same = listOf(tx(7, "X", 1.0, 5), tx(8, "X", 1.0, 5))
        assertEquals(listOf(7L, 8L), TransactionQuery.apply(same, "", TransactionSort.MERCHANT).map { it.id })
    }
}
