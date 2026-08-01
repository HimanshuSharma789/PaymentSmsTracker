package com.example.paymentsmstracker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.adapter.SplitTableAdapter
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SplitsActivity : AppCompatActivity() {

    private lateinit var adapter: SplitTableAdapter
    private lateinit var empty: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splits)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbarSplits)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        empty = findViewById(R.id.textSplitsEmpty)
        summary = findViewById(R.id.textSplitsSummary)
        val recycler: RecyclerView = findViewById(R.id.recyclerSplitsTable)
        adapter = SplitTableAdapter { id ->
            startActivity(
                Intent(this, TransactionEntryActivity::class.java).apply {
                    putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, id)
                }
            )
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false

        findViewById<MaterialButton>(R.id.buttonExportSplitwiseFromSplits).setOnClickListener {
            exportSplitwise()
        }

        lifecycleScope.launch {
            AppDatabase.getDatabase(applicationContext).transactionDao()
                .getSplitTransactions()
                .collectLatest { txs ->
                    val rows = txs.map { SplitTableAdapter.fromTransaction(it) }
                    adapter.submitList(rows)
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    val friendsTotal = rows.fold(BigDecimal.ZERO) { acc, row ->
                        acc.add(BigDecimal(row.friendsOwe))
                    }
                    summary.text = if (rows.isEmpty()) {
                        getString(R.string.no_splits_yet)
                    } else {
                        getString(
                            R.string.splits_summary,
                            rows.size,
                            friendsTotal.setScale(2).toPlainString()
                        )
                    }
                }
        }
    }

    private fun exportSplitwise() {
        lifecycleScope.launch {
            val txs = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).transactionDao()
                    .getSplitTransactionsList()
                    .filter {
                        it.reviewStatus == ReviewStatus.REVIEWED ||
                            it.reviewStatus == ReviewStatus.EXPORTED
                    }
            }
            if (txs.isEmpty()) {
                Toast.makeText(this@SplitsActivity, "No reviewed splits", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val csv = SplitwiseExporter.toCsv(txs, onlySplits = true)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "splitwise_import_$stamp.csv"
            FileHandler().saveCsvFileToDownloads(this@SplitsActivity, fileName, csv)
            Toast.makeText(this@SplitsActivity, "Exported → $fileName", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
