package com.example.paymentsmstracker.viewmodel // Or your preferred package

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.data.TransactionDao

class TransactionListViewModel(application: Application) : AndroidViewModel(application) {

    private val transactionDao: TransactionDao
    val allTransactions: LiveData<List<Transaction>>

    init {
        val database = AppDatabase.getDatabase(application)
        transactionDao = database.transactionDao()
        allTransactions =
            transactionDao.getAllTransactions().asLiveData() // Convert Flow to LiveData
    }

    // You can add methods here to delete transactions, etc., if needed
    // Example:
    // fun deleteTransaction(transaction: Transaction) {
    //     viewModelScope.launch {
    //         transactionDao.deleteTransactionById(transaction.id)
    //     }
    // }
}