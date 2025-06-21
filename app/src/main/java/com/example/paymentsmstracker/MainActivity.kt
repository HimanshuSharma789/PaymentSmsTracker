package com.example.paymentsmstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {


    // Declare the launcher at the top of your Activity/Fragment:
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. You can now safely post notifications.
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
                // You could trigger a function here that might need to post a notification
                // or set a flag indicating permission is available.
            } else {
                // Permission is denied. Explain to the user why the permission is needed
                // and how they can grant it manually from app settings.
                Toast.makeText(
                    this,
                    "Notification permission denied. Some features might not work.",
                    Toast.LENGTH_LONG
                ).show()
                // You could show a dialog explaining the importance of the permission
                // and guiding them to settings.
            }
        }

    private val requestSmsPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val receiveSmsGranted = permissions[Manifest.permission.RECEIVE_SMS] == true
            // val readSmsGranted = permissions[Manifest.permission.READ_SMS] ?: false // If you request READ_SMS

            if (receiveSmsGranted) {
                Toast.makeText(this, "SMS reading permission granted!", Toast.LENGTH_SHORT).show()
                // Now your SmsReceiver should work
            } else {
                Toast.makeText(
                    this,
                    "SMS reading permission denied. Transaction tracking from SMS will not work.",
                    Toast.LENGTH_LONG
                ).show()
                // Explain to the user why this is needed and how to grant it from settings
            }

            // You can also check for POST_NOTIFICATIONS permission here or separately
            // For Android 13+ ensure notification permission is also sought
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askForNotificationPermission()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Your main activity layout

        // Example: A button to explicitly request permission or trigger a feature
        val enableNotificationsButton: Button? =
            findViewById(R.id.your_button_to_enable_notifications)
        enableNotificationsButton?.setOnClickListener {
            askForNotificationPermission()
        }

        val navigateToTransactionButton: Button? = findViewById(R.id.button_go_to_transaction)
        navigateToTransactionButton?.setOnClickListener {
            val intent = Intent(this, TransactionEntryActivity::class.java)
            startActivity(intent)
        }

        val viewAllTransactionsButton: Button? = findViewById(R.id.button_view_all_transactions)
        viewAllTransactionsButton?.setOnClickListener {
            val intent = Intent(this, TransactionListActivity::class.java)
            startActivity(intent)
        }

        val enableSmsFeatureButton: Button? =
            findViewById(R.id.button_enable_sms_feature) // Assuming you add this button
        enableSmsFeatureButton?.setOnClickListener {
            checkAndRequestSmsPermissions()
        }

        // Or, request it when the app starts if crucial (consider UX)
        // askForNotificationPermission() // Call it here if you want to request on launch
    }

    private fun checkAndRequestSmsPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECEIVE_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }
        // If you need READ_SMS:
        // if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
        //     permissionsToRequest.add(Manifest.permission.READ_SMS)
        // }

        if (permissionsToRequest.isNotEmpty()) {
            // Check if we should show rationale
            var showRationale = false
            for (permission in permissionsToRequest) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (shouldShowRequestPermissionRationale(permission)) {
                        showRationale = true
                        break
                    }
                }
            }

            if (showRationale) {
                // Explain to the user why you need the permissions.
                // Show a dialog or a snackbar.
                Log.i("MainActivity", "Showing rationale for SMS permissions.")
                Toast.makeText(
                    this,
                    "This app needs SMS permission to automatically track transactions from messages.",
                    Toast.LENGTH_LONG
                ).show()
                // After showing rationale, you'd typically have a button in the dialog
                // that then calls:
                // requestSmsPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                // For simplicity here, we'll proceed to request.
                requestSmsPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            } else {
                // Directly request the permission
                Log.d("MainActivity", "Requesting SMS permissions: $permissionsToRequest")
                requestSmsPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            }
        } else {
            Log.d("MainActivity", "All required SMS permissions are already granted.")
            Toast.makeText(this, "SMS permissions already granted.", Toast.LENGTH_SHORT).show()
            // All necessary SMS permissions are granted.
            // For Android 13+ ensure notification permission is also sought
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askForNotificationPermission()
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is already granted
                    Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted.")
                    Toast.makeText(
                        this,
                        "Notification permissions already granted.",
                        Toast.LENGTH_SHORT
                    ).show()
                    // You can proceed with tasks that require notifications
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Explain to the user why you need the permission.
                    // This is shown if the user has previously denied the request.
                    // Show a dialog or a snackbar.
                    Log.i("MainActivity", "Showing rationale for POST_NOTIFICATIONS permission.")
                    // Example: showRationaleDialog() and then call requestPermissionLauncher.launch(...)
                    // For simplicity, directly launching here:
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> {
                    // Directly request the permission
                    Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // For Android 12L and below, no runtime permission needed for notifications
            Log.d("MainActivity", "POST_NOTIFICATIONS permission not required for this API level.")
        }
    }


    // ... other methods ...
}