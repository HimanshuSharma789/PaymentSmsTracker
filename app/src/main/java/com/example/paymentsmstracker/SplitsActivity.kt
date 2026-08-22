package com.example.paymentsmstracker

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.adapter.SplitRowUi
import com.example.paymentsmstracker.adapter.SplitTableAdapter
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.splitwise.SplitwiseApi
import com.example.paymentsmstracker.splitwise.SplitwiseAuthStore
import com.example.paymentsmstracker.splitwise.SplitwisePusher
import com.example.paymentsmstracker.splitwise.SplitwiseSessionEvents
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SplitsActivity : AppCompatActivity() {

    private enum class SplitFilter { PENDING, SUCCESS }

    private lateinit var adapter: SplitTableAdapter
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private lateinit var pushButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private var selectionModeActive = false
    private var lastCount = 0
    private var filter = SplitFilter.PENDING
    private var allRows: List<SplitRowUi> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splits)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbarSplits)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (selectionModeActive) exitSelectionMode() else finish()
        }
        SplitwiseSessionEvents.observe(this, this)

        empty = findViewById(R.id.textSplitsEmpty)
        summary = findViewById(R.id.textSplitsSummary)
        pushButton = findViewById(R.id.buttonPushSplitwiseFromSplits)
        exportButton = findViewById(R.id.buttonExportSplitwiseFromSplits)
        val recycler: RecyclerView = findViewById(R.id.recyclerSplitsTable)
        adapter = SplitTableAdapter(
            onOpen = { id ->
                startActivity(
                    Intent(this, TransactionEntryActivity::class.java).apply {
                        putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, id)
                    }
                )
            },
            onSelectionChanged = { count ->
                if (selectionModeActive) refreshSelectionTitle(count)
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        setupFilterChips()

        exportButton.setOnClickListener {
            exportSplitwise()
        }
        pushButton.setOnClickListener {
            if (selectionModeActive) {
                confirmPushSelected()
            } else {
                startSelectionMode()
            }
        }

        lifecycleScope.launch {
            AppDatabase.getDatabase(applicationContext).transactionDao()
                .getSplitsForTable()
                .collectLatest { txs ->
                    allRows = txs.map { SplitTableAdapter.fromTransaction(it) }
                    renderVisibleRows()
                }
        }
    }

    private fun setupFilterChips() {
        val group = findViewById<ChipGroup>(R.id.chipGroupSplits)
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chipSplitsSuccess -> SplitFilter.SUCCESS
                else -> SplitFilter.PENDING
            }
            if (checkedIds.isEmpty()) {
                findViewById<Chip>(R.id.chipSplitsPending).isChecked = true
            }
            if (selectionModeActive) {
                exitSelectionMode()
            }
            renderVisibleRows()
            invalidateOptionsMenu()
        }
    }

    private fun visibleRows(): List<SplitRowUi> = allRows.filter { row ->
        if (filter == SplitFilter.SUCCESS) row.pushed else !row.pushed
    }

    private fun isPendingTab(): Boolean = filter == SplitFilter.PENDING

    private fun renderVisibleRows() {
        val rows = visibleRows()
        adapter.submitList(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(
            if (isPendingTab()) R.string.no_pending_splits else R.string.no_success_splits
        )
        lastCount = rows.size
        val friendsTotal = rows.fold(BigDecimal.ZERO) { acc, row ->
            acc.add(BigDecimal(row.friendsOwe))
        }
        summary.text = if (rows.isEmpty()) {
            empty.text
        } else if (isPendingTab()) {
            buildString {
                append(
                    getString(
                        R.string.pending_splits_summary,
                        rows.size,
                        friendsTotal.setScale(2).toPlainString()
                    )
                )
                val failed = rows.count { it.failed }
                if (failed > 0) {
                    append(" · ")
                    append(getString(R.string.pending_splits_failed_count, failed))
                }
            }
        } else {
            getString(
                R.string.success_splits_summary,
                rows.size,
                friendsTotal.setScale(2).toPlainString()
            )
        }
        val showPushActions = isPendingTab()
        adapter.setAllowSelection(showPushActions)
        pushButton.visibility = if (showPushActions) View.VISIBLE else View.GONE
        exportButton.visibility = if (showPushActions) View.VISIBLE else View.GONE
        if (!selectionModeActive) {
            supportActionBar?.title = getString(R.string.splits_title)
            supportActionBar?.subtitle = if (rows.isEmpty()) {
                null
            } else {
                getString(R.string.list_count_items, rows.size)
            }
        }
        if (selectionModeActive && (rows.isEmpty() || !isPendingTab())) {
            exitSelectionMode()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(
            if (selectionModeActive) R.menu.splits_selection_menu else R.menu.splits_menu,
            menu
        )
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (!selectionModeActive) {
            menu.findItem(R.id.action_select_pending_splits)?.isVisible = isPendingTab()
            menu.findItem(R.id.action_export_pending_csv)?.isVisible = isPendingTab()
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_pending_splits -> {
                startSelectionMode()
                true
            }
            R.id.action_select_all_splits -> {
                adapter.selectAllVisible()
                true
            }
            R.id.action_push_selected_splits -> {
                confirmPushSelected()
                true
            }
            R.id.action_cancel_split_selection -> {
                exitSelectionMode()
                true
            }
            R.id.action_export_pending_csv -> {
                exportSplitwise()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startSelectionMode() {
        if (!isPendingTab()) {
            Toast.makeText(this, R.string.splits_already_pushed, Toast.LENGTH_SHORT).show()
            return
        }
        if (adapter.currentList.isEmpty()) {
            Toast.makeText(this, R.string.no_pending_splits, Toast.LENGTH_SHORT).show()
            return
        }
        selectionModeActive = true
        adapter.enterSelectionMode()
        pushButton.setText(R.string.splitwise_push_selected)
        invalidateOptionsMenu()
        refreshSelectionTitle(0)
    }

    private fun exitSelectionMode() {
        if (!selectionModeActive) return
        selectionModeActive = false
        adapter.exitSelectionMode()
        pushButton.setText(R.string.splitwise_select_and_push)
        supportActionBar?.title = getString(R.string.splits_title)
        supportActionBar?.subtitle =
            if (lastCount > 0) getString(R.string.list_count_items, lastCount) else null
        invalidateOptionsMenu()
    }

    private fun refreshSelectionTitle(selectedCount: Int) {
        supportActionBar?.title = if (selectedCount == 0) {
            getString(R.string.select_hint)
        } else {
            getString(R.string.selected_count, selectedCount)
        }
        supportActionBar?.subtitle = null
    }

    private fun confirmPushSelected() {
        if (!isPendingTab()) {
            Toast.makeText(this, R.string.splits_already_pushed, Toast.LENGTH_SHORT).show()
            return
        }
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.no_transactions_selected, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.splitwise_push_selected_title)
            .setMessage(getString(R.string.splitwise_push_selected_message, ids.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.splitwise_push_selected) { _, _ ->
                pushSelected(ids)
            }
            .show()
    }

    private fun exportSplitwise() {
        lifecycleScope.launch {
            val txs = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).transactionDao()
                    .getPendingSplitTransactionsList()
            }
            if (txs.isEmpty()) {
                Toast.makeText(this@SplitsActivity, R.string.no_pending_splits, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val csv = SplitwiseExporter.toCsv(txs, onlySplits = true)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "splitwise_import_$stamp.csv"
            FileHandler().saveCsvFileToDownloads(this@SplitsActivity, fileName, csv)
            Toast.makeText(this@SplitsActivity, "Exported → $fileName", Toast.LENGTH_LONG).show()
        }
    }

    private fun pushSelected(ids: Set<Long>) {
        val store = SplitwiseAuthStore(this)
        val auth = store.load()
        if (!auth.isSignedIn) {
            Toast.makeText(this, R.string.splitwise_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        pushButton.isEnabled = false
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                val txs = dao.getPendingSplitTransactionsList().filter { it.id in ids }
                if (txs.isEmpty()) {
                    return@withContext getString(R.string.no_pending_splits)
                }
                val api = SplitwiseApi.from(store)
                var ok = 0
                var failed = 0
                txs.forEach { tx ->
                    val prepared = if (tx.splitwiseGroupId > 0L) {
                        tx
                    } else if (auth.defaultGroupId > 0L) {
                        tx.copy(splitwiseGroupId = auth.defaultGroupId)
                    } else {
                        tx
                    }
                    val result = SplitwisePusher.pushOne(api, auth, prepared)
                    if (result.isSuccess && result.expenseId != null) {
                        dao.updateTransaction(
                            prepared.copy(
                                splitwiseGroupId = prepared.splitwiseGroupId,
                                splitwiseExpenseId = result.expenseId,
                                splitwisePushError = "",
                                lastUpdatedTimestamp = System.currentTimeMillis()
                            )
                        )
                        ok++
                    } else {
                        failed++
                        dao.updateTransaction(
                            prepared.copy(
                                splitwiseGroupId = prepared.splitwiseGroupId,
                                splitwisePushError = (result.error ?: "Push failed").take(240),
                                lastUpdatedTimestamp = System.currentTimeMillis()
                            )
                        )
                        if (result.unauthorized) {
                            return@withContext "SESSION_EXPIRED"
                        }
                    }
                }
                getString(R.string.splitwise_push_result, ok, failed)
            }
            pushButton.isEnabled = true
            if (message == "SESSION_EXPIRED") {
                SplitwiseSessionEvents.promptReauth(this@SplitsActivity)
            } else {
                Toast.makeText(this@SplitsActivity, message, Toast.LENGTH_LONG).show()
                exitSelectionMode()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (selectionModeActive) {
            exitSelectionMode()
            return true
        }
        finish()
        return true
    }
}
