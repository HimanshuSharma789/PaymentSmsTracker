package com.example.paymentsmstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.home.HomeSummary
import com.example.paymentsmstracker.home.HomeSummaryCalculator
import com.example.paymentsmstracker.home.Money
import com.example.paymentsmstracker.home.QuickReview
import com.example.paymentsmstracker.merchant.MerchantMemory
import com.example.paymentsmstracker.ui.HatchDrawable
import com.example.paymentsmstracker.ui.MonoNav
import com.example.paymentsmstracker.ui.WeekBarsView
import com.example.paymentsmstracker.viewmodel.ListFilter
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mono home: month spend, last 7 days, the review queue with one-tap confirm, and what's ready to send. */
class MainActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }

    private lateinit var textHero: TextView
    private lateinit var textSpentLabel: TextView
    private lateinit var textDelta: TextView
    private lateinit var textWeekTotal: TextView
    private lateinit var weekBars: WeekBarsView
    private lateinit var textLeft: TextView
    private lateinit var textReviewHint: TextView
    private lateinit var containerReview: LinearLayout
    private lateinit var textReviewEmpty: TextView
    private lateinit var buttonSeeAll: MaterialButton
    private lateinit var textReadyTotal: TextView
    private lateinit var textReadySub: TextView
    private lateinit var buttonSend: MaterialButton
    private lateinit var textSplitsSub: TextView

    /** Ids being confirmed right now, so a fast double tap or a re-render can't confirm twice. */
    private val confirming = mutableSetOf<Long>()

    /** Core permissions (SMS + notifications) are asked once on a clean install; after that only from Settings → Permissions. */
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textHero = findViewById(R.id.textHomeHero)
        textSpentLabel = findViewById(R.id.textHomeSpentLabel)
        textDelta = findViewById(R.id.textHomeDelta)
        textWeekTotal = findViewById(R.id.textHomeWeekTotal)
        weekBars = findViewById(R.id.weekBars)
        textLeft = findViewById(R.id.textHomeLeft)
        textReviewHint = findViewById(R.id.textHomeReviewHint)
        containerReview = findViewById(R.id.containerHomeReview)
        textReviewEmpty = findViewById(R.id.textHomeReviewEmpty)
        buttonSeeAll = findViewById(R.id.buttonHomeSeeAll)
        textReadyTotal = findViewById(R.id.textHomeReadyTotal)
        textReadySub = findViewById(R.id.textHomeReadySub)
        buttonSend = findViewById(R.id.buttonHomeSend)
        textSplitsSub = findViewById(R.id.textHomeSplitsSub)

        findViewById<TextView>(R.id.textHomeDate).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
        findViewById<ImageButton>(R.id.buttonHomeAdd).setOnClickListener {
            startActivity(Intent(this, TransactionEntryActivity::class.java))
        }
        findViewById<ImageButton>(R.id.buttonHomeSettings).setOnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }
        buttonSeeAll.setOnClickListener { openList(ListFilter.NEEDS_REVIEW) }
        buttonSend.setOnClickListener { openList(ListFilter.REVIEWED) }
        findViewById<MaterialCardView>(R.id.cardHomeSplits).setOnClickListener {
            startActivity(Intent(this, SplitsActivity::class.java))
        }
        MonoNav.bind(this, MonoNav.Tab.HOME)

        observeHome()
        askStartupPermissionsOnce()
    }

    private fun askStartupPermissionsOnce() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_STARTUP_PERMISSIONS_ASKED, false)) return
        prefs.edit().putBoolean(KEY_STARTUP_PERMISSIONS_ASKED, true).apply()
        val missing = PermissionsPanel.corePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        val ask = { startupPermissionsLauncher.launch(missing.toTypedArray()) }
        if (missing.contains(Manifest.permission.READ_SMS) || missing.contains(Manifest.permission.RECEIVE_SMS)) {
            Disclosures.sms(this) { ask() }
        } else {
            ask()
        }
    }

    private fun observeHome() {
        val dao = db.transactionDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(dao.getAllTransactions(), dao.countPendingSplits()) { all, splits ->
                    val summary = withContext(Dispatchers.Default) {
                        HomeSummaryCalculator.summarize(all, LocalDate.now())
                    }
                    Triple(summary, all.filter { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }, splits)
                }.collect { (summary, toReview, splits) ->
                    renderSummary(summary)
                    renderReview(toReview)
                    textSplitsSub.text = if (splits > 0) {
                        getString(R.string.mono_splits_sub, splits)
                    } else {
                        getString(R.string.mono_splits_none)
                    }
                }
            }
        }
    }

    private fun renderSummary(s: HomeSummary) {
        val today = LocalDate.now()
        val month = today.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val lastMonth = today.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        textSpentLabel.text = getString(R.string.mono_spent_in, month)
        textHero.text = Money.hero(s.monthSpent)

        val diff = s.monthSpent.subtract(s.lastMonthToDate)
        if (s.lastMonthToDate.signum() == 0) {
            textDelta.visibility = View.GONE
        } else {
            textDelta.visibility = View.VISIBLE
            textDelta.text = when (diff.signum()) {
                -1 -> getString(R.string.mono_vs_last_less, Money.format(diff.abs()), lastMonth).let { Money.styled(it) }
                1 -> getString(R.string.mono_vs_last_more, Money.format(diff), lastMonth).let { Money.styled(it) }
                else -> getString(R.string.mono_vs_last_same, lastMonth)
            }
            textDelta.setCompoundDrawablesRelativeWithIntrinsicBounds(
                when (diff.signum()) {
                    -1 -> R.drawable.ic_mono_arrow_down
                    1 -> R.drawable.ic_mono_arrow_up
                    else -> 0
                }, 0, 0, 0
            )
        }

        weekBars.setDays(s.lastSevenDays)
        val weekTotal = s.lastSevenDays.fold(java.math.BigDecimal.ZERO) { acc, d -> acc + d.amount }
        textWeekTotal.text = Money.styled(getString(R.string.mono_week_total, Money.format(weekTotal)))

        textReadyTotal.text = Money.hero(s.readyTotal, 0.6f)
        textReadySub.text = if (s.readyCount > 0) {
            getString(R.string.mono_ready_sub, s.readyCount)
        } else {
            getString(R.string.mono_ready_none)
        }
        buttonSend.isEnabled = s.readyCount > 0
    }

    private fun renderReview(toReview: List<Transaction>) {
        textLeft.text = getString(R.string.mono_left, toReview.size)
        textLeft.visibility = if (toReview.isEmpty()) View.GONE else View.VISIBLE
        textReviewHint.visibility = if (toReview.any { QuickReview.canConfirm(it) }) View.VISIBLE else View.GONE
        textReviewEmpty.visibility = if (toReview.isEmpty()) View.VISIBLE else View.GONE

        containerReview.removeAllViews()
        val inflater = LayoutInflater.from(this)
        toReview.take(MAX_ROWS).forEach { tx -> containerReview.addView(reviewRow(inflater, tx)) }

        if (toReview.size > MAX_ROWS) {
            buttonSeeAll.visibility = View.VISIBLE
            buttonSeeAll.text = getString(R.string.mono_see_all_review, toReview.size)
        } else {
            buttonSeeAll.visibility = View.GONE
        }
    }

    private fun reviewRow(inflater: LayoutInflater, tx: Transaction): View {
        val row = inflater.inflate(R.layout.item_home_review, containerReview, false)
        val merchant = tx.merchantName.ifBlank { "Payment" }
        row.findViewById<TextView>(R.id.textReviewMonogram).text = merchant.take(1).uppercase()
        row.findViewById<TextView>(R.id.textReviewMerchant).text = merchant
        val category = listOf(tx.category, tx.subcategory).filter { it.isNotBlank() }.joinToString(" / ")
        row.findViewById<TextView>(R.id.textReviewMeta).text =
            listOf(tx.account, category.ifBlank { "No guess yet" }).filter { it.isNotBlank() }.joinToString(" · ")
        row.findViewById<TextView>(R.id.textReviewAmount).text = Money.styled(Money.format(tx.amount))
        row.setOnClickListener { openEntry(tx.id) }

        val confirm = row.findViewById<ImageButton>(R.id.buttonReviewConfirm)
        val choose = row.findViewById<MaterialButton>(R.id.buttonReviewChoose)
        if (QuickReview.canConfirm(tx)) {
            confirm.background = HatchDrawable(
                ContextCompat.getColor(this, R.color.mono_surface),
                ContextCompat.getColor(this, R.color.mono_meta),
                resources.displayMetrics.density
            )
            confirm.imageTintList = ContextCompat.getColorStateList(this, R.color.mono_ink)
            confirm.contentDescription = getString(R.string.mono_confirm_guess, merchant, category)
            confirm.isEnabled = tx.id !in confirming
            confirm.setOnClickListener {
                confirm.isEnabled = false
                confirm.setBackgroundResource(R.drawable.bg_mono_circle_ink)
                confirm.imageTintList = ContextCompat.getColorStateList(this, R.color.mono_yellow)
                quickConfirm(tx)
            }
        } else {
            confirm.visibility = View.GONE
            choose.visibility = View.VISIBLE
            choose.setOnClickListener { openEntry(tx.id) }
        }
        return row
    }

    private fun quickConfirm(tx: Transaction) {
        if (!confirming.add(tx.id)) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.transactionDao().updateTransaction(QuickReview.confirmed(tx, System.currentTimeMillis()))
                    MerchantMemory.remember(
                        db = db,
                        merchant = tx.merchantName,
                        category = tx.category,
                        subcategory = tx.subcategory,
                        account = tx.account,
                        transactionType = tx.transactionType
                    )
                }
            } finally {
                confirming.remove(tx.id)
            }
            Snackbar.make(containerReview, getString(R.string.mono_confirmed, tx.merchantName), Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.monoNav)
                .setAction(R.string.mono_undo) {
                    lifecycleScope.launch(Dispatchers.IO) { db.transactionDao().updateTransaction(tx) }
                }
                .show()
        }
    }

    private fun openEntry(id: Long) {
        startActivity(
            Intent(this, TransactionEntryActivity::class.java)
                .putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, id)
        )
    }

    private fun openList(filter: ListFilter) {
        startActivity(
            Intent(this, TransactionListActivity::class.java)
                .putExtra(TransactionListActivity.EXTRA_FILTER, filter.name)
        )
    }

    companion object {
        private const val PREFS_NAME = "main_activity"
        private const val KEY_STARTUP_PERMISSIONS_ASKED = "startup_permissions_asked"
        private const val MAX_ROWS = 5
    }
}
