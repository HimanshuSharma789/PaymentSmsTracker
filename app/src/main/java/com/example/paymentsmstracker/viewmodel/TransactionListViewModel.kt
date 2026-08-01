package com.example.paymentsmstracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.data.TransactionDao
import java.util.Calendar

enum class ListFilter {
    ALL,
    NEEDS_REVIEW,
    REVIEWED,
    TODAY,
    MM_EXPORTED
}

class TransactionListViewModel(application: Application) : AndroidViewModel(application) {

    private val transactionDao: TransactionDao =
        AppDatabase.getDatabase(application).transactionDao()

    private val filter = MutableLiveData(ListFilter.NEEDS_REVIEW)

    val allTransactions: LiveData<List<Transaction>> = filter.switchMap { selected ->
        when (selected) {
            ListFilter.NEEDS_REVIEW ->
                transactionDao.getTransactionsByStatus(ReviewStatus.NEEDS_REVIEW).asLiveData()
            ListFilter.REVIEWED ->
                transactionDao.getTransactionsByStatus(ReviewStatus.REVIEWED).asLiveData()
            ListFilter.MM_EXPORTED ->
                transactionDao.getTransactionsByStatus(ReviewStatus.EXPORTED).asLiveData()
            ListFilter.TODAY -> {
                val start = startOfDayMillis()
                val end = start + 24L * 60 * 60 * 1000
                transactionDao.getTransactionsForDay(start, end).asLiveData()
            }
            ListFilter.ALL, null ->
                transactionDao.getAllTransactions().asLiveData()
        }
    }

    fun setFilter(value: ListFilter) {
        filter.value = value
    }

    fun currentFilter(): ListFilter = filter.value ?: ListFilter.NEEDS_REVIEW

    private fun startOfDayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
