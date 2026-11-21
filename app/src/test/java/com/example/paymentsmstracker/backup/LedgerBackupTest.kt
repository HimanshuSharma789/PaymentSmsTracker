package com.example.paymentsmstracker.backup

import com.example.paymentsmstracker.data.MerchantPreference
import com.example.paymentsmstracker.data.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class LedgerBackupTest {
    private fun tx(id: Long, amt: Double = 10.0, m: String = "Cafe", ts: Long = 1000, ref: String? = null, sms: String? = null) =
        Transaction(id = id, amount = amt, merchantName = m, timestamp = ts, referenceNumber = ref, originalSms = sms,
            participants = "ME,A", latitude = 1.5, notes = null)
    private fun pref(k: String, c: String = "Food") = MerchantPreference(k, k, c, "", "Cash", "Expense", 5)
    private fun snap(t: List<Transaction>, p: List<MerchantPreference> = emptyList()) = Snapshot(1, 9, t, p)

    @Test fun roundTrip() {
        val s = snap(listOf(tx(1, ref = "REF123456", sms = "Paid Rs 10"), tx(2)), listOf(pref("cafe")))
        val back = LedgerBackup.decode(LedgerBackup.encode(s, "secret1"), "secret1")
        assertEquals(s, back)
        assertNull(back.transactions[1].originalSms)
    }

    @Test fun wrongPasswordThrows() {
        val bytes = LedgerBackup.encode(snap(listOf(tx(1))), "secret1")
        try { LedgerBackup.decode(bytes, "secret2"); fail() } catch (_: BackupException) {}
    }

    @Test fun tamperedThrows() {
        val bytes = LedgerBackup.encode(snap(listOf(tx(1))), "secret1")
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        try { LedgerBackup.decode(bytes, "secret1"); fail() } catch (_: BackupException) {}
        try { LedgerBackup.decode(ByteArray(5), "secret1"); fail() } catch (_: BackupException) {}
    }

    @Test fun mergeSkipsExistingAndKeepsExisting() {
        val existing = listOf(tx(1, ref = "REF123456", amt = 99.0), tx(2, sms = "hello  world"), tx(3, m = "Shop", ts = 7))
        val incoming = snap(
            listOf(
                tx(10, ref = "ref123456"),            // same ref+type
                tx(11, sms = "Hello world"),          // same sms
                tx(12, m = "shop", ts = 7),           // amount+merchant+ts
                tx(13, amt = 55.0, ref = "NEWREF999"),            // new
                tx(13, amt = 55.0, ref = "NEWREF999")             // duplicate inside the backup
            ),
            listOf(pref("cafe", "Other"), pref("new"))
        )
        val r = LedgerBackup.merge(existing, listOf(pref("cafe")), incoming)
        assertEquals(1, r.transactionsToAdd.size)
        assertEquals(0L, r.transactionsToAdd[0].id)
        assertEquals(4, r.transactionsSkipped)
        assertEquals(listOf("new"), r.prefsToAdd.map { it.merchantKey })
        assertEquals(1, r.prefsSkipped)
    }
}
