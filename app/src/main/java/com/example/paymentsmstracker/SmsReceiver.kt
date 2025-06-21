package com.example.paymentsmstracker

//import androidx.compose.ui.text.intl.Locale
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
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

// A unique ID for the notification channel
const val CHANNEL_ID = "transaction_sms_channel"

// A unique ID for the notification itself
const val NOTIFICATION_ID = 101


class SmsReceiver : BroadcastReceiver() {

    private val TAG = "SmsReceiver"

    // --- Regex Patterns ---
    // Improved pattern for INR amounts specifically, including optional commas
    private val amountPattern = Pattern.compile(
        """(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)""", // Capture amount like "INR 129.25" or "Rs. 1,234.50"
        Pattern.CASE_INSENSITIVE
    )

    // Pattern to capture merchant after "to " and before " on " or " with reference" or end of relevant phrase
    private val merchantPattern = Pattern.compile(
        """to\s+(.+?)\s+on""", // Simpler and more direct
        Pattern.CASE_INSENSITIVE
    )
    // Simpler merchant pattern if the above is too greedy or complex:
    // private val merchantPattern = Pattern.compile("""to\s+([\w\s.&'-]+?)(?=\s+on\s|\s+with reference|\.|$)""", Pattern.CASE_INSENSITIVE)


    // Pattern for date like "13-Jun-25" or "13/06/2025" or "13 Jun 2025" etc.
    // This needs to be flexible. The example format "dd-MMM-yy" is specific.
    private val datePattern = Pattern.compile(
        """on\s+(\d{1,2}[-/]\w{3}[-/]\d{2,4}|\d{1,2}\s\w{3}\s\d{2,4})""", // "13-Jun-25" or "13 Jun 25"
        Pattern.CASE_INSENSITIVE
    )

    private val referencePattern = Pattern.compile(
        """with reference\s+(\d+[\w.-]*)""", // Captures alphanumeric reference numbers
        Pattern.CASE_INSENSITIVE
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            val messages: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (smsMessage in messages) {
                val messageBody = smsMessage.messageBody
                val sender = smsMessage.originatingAddress // Sender's phone number

                Log.d(TAG, "SMS Received from $sender: $messageBody")


                // --- More Specific Parsing Logic ---
                // First check if it's likely a payment message (adjust keywords as needed)
                if (messageBody.contains("paid", ignoreCase = true) ||
                    messageBody.contains("debited", ignoreCase = true) ||
                    messageBody.contains("spent", ignoreCase = true) ||
                    messageBody.contains("transaction of", ignoreCase = true)
                ) {
                    val amount = extractAmount(messageBody)
                    val merchant = extractMerchant(messageBody, sender)
                    val transactionDateMillis = extractTransactionDate(messageBody)
                        ?: System.currentTimeMillis() // Fallback to current time
                    val reference = extractReferenceNumber(messageBody)

                    if (amount != null) {
                        Log.i(
                            TAG,
                            "Debit transaction detected. Amount: $amount, Merchant: $merchant, Date: ${
                                Date(
                                    transactionDateMillis
                                )
                            }, Ref: $reference"
                        )
                        showTransactionNotification(
                            context,
                            amount,
                            merchant,
                            transactionDateMillis,
                            reference,
                            messageBody
                        )
                    } else {
                        Log.w(TAG, "Could not extract amount from: $messageBody")
                    }
                }
            }
        }
    }

    private fun extractAmount(message: String): Double? {
        val matcher = amountPattern.matcher(message)
        return if (matcher.find()) {
            matcher.group(1)?.replace(",", "")?.toDoubleOrNull()
        } else {
            null
        }
    }

    private fun extractMerchant(message: String, sender: String?): String {
        val matcher = merchantPattern.matcher(message)
        if (matcher.find()) {
            // Group 1 will contain the text captured by (.+?)
            return matcher.group(1)?.trim() ?: (sender ?: "Unknown Merchant")
        }
        // Fallback if the specific "to ... on" pattern is not found
        // You might want to add other patterns here or just use the sender
        Log.w(TAG, "Could not extract merchant using 'to ... on' pattern. Message: $message")
        return sender ?: "Unknown Merchant"
    }

    private fun extractTransactionDate(message: String): Long? {
        val matcher = datePattern.matcher(message)
        if (matcher.find()) {
            val dateStr = matcher.group(1)?.trim()
            if (dateStr != null) {
                // Try parsing known formats. This needs to be robust.
                // Format 1: "13-Jun-25"
                try {
                    val sdf = SimpleDateFormat("dd-MMM-yy", Locale.ENGLISH)
                    return sdf.parse(dateStr)?.time
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse date '$dateStr' with dd-MMM-yy: ${e.message}")
                }
                // Format 2: "13 Jun 25"
                try {
                    val sdf = SimpleDateFormat("dd MMM yy", Locale.ENGLISH)
                    return sdf.parse(dateStr)?.time
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse date '$dateStr' with dd MMM yy: ${e.message}")
                }
                // Add more SimpleDateFormat parsers for other common date formats you encounter
                // e.g., "dd/MM/yyyy", "MM/dd/yy", "yyyy-MM-dd"
            }
        }
        return null // Return null if no date found or parsed
    }

    private fun extractReferenceNumber(message: String): String? {
        val matcher = referencePattern.matcher(message)
        return if (matcher.find()) {
            matcher.group(1)?.trim()
        } else {
            null
        }
    }


    private fun showTransactionNotification(
        context: Context,
        amount: Double,
        merchant: String?,
        transactionDateMillis: Long, // Pass the date
        reference: String?,          // Pass the reference
        originalMessage: String
    ) {
        val intent = Intent(context, TransactionEntryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("transaction_amount", amount)
            putExtra("transaction_merchant", merchant)
            putExtra("transaction_date", transactionDateMillis) // Add date to intent
            putExtra("transaction_reference", reference)         // Add reference to intent
            putExtra("original_sms", originalMessage)
        }
        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(context, 0, intent, pendingIntentFlag)

        // Required for Android 8.0 (API 26) and above
        val name = "Transaction Notifications"
        val descriptionText = "Notifications for detected debit transactions"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        // Register the channel with the system
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val formattedDate =
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(transactionDateMillis))
        val contentText =
            "Amount: $amount, Merchant: ${merchant ?: "N/A"} on $formattedDate. Ref: ${reference ?: "N/A"}. Tap to categorize."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_background) // CHANGE THIS to your notification icon
            .setContentTitle("New Transaction Detected")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText)) // For longer text
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            // notificationId is a unique int for each notification that you must define
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show notification.")
                return
            }
            notify(
                System.currentTimeMillis().toInt(),
                builder.build()
            ) // Use unique ID for each notification
            // notify(NOTIFICATION_ID, builder.build())
        }
    }
}