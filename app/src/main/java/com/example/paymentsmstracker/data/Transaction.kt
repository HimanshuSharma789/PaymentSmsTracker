package com.example.paymentsmstracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.paymentsmstracker.split.SplitMode

object ReviewStatus {
    const val NEEDS_REVIEW = "needs_review"
    const val REVIEWED = "reviewed"
    const val EXPORTED = "exported"

    fun canPushToSplitwise(status: String): Boolean =
        status == REVIEWED || status == EXPORTED
}

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val amount: Double,
    val merchantName: String,
    val category: String = "",
    val subcategory: String = "",
    val account: String = "",
    val notes: String? = null,
    val timestamp: Long,
    val originalSms: String? = null,
    val isExpense: Boolean = true,
    val referenceNumber: String? = null,
    /** Comma-separated participant names, e.g. "ME,PUNEET,KUSHAGRA" */
    val participants: String = "",
    /**
     * equal | exact | percent | weight
     * Blank/equal with only participants means classic equal split.
     */
    val splitMode: String = SplitMode.EQUAL,
    /**
     * Encoded allocation inputs for non-equal modes, e.g. "ME:25,PUNEET:35,KUSHAGRA:40"
     * Values mean amounts (exact), percentages, or share weights depending on [splitMode].
     */
    val splitAllocations: String = "",
    val reviewStatus: String = ReviewStatus.NEEDS_REVIEW,
    /** Expense | Income | Transfer-Out */
    val transactionType: String = "Expense",
    /** Splitwise group to push into; 0 = none selected */
    val splitwiseGroupId: Long = 0L,
    /** Splitwise expense id after a successful API push; 0 = not pushed */
    val splitwiseExpenseId: Long = 0L,
    /**
     * Participant label → Splitwise user id, e.g. "ME=111,Puneet S=222".
     * Empty for legacy transactions that rely on the Admin friend map.
     */
    val splitwiseParticipantIds: String = "",
    /** Last Splitwise push failure message; blank when pending or pushed OK. */
    val splitwisePushError: String = "",
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    fun participantList(): List<String> =
        participants.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun participantIdMap(): Map<String, Long> = parseParticipantIds(splitwiseParticipantIds)

    companion object {
        fun encodeParticipantIds(map: Map<String, Long>): String =
            map.entries
                .filter { it.key.isNotBlank() && it.value > 0L }
                .joinToString(",") { "${it.key.trim()}=${it.value}" }

        fun parseParticipantIds(raw: String?): Map<String, Long> {
            if (raw.isNullOrBlank()) return emptyMap()
            val map = linkedMapOf<String, Long>()
            raw.split(',', '|').forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@forEach
                val sep = when {
                    trimmed.contains('=') -> '='
                    trimmed.contains(':') -> ':'
                    else -> return@forEach
                }
                val idx = trimmed.indexOf(sep)
                if (idx <= 0 || idx == trimmed.lastIndex) return@forEach
                val name = trimmed.substring(0, idx).trim()
                val id = trimmed.substring(idx + 1).trim().toLongOrNull() ?: return@forEach
                if (name.isNotEmpty() && id > 0L) map[name] = id
            }
            return map
        }
    }
}
