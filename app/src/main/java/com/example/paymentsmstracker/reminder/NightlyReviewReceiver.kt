package com.example.paymentsmstracker.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.paymentsmstracker.R
import com.example.paymentsmstracker.TransactionListActivity
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.viewmodel.ListFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NightlyReviewReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val config = ConfigStore(appContext).load()
                if (!config.nightlyReviewReminderEnabled) {
                    NightlyReviewScheduler.cancel(appContext)
                    return@launch
                }

                val count = AppDatabase.getDatabase(appContext)
                    .transactionDao()
                    .getTransactionsByStatusList(ReviewStatus.NEEDS_REVIEW)
                    .size

                showNotification(appContext, count)
                NightlyReviewScheduler.schedule(appContext, config)
            } catch (e: Exception) {
                Log.e(TAG, "Nightly review failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, pendingCount: Int) {
        val channelId = CHANNEL_ID
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Nightly review reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminds you to review today's transactions"
        }
        manager.createNotificationChannel(channel)

        val openIntent = Intent(context, TransactionListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TransactionListActivity.EXTRA_FILTER, ListFilter.NEEDS_REVIEW.name)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(context, 0, openIntent, flags)

        val body = if (pendingCount > 0) {
            context.getString(R.string.nightly_review_body_with_count, pendingCount)
        } else {
            context.getString(R.string.nightly_review_body_empty)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.outline_circle_notifications_24)
            .setContentTitle(context.getString(R.string.nightly_review_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping nightly reminder")
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "NightlyReviewReceiver"
        const val CHANNEL_ID = "nightly_review_channel"
        const val NOTIFICATION_ID = 9021
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        NightlyReviewScheduler.applyFromConfig(context.applicationContext)
    }
}
