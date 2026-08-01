package com.example.paymentsmstracker

import android.app.Application
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.reminder.NightlyReviewScheduler
import com.example.paymentsmstracker.theme.ThemeApplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MyApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ThemeApplier.applyFromConfig(this)
        ThemeApplier.applyDynamicColors(this)
        purgeOldMmExports()
        NightlyReviewScheduler.applyFromConfig(this)
    }

    private fun purgeOldMmExports() {
        appScope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            AppDatabase.getDatabase(this@MyApp).transactionDao()
                .deleteExportedOlderThan(ReviewStatus.EXPORTED, cutoff)
        }
    }
}
