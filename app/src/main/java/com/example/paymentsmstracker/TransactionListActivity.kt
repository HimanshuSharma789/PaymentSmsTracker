package com.example.paymentsmstracker

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.adapter.OnTransactionItemClickListener
import com.example.paymentsmstracker.adapter.TransactionAdapter
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.export.MoneyManagerSync
import com.example.paymentsmstracker.home.Money
import com.example.paymentsmstracker.merchant.MerchantMemory
import com.example.paymentsmstracker.ui.HatchDrawable
import com.example.paymentsmstracker.ui.MonoNav
import com.example.paymentsmstracker.viewmodel.ListFilter
import com.example.paymentsmstracker.viewmodel.TransactionListViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionListActivity : AppCompatActivity(), OnTransactionItemClickListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateTextView: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var exportButton: MaterialButton
    private lateinit var transactionAdapter: TransactionAdapter
    private lateinit var db: AppDatabase
    private val transactionListViewModel: TransactionListViewModel by viewModels()
    private var selectionModeActive = false
    private var lastListCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_list)

        toolbar = findViewById(R.id.toolbarTransactionList)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Mono: the H1 below the toolbar carries the title; the toolbar only shows it in selection mode.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener {
            if (selectionModeActive) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        db = AppDatabase.getDatabase(applicationContext)
        recyclerView = findViewById(R.id.recyclerViewTransactions)
        emptyStateTextView = findViewById(R.id.textViewEmptyState)
        exportButton = findViewById(R.id.buttonListExport)
        exportButton.setOnClickListener { confirmExportAllReviewed() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectionModeActive) {
                        exitSelectionMode()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        if (intent.getStringExtra(EXTRA_FILTER) == "SPLITS") {
            startActivity(Intent(this, SplitsActivity::class.java))
            finish()
            return
        }

        val initialFilter = intent.getStringExtra(EXTRA_FILTER)?.let {
            runCatching { ListFilter.valueOf(it) }.getOrNull()
        } ?: ListFilter.NEEDS_REVIEW
        transactionListViewModel.setFilter(initialFilter)
        MonoNav.bind(this, if (initialFilter == ListFilter.REVIEWED) MonoNav.Tab.SEND else MonoNav.Tab.ALL)
        checkFilterChip(initialFilter)
        setupFilterChips()
        setupRecyclerView()
        transactionAdapter.setListFilter(initialFilter)
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val menuRes = if (selectionModeActive) {
            R.menu.transaction_selection_menu
        } else {
            R.menu.transaction_list_menu
        }
        menuInflater.inflate(menuRes, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val filter = transactionListViewModel.currentFilter()
        if (selectionModeActive) {
            menu.findItem(R.id.action_mark_reviewed_selected)?.isVisible =
                filter == ListFilter.NEEDS_REVIEW
            menu.findItem(R.id.action_export_selected)?.isVisible =
                filter == ListFilter.REVIEWED
            menu.findItem(R.id.action_delete_selected)?.isVisible = true
        } else {
            // Mono: the "Export N entries" pill (buttonListExport) is this action now.
            menu.findItem(R.id.action_export_reviewed)?.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_splits -> {
                startActivity(Intent(this, SplitsActivity::class.java))
                true
            }
            R.id.action_agent_chat -> {
                startActivity(Intent(this, AgentChatActivity::class.java))
                true
            }
            R.id.action_export_reviewed -> {
                confirmExportAllReviewed()
                true
            }
            R.id.action_select_transactions -> {
                startSelectionMode()
                true
            }
            R.id.action_select_all -> {
                transactionAdapter.selectAllVisible()
                true
            }
            R.id.action_mark_reviewed_selected -> {
                confirmMarkReviewedSelected()
                true
            }
            R.id.action_export_selected -> {
                confirmExportSelected()
                true
            }
            R.id.action_delete_selected -> {
                confirmDeleteSelected()
                true
            }
            R.id.action_cancel_selection -> {
                exitSelectionMode()
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
            transactionAdapter.setListFilter(filter)
            invalidateOptionsMenu()
            if (selectionModeActive) {
                exitSelectionMode()
            }
        }
    }

    private fun checkFilterChip(filter: ListFilter) {
        findViewById<Chip>(R.id.chipNeedsReview).isChecked = filter == ListFilter.NEEDS_REVIEW
        findViewById<Chip>(R.id.chipReviewed).isChecked = filter == ListFilter.REVIEWED
        findViewById<Chip>(R.id.chipMmExported).isChecked = filter == ListFilter.MM_EXPORTED
        findViewById<Chip>(R.id.chipAll).isChecked = filter == ListFilter.ALL
    }

    private fun setupRecyclerView() {
        transactionAdapter = TransactionAdapter(this)
        recyclerView.adapter = MonoRows(transactionAdapter)
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun observeViewModel() {
        transactionListViewModel.allTransactions.observe(this) { transactions ->
            val filter = transactionListViewModel.currentFilter()
            transactionAdapter.setListFilter(filter)
            if (transactions.isNullOrEmpty()) {
                recyclerView.visibility = View.GONE
                emptyStateTextView.visibility = View.VISIBLE
                emptyStateTextView.text = when (filter) {
                    ListFilter.NEEDS_REVIEW -> getString(R.string.list_empty_needs_review)
                    ListFilter.REVIEWED -> getString(R.string.list_empty_reviewed)
                    ListFilter.MM_EXPORTED -> getString(R.string.list_empty_exported)
                    ListFilter.ALL -> getString(R.string.list_empty_all)
                }
                updateListSubtitle(0, filter)
                renderMonoChrome(filter, emptyList())
                invalidateOptionsMenu()
                if (selectionModeActive) {
                    exitSelectionMode()
                }
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyStateTextView.visibility = View.GONE
                transactionAdapter.submitList(transactions)
                updateListSubtitle(transactions.size, filter)
                renderMonoChrome(filter, transactions)
                invalidateOptionsMenu()
            }
        }
    }

    private fun updateListSubtitle(count: Int, filter: ListFilter) {
        lastListCount = count
        if (selectionModeActive) return
        val subtitle = findViewById<TextView>(R.id.textListSubtitle)
        subtitle.text = when {
            count <= 0 -> null
            filter == ListFilter.NEEDS_REVIEW -> getString(R.string.list_count_to_review, count)
            filter == ListFilter.REVIEWED -> getString(R.string.list_count_reviewed, count)
            filter == ListFilter.MM_EXPORTED -> getString(R.string.list_count_exported, count)
            else -> getString(R.string.list_count_items, count)
        }
        subtitle.isVisible = count > 0 && filter != ListFilter.REVIEWED
    }

    private fun startSelectionMode(initialId: Long? = null) {
        if (!selectionModeActive) {
            selectionModeActive = true
            supportActionBar?.setDisplayShowTitleEnabled(true)
            updateExportButton()
            invalidateOptionsMenu()
        }
        transactionAdapter.enterSelectionMode(initialId)
        refreshSelectionTitle(transactionAdapter.selectedIds().size)
    }

    private fun exitSelectionMode() {
        if (!selectionModeActive) return
        selectionModeActive = false
        transactionAdapter.exitSelectionMode()
        supportActionBar?.title = getString(R.string.transactions)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        updateExportButton()
        updateListSubtitle(lastListCount, transactionListViewModel.currentFilter())
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

    private fun confirmMarkReviewedSelected() {
        val ids = transactionAdapter.selectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, "No transactions selected", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mark_reviewed_selected_title)
            .setMessage(getString(R.string.mark_reviewed_selected_message, ids.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mark_reviewed_selected) { _, _ ->
                markSelectedReviewed(ids)
            }
            .show()
    }

    private fun confirmExportSelected() {
        val ids = transactionAdapter.selectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, "No transactions selected", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.export_selected_title)
            .setMessage(getString(R.string.export_selected_message, ids.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export_selected) { _, _ ->
                exportSelectedToMoneyManager(ids)
            }
            .show()
    }

    private fun confirmExportAllReviewed() {
        if (lastListCount <= 0) {
            Toast.makeText(this, R.string.no_reviewed_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.export_all_reviewed_title)
            .setMessage(getString(R.string.export_all_reviewed_message, lastListCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export_selected) { _, _ ->
                exportAllReviewedToMoneyManager()
            }
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
            exitSelectionMode()
        }
    }

    private fun markSelectedReviewed(ids: Set<Long>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = db.transactionDao()
                dao.updateStatusForIds(
                    ids.toList(),
                    ReviewStatus.REVIEWED,
                    System.currentTimeMillis()
                )
                ids.forEach { id ->
                    val tx = dao.getTransactionById(id) ?: return@forEach
                    if (tx.category.isNotBlank()) {
                        MerchantMemory.remember(
                            db = db,
                            merchant = tx.merchantName,
                            category = tx.category,
                            subcategory = tx.subcategory,
                            account = tx.account,
                            transactionType = tx.transactionType
                        )
                    }
                }
            }
            Toast.makeText(
                this@TransactionListActivity,
                getString(R.string.mark_reviewed_selected_done, ids.size),
                Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
        }
    }

    private fun exportSelectedToMoneyManager(ids: Set<Long>) {
        lifecycleScope.launch {
            val config = ConfigStore(this@TransactionListActivity).load()
            val txs = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionsByStatusList(ReviewStatus.REVIEWED)
                    .filter { it.id in ids }
            }
            if (txs.isEmpty()) {
                Toast.makeText(
                    this@TransactionListActivity,
                    "No reviewed transactions selected",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val outcome = MoneyManagerSync.syncToMoneyManager(
                activity = this@TransactionListActivity,
                transactions = txs,
                config = config,
                transactionDao = db.transactionDao(),
                markExported = true
            )
            if (outcome != null) {
                exitSelectionMode()
                findViewById<Chip>(R.id.chipMmExported).isChecked = true
            }
        }
    }

    private fun exportAllReviewedToMoneyManager() {
        lifecycleScope.launch {
            val config = ConfigStore(this@TransactionListActivity).load()
            val txs = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionsByStatusList(ReviewStatus.REVIEWED)
            }
            if (txs.isEmpty()) {
                Toast.makeText(
                    this@TransactionListActivity,
                    R.string.no_reviewed_to_export,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val outcome = MoneyManagerSync.syncToMoneyManager(
                activity = this@TransactionListActivity,
                transactions = txs,
                config = config,
                transactionDao = db.transactionDao(),
                markExported = true
            )
            if (outcome != null) {
                findViewById<Chip>(R.id.chipMmExported).isChecked = true
            }
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
        if (selectionModeActive) {
            refreshSelectionTitle(selectedCount)
        }
    }

    // ---- Mono presentation (look only; export/delete/selection logic above is unchanged) ----

    private fun renderMonoChrome(filter: ListFilter, transactions: List<Transaction>) {
        val sending = filter == ListFilter.REVIEWED
        MonoNav.bind(this, if (sending) MonoNav.Tab.SEND else MonoNav.Tab.ALL)
        findViewById<TextView>(R.id.textListTitle)
            .setText(if (sending) R.string.list_send_title else R.string.transactions)
        val showSend = sending && transactions.isNotEmpty()
        findViewById<View>(R.id.listSendHeader).isVisible = showSend
        exportButton.text = resources.getQuantityString(
            R.plurals.list_export_entries, transactions.size, transactions.size
        )
        updateExportButton()
        val dp = resources.displayMetrics.density
        recyclerView.updatePadding(bottom = ((if (showSend) 176 else 112) * dp).toInt())
        if (showSend) bindSendHeader(transactions)
    }

    private fun updateExportButton() {
        exportButton.isVisible = transactionListViewModel.currentFilter() == ListFilter.REVIEWED &&
            lastListCount > 0 && !selectionModeActive
    }

    /** M-Send summary, built from the exporter's own rows so it shows exactly what MM will get. */
    private fun bindSendHeader(transactions: List<Transaction>) {
        val config = ConfigStore(this).load()
        val exportable = transactions.filter { MoneyManagerExporter.exportBlocker(it, config) == null }
        val rows = exportable.flatMap { MoneyManagerExporter.expandTransaction(it, config) }
        val duesLabel = "${config.debtCategory} / ${config.debtSubcategory}"

        class Line(val dues: Boolean, var count: Int = 0, var amount: BigDecimal = BigDecimal.ZERO)
        val lines = linkedMapOf<String, Line>()
        var expenseTotal = BigDecimal.ZERO
        var mine = BigDecimal.ZERO
        rows.forEach { row ->
            val amount = row.amount.toBigDecimalOrNull() ?: return@forEach
            val expense = row.incomeExpense == "Expense"
            // Friends' share rows: what expandTransaction writes to debtCategory/debtSubcategory for a split.
            val dues = expense && !config.mmFullSplitAsOwnExpense &&
                row.category == config.debtCategory && row.subcategory == config.debtSubcategory &&
                row.note.contains("Split: ")
            val name = when {
                dues -> duesLabel
                expense -> row.category
                row.incomeExpense == "Income" -> "${row.category} · ${getString(R.string.list_send_income)}"
                else -> "${row.category} · ${getString(R.string.list_send_transfer)}"
            }
            val line = lines.getOrPut(name) { Line(dues) }
            line.count++
            line.amount = line.amount.add(amount)
            if (expense) expenseTotal = expenseTotal.add(amount)
            if (expense && !dues) mine = mine.add(amount)
        }

        findViewById<TextView>(R.id.textListSendHero).text = Money.hero(expenseTotal)
        findViewById<TextView>(R.id.textListSendCount).text =
            resources.getQuantityString(R.plurals.list_entries, transactions.size, transactions.size)
        val day = SimpleDateFormat("d MMM", Locale.getDefault())
        val first = day.format(Date(transactions.minOf { it.timestamp }))
        val last = day.format(Date(transactions.maxOf { it.timestamp }))
        findViewById<TextView>(R.id.textListSendRange).text =
            getString(R.string.list_send_range, if (first == last) first else "$first – $last")

        val container = findViewById<LinearLayout>(R.id.containerListSendBreakdown)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        lines.entries.sortedWith(compareBy<Map.Entry<String, Line>> { it.value.dues }.thenByDescending { it.value.amount })
            .forEach { (name, line) ->
                val v = LayoutInflater.from(this).inflate(R.layout.item_list_breakdown, container, false)
                if (line.dues) {
                    v.findViewById<View>(R.id.viewListBreakdownDot).background = HatchDrawable(
                        ContextCompat.getColor(this, R.color.mono_yellow),
                        ContextCompat.getColor(this, R.color.mono_ink),
                        density
                    )
                }
                v.findViewById<TextView>(R.id.textListBreakdownName).text = name
                val count = resources.getQuantityString(R.plurals.list_entries, line.count, line.count)
                v.findViewById<TextView>(R.id.textListBreakdownMeta).text =
                    if (line.dues) "$count · ${getString(R.string.list_send_dues_meta)}" else count
                v.findViewById<TextView>(R.id.textListBreakdownAmount).text = rupees(line.amount)
                container.addView(v)
            }
        findViewById<TextView>(R.id.textListSendMine).text = rupees(mine)

        findViewById<TextView>(R.id.textListSendFullSplitTitle).setText(
            if (config.mmFullSplitAsOwnExpense) R.string.list_send_full_on else R.string.list_send_full_off
        )
        findViewById<TextView>(R.id.textListSendFullSplit).text =
            if (config.mmFullSplitAsOwnExpense) getString(R.string.list_send_full_on_hint)
            else getString(R.string.list_send_full_off_hint, duesLabel)
        findViewById<TextView>(R.id.textListSendFile).text = resources.getQuantityString(
            R.plurals.list_send_file, rows.size, rows.size, MoneyManagerExporter.HEADERS.joinToString(", ")
        )
    }

    /** Wraps the shared adapter (unchanged) and restyles each bound row in the Mono look. */
    private class MonoRows(
        private val inner: TransactionAdapter
    ) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

        private val day = SimpleDateFormat("d MMM", Locale.getDefault())

        init {
            inner.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = notifyDataSetChanged()
                override fun onItemRangeChanged(start: Int, count: Int) = notifyItemRangeChanged(start, count)
                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) =
                    notifyItemRangeChanged(start, count, payload)
                override fun onItemRangeInserted(start: Int, count: Int) = notifyItemRangeInserted(start, count)
                override fun onItemRangeRemoved(start: Int, count: Int) = notifyItemRangeRemoved(start, count)
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) = notifyItemMoved(from, to)
            })
        }

        override fun getItemCount() = inner.itemCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            inner.onCreateViewHolder(parent, viewType)

        override fun onBindViewHolder(holder: TransactionAdapter.TransactionViewHolder, position: Int) {
            inner.onBindViewHolder(holder, position)
            style(holder.itemView, inner.currentList[position])
        }

        private fun style(view: View, tx: Transaction) {
            val context = view.context
            view.findViewById<TextView>(R.id.textListMonogram).text =
                tx.merchantName.trim().take(1).uppercase().ifEmpty { "?" }
            view.findViewById<TextView>(R.id.textViewAmount).text = rupees(BigDecimal.valueOf(tx.amount))

            val type = MoneyManagerExporter.normalizeType(tx.transactionType)
            view.findViewById<TextView>(R.id.textViewCategory).text = listOfNotNull(
                type.takeIf { it != "Expense" },
                tx.account.ifBlank { null },
                tx.category.ifBlank { null }?.let { c -> if (tx.subcategory.isBlank()) c else "$c / ${tx.subcategory}" },
                day.format(Date(tx.timestamp))
            ).joinToString(" · ")

            val status = view.findViewById<TextView>(R.id.textViewStatus)
            if (status.isVisible) {
                val (text, bg, fg) = when (tx.reviewStatus) {
                    ReviewStatus.NEEDS_REVIEW ->
                        Triple(R.string.list_status_needs_review, R.drawable.bg_mono_pill_outline, R.color.mono_ink)
                    ReviewStatus.REVIEWED ->
                        Triple(R.string.list_status_reviewed, R.drawable.bg_mono_pill_yellow, R.color.mono_on_yellow)
                    else ->
                        Triple(R.string.list_status_exported, R.drawable.list_bg_tag_grey, R.color.mono_ink)
                }
                status.setText(text)
                status.setBackgroundResource(bg)
                status.setTextColor(ContextCompat.getColor(context, fg))
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

    companion object {
        const val EXTRA_FILTER = "extra_list_filter"

        /** Money.format with paise only when they exist, ₹ shrunk onto the baseline. */
        private fun rupees(amount: BigDecimal): CharSequence {
            val text = Money.format(amount, withPaise = amount.stripTrailingZeros().scale() > 0)
            return SpannableString(text).apply {
                setSpan(RelativeSizeSpan(0.72f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
