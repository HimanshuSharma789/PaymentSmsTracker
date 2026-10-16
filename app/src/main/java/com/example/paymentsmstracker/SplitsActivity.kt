package com.example.paymentsmstracker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.adapter.SplitRowUi
import com.example.paymentsmstracker.adapter.SplitTableAdapter
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.SplitwiseExpenseIds
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.home.Money
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import com.example.paymentsmstracker.splitwise.SplitwiseApi
import com.example.paymentsmstracker.splitwise.SplitwiseAuthStore
import com.example.paymentsmstracker.splitwise.SplitwisePusher
import com.example.paymentsmstracker.splitwise.SplitwiseSessionEvents
import com.example.paymentsmstracker.ui.HatchDrawable
import com.example.paymentsmstracker.ui.MonoNav
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var markSuccessButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private var selectionModeActive = false
    private var lastCount = 0
    private var filter = SplitFilter.PENDING
    private var allRows: List<SplitRowUi> = emptyList()
    /** Loaded rows by id, for the Mono bill card (account, category, per-person shares). */
    private var txById: Map<Long, Transaction> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splits)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbarSplits)
        setSupportActionBar(toolbar)
        // Top-level tab: the up arrow only appears while selecting, to cancel the selection.
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        MonoNav.bind(this, MonoNav.Tab.SPLITS)
        toolbar.setNavigationOnClickListener {
            if (selectionModeActive) exitSelectionMode() else finish()
        }
        SplitwiseSessionEvents.observe(this, this)

        empty = findViewById(R.id.textSplitsEmpty)
        summary = findViewById(R.id.textSplitsSummary)
        pushButton = findViewById(R.id.buttonPushSplitwiseFromSplits)
        markSuccessButton = findViewById(R.id.buttonMarkSuccessFromSplits)
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
        recycler.adapter = MonoBillAdapter(adapter)
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
        markSuccessButton.setOnClickListener {
            if (selectionModeActive) {
                confirmMarkSelectedSuccess()
            } else {
                startSelectionMode()
            }
        }

        lifecycleScope.launch {
            AppDatabase.getDatabase(applicationContext).transactionDao()
                .getSplitsForTable()
                .collectLatest { txs ->
                    txById = txs.associateBy { it.id }
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
        renderHeader(rows)
        val showPushActions = isPendingTab()
        adapter.setAllowSelection(showPushActions)
        exportButton.visibility = if (showPushActions) View.VISIBLE else View.GONE
        if (!selectionModeActive) {
            supportActionBar?.title = getString(R.string.splits_title)
            supportActionBar?.subtitle = null
            pushButton.setText(R.string.splitwise_select_and_push)
            markSuccessButton.setText(R.string.splitwise_mark_success)
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
            R.id.action_mark_selected_success -> {
                confirmMarkSelectedSuccess()
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
        markSuccessButton.setText(R.string.splits_mono_mark_selected_added)
        updateSelectionChrome()
        invalidateOptionsMenu()
        refreshSelectionTitle(0)
    }

    private fun exitSelectionMode() {
        if (!selectionModeActive) return
        selectionModeActive = false
        adapter.exitSelectionMode()
        pushButton.setText(R.string.splitwise_select_and_push)
        markSuccessButton.setText(R.string.splitwise_mark_success)
        supportActionBar?.title = getString(R.string.splits_title)
        supportActionBar?.subtitle = null
        updateSelectionChrome()
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

    private fun confirmPushSelected(ids: Set<Long> = adapter.selectedIds()) {
        if (!isPendingTab()) {
            Toast.makeText(this, R.string.splits_already_pushed, Toast.LENGTH_SHORT).show()
            return
        }
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

    private fun confirmMarkSelectedSuccess(ids: Set<Long> = adapter.selectedIds()) {
        if (!isPendingTab()) {
            Toast.makeText(this, R.string.splits_already_pushed, Toast.LENGTH_SHORT).show()
            return
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.no_transactions_selected, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.splitwise_mark_success_title)
            .setMessage(getString(R.string.splitwise_mark_success_message, ids.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.splitwise_mark_selected_success) { _, _ ->
                markSelectedSuccess(ids)
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
            FileHandler().exportCsv(this@SplitsActivity, "splitwise_import_$stamp.csv", csv)
        }
    }

    private var pushJob: Job? = null

    private fun pushSelected(ids: Set<Long>) {
        val store = SplitwiseAuthStore(this)
        val auth = store.load()
        if (!auth.isSignedIn) {
            Toast.makeText(this, R.string.splitwise_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        // One push at a time: the menu item and the button both lead here.
        if (pushJob?.isActive == true) return
        pushButton.isEnabled = false
        pushJob = lifecycleScope.launch {
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
                    val result = SplitwisePusher.pushAndRecord(dao, api, auth, prepared)
                    if (result.isSuccess) {
                        ok++
                    } else {
                        failed++
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

    private fun markSelectedSuccess(ids: Set<Long>) {
        markSuccessButton.isEnabled = false
        pushButton.isEnabled = false
        lifecycleScope.launch {
            val marked = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                val txs = dao.getPendingSplitTransactionsList().filter { it.id in ids }
                val now = System.currentTimeMillis()
                txs.forEach { tx ->
                    dao.updateTransaction(
                        tx.copy(
                            splitwiseExpenseId = SplitwiseExpenseIds.MANUAL_SUCCESS,
                            splitwisePushError = "",
                            lastUpdatedTimestamp = now
                        )
                    )
                }
                txs.size
            }
            markSuccessButton.isEnabled = true
            pushButton.isEnabled = true
            Toast.makeText(
                this@SplitsActivity,
                getString(R.string.splitwise_mark_success_done, marked),
                Toast.LENGTH_LONG
            ).show()
            exitSelectionMode()
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.textSplitsConnection).setText(
            if (SplitwiseAuthStore(this).load().isSignedIn) R.string.splits_mono_connected
            else R.string.splits_mono_not_connected
        )
    }

    // ---- Mono presentation ----

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun money(amount: BigDecimal): String =
        Money.format(amount, withPaise = amount.stripTrailingZeros().scale() > 0)

    /** "PUNEET" → "Puneet"; mixed-case names are shown as saved. */
    private fun displayName(name: String): String =
        if (name != name.uppercase()) name
        else name.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    /** Each friend's share of one bill (SplitCalculator; "ME" is your own share). */
    private fun friendShares(id: Long): Map<String, BigDecimal> =
        txById[id]?.let { SplitCalculator.resolveOrEqual(it).amounts }.orEmpty()
            .filterKeys { !it.equals("ME", ignoreCase = true) }

    private fun renderHeader(rows: List<SplitRowUi>) {
        val owed = rows.fold(BigDecimal.ZERO) { acc, row -> acc.add(BigDecimal(row.friendsOwe)) }
        findViewById<TextView>(R.id.textSplitsHeroLabel).setText(
            if (isPendingTab()) R.string.splits_mono_owed_to_you else R.string.splits_mono_owed_on_splitwise
        )
        findViewById<TextView>(R.id.textSplitsHero).text = Money.hero(owed)

        val toPush = allRows.count { !it.pushed }
        findViewById<TextView>(R.id.textSplitsToPush).apply {
            if (toPush > 0) {
                text = getString(R.string.splits_mono_to_push, toPush)
                setBackgroundResource(R.drawable.bg_mono_pill_yellow)
                setTextColor(color(R.color.mono_on_yellow))
            } else {
                setText(R.string.splits_mono_all_pushed)
                setBackgroundResource(R.drawable.bg_split_status)
                setTextColor(color(R.color.mono_on_ink))
            }
        }

        val people = rows.flatMap { friendShares(it.id).entries }
            .groupBy({ displayName(it.key) }, { it.value })
            .mapValues { (_, shares) -> shares.fold(BigDecimal.ZERO, BigDecimal::add) }
            .entries.sortedByDescending { it.value }
        summary.text = if (rows.isEmpty()) empty.text else buildString {
            append(resources.getQuantityString(R.plurals.splits_mono_people, people.size, people.size))
            append(" · ")
            append(resources.getQuantityString(R.plurals.splits_mono_bills, rows.size, rows.size))
            val failed = rows.count { it.failed }
            if (failed > 0) {
                append(" · ")
                append(getString(R.string.pending_splits_failed_count, failed))
            }
        }

        val container = findViewById<LinearLayout>(R.id.containerSplitsPeople)
        container.removeAllViews()
        val gap = (10 * resources.displayMetrics.density).toInt()
        people.forEachIndexed { i, (name, amount) ->
            val card = LayoutInflater.from(this).inflate(R.layout.splits_item_person, container, false)
            card.findViewById<TextView>(R.id.textSplitsPersonMonogram).text = name.take(1).uppercase()
            card.findViewById<TextView>(R.id.textSplitsPersonName).text = name
            card.findViewById<TextView>(R.id.textSplitsPersonAmount).text = Money.hero(amount, 0.75f)
            card.contentDescription = getString(R.string.splits_mono_person_cd, name, money(amount))
            if (i < people.lastIndex) {
                (card.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
            }
            container.addView(card)
        }
        findViewById<View>(R.id.scrollSplitsPeople).visibility =
            if (people.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Selection mode shows the bulk bar and an up arrow that cancels it. */
    private fun updateSelectionChrome() {
        findViewById<View>(R.id.cardSplitsBulk).visibility =
            if (selectionModeActive) View.VISIBLE else View.GONE
        findViewById<View>(R.id.textSplitsConnection).visibility =
            if (selectionModeActive) View.GONE else View.VISIBLE
        supportActionBar?.setHomeAsUpIndicator(R.drawable.outline_arrow_back_24)
        supportActionBar?.setDisplayHomeAsUpEnabled(selectionModeActive)
        if (selectionModeActive) findViewById<AppBarLayout>(R.id.appBarSplits).setExpanded(true)
    }

    private fun decorateBill(view: View, row: SplitRowUi) {
        val tx = txById[row.id]
        val split = tx?.let { SplitCalculator.resolveOrEqual(it) }
        val friends = friendShares(row.id)
        val ink = color(R.color.mono_ink)
        val onInk = color(R.color.mono_on_ink)

        view.findViewById<TextView>(R.id.textSplitMonogram).apply {
            text = row.merchant.ifBlank { "?" }.take(1).uppercase()
            setBackgroundResource(if (row.pushed) R.drawable.bg_mono_circle_ink else R.drawable.splits_bg_circle_outline)
            setTextColor(if (row.pushed) onInk else ink)
        }
        if (tx != null) {
            val category = listOf(tx.category, tx.subcategory).filter { it.isNotBlank() }.joinToString(" / ")
            val mode = SplitMode.label(tx.splitMode).takeIf { it != "Equal" }.orEmpty()
            view.findViewById<TextView>(R.id.textSplitMeta).text =
                listOf(row.date, tx.account, category, mode).filter { it.isNotBlank() }.joinToString(" · ")
        }
        view.findViewById<TextView>(R.id.textSplitTotal).text = money(BigDecimal(row.total))
        view.findViewById<TextView>(R.id.textSplitBreakdown).text =
            if (friends.isEmpty()) row.people
            else friends.entries.joinToString(" · ") { "${displayName(it.key)} ${money(it.value)}" }

        // Your share solid, each friend's share hatched (still to come back to you).
        val bar = view.findViewById<LinearLayout>(R.id.splitsSegments)
        bar.removeAllViews()
        val d = resources.displayMetrics.density
        fun segment(share: BigDecimal, hatched: Boolean) {
            if (share.signum() <= 0) return
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, share.toFloat())
            if (bar.childCount > 0) params.marginStart = (4 * d).toInt()
            bar.addView(View(this).apply {
                background = if (hatched) HatchDrawable(color(R.color.mono_yellow), ink, d)
                else ContextCompat.getDrawable(context, R.drawable.bg_split_status)
            }, params)
        }
        split?.let { segment(it.myShare, false) }
        friends.values.forEach { segment(it, true) }
        bar.visibility = if (bar.childCount > 0) View.VISIBLE else View.GONE

        view.findViewById<TextView>(R.id.textSplitStatus).apply {
            when {
                row.pushed -> {
                    visibility = View.VISIBLE
                    setText(if (row.manualSuccess) R.string.splits_mono_added_by_hand else R.string.splits_mono_on_splitwise)
                    setBackgroundResource(R.drawable.bg_split_status)
                    setTextColor(onInk)
                }
                row.failed -> {
                    visibility = View.VISIBLE
                    setBackgroundResource(R.drawable.bg_mono_pill_outline)
                    setTextColor(color(R.color.mono_error))
                }
                else -> visibility = View.GONE
            }
            backgroundTintList = null
        }

        view.findViewById<View>(R.id.splitsActions).visibility =
            if (!row.pushed && isPendingTab() && !adapter.isSelectionMode()) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.buttonSplitPush).apply {
            setText(if (row.failed) R.string.splits_mono_retry else R.string.splits_mono_push)
            contentDescription = getString(R.string.splits_mono_push_cd, row.merchant)
            setOnClickListener { confirmPushSelected(setOf(row.id)) }
        }
        view.findViewById<MaterialButton>(R.id.buttonSplitMarkAdded).apply {
            contentDescription = getString(R.string.splits_mono_mark_added_cd, row.merchant)
            setOnClickListener { confirmMarkSelectedSuccess(setOf(row.id)) }
        }
    }

    /** Mono bill card on top of SplitTableAdapter, which keeps selection and click handling. */
    private inner class MonoBillAdapter(
        private val base: SplitTableAdapter
    ) : RecyclerView.Adapter<SplitTableAdapter.VH>() {
        init {
            base.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = notifyDataSetChanged()
                override fun onItemRangeChanged(start: Int, count: Int) = notifyItemRangeChanged(start, count)
                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) =
                    notifyItemRangeChanged(start, count, payload)
                override fun onItemRangeInserted(start: Int, count: Int) = notifyItemRangeInserted(start, count)
                override fun onItemRangeRemoved(start: Int, count: Int) = notifyItemRangeRemoved(start, count)
                // DiffUtil only reports single-item moves.
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) = notifyItemMoved(from, to)
            })
        }

        override fun getItemCount() = base.itemCount
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = base.onCreateViewHolder(parent, viewType)
        override fun onBindViewHolder(holder: SplitTableAdapter.VH, position: Int) {
            base.onBindViewHolder(holder, position)
            decorateBill(holder.itemView, base.currentList[position])
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
