package com.example.paymentsmstracker

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paymentsmstracker.adapter.TransactionAdapter
import com.example.paymentsmstracker.viewmodel.TransactionListViewModel

class TransactionListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateTextView: TextView
    private lateinit var transactionAdapter: TransactionAdapter

    // Use the by viewModels() Kotlin property delegate from activity-ktx
    private val transactionListViewModel: TransactionListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_list)

        // Set up the action bar (optional)
        supportActionBar?.title = "All Transactions"
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

    // Handle the back button in the action bar
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}