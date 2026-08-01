package com.example.paymentsmstracker

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import com.example.paymentsmstracker.ui.OptionPickerBottomSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TransactionEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "com.example.paymentsmstracker.EXTRA_TRANSACTION_ID"
        const val EXTRA_TRANSACTION_AMOUNT = "transaction_amount"
        const val EXTRA_TRANSACTION_MERCHANT = "transaction_merchant"
        const val EXTRA_ORIGINAL_SMS = "original_sms"
        const val EXTRA_TRANSACTION_DATE = "transaction_date"
        const val EXTRA_TRANSACTION_REFERENCE = "transaction_reference"
        const val EXTRA_TRANSACTION_ACCOUNT = "transaction_account"
        private const val INVALID_TRANSACTION_ID = 0L
        private val TYPES = listOf("Expense", "Income", "Transfer-Out")
    }

    private lateinit var amountEditText: EditText
    private lateinit var merchantEditText: EditText
    private lateinit var buttonPickAccount: MaterialButton
    private lateinit var buttonPickType: MaterialButton
    private lateinit var buttonPickCategory: MaterialButton
    private lateinit var buttonPickSubcategory: MaterialButton
    private lateinit var notesEditText: EditText
    private lateinit var dateButton: Button
    private lateinit var saveButton: Button
    private lateinit var saveDraftButton: Button
    private lateinit var deleteButton: Button
    private lateinit var originalSmsTextView: TextView
    private lateinit var splitPreviewText: TextView
    private lateinit var splitModeHintText: TextView
    private lateinit var chipGroupParticipants: ChipGroup
    private lateinit var chipGroupSplitMode: ChipGroup
    private lateinit var containerSplitAllocations: LinearLayout
    private lateinit var buttonToggleSms: MaterialButton
    private lateinit var buttonToggleMore: MaterialButton
    private lateinit var containerMoreDetails: View
    private lateinit var originalSmsCard: View

    private lateinit var db: AppDatabase
    private lateinit var config: AppConfig
    private var currentTransactionId: Long = INVALID_TRANSACTION_ID
    private var transactionToEdit: Transaction? = null
    private var selectedTimestamp: Long = System.currentTimeMillis()
    private var originalSmsFromIntent: String? = null
    private var referenceFromIntent: String? = null

    private var selectedAccount: String = ""
    private var selectedType: String = "Expense"
    private var selectedCategory: String = ""
    private var selectedSubcategory: String = ""
    private var selectedSplitMode: String = SplitMode.EQUAL
    private val allocationInputs = linkedMapOf<String, BigDecimal>()
    private var rebuildingAllocations = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_entry)

        db = AppDatabase.getDatabase(applicationContext)
        config = ConfigStore(this).load()

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar_transaction_entry)
        amountEditText = findViewById(R.id.editTextAmount)
        merchantEditText = findViewById(R.id.editTextMerchant)
        buttonPickAccount = findViewById(R.id.buttonPickAccount)
        buttonPickType = findViewById(R.id.buttonPickType)
        buttonPickCategory = findViewById(R.id.buttonPickCategory)
        buttonPickSubcategory = findViewById(R.id.buttonPickSubcategory)
        notesEditText = findViewById(R.id.editTextNotes)
        dateButton = findViewById(R.id.buttonDate)
        saveButton = findViewById(R.id.buttonSaveTransaction)
        saveDraftButton = findViewById(R.id.buttonSaveDraft)
        deleteButton = findViewById(R.id.buttonDeleteTransaction)
        originalSmsTextView = findViewById(R.id.textViewOriginalSms)
        splitPreviewText = findViewById(R.id.textViewSplitPreview)
        splitModeHintText = findViewById(R.id.textViewSplitModeHint)
        chipGroupParticipants = findViewById(R.id.chipGroupParticipants)
        chipGroupSplitMode = findViewById(R.id.chipGroupSplitMode)
        containerSplitAllocations = findViewById(R.id.containerSplitAllocations)
        buttonToggleSms = findViewById(R.id.buttonToggleSms)
        buttonToggleMore = findViewById(R.id.buttonToggleMore)
        containerMoreDetails = findViewById(R.id.containerMoreDetails)
        originalSmsCard = findViewById(R.id.card_original_sms)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupPickers()
        setupFieldNavigation()
        setupParticipantChips(emptySet())
        setupSplitModeChips()
        updateDateButtonText()
        updatePickerLabels()
        refreshSplitUi()

        currentTransactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, INVALID_TRANSACTION_ID)
        if (currentTransactionId != INVALID_TRANSACTION_ID) {
            supportActionBar?.title = "Review Transaction"
            deleteButton.visibility = View.VISIBLE
            loadTransactionData(currentTransactionId)
        } else {
            supportActionBar?.title = "Add Transaction"
            deleteButton.visibility = View.GONE
            prefillFromSmsIntent()
        }

        dateButton.setOnClickListener { showDatePickerDialog() }
        saveButton.setOnClickListener { saveOrUpdateTransaction(markReviewed = true) }
        saveDraftButton.setOnClickListener { saveOrUpdateTransaction(markReviewed = false) }
        deleteButton.setOnClickListener { showDeleteConfirmationDialog() }
        buttonToggleSms.setOnClickListener {
            toggleSection(originalSmsCard, buttonToggleSms, R.string.show_original_sms, R.string.hide_original_sms)
        }
        buttonToggleMore.setOnClickListener {
            toggleSection(containerMoreDetails, buttonToggleMore, R.string.add_split, R.string.hide_split)
        }
        chipGroupParticipants.setOnCheckedStateChangeListener { _, _ ->
            rebuildAllocationRows()
            refreshSplitUi()
        }
        amountEditText.addTextChangedListener(SimpleWatcher { refreshSplitUi() })
    }

    private fun setupFieldNavigation() {
        amountEditText.setOnEditorActionListener { _, actionId, event ->
            val isNext = actionId == EditorInfo.IME_ACTION_NEXT ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isNext) {
                merchantEditText.requestFocus()
                merchantEditText.setSelection(merchantEditText.text?.length ?: 0)
                true
            } else {
                false
            }
        }
        merchantEditText.setOnEditorActionListener { _, actionId, event ->
            val isNext = actionId == EditorInfo.IME_ACTION_NEXT ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isNext) {
                hideKeyboard(merchantEditText)
                openAccountPicker(advanceAfterSelect = true)
                true
            } else {
                false
            }
        }
    }

    private fun setupPickers() {
        buttonPickAccount.setOnClickListener { openAccountPicker(advanceAfterSelect = false) }
        buttonPickType.setOnClickListener { openTypePicker(advanceAfterSelect = false) }
        buttonPickCategory.setOnClickListener { openCategoryPicker(advanceAfterSelect = false) }
        buttonPickSubcategory.setOnClickListener { openSubcategoryPicker(advanceAfterSelect = false) }
    }

    private fun setupSplitModeChips() {
        chipGroupSplitMode.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
            selectedSplitMode = SplitMode.normalize(chip.tag?.toString())
            rebuildAllocationRows()
            refreshSplitUi()
        }
    }

    private fun openAccountPicker(advanceAfterSelect: Boolean) {
        OptionPickerBottomSheet.show(
            this,
            getString(R.string.account),
            config.accounts,
            selectedAccount.ifBlank { null },
            spanCount = 2
        ) { value ->
            selectedAccount = value
            updatePickerLabels()
            if (advanceAfterSelect) {
                buttonPickAccount.post { openTypePicker(advanceAfterSelect = true) }
            }
        }
    }

    private fun openTypePicker(advanceAfterSelect: Boolean) {
        OptionPickerBottomSheet.show(
            this,
            getString(R.string.transaction_type),
            TYPES,
            selectedType,
            spanCount = 3
        ) { value ->
            selectedType = value
            selectedCategory = ""
            selectedSubcategory = ""
            updatePickerLabels()
            if (advanceAfterSelect) {
                buttonPickType.post { openCategoryPicker(advanceAfterSelect = true) }
            }
        }
    }

    private fun openCategoryPicker(advanceAfterSelect: Boolean) {
        val options = categoryOptionsForType()
        if (options.isEmpty()) {
            Toast.makeText(this, "No categories available", Toast.LENGTH_SHORT).show()
            return
        }
        OptionPickerBottomSheet.show(
            this,
            getString(R.string.category),
            options,
            selectedCategory.ifBlank { null },
            spanCount = 2
        ) { value ->
            selectedCategory = value
            selectedSubcategory = ""
            updatePickerLabels()
            if (advanceAfterSelect) {
                val subs = config.expenseCategories[selectedCategory].orEmpty()
                if (selectedType == "Expense" && subs.isNotEmpty()) {
                    buttonPickCategory.post { openSubcategoryPicker(advanceAfterSelect = true) }
                } else if (advanceAfterSelect) {
                    focusNotesAfterClassification()
                }
            }
        }
    }

    private fun openSubcategoryPicker(advanceAfterSelect: Boolean) {
        val options = config.expenseCategories[selectedCategory].orEmpty()
        if (options.isEmpty()) {
            Toast.makeText(this, "No subcategory for this category", Toast.LENGTH_SHORT).show()
            return
        }
        OptionPickerBottomSheet.show(
            this,
            getString(R.string.subcategory),
            options,
            selectedSubcategory.ifBlank { null },
            spanCount = 2
        ) { value ->
            selectedSubcategory = value
            updatePickerLabels()
            if (advanceAfterSelect) {
                focusNotesAfterClassification()
            }
        }
    }

    private fun focusNotesAfterClassification() {
        notesEditText.post {
            notesEditText.requestFocus()
            showKeyboard(notesEditText)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun categoryOptionsForType(): List<String> = when (selectedType) {
        "Income" -> config.incomeCategories
        "Transfer-Out" -> config.accounts
        else -> config.expenseCategories.keys.sorted()
    }

    private fun updatePickerLabels() {
        buttonPickAccount.text = selectedAccount.ifBlank { getString(R.string.account) }
        buttonPickType.text = selectedType.ifBlank { getString(R.string.transaction_type) }
        buttonPickCategory.text = selectedCategory.ifBlank { getString(R.string.category) }
        buttonPickSubcategory.text =
            selectedSubcategory.ifBlank { getString(R.string.subcategory) }
        val showSub = selectedType == "Expense" &&
            config.expenseCategories[selectedCategory].orEmpty().isNotEmpty()
        buttonPickSubcategory.isEnabled = showSub
        buttonPickSubcategory.alpha = if (showSub) 1f else 0.5f
    }

    private fun setupParticipantChips(selected: Set<String>) {
        chipGroupParticipants.removeAllViews()
        config.participants.forEach { name ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = selected.any { it.equals(name, ignoreCase = true) }
            }
            chipGroupParticipants.addView(chip)
        }
    }

    private fun selectedParticipants(): List<String> {
        val selected = mutableListOf<String>()
        for (i in 0 until chipGroupParticipants.childCount) {
            val chip = chipGroupParticipants.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) selected.add(chip.text.toString())
        }
        return selected
    }

    private fun selectSplitModeChip(mode: String) {
        val normalized = SplitMode.normalize(mode)
        selectedSplitMode = normalized
        for (i in 0 until chipGroupSplitMode.childCount) {
            val chip = chipGroupSplitMode.getChildAt(i) as? Chip ?: continue
            chip.isChecked = SplitMode.normalize(chip.tag?.toString()) == normalized
        }
    }

    private fun rebuildAllocationRows() {
        if (rebuildingAllocations) return
        rebuildingAllocations = true
        try {
            val people = selectedParticipants()
            val keep = linkedMapOf<String, BigDecimal>()
            people.forEach { name ->
                allocationInputs.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.let { keep[name] = it.value }
            }
            allocationInputs.clear()
            allocationInputs.putAll(keep)

            containerSplitAllocations.removeAllViews()
            if (selectedSplitMode == SplitMode.EQUAL || people.isEmpty()) {
                return
            }

            val inflater = LayoutInflater.from(this)
            people.forEach { name ->
                val row = inflater.inflate(R.layout.item_split_allocation, containerSplitAllocations, false)
                val nameView = row.findViewById<TextView>(R.id.textAllocationName)
                val layout = row.findViewById<TextInputLayout>(R.id.layoutAllocationValue)
                val edit = row.findViewById<TextInputEditText>(R.id.editAllocationValue)
                nameView.text = name
                layout.hint = when (selectedSplitMode) {
                    SplitMode.PERCENT -> "%"
                    SplitMode.WEIGHT -> "Shares"
                    else -> "₹"
                }
                val existing = allocationInputs[name]
                if (existing != null) {
                    edit.setText(existing.stripTrailingZeros().toPlainString())
                } else if (selectedSplitMode == SplitMode.WEIGHT) {
                    edit.setText("1")
                    allocationInputs[name] = BigDecimal.ONE
                }
                edit.addTextChangedListener(SimpleWatcher {
                    val value = edit.text?.toString()?.toBigDecimalOrNull()
                    if (value != null) {
                        allocationInputs[name] = value
                    } else {
                        allocationInputs.remove(name)
                    }
                    refreshSplitUi()
                })
                containerSplitAllocations.addView(row)
            }
        } finally {
            rebuildingAllocations = false
        }
    }

    private fun refreshSplitUi() {
        val people = selectedParticipants()
        val needsInputs = selectedSplitMode != SplitMode.EQUAL && people.isNotEmpty()
        containerSplitAllocations.visibility = if (needsInputs) View.VISIBLE else View.GONE
        splitModeHintText.setText(
            when (selectedSplitMode) {
                SplitMode.EXACT -> R.string.split_hint_exact
                SplitMode.PERCENT -> R.string.split_hint_percent
                SplitMode.WEIGHT -> R.string.split_hint_weight
                else -> R.string.split_hint_equal
            }
        )

        val amount = amountEditText.text.toString().toDoubleOrNull() ?: 0.0
        val result = SplitCalculator.resolve(
            totalAmount = amount,
            participants = people,
            mode = selectedSplitMode,
            allocationInputs = allocationInputs.toMap()
        )

        splitPreviewText.text = if (people.isEmpty()) {
            MoneyManagerExporter.splitPreview(amount, people, config)
        } else if (!result.isValid) {
            result.error
        } else {
            buildString {
                append(MoneyManagerExporter.splitPreviewFromShares(result.myShare, result.friendsOwe, config))
                append('\n')
                append(
                    result.amounts.entries.joinToString(" · ") {
                        "${it.key}: ₹${it.value.toPlainString()}"
                    }
                )
            }
        }
    }

    private fun prefillFromSmsIntent() {
        val receivedAmount = intent.getDoubleExtra(EXTRA_TRANSACTION_AMOUNT, 0.0)
        val receivedMerchant = intent.getStringExtra(EXTRA_TRANSACTION_MERCHANT)
        originalSmsFromIntent = intent.getStringExtra(EXTRA_ORIGINAL_SMS)
        referenceFromIntent = intent.getStringExtra(EXTRA_TRANSACTION_REFERENCE)
        val date = intent.getLongExtra(EXTRA_TRANSACTION_DATE, 0L)
        if (date > 0L) selectedTimestamp = date
        val account = intent.getStringExtra(EXTRA_TRANSACTION_ACCOUNT).orEmpty()

        if (receivedAmount > 0.0) amountEditText.setText(receivedAmount.toString())
        if (!receivedMerchant.isNullOrEmpty()) merchantEditText.setText(receivedMerchant)
        if (account.isNotBlank()) selectedAccount = account
        updateDateButtonText()
        updatePickerLabels()
        refreshSplitUi()

        if (!originalSmsFromIntent.isNullOrEmpty()) {
            originalSmsTextView.text = originalSmsFromIntent
            buttonToggleSms.visibility = View.VISIBLE
            originalSmsCard.visibility = View.GONE
        } else {
            buttonToggleSms.visibility = View.GONE
            originalSmsCard.visibility = View.GONE
        }
    }

    private fun loadTransactionData(transactionId: Long) {
        lifecycleScope.launch {
            transactionToEdit = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionById(transactionId)
            }
            transactionToEdit?.let { tx ->
                amountEditText.setText(tx.amount.toString())
                merchantEditText.setText(tx.merchantName)
                selectedAccount = tx.account
                selectedType = tx.transactionType.ifBlank { "Expense" }
                selectedCategory = tx.category
                selectedSubcategory = tx.subcategory
                notesEditText.setText(tx.notes.orEmpty())
                selectedTimestamp = tx.timestamp
                selectedSplitMode = SplitMode.normalize(tx.splitMode)
                allocationInputs.clear()
                allocationInputs.putAll(SplitCalculator.parseAllocations(tx.splitAllocations))
                updateDateButtonText()
                updatePickerLabels()
                setupParticipantChips(tx.participantList().toSet())
                selectSplitModeChip(selectedSplitMode)
                rebuildAllocationRows()
                refreshSplitUi()
                if (tx.participants.isNotBlank()) {
                    containerMoreDetails.visibility = View.VISIBLE
                    buttonToggleMore.setText(R.string.hide_split)
                }
                originalSmsFromIntent = tx.originalSms
                referenceFromIntent = tx.referenceNumber
                if (!tx.originalSms.isNullOrEmpty()) {
                    originalSmsTextView.text = tx.originalSms
                    buttonToggleSms.visibility = View.VISIBLE
                    originalSmsCard.visibility = View.GONE
                } else {
                    buttonToggleSms.visibility = View.GONE
                }
            }
        }
    }

    private fun toggleSection(
        section: View,
        button: MaterialButton,
        showText: Int,
        hideText: Int
    ) {
        val willShow = section.visibility != View.VISIBLE
        section.visibility = if (willShow) View.VISIBLE else View.GONE
        button.setText(if (willShow) hideText else showText)
    }

    private fun updateDateButtonText() {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        dateButton.text = sdf.format(Date(selectedTimestamp))
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                calendar.set(y, m, d)
                selectedTimestamp = calendar.timeInMillis
                updateDateButtonText()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveOrUpdateTransaction(markReviewed: Boolean) {
        val amountStr = amountEditText.text.toString()
        val merchant = merchantEditText.text.toString().trim()
        val notes = notesEditText.text.toString().trim()
        val participants = selectedParticipants()

        if (amountStr.isEmpty()) {
            amountEditText.error = "Required"; return
        }
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            amountEditText.error = "Invalid amount"; return
        }
        if (merchant.isEmpty()) {
            merchantEditText.error = "Required"; return
        }
        if (selectedAccount.isBlank()) {
            Toast.makeText(this, "Choose an account", Toast.LENGTH_SHORT).show()
            return
        }
        if (markReviewed && selectedType == "Expense" && selectedCategory.isBlank()) {
            Toast.makeText(this, "Choose a category", Toast.LENGTH_SHORT).show()
            return
        }

        val mode = if (participants.isEmpty()) SplitMode.EQUAL else selectedSplitMode
        val allocations = if (mode == SplitMode.EQUAL || participants.isEmpty()) {
            emptyMap()
        } else {
            allocationInputs.toMap()
        }
        if (participants.isNotEmpty() && mode != SplitMode.EQUAL) {
            val result = SplitCalculator.resolve(amount, participants, mode, allocations)
            if (!result.isValid) {
                Toast.makeText(this, result.error, Toast.LENGTH_LONG).show()
                return
            }
        }

        val status = if (markReviewed) ReviewStatus.REVIEWED else ReviewStatus.NEEDS_REVIEW
        val transaction = Transaction(
            id = if (currentTransactionId == INVALID_TRANSACTION_ID) 0L else currentTransactionId,
            amount = amount,
            merchantName = merchant,
            category = selectedCategory,
            subcategory = selectedSubcategory,
            account = selectedAccount,
            notes = notes.ifEmpty { null },
            timestamp = selectedTimestamp,
            originalSms = transactionToEdit?.originalSms ?: originalSmsFromIntent,
            isExpense = selectedType != "Income",
            referenceNumber = transactionToEdit?.referenceNumber ?: referenceFromIntent,
            participants = participants.joinToString(","),
            splitMode = mode,
            splitAllocations = SplitCalculator.encodeAllocations(allocations),
            reviewStatus = status,
            transactionType = selectedType,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (currentTransactionId == INVALID_TRANSACTION_ID) {
                        db.transactionDao().insertTransaction(transaction)
                    } else {
                        db.transactionDao().updateTransaction(transaction)
                    }
                }
                Toast.makeText(
                    this@TransactionEntryActivity,
                    if (markReviewed) "Marked reviewed" else "Saved draft",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: Exception) {
                Log.e("TransactionEntry", "Save failed", e)
                Toast.makeText(this@TransactionEntryActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDeleteConfirmationDialog() {
        if (currentTransactionId == INVALID_TRANSACTION_ID || transactionToEdit == null) return
        AlertDialog.Builder(this)
            .setTitle("Delete Transaction")
            .setMessage("Delete this transaction?")
            .setPositiveButton("Delete") { _, _ -> deleteCurrentTransaction() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCurrentTransaction() {
        transactionToEdit?.let { tx ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.transactionDao().deleteTransaction(tx) }
                Toast.makeText(this@TransactionEntryActivity, "Deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private class SimpleWatcher(private val onChange: () -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }
}
