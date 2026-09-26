package com.example.paymentsmstracker

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.viewmodel.ListFilter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class HomePrimaryAction {
        REVIEW,
        EXPORT_MM,
        SPLITS,
        ADD
    }

    private lateinit var textHomeSummary: TextView
    private lateinit var textHomeReviewCount: TextView
    private lateinit var textHomeReadyCount: TextView
    private lateinit var textHomeSplitsCount: TextView
    private lateinit var buttonHomePrimary: MaterialButton

    private var primaryAction = HomePrimaryAction.ADD
    private var needsReviewCount = 0
    private var readyForMmCount = 0
    private var pendingSplitsCount = 0

    /** Core permissions (SMS + notifications) are asked once on a clean install; after that only from Settings → Permissions. */
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbarHome)
        setSupportActionBar(toolbar)

        textHomeSummary = findViewById(R.id.textHomeSummary)
        textHomeReviewCount = findViewById(R.id.textHomeReviewCount)
        textHomeReadyCount = findViewById(R.id.textHomeReadyCount)
        textHomeSplitsCount = findViewById(R.id.textHomeSplitsCount)
        buttonHomePrimary = findViewById(R.id.buttonHomePrimary)

        findViewById<View>(R.id.rowHomeReview).setOnClickListener {
            openTransactionList(ListFilter.NEEDS_REVIEW)
        }
        findViewById<View>(R.id.rowHomeReadyMm).setOnClickListener {
            openTransactionList(ListFilter.REVIEWED)
        }
        findViewById<View>(R.id.rowHomeSplits).setOnClickListener {
            startActivity(Intent(this, SplitsActivity::class.java))
        }
        buttonHomePrimary.setOnClickListener { runPrimaryAction() }
        findViewById<MaterialButton>(R.id.button_go_to_transaction).setOnClickListener {
            startActivity(Intent(this, TransactionEntryActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.button_view_all_transactions).setOnClickListener {
            openTransactionList(ListFilter.ALL)
        }

        observeHomeCounts()
        askStartupPermissionsOnce()
    }

    private fun askStartupPermissionsOnce() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_STARTUP_PERMISSIONS_ASKED, false)) return
        prefs.edit().putBoolean(KEY_STARTUP_PERMISSIONS_ASKED, true).apply()
        val missing = PermissionsPanel.corePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) startupPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun observeHomeCounts() {
        val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    dao.countByStatus(ReviewStatus.NEEDS_REVIEW),
                    dao.countByStatus(ReviewStatus.REVIEWED),
                    dao.countPendingSplits()
                ) { review, ready, splits ->
                    Triple(review, ready, splits)
                }.collect { (review, ready, splits) ->
                    needsReviewCount = review
                    readyForMmCount = ready
                    pendingSplitsCount = splits
                    renderHomeCounts()
                }
            }
        }
    }

    private fun renderHomeCounts() {
        textHomeReviewCount.text = needsReviewCount.toString()
        textHomeReadyCount.text = readyForMmCount.toString()
        textHomeSplitsCount.text = pendingSplitsCount.toString()

        val totalLeft = needsReviewCount + readyForMmCount + pendingSplitsCount
        textHomeSummary.text = if (totalLeft == 0) {
            getString(R.string.home_summary_caught_up)
        } else {
            getString(
                R.string.home_summary,
                needsReviewCount,
                readyForMmCount,
                pendingSplitsCount
            )
        }

        primaryAction = when {
            needsReviewCount > 0 -> HomePrimaryAction.REVIEW
            readyForMmCount > 0 -> HomePrimaryAction.EXPORT_MM
            pendingSplitsCount > 0 -> HomePrimaryAction.SPLITS
            else -> HomePrimaryAction.ADD
        }
        when (primaryAction) {
            HomePrimaryAction.REVIEW -> {
                buttonHomePrimary.setText(R.string.home_cta_review)
                buttonHomePrimary.setIconResource(R.drawable.baseline_history_24)
            }
            HomePrimaryAction.EXPORT_MM -> {
                buttonHomePrimary.setText(R.string.home_cta_export)
                buttonHomePrimary.setIconResource(R.drawable.outline_task_alt_24)
            }
            HomePrimaryAction.SPLITS -> {
                buttonHomePrimary.setText(R.string.home_cta_splits)
                buttonHomePrimary.setIconResource(R.drawable.outline_checklist_24)
            }
            HomePrimaryAction.ADD -> {
                buttonHomePrimary.setText(R.string.home_cta_caught_up)
                buttonHomePrimary.setIconResource(R.drawable.outline_attach_money_24)
            }
        }
    }

    private fun runPrimaryAction() {
        when (primaryAction) {
            HomePrimaryAction.REVIEW -> openTransactionList(ListFilter.NEEDS_REVIEW)
            HomePrimaryAction.EXPORT_MM -> openTransactionList(ListFilter.REVIEWED)
            HomePrimaryAction.SPLITS -> startActivity(Intent(this, SplitsActivity::class.java))
            HomePrimaryAction.ADD ->
                startActivity(Intent(this, TransactionEntryActivity::class.java))
        }
    }

    private fun openTransactionList(filter: ListFilter) {
        startActivity(
            Intent(this, TransactionListActivity::class.java).apply {
                putExtra(TransactionListActivity.EXTRA_FILTER, filter.name)
            }
        )
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

    companion object {
        private const val PREFS_NAME = "main_activity"
        private const val KEY_STARTUP_PERMISSIONS_ASKED = "startup_permissions_asked"
    }
}
