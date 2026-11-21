package com.example.paymentsmstracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.home.Money
import com.example.paymentsmstracker.insights.Insights
import com.example.paymentsmstracker.insights.InsightsCalculator
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only month insights: total vs last month, by category, top merchants. */
class InsightsActivity : AppCompatActivity() {

    private var rows: List<Transaction> = emptyList()
    private var month: YearMonth = YearMonth.now()
    private var earliest: YearMonth? = null

    private lateinit var prev: ImageButton
    private lateinit var next: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insights)
        month = savedInstanceState?.getString(KEY_MONTH)?.let { YearMonth.parse(it) } ?: YearMonth.now()
        prev = findViewById(R.id.buttonInsightsPrev)
        next = findViewById(R.id.buttonInsightsNext)
        findViewById<ImageButton>(R.id.buttonInsightsBack).setOnClickListener { finish() }
        prev.setOnClickListener { month = month.minusMonths(1); render() }
        next.setOnClickListener { month = month.plusMonths(1); render() }

        val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getAllTransactions().collect {
                    rows = it
                    earliest = withContext(Dispatchers.Default) { InsightsCalculator.earliestMonth(it) }
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MONTH, month.toString())
    }

    private fun render() {
        val now = YearMonth.now()
        if (month.isAfter(now)) month = now
        val floor = earliest ?: now
        if (month.isBefore(floor)) month = floor
        setEnabledAlpha(prev, month.isAfter(floor))
        setEnabledAlpha(next, month.isBefore(now))
        lifecycleScope.launch {
            val shown = month
            val r = withContext(Dispatchers.Default) { InsightsCalculator.calculate(rows, shown) }
            if (shown == month) show(r)
        }
    }

    private fun setEnabledAlpha(b: ImageButton, on: Boolean) {
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.35f
    }

    private fun name(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

    private fun show(r: Insights) {
        findViewById<TextView>(R.id.textInsightsMonth).text =
            getString(R.string.insights_spent_in, "${name(r.month)} ${r.month.year}")
        findViewById<TextView>(R.id.textInsightsHero).text = Money.hero(r.total)

        val delta = findViewById<TextView>(R.id.textInsightsDelta)
        val empty = r.rowCount == 0
        findViewById<View>(R.id.textInsightsEmpty).visibility = if (empty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupInsightsBody).visibility = if (empty) View.GONE else View.VISIBLE
        if (r.previousTotal.signum() == 0) {
            delta.visibility = View.GONE
        } else {
            delta.visibility = View.VISIBLE
            val last = name(r.month.minusMonths(1))
            val pct = r.deltaPercent?.let { " (${if (it > 0) "+" else ""}$it%)" } ?: ""
            delta.text = when (r.delta.signum()) {
                -1 -> Money.styled(getString(R.string.insights_less, Money.format(r.delta.abs()), last) + pct)
                1 -> Money.styled(getString(R.string.insights_more, Money.format(r.delta), last) + pct)
                else -> getString(R.string.insights_same, last)
            }
            delta.setCompoundDrawablesRelativeWithIntrinsicBounds(
                when (r.delta.signum()) {
                    -1 -> R.drawable.ic_mono_arrow_down
                    1 -> R.drawable.ic_mono_arrow_up
                    else -> 0
                }, 0, 0, 0
            )
        }

        val inflater = LayoutInflater.from(this)
        val cats = findViewById<LinearLayout>(R.id.containerInsightsCategories)
        cats.removeAllViews()
        r.categories.forEach { c ->
            val sign = when (c.delta.signum()) { 1 -> "+"; -1 -> "−"; else -> "" }
            val meta = "${c.sharePercent}%" +
                if (r.previousTotal.signum() != 0) " · $sign${Money.format(c.delta.abs())} vs ${name(r.month.minusMonths(1))}" else ""
            cats.addView(row(inflater, cats, c.category, c.amount, meta, c.sharePercent))
        }
        val merchants = findViewById<LinearLayout>(R.id.containerInsightsMerchants)
        merchants.removeAllViews()
        r.topMerchants.forEach { m ->
            val share = if (r.total.signum() == 0) 0
            else m.amount.multiply(BigDecimal(100)).divide(r.total, 0, java.math.RoundingMode.HALF_UP).toInt()
            merchants.addView(row(inflater, merchants, m.merchant, m.amount,
                resources.getQuantityString(R.plurals.insights_payments, m.count, m.count), share))
        }
    }

    private fun row(inflater: LayoutInflater, parent: LinearLayout, title: String, amount: BigDecimal, meta: String, pct: Int): View {
        val v = inflater.inflate(R.layout.item_insight_row, parent, false)
        v.findViewById<TextView>(R.id.textInsightName).text = title
        v.findViewById<TextView>(R.id.textInsightAmount).text = Money.styled(Money.format(amount))
        v.findViewById<TextView>(R.id.textInsightMeta).text = meta
        val p = pct.coerceIn(0, 100).toFloat()
        (v.findViewById<View>(R.id.insightBarFill).layoutParams as LinearLayout.LayoutParams).weight = p
        (v.findViewById<View>(R.id.insightBarRest).layoutParams as LinearLayout.LayoutParams).weight = 100f - p
        return v
    }

    private companion object {
        const val KEY_MONTH = "month"
    }
}
