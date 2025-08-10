package com.example.paymentsmstracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow // For observing data changes

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE) // Replace if a transaction with the same ID exists (though less likely with autoGenerate)
    suspend fun insertTransaction(transaction: Transaction) // `suspend` for coroutines

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<Transaction>> // Use Flow for reactive updates

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactionsList(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE id = :transactionId")
    suspend fun getTransactionById(transactionId: Long): Transaction?

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteTransactionById(transactionId: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Update
    suspend fun updateTransaction(transaction: Transaction)

    @Delete
    suspend fun deleteTransaction(transaction: Transaction)

    // You can add more specific queries here, e.g., filter by category, date range, etc.
    @Query("SELECT * FROM transactions WHERE category = :categoryName ORDER BY timestamp DESC")
    fun getTransactionsByCategory(categoryName: String): Flow<List<Transaction>>
}