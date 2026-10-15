package com.example.paymentsmstracker

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.SplitwiseExpenseIds
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerExporter
import com.example.paymentsmstracker.merchant.MerchantMemory
import com.example.paymentsmstracker.split.SplitCalculator
import com.example.paymentsmstracker.split.SplitMode
import com.example.paymentsmstracker.splitwise.SplitwiseApi
import com.example.paymentsmstracker.splitwise.SplitwiseAuthStore
import com.example.paymentsmstracker.splitwise.SplitwiseMemberLabels
import com.example.paymentsmstracker.splitwise.SplitwisePusher
import com.example.paymentsmstracker.splitwise.SplitwiseSessionEvents
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
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_TRANSACTION_DATE = "transaction_date"
        const val EXTRA_TRANSACTION_REFERENCE = "transaction_reference"
        const val EXTRA_TRANSACTION_ACCOUNT = "transaction_account"
        private const val INVALID_TRANSACTION_ID = 0L
        private val TYPES = listOf("Expense", "Income", "Transfer-Out")
    }

    private lateinit var amountEditText: EditText
    private lateinit var merchantEditText: EditText
    private lateinit var merchantInputLayout: TextInputLayout
    private lateinit var buttonPickAccount: MaterialButton
    private lateinit var buttonPickType: MaterialButton
    private lateinit var buttonPickCategory: MaterialButton
    private lateinit var buttonPickSubcategory: MaterialButton
    private lateinit var notesEditText: EditText
    private lateinit var dateButton: Button
    private lateinit var saveButton: Button
    private lateinit var saveDraftButton: Button
    private lateinit var originalSmsTextView: TextView
    private lateinit var splitPreviewText: TextView
    private lateinit var splitModeHintText: TextView
    private lateinit var chipGroupParticipants: ChipGroup
    private lateinit var chipGroupSplitMode: ChipGroup
    private lateinit var containerSplitAllocations: LinearLayout
    private lateinit var buttonToggleSms: MaterialButton
    private lateinit var buttonToggleMore: MaterialButton
    private lateinit var buttonCustomizeSplit: MaterialButton
    private lateinit var containerMoreDetails: View
    private lateinit var originalSmsCard: View
    private lateinit var buttonPickSplitwiseGroup: MaterialButton
    private lateinit var buttonPushSplitwise: MaterialButton
    private lateinit var textSplitwisePushStatus: TextView
    private lateinit var textSplitMembersHint: TextView
    private lateinit var textSplitWithLabel: TextView
    private lateinit var textSplitTypeLabel: TextView
    private lateinit var splitwiseStore: SplitwiseAuthStore

    private lateinit var db: AppDatabase
    private lateinit var config: AppConfig
    private var currentTransactionId: Long = INVALID_TRANSACTION_ID
    private var transactionToEdit: Transaction? = null
    private var selectedTimestamp: Long = System.currentTimeMillis()
    private var originalSmsFromIntent: String? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private lateinit var buttonOpenLocation: View
    private var referenceFromIntent: String? = null

    private var selectedAccount: String = ""
    private var selectedType: String = "Expense"
    private var selectedCategory: String = ""
    private var selectedSubcategory: String = ""
    private var selectedSplitMode: String = SplitMode.EQUAL
    private var customizeSplitExpanded: Boolean = false
    private var selectedSplitwiseGroupId: Long = 0L
    private var selectedSplitwiseExpenseId: Long = 0L
    private var currentReviewStatus: String = ReviewStatus.NEEDS_REVIEW
    private val allocationInputs = linkedMapOf<String, BigDecimal>()
    private var loadedAllocations = emptyMap<String, BigDecimal>()
    private var loadedParticipantIds = emptyMap<String, Long>()
    private var loadedParticipantOrder = emptyList<String>()
    private var rebuildingAllocations = false
    private var suppressingParticipantCallback = false
    private var canDeleteTransaction: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_entry)

        db = AppDatabase.getDatabase(applicationContext)
        config = ConfigStore(this).load()
        splitwiseStore = SplitwiseAuthStore(this)
        SplitwiseSessionEvents.observe(this, this)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar_transaction_entry)
        amountEditText = findViewById(R.id.editTextAmount)
        merchantEditText = findViewById(R.id.editTextMerchant)
        merchantInputLayout = findViewById(R.id.textField_merchant)
        buttonPickAccount = findViewById(R.id.buttonPickAccount)
        buttonPickType = findViewById(R.id.buttonPickType)
        buttonPickCategory = findViewById(R.id.buttonPickCategory)
        buttonPickSubcategory = findViewById(R.id.buttonPickSubcategory)
        notesEditText = findViewById(R.id.editTextNotes)
        dateButton = findViewById(R.id.buttonDate)
        saveButton = findViewById(R.id.buttonSaveTransaction)
        saveDraftButton = findViewById(R.id.buttonSaveDraft)
        originalSmsTextView = findViewById(R.id.textViewOriginalSms)
        splitPreviewText = findViewById(R.id.textViewSplitPreview)
        splitModeHintText = findViewById(R.id.textViewSplitModeHint)
        chipGroupParticipants = findViewById(R.id.chipGroupParticipants)
        chipGroupSplitMode = findViewById(R.id.chipGroupSplitMode)
        containerSplitAllocations = findViewById(R.id.containerSplitAllocations)
        buttonToggleSms = findViewById(R.id.buttonToggleSms)
        buttonOpenLocation = findViewById(R.id.buttonOpenLocation)
        buttonToggleMore = findViewById(R.id.buttonToggleMore)
        buttonCustomizeSplit = findViewById(R.id.buttonCustomizeSplit)
        containerMoreDetails = findViewById(R.id.containerMoreDetails)
        originalSmsCard = findViewById(R.id.card_original_sms)
        buttonPickSplitwiseGroup = findViewById(R.id.buttonPickSplitwiseGroup)
        buttonPushSplitwise = findViewById(R.id.buttonPushSplitwise)
        textSplitwisePushStatus = findViewById(R.id.textSplitwisePushStatus)
        textSplitMembersHint = findViewById(R.id.textSplitMembersHint)
        textSplitWithLabel = findViewById(R.id.textSplitWithLabel)
        textSplitTypeLabel = findViewById(R.id.textSplitTypeLabel)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        selectedSplitwiseGroupId = splitwiseStore.load().defaultGroupId
        setupPickers()
        setupFieldNavigation()
        setupSplitModeChips()
        updateDateButtonText()
        updatePickerLabels()
        bindSplitwiseGroupMembers(preferSelectedIds = emptySet(), selectAllIfEmpty = true)
        updateSplitwiseGroupLabel()
        refreshSplitUi()
        updatePushVisibility()

        currentTransactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, INVALID_TRANSACTION_ID)
        if (currentTransactionId != INVALID_TRANSACTION_ID) {
            supportActionBar?.title = "Review Transaction"
            canDeleteTransaction = true
            loadTransactionData(currentTransactionId)
        } else {
            supportActionBar?.title = "Add Transaction"
            canDeleteTransaction = false
            prefillFromSmsIntent()
        }
        invalidateOptionsMenu()

        dateButton.setOnClickListener { showDatePickerDialog() }
        saveButton.setOnClickListener { saveOrUpdateTransaction(markReviewed = true) }
        saveDraftButton.setOnClickListener { saveOrUpdateTransaction(markReviewed = false) }
        buttonToggleSms.setOnClickListener {
            toggleSection(originalSmsCard, buttonToggleSms, R.string.show_original_sms, R.string.hide_original_sms)
        }
        buttonOpenLocation.setOnClickListener { openLocationInMaps() }
        buttonToggleMore.setOnClickListener {
            toggleSection(containerMoreDetails, buttonToggleMore, R.string.add_split, R.string.hide_split)
        }
        buttonCustomizeSplit.setOnClickListener { toggleCustomizeSplit() }
        buttonPickSplitwiseGroup.setOnClickListener { openSplitwiseGroupPicker() }
        buttonPushSplitwise.setOnClickListener { pushCurrentToSplitwise() }
        chipGroupParticipants.setOnCheckedStateChangeListener { _, _ ->
            if (suppressingParticipantCallback) return@setOnCheckedStateChangeListener
            rebuildAllocationRows()
            refreshSplitUi()
            updatePushVisibility()
        }
        amountEditText.addTextChangedListener(SimpleWatcher { refreshSplitUi() })
        setupAiChatButton()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.transaction_entry_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_delete_transaction)?.isVisible = canDeleteTransaction
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete_transaction -> {
                showDeleteConfirmationDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
            if (selectedSplitMode != SplitMode.EQUAL) {
                customizeSplitExpanded = true
            }
            applyCustomizeSplitVisibility()
            rebuildAllocationRows()
            refreshSplitUi()
        }
    }

    private fun isTransfer(): Boolean = selectedType == "Transfer-Out"

    private fun openAccountPicker(advanceAfterSelect: Boolean) {
        OptionPickerBottomSheet.show(
            this,
            getString(if (isTransfer()) R.string.transfer_from else R.string.account),
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
            if (isTransfer()) {
                clearSplitSelection()
            }
            updatePickerLabels()
            if (advanceAfterSelect) {
                buttonPickType.post { openCategoryPicker(advanceAfterSelect = true) }
            }
        }
    }

    private fun openCategoryPicker(advanceAfterSelect: Boolean) {
        val options = categoryOptionsForType()
        if (options.isEmpty()) {
            Toast.makeText(
                this,
                if (isTransfer()) "No accounts available" else "No categories available",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        OptionPickerBottomSheet.show(
            this,
            getString(if (isTransfer()) R.string.transfer_to else R.string.category),
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

    private fun clearSplitSelection() {
        selectedSplitwiseGroupId = 0L
        selectedSplitwiseExpenseId = 0L
        selectSplitModeChip(SplitMode.EQUAL)
        allocationInputs.clear()
        bindSplitwiseGroupMembers(preferSelectedIds = emptySet(), selectAllIfEmpty = false)
        containerMoreDetails.visibility = View.GONE
        buttonToggleMore.setText(R.string.add_split)
        updatePushVisibility()
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
        val transfer = isTransfer()
        buttonPickAccount.text = selectedAccount.ifBlank {
            getString(if (transfer) R.string.transfer_from else R.string.account)
        }
        buttonPickType.text = selectedType.ifBlank { getString(R.string.transaction_type) }
        val typeColor = when (MoneyManagerExporter.normalizeType(selectedType)) {
            "Income" -> getColor(R.color.transaction_income)
            "Transfer-Out" -> getColor(R.color.transaction_transfer)
            else -> buttonPickAccount.currentTextColor
        }
        buttonPickType.setTextColor(typeColor)
        buttonPickCategory.text = selectedCategory.ifBlank {
            getString(if (transfer) R.string.transfer_to else R.string.category)
        }
        buttonPickSubcategory.text =
            selectedSubcategory.ifBlank { getString(R.string.subcategory) }
        val showSub = selectedType == "Expense" &&
            config.expenseCategories[selectedCategory].orEmpty().isNotEmpty()
        buttonPickSubcategory.visibility = if (transfer) View.GONE else View.VISIBLE
        buttonPickSubcategory.isEnabled = showSub
        buttonPickSubcategory.alpha = if (showSub) 1f else 0.5f
        if (transfer) {
            (buttonPickCategory.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
        }
        merchantInputLayout.hint = getString(
            if (transfer) R.string.transfer_description_optional else R.string.merchant_name
        )
        buttonToggleMore.visibility = if (transfer) View.GONE else View.VISIBLE
        if (transfer) {
            containerMoreDetails.visibility = View.GONE
        }
    }

    private fun bindSplitwiseGroupMembers(
        preferSelectedIds: Set<Long>,
        selectAllIfEmpty: Boolean
    ) {
        val auth = splitwiseStore.load()
        val group = auth.groupById(selectedSplitwiseGroupId)
        suppressingParticipantCallback = true
        try {
            chipGroupParticipants.removeAllViews()
            if (group == null || group.members.isEmpty()) {
                textSplitMembersHint.visibility = View.VISIBLE
                textSplitMembersHint.setText(
                    if (!auth.isSignedIn || auth.groups.isEmpty()) {
                        R.string.split_sync_groups_hint
                    } else {
                        R.string.split_pick_group_first
                    }
                )
                textSplitWithLabel.visibility = View.GONE
                chipGroupParticipants.visibility = View.GONE
                textSplitTypeLabel.visibility = View.GONE
                buttonCustomizeSplit.visibility = View.GONE
                chipGroupSplitMode.visibility = View.GONE
                splitModeHintText.visibility = View.GONE
                splitPreviewText.visibility = View.GONE
                containerSplitAllocations.visibility = View.GONE
                return
            }

            textSplitMembersHint.visibility = View.GONE
            textSplitWithLabel.visibility = View.VISIBLE
            chipGroupParticipants.visibility = View.VISIBLE
            textSplitTypeLabel.visibility = View.VISIBLE
            chipGroupSplitMode.visibility = View.VISIBLE
            buttonCustomizeSplit.visibility = View.VISIBLE
            splitModeHintText.visibility = View.VISIBLE
            splitPreviewText.visibility = View.VISIBLE
            applyCustomizeSplitVisibility()

            val labels = SplitwiseMemberLabels.labelsFor(group.members, auth.currentUserId)
            val selectAll = selectAllIfEmpty && preferSelectedIds.isEmpty()
            labels.forEach { (label, userId) ->
                val chip = Chip(this).apply {
                    text = label
                    tag = userId
                    isCheckable = true
                    isChecked = selectAll || preferSelectedIds.contains(userId)
                }
                chipGroupParticipants.addView(chip)
            }
            restoreAllocationsForLabels(labels)
        } finally {
            suppressingParticipantCallback = false
        }
        rebuildAllocationRows()
        refreshSplitUi()
    }

    private fun restoreAllocationsForLabels(labels: List<Pair<String, Long>>) {
        val source = if (loadedAllocations.isNotEmpty()) {
            loadedAllocations
        } else {
            allocationInputs.toMap()
        }
        if (source.isEmpty()) return
        val remapped = SplitCalculator.remapAllocationsToLabels(
            allocations = source,
            savedNameToId = loadedParticipantIds,
            savedParticipantOrder = loadedParticipantOrder,
            currentLabels = labels
        )
        if (remapped.isEmpty()) return
        allocationInputs.clear()
        allocationInputs.putAll(remapped)
    }

    private fun participantUserId(name: String): Long? {
        for (i in 0 until chipGroupParticipants.childCount) {
            val chip = chipGroupParticipants.getChildAt(i) as? Chip ?: continue
            if (chip.text?.toString().equals(name, ignoreCase = true) == true) {
                return chip.tag as? Long
            }
        }
        return loadedParticipantIds.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
    }

    private fun allocationValueFor(name: String, userId: Long?): BigDecimal? {
        allocationInputs.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.let { return it }

        if (userId != null && userId > 0L) {
            val savedName = loadedParticipantIds.entries
                .firstOrNull { it.value == userId }
                ?.key
            if (savedName != null) {
                loadedAllocations.entries
                    .firstOrNull { it.key.equals(savedName, ignoreCase = true) }
                    ?.value
                    ?.let { return it }
            }
            // Direct id-keyed allocations if we ever persist them that way
            loadedAllocations[userId.toString()]?.let { return it }
        }

        loadedAllocations.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.let { return it }

        if (loadedParticipantOrder.isNotEmpty()) {
            val index = selectedParticipants().indexOfFirst { it.equals(name, ignoreCase = true) }
            if (index in loadedParticipantOrder.indices) {
                val savedName = loadedParticipantOrder[index]
                loadedAllocations.entries
                    .firstOrNull { it.key.equals(savedName, ignoreCase = true) }
                    ?.value
                    ?.let { return it }
            }
        }
        return null
    }

    private fun selectedParticipants(): List<String> {
        val selected = mutableListOf<String>()
        for (i in 0 until chipGroupParticipants.childCount) {
            val chip = chipGroupParticipants.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) selected.add(chip.text.toString())
        }
        return selected
    }

    private fun selectedParticipantIdMap(): Map<String, Long> {
        val map = linkedMapOf<String, Long>()
        for (i in 0 until chipGroupParticipants.childCount) {
            val chip = chipGroupParticipants.getChildAt(i) as? Chip ?: continue
            if (!chip.isChecked) continue
            val userId = (chip.tag as? Long) ?: continue
            if (userId > 0L) map[chip.text.toString()] = userId
        }
        return map
    }

    private fun selectSplitModeChip(mode: String) {
        val normalized = SplitMode.normalize(mode)
        selectedSplitMode = normalized
        customizeSplitExpanded = normalized != SplitMode.EQUAL
        for (i in 0 until chipGroupSplitMode.childCount) {
            val chip = chipGroupSplitMode.getChildAt(i) as? Chip ?: continue
            chip.isChecked = SplitMode.normalize(chip.tag?.toString()) == normalized
        }
        applyCustomizeSplitVisibility()
    }

    private fun toggleCustomizeSplit() {
        if (customizeSplitExpanded) {
            customizeSplitExpanded = false
            selectSplitModeChip(SplitMode.EQUAL)
            rebuildAllocationRows()
            refreshSplitUi()
        } else {
            customizeSplitExpanded = true
            applyCustomizeSplitVisibility()
        }
    }

    private fun applyCustomizeSplitVisibility() {
        if (textSplitWithLabel.visibility != View.VISIBLE) {
            return
        }
        chipGroupSplitMode.visibility = View.VISIBLE
        buttonCustomizeSplit.visibility = View.VISIBLE
        val advancedVisibility = if (customizeSplitExpanded) View.VISIBLE else View.GONE
        findViewById<Chip>(R.id.chipSplitExact).visibility = advancedVisibility
        findViewById<Chip>(R.id.chipSplitPercent).visibility = advancedVisibility
        findViewById<Chip>(R.id.chipSplitWeight).visibility = advancedVisibility
        buttonCustomizeSplit.setText(
            if (customizeSplitExpanded) R.string.split_use_equal else R.string.split_customize
        )
        if (!customizeSplitExpanded) {
            selectedSplitMode = SplitMode.EQUAL
        }
    }

    private fun rebuildAllocationRows() {
        if (rebuildingAllocations) return
        rebuildingAllocations = true
        try {
            val people = selectedParticipants()
            // When members are not bound yet (e.g. mode chip selected during load),
            // keep parsed allocations so they can populate after chips appear.
            if (people.isEmpty()) {
                containerSplitAllocations.removeAllViews()
                return
            }

            // Resolve each person from in-memory inputs, remapped labels, or loaded DB values
            // before rebuilding rows. Do not name-filter first — that drops values when chip
            // labels differ from the keys stored on the transaction.
            val resolved = linkedMapOf<String, BigDecimal>()
            people.forEach { name ->
                val value = allocationValueFor(name, participantUserId(name))
                if (value != null) resolved[name] = value
            }
            allocationInputs.clear()
            allocationInputs.putAll(resolved)

            containerSplitAllocations.removeAllViews()
            if (selectedSplitMode == SplitMode.EQUAL) {
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
                    if (rebuildingAllocations) return@SimpleWatcher
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

        val amount = parseAmount(amountEditText.text.toString()) ?: 0.0
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
        if (intent.hasExtra(EXTRA_LATITUDE) && intent.hasExtra(EXTRA_LONGITUDE)) {
            showLocation(
                intent.getDoubleExtra(EXTRA_LATITUDE, 0.0),
                intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
            )
        }
        referenceFromIntent = intent.getStringExtra(EXTRA_TRANSACTION_REFERENCE)
        val date = intent.getLongExtra(EXTRA_TRANSACTION_DATE, 0L)
        if (date > 0L) selectedTimestamp = date
        val account = intent.getStringExtra(EXTRA_TRANSACTION_ACCOUNT).orEmpty()

        if (receivedAmount > 0.0) amountEditText.setText(formatAmount(receivedAmount))
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

        if (!receivedMerchant.isNullOrBlank()) {
            applyMerchantMemory(receivedMerchant, preferExistingAccount = account.isNotBlank())
        }
    }

    private fun loadTransactionData(transactionId: Long) {
        lifecycleScope.launch {
            transactionToEdit = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionById(transactionId)
            }
            transactionToEdit?.let { tx ->
                amountEditText.setText(formatAmount(tx.amount))
                merchantEditText.setText(tx.merchantName)
                selectedAccount = tx.account
                selectedType = MoneyManagerExporter.normalizeType(tx.transactionType)
                selectedCategory = tx.category
                selectedSubcategory = tx.subcategory
                notesEditText.setText(tx.notes.orEmpty())
                selectedTimestamp = tx.timestamp
                selectedSplitMode = SplitMode.normalize(tx.splitMode)
                currentReviewStatus = tx.reviewStatus
                // Exported rows can only be saved as exported; a "draft" would re-export them.
                saveDraftButton.isVisible = tx.reviewStatus != ReviewStatus.EXPORTED
                selectedSplitwiseGroupId = tx.splitwiseGroupId.takeIf { it > 0L }
                    ?: splitwiseStore.load().defaultGroupId
                selectedSplitwiseExpenseId = tx.splitwiseExpenseId
                loadedAllocations = SplitCalculator.parseAllocations(tx.splitAllocations)
                loadedParticipantIds = tx.participantIdMap()
                loadedParticipantOrder = tx.participantList()
                allocationInputs.clear()
                allocationInputs.putAll(loadedAllocations)
                updateDateButtonText()
                updatePickerLabels()
                val preferredIds = resolvePreferredMemberIds(tx)
                // Bind members before selecting mode so mode-chip rebuild cannot wipe
                // parsed allocations while the participant chip group is still empty.
                bindSplitwiseGroupMembers(
                    preferSelectedIds = preferredIds,
                    selectAllIfEmpty = preferredIds.isEmpty() && tx.participants.isBlank()
                )
                selectSplitModeChip(selectedSplitMode)
                rebuildAllocationRows()
                refreshSplitUi()
                updateSplitwiseGroupLabel()
                updatePushVisibility()
                if (!isTransfer() && (tx.participants.isNotBlank() || tx.splitwiseGroupId > 0L)) {
                    containerMoreDetails.visibility = View.VISIBLE
                    buttonToggleMore.setText(R.string.hide_split)
                }
                originalSmsFromIntent = tx.originalSms
                referenceFromIntent = tx.referenceNumber
                showLocation(tx.latitude, tx.longitude)
                if (!tx.originalSms.isNullOrEmpty()) {
                    originalSmsTextView.text = tx.originalSms
                    buttonToggleSms.visibility = View.VISIBLE
                    originalSmsCard.visibility = View.GONE
                } else {
                    buttonToggleSms.visibility = View.GONE
                }
                if (tx.category.isBlank() || tx.account.isBlank()) {
                    applyMerchantMemory(
                        tx.merchantName,
                        preferExistingAccount = tx.account.isNotBlank()
                    )
                }
            }
        }
    }

    private fun applyMerchantMemory(merchant: String, preferExistingAccount: Boolean) {
        lifecycleScope.launch {
            val hint = withContext(Dispatchers.IO) {
                MerchantMemory.lookup(db, merchant)
            } ?: return@launch
            val filled = MerchantMemory.applyToBlankFields(
                category = selectedCategory,
                subcategory = selectedSubcategory,
                account = if (preferExistingAccount && selectedAccount.isNotBlank()) {
                    selectedAccount
                } else {
                    selectedAccount
                },
                transactionType = selectedType,
                hint = hint
            )
            var changed = false
            if (selectedCategory.isBlank() && filled.category.isNotBlank()) {
                selectedCategory = filled.category
                changed = true
            }
            if (selectedSubcategory.isBlank() && filled.subcategory.isNotBlank()) {
                selectedSubcategory = filled.subcategory
                changed = true
            }
            if (selectedAccount.isBlank() && filled.account.isNotBlank()) {
                selectedAccount = filled.account
                changed = true
            }
            if (changed) updatePickerLabels()
        }
    }

    private fun resolvePreferredMemberIds(tx: Transaction): Set<Long> {
        val fromIds = tx.participantIdMap().values.filter { it > 0L }.toSet()
        if (fromIds.isNotEmpty()) return fromIds
        val auth = splitwiseStore.load()
        val group = auth.groupById(tx.splitwiseGroupId.takeIf { it > 0L } ?: selectedSplitwiseGroupId)
            ?: return emptySet()
        val labels = SplitwiseMemberLabels.labelsFor(group.members, auth.currentUserId)
        val names = tx.participantList()
        if (names.isEmpty()) return emptySet()
        return labels
            .filter { (label, _) -> names.any { it.equals(label, ignoreCase = true) } }
            .map { it.second }
            .toSet()
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
        val sdf = SimpleDateFormat("MMM dd, yyyy · h:mm a", Locale.getDefault())
        dateButton.text = sdf.format(Date(selectedTimestamp))
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                calendar.set(Calendar.YEAR, y)
                calendar.set(Calendar.MONTH, m)
                calendar.set(Calendar.DAY_OF_MONTH, d)
                selectedTimestamp = calendar.timeInMillis
                updateDateButtonText()
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        calendar.timeInMillis = selectedTimestamp
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        selectedTimestamp = calendar.timeInMillis
                        updateDateButtonText()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    false
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showLocation(lat: Double?, lng: Double?) {
        latitude = lat
        longitude = lng
        buttonOpenLocation.visibility = if (lat != null && lng != null) View.VISIBLE else View.GONE
    }

    private fun openLocationInMaps() {
        val lat = latitude ?: return
        val lng = longitude ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAiChatButton() {
        findViewById<MaterialButton>(R.id.buttonAiOpenChat).setOnClickListener {
            val intent = Intent(this, AgentChatActivity::class.java)
            if (currentTransactionId != INVALID_TRANSACTION_ID) {
                intent.putExtra(AgentChatActivity.EXTRA_TRANSACTION_ID, currentTransactionId)
            }
            startActivity(intent)
        }
    }

    private var saving = false

    /** Accepts "1,250.00" as pasted from an SMS. */
    private fun parseAmount(raw: String): Double? =
        raw.replace(",", "").trim().toBigDecimalOrNull()?.toDouble()

    /** Plain digits: Double.toString() shows ₹1 crore as "1.0E7". */
    private fun formatAmount(amount: Double): String =
        BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

    private fun saveOrUpdateTransaction(markReviewed: Boolean) {
        val amountStr = amountEditText.text.toString()
        var merchant = merchantEditText.text.toString().trim()
        val notes = notesEditText.text.toString().trim()
        val transfer = isTransfer()
        val participants = if (transfer) emptyList() else selectedParticipants()

        if (amountStr.isEmpty()) {
            amountEditText.error = "Required"; return
        }
        val amount = parseAmount(amountStr)
        if (amount == null || amount <= 0) {
            amountEditText.error = "Invalid amount"; return
        }
        if (selectedAccount.isBlank()) {
            Toast.makeText(
                this,
                if (transfer) "Choose From account" else "Choose an account",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (transfer) {
            if (selectedCategory.isBlank()) {
                Toast.makeText(this, "Choose To account", Toast.LENGTH_SHORT).show()
                return
            }
            if (selectedAccount.equals(selectedCategory, ignoreCase = true)) {
                Toast.makeText(this, "From and To must be different", Toast.LENGTH_SHORT).show()
                return
            }
            if (merchant.isEmpty()) {
                merchant = "Transfer to $selectedCategory"
            }
        } else if (merchant.isEmpty()) {
            merchantEditText.error = "Required"; return
        }
        if (markReviewed && selectedType == "Expense" && selectedCategory.isBlank()) {
            Toast.makeText(this, "Choose a category", Toast.LENGTH_SHORT).show()
            return
        }

        if (participants.isNotEmpty() && selectedSplitwiseGroupId <= 0L) {
            Toast.makeText(this, R.string.split_pick_group_first, Toast.LENGTH_LONG).show()
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

        val status = ReviewStatus.afterSave(transactionToEdit?.reviewStatus, markReviewed)
        val idMap = if (transfer || participants.isEmpty()) {
            emptyMap()
        } else {
            selectedParticipantIdMap()
        }
        val transaction = Transaction(
            id = if (currentTransactionId == INVALID_TRANSACTION_ID) 0L else currentTransactionId,
            amount = amount,
            merchantName = merchant,
            category = selectedCategory,
            subcategory = if (transfer) "" else selectedSubcategory,
            account = selectedAccount,
            notes = notes.ifEmpty { null },
            timestamp = selectedTimestamp,
            originalSms = transactionToEdit?.originalSms ?: originalSmsFromIntent,
            isExpense = selectedType != "Income",
            referenceNumber = transactionToEdit?.referenceNumber ?: referenceFromIntent,
            participants = participants.joinToString(","),
            splitMode = if (transfer) SplitMode.EQUAL else mode,
            splitAllocations = if (transfer) {
                ""
            } else {
                SplitCalculator.encodeAllocations(allocations, idMap)
            },
            reviewStatus = status,
            transactionType = MoneyManagerExporter.normalizeType(selectedType),
            splitwiseGroupId = if (transfer || participants.isEmpty()) 0L else selectedSplitwiseGroupId,
            splitwiseExpenseId = selectedSplitwiseExpenseId,
            splitwiseParticipantIds = Transaction.encodeParticipantIds(idMap),
            splitwisePushError = transactionToEdit?.splitwisePushError.orEmpty(),
            latitude = latitude,
            longitude = longitude,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )

        // A double tap would otherwise insert the same new transaction twice.
        if (saving) return
        saving = true
        saveButton.isEnabled = false
        saveDraftButton.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (currentTransactionId == INVALID_TRANSACTION_ID) {
                        db.transactionDao().insertTransaction(transaction)
                    } else {
                        db.transactionDao().updateTransaction(transaction)
                    }
                    if (transaction.category.isNotBlank()) {
                        MerchantMemory.remember(
                            db = db,
                            merchant = transaction.merchantName,
                            category = transaction.category,
                            subcategory = transaction.subcategory,
                            account = transaction.account,
                            transactionType = transaction.transactionType
                        )
                    }
                }
                Toast.makeText(
                    this@TransactionEntryActivity,
                    when {
                        status == ReviewStatus.EXPORTED -> "Saved (exported)"
                        markReviewed -> "Marked reviewed"
                        else -> "Saved draft"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: Exception) {
                Log.e("TransactionEntry", "Save failed", e)
                saving = false
                saveButton.isEnabled = true
                saveDraftButton.isEnabled = true
                Toast.makeText(this@TransactionEntryActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateSplitwiseGroupLabel() {
        val auth = splitwiseStore.load()
        val name = auth.groupById(selectedSplitwiseGroupId)?.name
        buttonPickSplitwiseGroup.text = when {
            name != null -> "${getString(R.string.splitwise_group_picker)}: $name"
            else -> getString(R.string.splitwise_group_picker)
        }
        updatePushVisibility()
    }

    private fun updatePushVisibility() {
        val isSplit = selectedParticipants().size >= 2 ||
            selectedParticipants().any { !it.equals("ME", true) }
        val canShow = currentTransactionId != INVALID_TRANSACTION_ID &&
            ReviewStatus.canPushToSplitwise(currentReviewStatus) &&
            isSplit &&
            !isTransfer()
        buttonPushSplitwise.visibility = if (canShow) View.VISIBLE else View.GONE
        if (SplitwiseExpenseIds.isDone(selectedSplitwiseExpenseId)) {
            textSplitwisePushStatus.visibility = View.VISIBLE
            textSplitwisePushStatus.text = if (SplitwiseExpenseIds.isManualSuccess(selectedSplitwiseExpenseId)) {
                getString(R.string.splitwise_marked_done_manual)
            } else {
                getString(R.string.splitwise_already_pushed, selectedSplitwiseExpenseId)
            }
            buttonPushSplitwise.isEnabled = false
        } else {
            textSplitwisePushStatus.visibility = View.GONE
            buttonPushSplitwise.isEnabled = canShow
        }
    }

    private fun openSplitwiseGroupPicker() {
        val auth = splitwiseStore.load()
        if (!auth.isSignedIn || auth.groups.isEmpty()) {
            Toast.makeText(
                this,
                R.string.split_sync_groups_hint,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = listOf(getString(R.string.splitwise_group_none)) +
            auth.groups.map { "${it.name} (${it.members.size})" }
        val ids = listOf(0L) + auth.groups.map { it.id }
        val checked = ids.indexOf(selectedSplitwiseGroupId).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.splitwise_group_picker)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                selectedSplitwiseGroupId = ids[which]
                bindSplitwiseGroupMembers(
                    preferSelectedIds = emptySet(),
                    selectAllIfEmpty = selectedSplitwiseGroupId > 0L
                )
                updateSplitwiseGroupLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasUnsavedSplitChanges(saved: Transaction): Boolean {
        val uiAmount = parseAmount(amountEditText.text.toString())
        return selectedSplitwiseGroupId != saved.splitwiseGroupId ||
            selectedParticipants().joinToString(",") != saved.participants ||
            SplitMode.normalize(selectedSplitMode) != SplitMode.normalize(saved.splitMode) ||
            uiAmount == null || kotlin.math.abs(uiAmount - saved.amount) >= 0.005
    }

    private fun pushCurrentToSplitwise() {
        if (currentTransactionId == INVALID_TRANSACTION_ID) {
            Toast.makeText(this, "Save the transaction first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ReviewStatus.canPushToSplitwise(currentReviewStatus)) {
            Toast.makeText(this, R.string.splitwise_push_needs_reviewed, Toast.LENGTH_LONG).show()
            return
        }
        val auth = splitwiseStore.load()
        if (!auth.isSignedIn) {
            Toast.makeText(this, R.string.splitwise_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        buttonPushSplitwise.isEnabled = false
        lifecycleScope.launch {
            val dao = db.transactionDao()
            val saved = withContext(Dispatchers.IO) { dao.getTransactionById(currentTransactionId) }
            // Push exactly what is saved: mixing the saved amount with unsaved people/group
            // sent Splitwise a split nobody chose.
            val unsaved = saved != null && hasUnsavedSplitChanges(saved)
            val result = withContext(Dispatchers.IO) {
                val latest = saved ?: return@withContext null to "Transaction missing"
                if (!ReviewStatus.canPushToSplitwise(latest.reviewStatus)) {
                    return@withContext null to getString(R.string.splitwise_push_needs_reviewed)
                }
                if (unsaved) {
                    return@withContext null to getString(R.string.splitwise_save_before_push)
                }
                val push = SplitwisePusher.pushAndRecord(
                    dao, SplitwiseApi.from(splitwiseStore), auth, latest
                )
                push to null
            }
            val (push, missing) = result
            if (missing != null || push == null) {
                buttonPushSplitwise.isEnabled = true
                Toast.makeText(this@TransactionEntryActivity, missing ?: "Push failed", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (push.isSuccess) {
                selectedSplitwiseExpenseId = push.expenseId ?: 0L
                updatePushVisibility()
                Toast.makeText(
                    this@TransactionEntryActivity,
                    "Pushed to Splitwise (#${push.expenseId})",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                buttonPushSplitwise.isEnabled = true
                if (push.unauthorized) {
                    SplitwiseSessionEvents.promptReauth(this@TransactionEntryActivity)
                } else {
                    Toast.makeText(
                        this@TransactionEntryActivity,
                        push.error ?: "Push failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
