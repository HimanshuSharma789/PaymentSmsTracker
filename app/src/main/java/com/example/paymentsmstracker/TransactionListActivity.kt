package com.example.paymentsmstracker

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.adapter.OnTransactionItemClickListener
import com.example.paymentsmstracker.adapter.TransactionAdapter
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.viewmodel.ListFilter
import com.example.paymentsmstracker.viewmodel.TransactionListViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TransactionListActivity : AppCompatActivity(), OnTransactionItemClickListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateTextView: TextView
    private lateinit var transactionAdapter: TransactionAdapter
    private lateinit var db: AppDatabase
    private val transactionListViewModel: TransactionListViewModel by viewModels()
    private var actionMode: ActionMode? = null

    private val selectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.transaction_selection_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_select_all -> {
                    transactionAdapter.selectAllVisible()
                    true
                }
                R.id.action_delete_selected -> {
                    confirmDeleteSelected()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            transactionAdapter.exitSelectionMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_list)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbarTransactionList)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (transactionAdapter.isSelectionMode()) {
                actionMode?.finish()
            } else {
                finish()
            }
        }

        db = AppDatabase.getDatabase(applicationContext)
        recyclerView = findViewById(R.id.recyclerViewTransactions)
        emptyStateTextView = findViewById(R.id.textViewEmptyState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (transactionAdapter.isSelectionMode()) {
                        actionMode?.finish()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            db.transactionDao().deleteExportedOlderThan(ReviewStatus.EXPORTED, cutoff)
        }

        val initialFilter = intent.getStringExtra(EXTRA_FILTER)?.let {
            runCatching { ListFilter.valueOf(it) }.getOrNull()
        } ?: ListFilter.NEEDS_REVIEW
        if (intent.getStringExtra(EXTRA_FILTER) == "SPLITS") {
            startActivity(Intent(this, SplitsActivity::class.java))
        }
        transactionListViewModel.setFilter(initialFilter)
        checkFilterChip(initialFilter)
        setupFilterChips()

        findViewById<MaterialButton>(R.id.buttonExportReviewed).setOnClickListener {
            exportReviewedToMoneyManager()
        }
        findViewById<MaterialButton>(R.id.buttonExportSplitwise).setOnClickListener {
            exportSplitwise()
        }

        setupRecyclerView()
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.transaction_list_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_splits -> {
                startActivity(Intent(this, SplitsActivity::class.java))
                true
            }
            R.id.action_select_transactions -> {
                startSelectionMode()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupFilterChips() {
        val group = findViewById<ChipGroup>(R.id.chipGroupFilter)
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipNeedsReview -> ListFilter.NEEDS_REVIEW
                R.id.chipToday -> ListFilter.TODAY
                R.id.chipReviewed -> ListFilter.REVIEWED
                R.id.chipMmExported -> ListFilter.MM_EXPORTED
                R.id.chipAll -> ListFilter.ALL
                null -> ListFilter.ALL
                else -> ListFilter.ALL
            }
            if (checkedIds.isEmpty()) {
                findViewById<Chip>(R.id.chipAll).isChecked = true
            }
            transactionListViewModel.setFilter(filter)
            if (transactionAdapter.isSelectionMode()) {
                actionMode?.finish()
            }
        }
    }

    private fun checkFilterChip(filter: ListFilter) {
        findViewById<Chip>(R.id.chipNeedsReview).isChecked = filter == ListFilter.NEEDS_REVIEW
        findViewById<Chip>(R.id.chipToday).isChecked = filter == ListFilter.TODAY
        findViewById<Chip>(R.id.chipReviewed).isChecked = filter == ListFilter.REVIEWED
        findViewById<Chip>(R.id.chipMmExported).isChecked = filter == ListFilter.MM_EXPORTED
        findViewById<Chip>(R.id.chipAll).isChecked = filter == ListFilter.ALL
    }

    private fun setupRecyclerView() {
        transactionAdapter = TransactionAdapter(this)
        recyclerView.adapter = transactionAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun observeViewModel() {
        transactionListViewModel.allTransactions.observe(this) { transactions ->
            if (transactions.isNullOrEmpty()) {
                recyclerView.visibility = View.GONE
                emptyStateTextView.visibility = View.VISIBLE
                emptyStateTextView.text = when (transactionListViewModel.currentFilter()) {
                    ListFilter.MM_EXPORTED -> getString(R.string.no_mm_exported)
                    else -> getString(R.string.no_transactions_yet)
                }
                if (transactionAdapter.isSelectionMode()) {
                    actionMode?.finish()
                }
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyStateTextView.visibility = View.GONE
                transactionAdapter.submitList(transactions)
            }
        }
    }

    private fun startSelectionMode(initialId: Long? = null) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(selectionActionModeCallback)
        }
        transactionAdapter.enterSelectionMode(initialId)
    }

    private fun confirmDeleteSelected() {
        val ids = transactionAdapter.selectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, "No transactions selected", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_selected_title)
            .setMessage(getString(R.string.delete_selected_message, ids.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteSelected(ids) }
            .show()
    }

    private fun deleteSelected(ids: Set<Long>) {
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                db.transactionDao().deleteTransactionsByIds(ids.toList())
            }
            Toast.makeText(
                this@TransactionListActivity,
                "Deleted $deleted transactions",
                Toast.LENGTH_SHORT
            ).show()
            actionMode?.finish()
        }
    }

    override fun onTransactionItemClick(transactionId: Long) {
        startActivity(
            Intent(this, TransactionEntryActivity::class.java).apply {
                putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, transactionId)
            }
        )
    }

    override fun onTransactionItemLongClick(transactionId: Long): Boolean {
        startSelectionMode(transactionId)
        return true
    }

    override fun onSelectionChanged(selectedCount: Int) {
        actionMode?.title = getString(R.string.selected_count, selectedCount)
        if (selectedCount == 0 && transactionAdapter.isSelectionMode()) {
            actionMode?.title = getString(R.string.select_to_delete)
        }
    }

    private fun exportReviewedToMoneyManager() {
        lifecycleScope.launch {
            val config = ConfigStore(this@TransactionListActivity).load()
            val txs = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionsByStatusList(ReviewStatus.REVIEWED)
            }
            if (txs.isEmpty()) {
                Toast.makeText(
                    this@TransactionListActivity,
                    "No reviewed transactions",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val tsv = MoneyManagerExporter.toTsv(txs, config)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "money_manager_import_$stamp.tsv"
            FileHandler().saveTextFile(
                this@TransactionListActivity,
                fileName,
                tsv,
                mimeType = "text/tab-separated-values"
            )
            withContext(Dispatchers.IO) {
                db.transactionDao().updateStatusForIds(
                    txs.map { it.id },
                    ReviewStatus.EXPORTED,
                    System.currentTimeMillis()
                )
            }
            Toast.makeText(
                this@TransactionListActivity,
                "Moved ${txs.size} to MM exported → Downloads/$fileName",
                Toast.LENGTH_LONG
            ).show()
            findViewById<Chip>(R.id.chipMmExported).isChecked = true
        }
    }

    private fun exportSplitwise() {
        lifecycleScope.launch {
            val txs = withContext(Dispatchers.IO) {
                db.transactionDao().getAllTransactionsList().filter {
                    (it.reviewStatus == ReviewStatus.REVIEWED ||
                        it.reviewStatus == ReviewStatus.EXPORTED) &&
                        SplitwiseExporter.isSplit(it)
                }
            }
            if (txs.isEmpty()) {
                Toast.makeText(
                    this@TransactionListActivity,
                    "No reviewed split transactions",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val csv = SplitwiseExporter.toCsv(txs, onlySplits = true)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "splitwise_import_$stamp.csv"
            FileHandler().saveCsvFileToDownloads(this@TransactionListActivity, fileName, csv)
            Toast.makeText(
                this@TransactionListActivity,
                "Exported ${txs.size} splits → Downloads/$fileName",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (transactionAdapter.isSelectionMode()) {
            actionMode?.finish()
            return true
        }
        finish()
        return true
    }

    companion object {
        const val EXTRA_FILTER = "extra_list_filter"
    }
}
