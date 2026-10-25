package com.example.paymentsmstracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.location.LocationCapture
import com.example.paymentsmstracker.sms.ParsedSms
import com.example.paymentsmstracker.sms.SmsIngest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val CHANNEL_ID = "transaction_sms_channel"

class SmsReceiver : BroadcastReceiver() {

    private val tag = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val pendingResult = goAsync()
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress
        if (body.isBlank()) {
            pendingResult.finish()
            return
        }
        val receivedAtMs = messages
            .mapNotNull { msg -> msg.timestampMillis.takeIf { it > 0L } }
            .maxOrNull()
            ?: System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleMessage(context.applicationContext, body, receivedAtMs, sender)
            } catch (e: Exception) {
                Log.e(tag, "Failed to handle SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleMessage(
        context: Context,
        messageBody: String,
        receivedAtMs: Long,
        sender: String?
    ) {
        val config = ConfigStore(context).load()
        val outcome = SmsIngest.ingest(
            db = AppDatabase.getDatabase(context),
            config = config,
            body = messageBody,
            receivedAtMs = receivedAtMs,
            sender = sender,
            insert = config.autoSaveFromSms,
            location = { LocationCapture.current(context) }
        )
        when (outcome) {
            is SmsIngest.Outcome.Accepted -> showNotification(
                context, outcome.transaction, outcome.parsed, messageBody, outcome.id,
                outcome.transaction.account
            )
            is SmsIngest.Outcome.Duplicate -> Log.i(tag, "Skipping duplicate SMS")
            else -> Log.d(tag, "Ignored SMS: $outcome")
        }
    }

    private fun showNotification(
        context: Context,
        transaction: Transaction,
        parsed: ParsedSms,
        originalMessage: String,
        transactionId: Long,
        accountForIntent: String
    ) {
        val intent = Intent(context, TransactionEntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (transactionId > 0L) {
                putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, transactionId)
            } else {
                putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_AMOUNT, parsed.amount)
                putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_MERCHANT, parsed.merchantName)
                putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_DATE, parsed.timestamp)
                putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_REFERENCE, parsed.referenceNumber)
                putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ACCOUNT, accountForIntent)
                putExtra(TransactionEntryActivity.EXTRA_ORIGINAL_SMS, originalMessage)
                transaction.latitude?.let { putExtra(TransactionEntryActivity.EXTRA_LATITUDE, it) }
                transaction.longitude?.let { putExtra(TransactionEntryActivity.EXTRA_LONGITUDE, it) }
            }
        }
        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val requestCode = (transactionId.takeIf { it > 0 } ?: System.currentTimeMillis()).toInt()
        val pendingIntent = PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlag)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transaction Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for detected debit transactions"
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val formattedDate =
            SimpleDateFormat("dd MMM yyyy · h:mm a", Locale.getDefault())
                .format(Date(parsed.timestamp))
        val contentText =
            "₹${"%,.2f".format(parsed.amount)} at ${parsed.merchantName} on $formattedDate. Tap to review."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_sms_24)
            .setContentTitle("New transaction")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            Log.w(tag, "POST_NOTIFICATIONS not granted")
            return
        }
        NotificationManagerCompat.from(context).notify(requestCode, builder.build())
    }
}
