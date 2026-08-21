package com.example.paymentsmstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.paymentsmstracker.viewmodel.ListFilter
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission denied. Some features might not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val requestSmsPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val receiveSmsGranted = permissions[Manifest.permission.RECEIVE_SMS] == true
            if (receiveSmsGranted) {
                Toast.makeText(this, "SMS reading permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "SMS reading permission denied. Transaction tracking from SMS will not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askForNotificationPermission()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbarHome)
        setSupportActionBar(toolbar)

        findViewById<Button>(R.id.your_button_to_enable_notifications)?.setOnClickListener {
            askForNotificationPermission()
        }
        findViewById<Button>(R.id.button_go_to_transaction)?.setOnClickListener {
            startActivity(Intent(this, TransactionEntryActivity::class.java))
        }
        findViewById<Button>(R.id.button_review_today)?.setOnClickListener {
            startActivity(
                Intent(this, TransactionListActivity::class.java).apply {
                    putExtra(TransactionListActivity.EXTRA_FILTER, ListFilter.NEEDS_REVIEW.name)
                }
            )
        }
        findViewById<Button>(R.id.button_view_all_transactions)?.setOnClickListener {
            startActivity(
                Intent(this, TransactionListActivity::class.java).apply {
                    putExtra(TransactionListActivity.EXTRA_FILTER, ListFilter.ALL.name)
                }
            )
        }
        findViewById<Button>(R.id.button_view_exported)?.setOnClickListener {
            startActivity(
                Intent(this, TransactionListActivity::class.java).apply {
                    putExtra(TransactionListActivity.EXTRA_FILTER, ListFilter.MM_EXPORTED.name)
                }
            )
        }
        findViewById<Button>(R.id.button_open_splits)?.setOnClickListener {
            startActivity(Intent(this, SplitsActivity::class.java))
        }
        findViewById<Button>(R.id.button_enable_sms_feature)?.setOnClickListener {
            checkAndRequestSmsPermissions()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_admin -> {
                startActivity(Intent(this, AdminActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

        if (permissionsToRequest.isNotEmpty()) {
            var showRationale = false
            for (permission in permissionsToRequest) {
                if (shouldShowRequestPermissionRationale(permission)) {
                    showRationale = true
                    break
                }
            }
            if (showRationale) {
                Log.i("MainActivity", "Showing rationale for SMS permissions.")
                Toast.makeText(
                    this,
                    "This app needs SMS permission to automatically track transactions from messages.",
                    Toast.LENGTH_LONG
                ).show()
            }
            requestSmsPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Toast.makeText(this, "SMS permissions already granted.", Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askForNotificationPermission()
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Toast.makeText(
                        this,
                        "Notification permissions already granted.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
