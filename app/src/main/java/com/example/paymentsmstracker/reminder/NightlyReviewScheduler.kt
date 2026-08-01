package com.example.paymentsmstracker.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.config.ConfigStore
import java.util.Calendar

object NightlyReviewScheduler {

    private const val TAG = "NightlyReviewScheduler"
    const val REQUEST_CODE = 4201

    fun applyFromConfig(context: Context) {
        val config = ConfigStore(context).load()
        if (config.nightlyReviewReminderEnabled) {
            schedule(context, config)
        } else {
            cancel(context)
        }
    }

    fun schedule(context: Context, config: AppConfig = ConfigStore(context).load()) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context)
        val triggerAt = nextTriggerMillis(config.nightlyReviewHour, config.nightlyReviewMinute)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.i(TAG, "Scheduled nightly review for $triggerAt")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule nightly review", e)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
        Log.i(TAG, "Cancelled nightly review reminder")
    }

    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NightlyReviewReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
