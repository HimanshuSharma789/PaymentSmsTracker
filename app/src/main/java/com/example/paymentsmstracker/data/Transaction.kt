package com.example.paymentsmstracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.paymentsmstracker.split.SplitMode

object ReviewStatus {
    const val NEEDS_REVIEW = "needs_review"
    const val REVIEWED = "reviewed"
    const val EXPORTED = "exported"
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
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    fun participantList(): List<String> =
        participants.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
