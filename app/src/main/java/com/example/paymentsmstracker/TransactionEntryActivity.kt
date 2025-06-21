package com.example.paymentsmstracker // Your activity package

import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.Transaction
import kotlinx.coroutines.launch

class TransactionEntryActivity : AppCompatActivity() {

    private lateinit var amountEditText: EditText
    private lateinit var merchantEditText: EditText
    private lateinit var autoCompleteCategoryTextView: AutoCompleteTextView
    private lateinit var notesEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var originalSmsTextView: TextView

    private lateinit var db: AppDatabase // Database instance
    private var originalSmsFromIntent: String? = null // To store original SMS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_entry)

        // Initialize Room Database instance
        db = AppDatabase.getDatabase(applicationContext)

        amountEditText = findViewById(R.id.editTextAmount)
        merchantEditText = findViewById(R.id.editTextMerchant)
        autoCompleteCategoryTextView = findViewById(R.id.autoCompleteTextView_category)
        saveButton = findViewById(R.id.buttonSaveTransaction)
        notesEditText = findViewById(R.id.editTextNotes)
        originalSmsTextView = findViewById(R.id.textViewOriginalSms)

        val categories = listOf(
            "Food",
            "Travel",
            "Shopping",
            "Bills",
            "Entertainment",
            "Health",
            "Education",
            "Groceries",
            "Other"
        )
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            categories
        )
        autoCompleteCategoryTextView.setAdapter(adapter)

        // --- Get data from Intent ---
        val receivedAmount = intent.getDoubleExtra("transaction_amount", 0.0)
        val receivedMerchant = intent.getStringExtra("transaction_merchant")
        originalSmsFromIntent = intent.getStringExtra("original_sms") // Store it

        if (receivedAmount > 0.0) {
            amountEditText.setText(receivedAmount.toString())
        }
        if (!receivedMerchant.isNullOrEmpty()) {
            merchantEditText.setText(receivedMerchant)
        }
        if (!originalSmsFromIntent.isNullOrEmpty()) {
            originalSmsTextView.text = "Original SMS:\n$originalSmsFromIntent"
            originalSmsTextView.visibility = TextView.VISIBLE // Make it visible if SMS exists
        } else {
            originalSmsTextView.visibility = TextView.GONE // Hide if no SMS
        }

        saveButton.setOnClickListener {
            saveTransaction()
        }
    }

    private fun saveTransaction() {
        val amountStr = amountEditText.text.toString()
        val merchant = merchantEditText.text.toString().trim()
        val category = autoCompleteCategoryTextView.text.toString()
        val notes = notesEditText.text.toString().trim()

        if (amountStr.isEmpty()) {
            amountEditText.error = "Amount cannot be empty"
            amountEditText.requestFocus()
            return
        }

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            amountEditText.error = "Invalid amount"
            amountEditText.requestFocus()
            return
        }

        if (merchant.isEmpty()) {
            merchantEditText.error = "Merchant cannot be empty"
            merchantEditText.requestFocus()
            return
        }

        // --- Create Transaction Object ---
        val transaction = Transaction(
            amount = amount,
            merchantName = merchant,
            category = category,
            notes = if (notes.isNotEmpty()) notes else null, // Store null if notes are empty
            timestamp = System.currentTimeMillis(),
            originalSms = originalSmsFromIntent // Save original SMS
        )

        // --- Save to Database using a Coroutine ---
        lifecycleScope.launch { // Use lifecycleScope for automatic cancellation
            try {
                db.transactionDao().insertTransaction(transaction)
                Log.d("TransactionEntry", "Transaction saved successfully: $transaction")
                Toast.makeText(
                    this@TransactionEntryActivity,
                    "Transaction saved!",
                    Toast.LENGTH_SHORT
                ).show()
                finish() // Close this activity
            } catch (e: Exception) {
                Log.e("TransactionEntry", "Failed to save transaction", e)
                Toast.makeText(
                    this@TransactionEntryActivity,
                    "Failed to save transaction: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}