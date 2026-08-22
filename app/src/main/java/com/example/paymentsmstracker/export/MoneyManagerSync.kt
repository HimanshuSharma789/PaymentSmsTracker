package com.example.paymentsmstracker.export

import android.app.Activity
import com.example.paymentsmstracker.FileHandler
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.data.TransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MoneyManagerSync {

    /** Money Manager's import picker only accepts .tsv or .txt filenames. */
    private const val TSV_MIME = "text/tab-separated-values"

    data class SyncOutcome(
        val transactionCount: Int,
        val filename: String,
        val handoffResult: MoneyManagerHandoff.Result,
        val splitCount: Int = 0
    )

    suspend fun syncToMoneyManager(
        activity: Activity,
        transactions: List<Transaction>,
        config: AppConfig,
        transactionDao: TransactionDao,
        markExported: Boolean
    ): SyncOutcome? {
        if (transactions.isEmpty()) {
            return null
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "money_manager_import_$stamp.tsv"

        val saved = withContext(Dispatchers.IO) {
            val tsv = MoneyManagerExporter.toTsv(transactions, config)
            val fileHandler = FileHandler()
            // Downloads is what Money Manager's own document picker browses.
            val downloadsUri = fileHandler.saveTextFile(activity, fileName, tsv, TSV_MIME)
            val shareUri = fileHandler.saveTextFileForShare(activity, fileName, tsv, TSV_MIME)
            if (downloadsUri == null && shareUri == null) {
                null
            } else {
                shareUri ?: downloadsUri
            }
        }

        if (saved == null) {
            MoneyManagerHandoff.showFailureToast(activity)
            return null
        }

        if (markExported) {
            withContext(Dispatchers.IO) {
                transactionDao.updateStatusForIds(
                    transactions.map { it.id },
                    ReviewStatus.EXPORTED,
                    System.currentTimeMillis()
                )
            }
        }

        val splitCount = transactions.count { SplitwiseExporter.isSplit(it) }
        val handoffResult = MoneyManagerHandoff.promptImport(
            activity = activity,
            shareUri = saved,
            filename = fileName,
            transactionCount = transactions.size,
            splitCount = splitCount
        )
        return SyncOutcome(
            transactionCount = transactions.size,
            filename = fileName,
            handoffResult = handoffResult,
            splitCount = splitCount
        )
    }
}
