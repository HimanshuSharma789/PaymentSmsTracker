package com.example.paymentsmstracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** MM-exported shared expense not yet pushed to Splitwise (the Splits "Pending" queue). */
const val PENDING_SPLIT = """
    participants IS NOT NULL
      AND TRIM(participants) != ''
      AND UPPER(TRIM(participants)) != 'ME'
      AND reviewStatus = 'exported'
      AND splitwiseExpenseId = 0
"""

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction): Long

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAllTransactionsList(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE reviewStatus = :status ORDER BY timestamp DESC")
    fun getTransactionsByStatus(status: String): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE reviewStatus = :status ORDER BY timestamp DESC")
    suspend fun getTransactionsByStatusList(status: String): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE reviewStatus = :status")
    fun countByStatus(status: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE participants IS NOT NULL
          AND TRIM(participants) != ''
          AND UPPER(TRIM(participants)) != 'ME'
          AND reviewStatus = 'exported'
          AND splitwiseExpenseId = 0
        """
    )
    fun countPendingSplits(): Flow<Int>

    /**
     * Splits screen: pending (MM-exported, not pushed, including failures)
     * plus successful Splitwise pushes.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE participants IS NOT NULL
          AND TRIM(participants) != ''
          AND UPPER(TRIM(participants)) != 'ME'
          AND (
            (reviewStatus = 'exported' AND splitwiseExpenseId = 0)
            OR splitwiseExpenseId != 0
          )
        ORDER BY timestamp DESC
        """
    )
    fun getSplitsForTable(): Flow<List<Transaction>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE participants IS NOT NULL
          AND TRIM(participants) != ''
          AND UPPER(TRIM(participants)) != 'ME'
          AND reviewStatus = 'exported'
          AND splitwiseExpenseId = 0
        ORDER BY timestamp DESC
        """
    )
    suspend fun getPendingSplitTransactionsList(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE id = :transactionId")
    suspend fun getTransactionById(transactionId: Long): Transaction?

    /**
     * Recent categorized rows for merchant-memory fallback when prefs miss.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE TRIM(category) != ''
          AND reviewStatus IN ('reviewed', 'exported')
        ORDER BY lastUpdatedTimestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentCategorizedForMemory(limit: Int): List<Transaction>

    // SMS dedupe candidates (see SmsIngest).
    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :from AND :to")
    suspend fun getTransactionsBetween(from: Long, to: Long): List<Transaction>

    @Query("SELECT * FROM transactions WHERE TRIM(referenceNumber) = :reference COLLATE NOCASE")
    suspend fun findAllByReference(reference: String): List<Transaction>

    @Query("SELECT * FROM transactions WHERE originalSms = :originalSms")
    suspend fun findAllByOriginalSms(originalSms: String): List<Transaction>

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteTransactionsByIds(ids: List<Long>): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    /** Keeps exported splits still waiting for Splitwise: deleting them would lose the push. */
    @Query("DELETE FROM transactions WHERE reviewStatus = :status AND NOT ($PENDING_SPLIT)")
    suspend fun deleteTransactionsByStatus(status: String): Int

    @Query(
        """
        UPDATE transactions
        SET reviewStatus = :newStatus, lastUpdatedTimestamp = :updatedAt
        WHERE id IN (:ids)
        """
    )
    suspend fun updateStatusForIds(ids: List<Long>, newStatus: String, updatedAt: Long)

    @Query(
        """
        DELETE FROM transactions
        WHERE reviewStatus = :status
          AND lastUpdatedTimestamp < :olderThan
          AND NOT ($PENDING_SPLIT)
        """
    )
    suspend fun deleteExportedOlderThan(status: String, olderThan: Long): Int

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    /** Writes only the push outcome, so edits made while the network call ran are kept. */
    @Query(
        """
        UPDATE transactions
        SET splitwiseGroupId = :groupId, splitwiseExpenseId = :expenseId,
            splitwisePushError = :error, lastUpdatedTimestamp = :now
        WHERE id = :id
        """
    )
    suspend fun recordSplitwisePush(id: Long, groupId: Long, expenseId: Long, error: String, now: Long)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)
}
