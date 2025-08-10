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
// Make sure to import your click listener interface and the TransactionEntryActivity
import com.example.paymentsmstracker.adapter.OnTransactionItemClickListener
import com.example.paymentsmstracker.adapter.TransactionAdapter
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.viewmodel.TransactionListViewModel
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 1. Implement the OnTransactionItemClickListener interface
class TransactionListActivity : AppCompatActivity(), OnTransactionItemClickListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateTextView: TextView
    private lateinit var transactionAdapter: TransactionAdapter // Keep this type

    private lateinit var db: AppDatabase
    private val transactionListViewModel: TransactionListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_list)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbarTransactionList)
        setSupportActionBar(toolbar)

        db = AppDatabase.getDatabase(applicationContext)

        supportActionBar?.title = "History"
        // supportActionBar?.setDisplayHomeAsUpEnabled(true) // Usually not needed for the main list screen

        recyclerView = findViewById(R.id.recyclerViewTransactions)
        emptyStateTextView = findViewById(R.id.textViewEmptyState)

        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        // 2. Pass 'this' (the activity) as the click listener when creating the adapter
        transactionAdapter = TransactionAdapter(this)
        recyclerView.adapter = transactionAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
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

    // 3. Implement the interface method
    override fun onTransactionItemClick(transactionId: Long) {
        val intent = Intent(this, TransactionEntryActivity::class.java).apply {
            // Use the constant defined in TransactionEntryActivity
            putExtra(TransactionEntryActivity.EXTRA_TRANSACTION_ID, transactionId)
        }
        startActivity(intent)
    }


    suspend fun exportTransactionsToCsv(context: Context, db: AppDatabase): Boolean {
        val TAG = "CsvExport"
        // Ensure getAllTransactionsList() is a suspend function or called from a coroutine
        val transactions = withContext(Dispatchers.IO) { // Example if getAllTransactionsList is suspend
            db.transactionDao().getAllTransactionsList()
        }


        if (transactions.isEmpty()) {
            Log.i(TAG, "No transactions to export.")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No transactions to export.", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val fileName = "transactions_export_$fileTimestamp.csv"

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        // Your CSV generation logic (ensure it's robust, especially with quotes and commas in data)
        val csvHeader = "ID,Amount,Merchant Name,Category,Notes,Timestamp,Original SMS\n"
        val csvBody = transactions.joinToString(separator = "\n") { transaction ->
            val id = transaction.id
            val amount = transaction.amount
            val merchantName = "\"${transaction.merchantName.replace("\"", "\"\"")}\"" // Escape quotes
            val category = "\"${transaction.category.replace("\"", "\"\"")}\""
            val notes = "\"${transaction.notes?.replace("\"", "\"\"") ?: ""}\""
            val timestamp = sdf.format(Date(transaction.timestamp))
            val originalSms = "\"${transaction.originalSms?.replace("\"", "\"\"") ?: ""}\""
            "$id,$amount,$merchantName,$category,$notes,$timestamp,$originalSms"
        }
        val csvContent = csvHeader + csvBody

        try {
            // Assuming FileHandler().saveCsvFileToDownloads(...) is implemented correctly
            // and handles permissions if necessary (e.g., using MediaStore for Android 10+)
            FileHandler().saveCsvFileToDownloads(this, fileName, csvContent) // Consider making this suspend too
            Log.i(TAG, "Successfully exported ${transactions.size} transactions to $fileName")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Exported to: $fileName", Toast.LENGTH_LONG).show()
            }
            return true
        } catch (e: Exception) { // Catch broader exceptions during file saving
            Log.e(TAG, "Error writing CSV file", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }


    private fun handleExportAction() {
        CoroutineScope(Dispatchers.Main).launch { // Already on Main, IO for db/file access
            val success = exportTransactionsToCsv(this@TransactionListActivity, db)
            // No need to switch context for exportTransactionsToCsv if it handles its own IO
            // val success = withContext(Dispatchers.IO) { // Not strictly needed here if exportTransactionsToCsv is well-defined
            //     exportTransactionsToCsv(this@TransactionListActivity, db)
            // }
            if (success) {
                Log.d("TransactionListActivity", "CSV Export successful call.")
            } else {
                Log.d("TransactionListActivity", "CSV Export failed call.")
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        // If this is your top-level activity in this task flow,
        // you might not need a back button or could just finish().
        // For actual navigation, use NavController or finish().
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
            // Add other menu items here if needed
            android.R.id.home -> { // Handle the Up button if setDisplayHomeAsUpEnabled(true)
                onSupportNavigateUp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
