package com.example.paymentsmstracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    @Query(
        """
        SELECT * FROM transactions
        WHERE timestamp >= :dayStart AND timestamp < :dayEnd
        ORDER BY timestamp DESC
        """
    )
    fun getTransactionsForDay(dayStart: Long, dayEnd: Long): Flow<List<Transaction>>

    /** Shared expenses: participants set and not only ME */
    @Query(
        """
        SELECT * FROM transactions
        WHERE participants IS NOT NULL
          AND TRIM(participants) != ''
          AND UPPER(TRIM(participants)) != 'ME'
        ORDER BY timestamp DESC
        """
    )
    fun getSplitTransactions(): Flow<List<Transaction>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE participants IS NOT NULL
          AND TRIM(participants) != ''
          AND UPPER(TRIM(participants)) != 'ME'
        ORDER BY timestamp DESC
        """
    )
    suspend fun getSplitTransactionsList(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE id = :transactionId")
    suspend fun getTransactionById(transactionId: Long): Transaction?

    @Query(
        """
        SELECT * FROM transactions
        WHERE referenceNumber = :reference
        LIMIT 1
        """
    )
    suspend fun findByReference(reference: String): Transaction?

    @Query(
        """
        SELECT * FROM transactions
        WHERE originalSms = :originalSms
        LIMIT 1
        """
    )
    suspend fun findByOriginalSms(originalSms: String): Transaction?

    @Query(
        """
        SELECT * FROM transactions
        WHERE amount = :amount
          AND merchantName = :merchant
          AND timestamp >= :since
        LIMIT 1
        """
    )
    suspend fun findRecentDuplicate(amount: Double, merchant: String, since: Long): Transaction?

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteTransactionById(transactionId: Long)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteTransactionsByIds(ids: List<Long>): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM transactions WHERE reviewStatus = :status")
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
        """
    )
    suspend fun deleteExportedOlderThan(status: String, olderThan: Long): Int

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)
}
