package com.example.paymentsmstracker

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.adapter.TransactionAdapter
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.viewmodel.TransactionListViewModel
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class TransactionListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateTextView: TextView
    private lateinit var transactionAdapter: TransactionAdapter

    // Use the by viewModels() Kotlin property delegate from activity-ktx
    private lateinit var db: AppDatabase
    private val transactionListViewModel: TransactionListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_list)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbarTransactionList)
        setSupportActionBar(toolbar)

        // Initialize Room Database instance
        db = AppDatabase.getDatabase(applicationContext)

        // Set up the action bar (optional)
        supportActionBar?.title = "History"
        supportActionBar?.setDisplayHomeAsUpEnabled(true) // Show back button

        recyclerView = findViewById(R.id.recyclerViewTransactions)
        emptyStateTextView = findViewById(R.id.textViewEmptyState)

        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        transactionAdapter = TransactionAdapter()
        recyclerView.adapter = transactionAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        // You can add ItemDecoration for dividers if you like:
//         recyclerView.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
    }

    private fun observeViewModel() {
        transactionListViewModel.allTransactions.observe(this, Observer { transactions ->
            transactions?.let {
                if (it.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyStateTextView.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyStateTextView.visibility = View.GONE
                    transactionAdapter.submitList(it)
                }
            } ?: run {
                Log.d("TransactionList", "Observer received null transaction list.")
                recyclerView.visibility = View.GONE
                emptyStateTextView.visibility = View.VISIBLE
            }
        })
    }

    suspend fun exportTransactionsToCsv(context: Context, db: AppDatabase): Boolean {
        val TAG = "CsvExport"
        val transactions = db.transactionDao().getAllTransactionsList() // Assuming this method exists and returns List<Transaction>

        if (transactions.isEmpty()) {
            Log.i(TAG, "No transactions to export.")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No transactions to export.", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val fileName = "transactions_export_$fileTimestamp.csv"

        val csvHeader = "ID,Amount,Merchant Name,Category,Notes,Timestamp,Original SMS\n"
        val csvBody = transactions.joinToString(separator = "\n") {
            "${it.id},${it.amount},\"${it.merchantName}\",${it.category},${it.notes},${it.timestamp},,${it.originalSms}" // Note the quotes around merchant name
        }
        val csvContent = csvHeader + csvBody



        try {
            FileHandler().saveCsvFileToDownloads(this, fileName, csvContent)
            Log.i(TAG, "Successfully exported ${transactions.size} transactions to $fileName")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Exported to: $fileName", Toast.LENGTH_LONG).show()
            }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Error writing CSV file", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }

    private fun handleExportAction() {
        CoroutineScope(Dispatchers.Main).launch {
            val success = withContext(Dispatchers.IO) {
                exportTransactionsToCsv(this@TransactionListActivity, db)
            }
            if (success) {
                Log.d("TransactionListActivity", "CSV Export successful call.")
                // You can add further UI updates here if needed,
                // though the export function already shows a Toast.
            } else {
                Log.d("TransactionListActivity", "CSV Export failed call.")
            }
        }
    }

    // Handle the back button in the action bar
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                handleExportAction()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

}