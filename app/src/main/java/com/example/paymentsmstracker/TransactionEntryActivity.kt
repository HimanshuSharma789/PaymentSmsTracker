package com.example.paymentsmstracker // Your activity package

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TransactionEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "com.example.paymentsmstracker.EXTRA_TRANSACTION_ID"
        // Intent extras for pre-filling from SMS (for new transactions)
        const val EXTRA_TRANSACTION_AMOUNT = "transaction_amount"
        const val EXTRA_TRANSACTION_MERCHANT = "transaction_merchant"
        const val EXTRA_ORIGINAL_SMS = "original_sms"

        private const val INVALID_TRANSACTION_ID = 0L // Assuming your IDs start from 1
    }

    private lateinit var amountEditText: EditText
    private lateinit var merchantEditText: EditText
    private lateinit var autoCompleteCategoryTextView: AutoCompleteTextView
    private lateinit var notesEditText: EditText
    private lateinit var dateButton: Button // For picking/displaying the date
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button // Optional: For deleting transactions
    private lateinit var originalSmsTextView: TextView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar // Add toolbar


    private lateinit var db: AppDatabase
    private var currentTransactionId: Long = INVALID_TRANSACTION_ID
    private var transactionToEdit: Transaction? = null
    private var selectedTimestamp: Long = System.currentTimeMillis() // Default to now, will be updated
    private var originalSmsFromIntent: String? = null // To store original SMS for new transactions

    private val categories = listOf(
        "Food", "Travel", "Shopping", "Bills", "Entertainment",
        "Health", "Education", "Groceries", "Salary", "Freelance", "Other"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_entry)

        db = AppDatabase.getDatabase(applicationContext)

        // Initialize Views
        toolbar = findViewById(R.id.toolbar_transaction_entry) // Make sure this ID exists in your layout
        amountEditText = findViewById(R.id.editTextAmount)
        merchantEditText = findViewById(R.id.editTextMerchant)
        autoCompleteCategoryTextView = findViewById(R.id.autoCompleteTextView_category)
        notesEditText = findViewById(R.id.editTextNotes)
        dateButton = findViewById(R.id.buttonDate) // Make sure this ID exists for the date button
        saveButton = findViewById(R.id.buttonSaveTransaction)
        deleteButton = findViewById(R.id.buttonDeleteTransaction) // Make sure this ID exists
        originalSmsTextView = findViewById(R.id.textViewOriginalSms)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        setupCategoryAutoComplete()
        updateDateButtonText() // Initialize with current/default date

        currentTransactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, INVALID_TRANSACTION_ID)

        if (currentTransactionId != INVALID_TRANSACTION_ID) {
            supportActionBar?.title = "Edit Transaction"
            deleteButton.visibility = View.VISIBLE
            loadTransactionData(currentTransactionId)
        } else {
            supportActionBar?.title = "Add Transaction"
            deleteButton.visibility = View.GONE
            // Pre-fill from SMS intent if it's a new transaction
            prefillFromSmsIntent()
        }

        dateButton.setOnClickListener {
            showDatePickerDialog()
        }

        saveButton.setOnClickListener {
            saveOrUpdateTransaction()
        }

        deleteButton.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun setupCategoryAutoComplete() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
        autoCompleteCategoryTextView.setAdapter(adapter)
        autoCompleteCategoryTextView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                autoCompleteCategoryTextView.showDropDown()
            }
        }
    }

    private fun prefillFromSmsIntent() {
        val receivedAmount = intent.getDoubleExtra(EXTRA_TRANSACTION_AMOUNT, 0.0)
        val receivedMerchant = intent.getStringExtra(EXTRA_TRANSACTION_MERCHANT)
        originalSmsFromIntent = intent.getStringExtra(EXTRA_ORIGINAL_SMS) // Store it

        if (receivedAmount > 0.0) {
            amountEditText.setText(receivedAmount.toString())
        }
        if (!receivedMerchant.isNullOrEmpty()) {
            merchantEditText.setText(receivedMerchant)
        }
        if (!originalSmsFromIntent.isNullOrEmpty()) {
            originalSmsTextView.text = "Original SMS:\n$originalSmsFromIntent"
            originalSmsTextView.visibility = TextView.VISIBLE
        } else {
            originalSmsTextView.visibility = TextView.GONE
        }
        // For new transactions, timestamp defaults to current time (selectedTimestamp)
    }

    private fun loadTransactionData(transactionId: Long) {
        lifecycleScope.launch {
            transactionToEdit = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionById(transactionId)
            }
            transactionToEdit?.let { transaction ->
                amountEditText.setText(transaction.amount.toString())
                merchantEditText.setText(transaction.merchantName)
                autoCompleteCategoryTextView.setText(transaction.category, false) // Set text without filtering
                notesEditText.setText(transaction.notes ?: "")
                selectedTimestamp = transaction.timestamp // Important: update selectedTimestamp
                updateDateButtonText()

                if (!transaction.originalSms.isNullOrEmpty()) {
                    originalSmsTextView.text = "Original SMS:\n${transaction.originalSms}"
                    originalSmsTextView.visibility = TextView.VISIBLE
                } else {
                    originalSmsTextView.visibility = TextView.GONE
                }
                originalSmsFromIntent = transaction.originalSms // Store for potential re-save
            }
        }
    }

    private fun updateDateButtonText() {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        dateButton.text = sdf.format(Date(selectedTimestamp))
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
            calendar.set(selectedYear, selectedMonth, selectedDay)
            selectedTimestamp = calendar.timeInMillis
            updateDateButtonText()
        }, year, month, day).show()
    }

    private fun saveOrUpdateTransaction() {
        val amountStr = amountEditText.text.toString()
        val merchant = merchantEditText.text.toString().trim()
        val category = autoCompleteCategoryTextView.text.toString().trim()
        val notes = notesEditText.text.toString().trim()

        if (amountStr.isEmpty()) {
            amountEditText.error = "Amount cannot be empty"; amountEditText.requestFocus(); return
        }
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            amountEditText.error = "Invalid amount"; amountEditText.requestFocus(); return
        }
        if (merchant.isEmpty()) {
            merchantEditText.error = "Merchant cannot be empty"; merchantEditText.requestFocus(); return
        }
        if (category.isEmpty()) {
            autoCompleteCategoryTextView.error = "Category cannot be empty"; autoCompleteCategoryTextView.requestFocus(); return
        }


        // Preserve the original SMS. If editing, use the one from transactionToEdit.
        // If new and from SMS, use originalSmsFromIntent. If new and manual, it's null.
        val finalOriginalSms = if (transactionToEdit != null) {
            transactionToEdit?.originalSms
        } else {
            originalSmsFromIntent
        }

        val transaction = Transaction(
            id = if (currentTransactionId == INVALID_TRANSACTION_ID) 0L else currentTransactionId,
            amount = amount,
            merchantName = merchant,
            category = category,
            notes = if (notes.isNotEmpty()) notes else null,
            timestamp = selectedTimestamp, // Use the potentially edited timestamp
            originalSms = finalOriginalSms,
            // You might need to adjust isExpense and referenceNumber based on your logic
            isExpense = true, // Defaulting, adjust as needed
            referenceNumber = transactionToEdit?.referenceNumber // Preserve if editing, or set if new
        )

        lifecycleScope.launch {
            try {
                val message: String
                if (currentTransactionId == INVALID_TRANSACTION_ID) {
                    db.transactionDao().insertTransaction(transaction)
                    message = "Transaction saved!"
                    Log.d("TransactionEntry", "Transaction saved successfully: $transaction")
                } else {
                    db.transactionDao().updateTransaction(transaction) // Ensure you have updateTransaction in DAO
                    message = "Transaction updated!"
                    Log.d("TransactionEntry", "Transaction updated successfully: $transaction")
                }
                Toast.makeText(this@TransactionEntryActivity, message, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Log.e("TransactionEntry", "Failed to save/update transaction", e)
                Toast.makeText(this@TransactionEntryActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDeleteConfirmationDialog() {
        if (currentTransactionId == INVALID_TRANSACTION_ID || transactionToEdit == null) return

        AlertDialog.Builder(this)
            .setTitle("Delete Transaction")
            .setMessage("Are you sure you want to delete this transaction?")
            .setPositiveButton("Delete") { _, _ -> deleteCurrentTransaction() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCurrentTransaction() {
        transactionToEdit?.let {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        db.transactionDao().deleteTransaction(it) // Ensure you have deleteTransaction in DAO
                    }
                    Toast.makeText(this@TransactionEntryActivity, "Transaction deleted", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Log.e("TransactionEntry", "Failed to delete transaction", e)
                    Toast.makeText(this@TransactionEntryActivity, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        // finish() // or onBackPressedDispatcher.onBackPressed()
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
