package com.example.paymentsmstracker.backup

import com.example.paymentsmstracker.data.MerchantPreference
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.sms.SmsDedupe
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Thrown for a wrong password, a tampered file, or something that is not a backup. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Ledger rows only. Splitwise tokens, AI keys and other prefs are never part of this. */
data class Snapshot(
    val formatVersion: Int,
    val dbVersion: Int,
    val transactions: List<Transaction>,
    val merchantPrefs: List<MerchantPreference>
)

data class MergeResult(
    val transactionsToAdd: List<Transaction>,
    val transactionsSkipped: Int,
    val prefsToAdd: List<MerchantPreference>,
    val prefsSkipped: Int
)

/** File = "PSTB1" + salt(16) + iv(12) + AES-256-GCM(JSON). Key = PBKDF2-HMAC-SHA256(password). */
object LedgerBackup {
    const val FORMAT_VERSION = 1
    const val MIN_PASSWORD = 6
    private val MAGIC = "PSTB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 150_000
    private val rng = SecureRandom()

    fun encode(snapshot: Snapshot, password: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also(rng::nextBytes)
        val iv = ByteArray(IV_LEN).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(toJson(snapshot).toString().toByteArray(Charsets.UTF_8))
        return MAGIC + salt + iv + ct
    }

    fun decode(bytes: ByteArray, password: String): Snapshot {
        val header = MAGIC.size + SALT_LEN + IV_LEN
        if (bytes.size <= header + 16 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("Not a Payment SMS Tracker backup file")
        }
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = bytes.copyOfRange(MAGIC.size + SALT_LEN, header)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.doFinal(bytes, header, bytes.size - header)
        } catch (e: GeneralSecurityException) {
            throw BackupException("Wrong password, or the backup file is damaged", e)
        }
        return try {
            fromJson(JSONObject(String(plain, Charsets.UTF_8)))
        } catch (e: org.json.JSONException) {
            throw BackupException("Backup contents are unreadable", e)
        }
    }

    /**
     * Merge, never overwrite. A backup row is skipped when an existing (or already accepted) row
     * matches by SMS body, by bank reference (6+ chars) + type, or by amount + merchant + timestamp.
     */
    fun merge(
        existingTx: List<Transaction>,
        existingPrefs: List<MerchantPreference>,
        snapshot: Snapshot
    ): MergeResult {
        val known = existingTx.toMutableList()
        val add = mutableListOf<Transaction>()
        for (t in snapshot.transactions) {
            if (known.any { sameTransaction(it, t) }) continue
            val fresh = t.copy(id = 0L) // new local id; the old one may collide
            add += fresh
            known += fresh
        }
        val keys = existingPrefs.map { it.merchantKey }.toMutableSet()
        val prefs = snapshot.merchantPrefs.filter { keys.add(it.merchantKey) }
        return MergeResult(
            add, snapshot.transactions.size - add.size,
            prefs, snapshot.merchantPrefs.size - prefs.size
        )
    }

    internal fun sameTransaction(a: Transaction, b: Transaction): Boolean {
        val bodyA = SmsDedupe.normalizeSms(a.originalSms.orEmpty())
        if (bodyA.isNotEmpty() && bodyA == SmsDedupe.normalizeSms(b.originalSms.orEmpty())) return true
        val refA = a.referenceNumber?.trim().orEmpty()
        val refB = b.referenceNumber?.trim().orEmpty()
        if (refA.length >= 6 && refA.equals(refB, ignoreCase = true)) {
            return a.transactionType.equals(b.transactionType, ignoreCase = true)
        }
        // Two different usable references are two different payments (as in SmsDedupe).
        if (refA.length >= 6 && refB.length >= 6) return false
        return abs(a.amount - b.amount) < 0.005 && a.timestamp == b.timestamp &&
            SmsDedupe.normalizeSms(a.merchantName) == SmsDedupe.normalizeSms(b.merchantName)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun toJson(s: Snapshot) = JSONObject().apply {
        put("formatVersion", s.formatVersion)
        put("dbVersion", s.dbVersion)
        put("transactions", JSONArray(s.transactions.map { tx ->
            JSONObject().apply {
                put("id", tx.id); put("amount", tx.amount); put("merchantName", tx.merchantName)
                put("category", tx.category); put("subcategory", tx.subcategory)
                put("account", tx.account); put("notes", tx.notes ?: JSONObject.NULL)
                put("timestamp", tx.timestamp); put("originalSms", tx.originalSms ?: JSONObject.NULL)
                put("isExpense", tx.isExpense); put("referenceNumber", tx.referenceNumber ?: JSONObject.NULL)
                put("participants", tx.participants); put("splitMode", tx.splitMode)
                put("splitAllocations", tx.splitAllocations); put("reviewStatus", tx.reviewStatus)
                put("transactionType", tx.transactionType); put("splitwiseGroupId", tx.splitwiseGroupId)
                put("splitwiseExpenseId", tx.splitwiseExpenseId)
                put("splitwiseParticipantIds", tx.splitwiseParticipantIds)
                put("splitwisePushError", tx.splitwisePushError)
                put("latitude", tx.latitude ?: JSONObject.NULL)
                put("longitude", tx.longitude ?: JSONObject.NULL)
                put("lastUpdatedTimestamp", tx.lastUpdatedTimestamp)
            }
        }))
        put("merchantPrefs", JSONArray(s.merchantPrefs.map { p ->
            JSONObject().apply {
                put("merchantKey", p.merchantKey); put("displayMerchant", p.displayMerchant)
                put("category", p.category); put("subcategory", p.subcategory)
                put("account", p.account); put("transactionType", p.transactionType)
                put("updatedAt", p.updatedAt)
            }
        }))
    }

    private fun JSONObject.strOrNull(k: String) = if (isNull(k)) null else getString(k)
    private fun JSONObject.dblOrNull(k: String) = if (isNull(k)) null else getDouble(k)

    private fun fromJson(o: JSONObject): Snapshot {
        val ver = o.getInt("formatVersion")
        if (ver > FORMAT_VERSION) throw BackupException("Backup was made by a newer app version")
        val txs = o.getJSONArray("transactions")
        val prefs = o.getJSONArray("merchantPrefs")
        return Snapshot(
            ver, o.getInt("dbVersion"),
            (0 until txs.length()).map { i ->
                val t = txs.getJSONObject(i)
                Transaction(
                    id = t.getLong("id"), amount = t.getDouble("amount"),
                    merchantName = t.getString("merchantName"), category = t.getString("category"),
                    subcategory = t.getString("subcategory"), account = t.getString("account"),
                    notes = t.strOrNull("notes"), timestamp = t.getLong("timestamp"),
                    originalSms = t.strOrNull("originalSms"), isExpense = t.getBoolean("isExpense"),
                    referenceNumber = t.strOrNull("referenceNumber"),
                    participants = t.getString("participants"), splitMode = t.getString("splitMode"),
                    splitAllocations = t.getString("splitAllocations"),
                    reviewStatus = t.getString("reviewStatus"),
                    transactionType = t.getString("transactionType"),
                    splitwiseGroupId = t.getLong("splitwiseGroupId"),
                    splitwiseExpenseId = t.getLong("splitwiseExpenseId"),
                    splitwiseParticipantIds = t.getString("splitwiseParticipantIds"),
                    splitwisePushError = t.getString("splitwisePushError"),
                    latitude = t.dblOrNull("latitude"), longitude = t.dblOrNull("longitude"),
                    lastUpdatedTimestamp = t.getLong("lastUpdatedTimestamp")
                )
            },
            (0 until prefs.length()).map { i ->
                val p = prefs.getJSONObject(i)
                MerchantPreference(
                    p.getString("merchantKey"), p.getString("displayMerchant"),
                    p.getString("category"), p.getString("subcategory"), p.getString("account"),
                    p.getString("transactionType"), p.getLong("updatedAt")
                )
            }
        )
    }
}
