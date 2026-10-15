package com.example.paymentsmstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.paymentsmstracker.agent.AgentPrivacy
import com.example.paymentsmstracker.agent.AgentProvider
import com.example.paymentsmstracker.agent.AgentSettings
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.ReviewStatus
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.export.MoneyManagerSync
import com.example.paymentsmstracker.export.SplitwiseExporter
import com.example.paymentsmstracker.merchant.MerchantMemory
import com.example.paymentsmstracker.reminder.NightlyReviewScheduler
import com.example.paymentsmstracker.server.LanAccessService
import com.example.paymentsmstracker.server.LanServerState
import com.example.paymentsmstracker.server.NetworkUtils
import com.example.paymentsmstracker.sms.SmsIngest
import com.example.paymentsmstracker.sms.SmsParser
import com.example.paymentsmstracker.splitwise.SplitwiseApi
import com.example.paymentsmstracker.splitwise.SplitwiseAuthMethod
import com.example.paymentsmstracker.splitwise.SplitwiseAuthStore
import com.example.paymentsmstracker.splitwise.SplitwiseOAuthActivity
import com.example.paymentsmstracker.splitwise.SplitwiseSessionEvents
import com.example.paymentsmstracker.splitwise.SplitwiseWebAuthActivity
import com.example.paymentsmstracker.theme.ThemeApplier
import com.example.paymentsmstracker.theme.ThemeMode
import com.example.paymentsmstracker.viewmodel.ListFilter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class AdminActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var splitwiseStore: SplitwiseAuthStore
    private lateinit var editAccounts: TextInputEditText
    private lateinit var switchMmFullSplitAsOwn: MaterialSwitch
    private lateinit var layoutDebtCategory: TextInputLayout
    private lateinit var layoutDebtSubcategory: TextInputLayout
    private lateinit var editDebtCategory: TextInputEditText
    private lateinit var editDebtSubcategory: TextInputEditText
    private lateinit var editDedupeMinutes: TextInputEditText
    private lateinit var switchAutoSave: MaterialSwitch
    private lateinit var switchNightlyReminder: MaterialSwitch
    private lateinit var layoutNightlyTime: TextInputLayout
    private lateinit var editNightlyTime: TextInputEditText
    private lateinit var editConfigJson: TextInputEditText
    private lateinit var editTestSms: TextInputEditText
    private lateinit var buttonBackfillFromDate: MaterialButton
    private lateinit var buttonBackfillToDate: MaterialButton
    private lateinit var textBackfillResult: android.widget.TextView
    private lateinit var textParseResult: android.widget.TextView
    private lateinit var panelBasics: View
    private lateinit var panelSplitwise: View
    private lateinit var panelTheme: View
    private lateinit var panelLaptop: View
    private lateinit var panelRules: View
    private lateinit var panelTest: View
    private lateinit var panelMaintenance: View
    private lateinit var panelPermissions: View
    private lateinit var panelAi: View
    private lateinit var permissionsPanel: PermissionsPanel
    private lateinit var chipGroupThemeMode: ChipGroup
    private lateinit var textThemeModeHint: TextView
    private lateinit var chipGroupLanDuration: ChipGroup
    private lateinit var switchLanRequireAccessCode: MaterialSwitch
    private lateinit var textLanStatus: TextView
    private lateinit var textLanUrl: TextView
    private lateinit var textLanCode: TextView
    private lateinit var textLanCountdown: TextView
    private lateinit var textLanWarning: TextView
    private lateinit var buttonLanStart: MaterialButton
    private lateinit var buttonLanStop: MaterialButton
    private lateinit var editSplitwiseCallback: TextInputEditText
    private lateinit var editSplitwiseApiKey: TextInputEditText
    private lateinit var editSplitwiseConsumerKey: TextInputEditText
    private lateinit var editSplitwiseConsumerSecret: TextInputEditText
    private lateinit var textSplitwiseStatus: TextView
    private lateinit var textSplitwiseAuthMethod: TextView
    private lateinit var textSplitwiseGroups: TextView
    private lateinit var buttonSplitwiseDefaultGroup: MaterialButton
    private lateinit var tabLayoutAdmin: TabLayout

    private var nightlyHour: Int = 21
    private var nightlyMinute: Int = 0
    private var backfillFromDate: Long = 0L
    private var backfillToDate: Long = 0L
    private val lanUiHandler = Handler(Looper.getMainLooper())
    private val lanUiTicker = object : Runnable {
        override fun run() {
            refreshLanStatusUi()
            if (LanServerState.get().running) {
                lanUiHandler.postDelayed(this, 5_000L)
            }
        }
    }

    private val requestReadSmsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                confirmBackfill()
            } else {
                Toast.makeText(
                    this,
                    R.string.sms_read_permission_required,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        configStore = ConfigStore(this)
        splitwiseStore = SplitwiseAuthStore(this)
        SplitwiseSessionEvents.observe(this, this)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbarAdmin)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        editAccounts = findViewById(R.id.editAccounts)
        switchMmFullSplitAsOwn = findViewById(R.id.switchMmFullSplitAsOwn)
        layoutDebtCategory = findViewById(R.id.layoutDebtCategory)
        layoutDebtSubcategory = findViewById(R.id.layoutDebtSubcategory)
        editDebtCategory = findViewById(R.id.editDebtCategory)
        editDebtSubcategory = findViewById(R.id.editDebtSubcategory)
        editDedupeMinutes = findViewById(R.id.editDedupeMinutes)
        switchAutoSave = findViewById(R.id.switchAutoSave)
        switchNightlyReminder = findViewById(R.id.switchNightlyReminder)
        layoutNightlyTime = findViewById(R.id.layoutNightlyTime)
        editNightlyTime = findViewById(R.id.editNightlyTime)
        editConfigJson = findViewById(R.id.editConfigJson)
        editTestSms = findViewById(R.id.editTestSms)
        buttonBackfillFromDate = findViewById(R.id.buttonBackfillFromDate)
        buttonBackfillToDate = findViewById(R.id.buttonBackfillToDate)
        textBackfillResult = findViewById(R.id.textBackfillResult)
        textParseResult = findViewById(R.id.textParseResult)
        panelBasics = findViewById(R.id.panelBasics)
        panelSplitwise = findViewById(R.id.panelSplitwise)
        panelTheme = findViewById(R.id.panelTheme)
        panelLaptop = findViewById(R.id.panelLaptop)
        panelRules = findViewById(R.id.panelRules)
        panelTest = findViewById(R.id.panelTest)
        panelMaintenance = findViewById(R.id.panelMaintenance)
        panelPermissions = findViewById(R.id.panelPermissions)
        panelAi = findViewById(R.id.panelAi)
        permissionsPanel = PermissionsPanel(this, panelPermissions)
        chipGroupThemeMode = findViewById(R.id.chipGroupThemeMode)
        textThemeModeHint = findViewById(R.id.textThemeModeHint)
        chipGroupLanDuration = findViewById(R.id.chipGroupLanDuration)
        switchLanRequireAccessCode = findViewById(R.id.switchLanRequireAccessCode)
        textLanStatus = findViewById(R.id.textLanStatus)
        textLanUrl = findViewById(R.id.textLanUrl)
        textLanCode = findViewById(R.id.textLanCode)
        textLanCountdown = findViewById(R.id.textLanCountdown)
        textLanWarning = findViewById(R.id.textLanWarning)
        buttonLanStart = findViewById(R.id.buttonLanStart)
        buttonLanStop = findViewById(R.id.buttonLanStop)
        editSplitwiseCallback = findViewById(R.id.editSplitwiseCallback)
        editSplitwiseApiKey = findViewById(R.id.editSplitwiseApiKey)
        editSplitwiseConsumerKey = findViewById(R.id.editSplitwiseConsumerKey)
        editSplitwiseConsumerSecret = findViewById(R.id.editSplitwiseConsumerSecret)
        textSplitwiseStatus = findViewById(R.id.textSplitwiseStatus)
        textSplitwiseAuthMethod = findViewById(R.id.textSplitwiseAuthMethod)
        textSplitwiseGroups = findViewById(R.id.textSplitwiseGroups)
        buttonSplitwiseDefaultGroup = findViewById(R.id.buttonSplitwiseDefaultGroup)
        tabLayoutAdmin = findViewById(R.id.tabLayoutAdmin)

        setupTabs()
        initializeBackfillDates()
        bindFromStore()
        bindSplitwiseUi()
        refreshLanStatusUi()

        switchMmFullSplitAsOwn.setOnCheckedChangeListener { _, _ ->
            updateDebtCategoryEnabled()
        }
        switchNightlyReminder.setOnCheckedChangeListener { _, _ ->
            updateNightlyTimeEnabled()
        }
        editNightlyTime.setOnClickListener { showNightlyTimePicker() }
        layoutNightlyTime.setEndIconOnClickListener { showNightlyTimePicker() }
        chipGroupThemeMode.setOnCheckedStateChangeListener { _, _ ->
            refreshThemeModeHint()
        }

        findViewById<MaterialButton>(R.id.buttonSaveBasics).setOnClickListener { saveBasics() }
        findViewById<MaterialButton>(R.id.buttonSaveTheme).setOnClickListener { saveTheme() }
        buttonLanStart.setOnClickListener { startLanServer() }
        buttonLanStop.setOnClickListener { stopLanServer() }
        switchLanRequireAccessCode.setOnCheckedChangeListener { _, _ ->
            if (!LanServerState.get().running) {
                updateLanWarningText()
            }
        }
        findViewById<MaterialButton>(R.id.buttonSaveRules).setOnClickListener { saveRules() }
        findViewById<MaterialButton>(R.id.buttonResetConfig).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset config?")
                .setMessage("Restore default accounts and SMS regex rules.")
                .setPositiveButton("Reset") { _, _ ->
                    configStore.resetToDefaults()
                    bindFromStore()
                    NightlyReviewScheduler.applyFromConfig(this)
                    ThemeApplier.applyFromConfig(this)
                    recreate()
                    Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<MaterialButton>(R.id.buttonTestParse).setOnClickListener { runParseTest() }
        findViewById<MaterialButton>(R.id.buttonInjectSms).setOnClickListener { injectParsedSms() }
        findViewById<MaterialButton>(R.id.buttonCreateSmsRule).setOnClickListener {
            val sms = editTestSms.text?.toString().orEmpty()
            startActivity(
                Intent(this, SmsRuleWizardActivity::class.java).apply {
                    if (sms.isNotBlank()) {
                        putExtra(SmsRuleWizardActivity.EXTRA_SMS, sms)
                    }
                }
            )
        }
        findViewById<MaterialButton>(R.id.buttonExportAllRaw).setOnClickListener { exportRawCsv() }
        findViewById<MaterialButton>(R.id.buttonExportMoneyManager).setOnClickListener {
            exportMoneyManager(markExported = true)
        }
        findViewById<MaterialButton>(R.id.buttonExportSplitwise).setOnClickListener {
            exportSplitwise(onlySplits = false)
        }
        findViewById<MaterialButton>(R.id.buttonExportSplitsOnly).setOnClickListener {
            exportSplitLedger()
        }
        buttonBackfillFromDate.setOnClickListener { showBackfillDatePicker(selectFrom = true) }
        buttonBackfillToDate.setOnClickListener { showBackfillDatePicker(selectFrom = false) }
        findViewById<MaterialButton>(R.id.buttonBackfillSms).setOnClickListener {
            requestPermissionAndStartBackfill()
        }
        findViewById<MaterialButton>(R.id.buttonClearNeedsReview).setOnClickListener {
            confirmClearStatus(ReviewStatus.NEEDS_REVIEW, getString(R.string.clear_needs_review))
        }
        findViewById<MaterialButton>(R.id.buttonClearReviewed).setOnClickListener {
            confirmClearStatus(ReviewStatus.REVIEWED, getString(R.string.clear_reviewed))
        }
        findViewById<MaterialButton>(R.id.buttonClearExported).setOnClickListener {
            confirmClearStatus(ReviewStatus.EXPORTED, getString(R.string.clear_mm_exported))
        }
        findViewById<MaterialButton>(R.id.buttonClearExportedOlder).setOnClickListener {
            confirmClearExportedOlderThan30Days()
        }
        findViewById<MaterialButton>(R.id.buttonClearAll).setOnClickListener {
            confirmClearAll()
        }

        setupAiSettings()
        findViewById<MaterialButton>(R.id.buttonSplitwiseSaveCredentials).setOnClickListener {
            saveSplitwiseCredentials()
        }
        findViewById<MaterialButton>(R.id.buttonSplitwiseConnectApiKey).setOnClickListener {
            connectSplitwiseWithApiKey()
        }
        findViewById<MaterialButton>(R.id.buttonSplitwiseDirectSignIn).setOnClickListener {
            SplitwiseWebAuthActivity.start(this, oauthMode = false)
        }
        findViewById<MaterialButton>(R.id.buttonSplitwiseOAuthSignIn).setOnClickListener {
            val key = editSplitwiseConsumerKey.text?.toString().orEmpty().trim()
            val secret = editSplitwiseConsumerSecret.text?.toString().orEmpty().trim()
            if (key.isBlank() || secret.isBlank()) {
                Toast.makeText(this, "Consumer key and secret are required for OAuth", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            splitwiseStore.saveCredentials(key, secret)
            SplitwiseWebAuthActivity.start(this, oauthMode = true)
        }
        findViewById<MaterialButton>(R.id.buttonSplitwiseAdvancedToggle).setOnClickListener {
            toggleSplitwiseAdvancedOAuth()
        }
        findViewById<MaterialButton>(R.id.buttonSplitwiseSignOut).setOnClickListener {
            splitwiseStore.signOut()
            editSplitwiseApiKey.setText("")
            bindSplitwiseUi()
            Toast.makeText(this, "Signed out of Splitwise", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.buttonSplitwiseSyncGroups).setOnClickListener {
            syncSplitwiseGroups()
        }
        buttonSplitwiseDefaultGroup.setOnClickListener { pickDefaultSplitwiseGroup() }

        if (intent.getBooleanExtra(SplitwiseOAuthActivity.EXTRA_OPEN_SPLITWISE_TAB, false)) {
            tabLayoutAdmin.getTabAt(1)?.select()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(SplitwiseOAuthActivity.EXTRA_OPEN_SPLITWISE_TAB, false)) {
            tabLayoutAdmin.getTabAt(1)?.select()
            bindSplitwiseUi()
        }
    }

    private fun setupTabs() {
        val tabs = tabLayoutAdmin
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_basics))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_splitwise))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_theme))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_laptop))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_rules))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_test))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_maintenance))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_permissions))
        tabs.addTab(tabs.newTab().setText(R.string.admin_tab_ai))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showPanel(0)
    }

    private fun showPanel(index: Int) {
        panelBasics.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelSplitwise.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelTheme.visibility = if (index == 2) View.VISIBLE else View.GONE
        panelLaptop.visibility = if (index == 3) View.VISIBLE else View.GONE
        panelRules.visibility = if (index == 4) View.VISIBLE else View.GONE
        panelTest.visibility = if (index == 5) View.VISIBLE else View.GONE
        panelMaintenance.visibility = if (index == 6) View.VISIBLE else View.GONE
        panelPermissions.visibility = if (index == 7) View.VISIBLE else View.GONE
        panelAi.visibility = if (index == 8) View.VISIBLE else View.GONE
        if (index == 7) permissionsPanel.refresh()
        if (index == 3) {
            refreshLanStatusUi()
            lanUiHandler.removeCallbacks(lanUiTicker)
            if (LanServerState.get().running) {
                lanUiHandler.post(lanUiTicker)
            }
        } else {
            lanUiHandler.removeCallbacks(lanUiTicker)
        }
        if (index == 1) bindSplitwiseUi()
    }

    private var splitwiseAdvancedOAuthExpanded = false

    private fun setupAiSettings() {
        val settings = AgentSettings(this)
        val chips = findViewById<ChipGroup>(R.id.chipGroupAiProvider)
        val hint = findViewById<TextView>(R.id.textAiProviderHint)
        val url = findViewById<TextInputEditText>(R.id.editAiBaseUrl)
        val model = findViewById<TextInputEditText>(R.id.editAiModel)
        val key = findViewById<TextInputEditText>(R.id.editAiApiKey)
        val keyLayout = findViewById<TextInputLayout>(R.id.layoutAiApiKey)
        var shown = settings.provider

        fun load(p: AgentProvider) {
            shown = p
            url.setText(settings.baseUrl(p))
            model.setText(settings.model(p))
            key.setText(settings.apiKey(p))
            keyLayout.error = null
            hint.setText(
                when (p) {
                    AgentProvider.CLAUDE -> R.string.ai_provider_claude_hint
                    AgentProvider.OPENROUTER -> R.string.ai_provider_openrouter_hint
                    AgentProvider.OPENAI_COMPATIBLE -> R.string.ai_provider_openai_hint
                }
            )
        }

        fun saveShown(): Boolean {
            val baseUrl = url.text?.toString().orEmpty().trim()
            if (baseUrl.toHttpUrlOrNull() == null) {
                url.error = getString(R.string.ai_invalid_url); return false
            }
            val apiKey = key.text?.toString().orEmpty()
            if (shown.keyRequired && apiKey.isBlank()) {
                keyLayout.error = getString(R.string.ai_key_required); return false
            }
            settings.save(shown, apiKey, model.text?.toString().orEmpty(), baseUrl)
            settings.provider = shown
            return true
        }

        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as Chip
            chip.isChecked = chip.tag == shown.name
        }
        load(shown)
        chips.setOnCheckedStateChangeListener { group, ids ->
            val tag = group.findViewById<Chip>(ids.firstOrNull() ?: return@setOnCheckedStateChangeListener).tag
            load(AgentProvider.valueOf(tag.toString()))
        }
        findViewById<MaterialButton>(R.id.buttonAiSave).setOnClickListener {
            if (saveShown()) Toast.makeText(this, R.string.ai_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialSwitch>(R.id.switchAiEnabled).apply {
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, checked -> settings.enabled = checked }
        }
        val p = settings.privacy
        listOf<Triple<Int, Boolean, (AgentPrivacy, Boolean) -> AgentPrivacy>>(
            Triple(R.id.switchAiReadAmounts, p.amounts) { x, v -> x.copy(amounts = v) },
            Triple(R.id.switchAiReadMerchants, p.merchants) { x, v -> x.copy(merchants = v) },
            Triple(R.id.switchAiReadDates, p.dates) { x, v -> x.copy(dates = v) },
            Triple(R.id.switchAiReadCategories, p.categories) { x, v -> x.copy(categories = v) },
            Triple(R.id.switchAiReadNotes, p.notes) { x, v -> x.copy(notes = v) },
            Triple(R.id.switchAiReadFriends, p.friends) { x, v -> x.copy(friends = v) },
            Triple(R.id.switchAiReadMerchantMemory, p.merchantMemory) { x, v -> x.copy(merchantMemory = v) },
            Triple(R.id.switchAiReadHistory, p.history) { x, v -> x.copy(history = v) },
            Triple(R.id.switchAiIncludeSms, p.sms) { x, v -> x.copy(sms = v) },
            Triple(R.id.switchAiReadLocation, p.location) { x, v -> x.copy(location = v) }
        ).forEach { (id, on, toggle) ->
            findViewById<MaterialSwitch>(id).apply {
                isChecked = on
                setOnCheckedChangeListener { _, checked -> settings.privacy = toggle(settings.privacy, checked) }
            }
        }
    }

    private fun bindSplitwiseUi() {
        val auth = splitwiseStore.load()
        editSplitwiseCallback.setText(SplitwiseAuthStore.REDIRECT_URI)
        // Don't overwrite a key the user is currently typing; only fill when empty.
        if (editSplitwiseApiKey.text.isNullOrBlank() && auth.accessToken.isNotBlank()) {
            editSplitwiseApiKey.setText(auth.accessToken)
        }
        if (editSplitwiseConsumerKey.text.isNullOrBlank()) {
            editSplitwiseConsumerKey.setText(auth.consumerKey)
        }
        if (editSplitwiseConsumerSecret.text.isNullOrBlank()) {
            editSplitwiseConsumerSecret.setText(auth.consumerSecret)
        }
        textSplitwiseStatus.text = if (auth.isSignedIn) {
            getString(
                R.string.splitwise_status_signed_in,
                auth.currentUserName.ifBlank { "user #${auth.currentUserId}" }
            )
        } else {
            getString(R.string.splitwise_status_signed_out)
        }
        textSplitwiseAuthMethod.text = when (auth.resolvedAuthMethod) {
            SplitwiseAuthMethod.SESSION -> getString(R.string.splitwise_auth_method_session)
            SplitwiseAuthMethod.OAUTH -> getString(R.string.splitwise_auth_method_oauth)
            SplitwiseAuthMethod.TOKEN -> getString(R.string.splitwise_auth_method_token)
            SplitwiseAuthMethod.NONE -> getString(R.string.splitwise_auth_method_none)
        }
        if (auth.resolvedAuthMethod == SplitwiseAuthMethod.OAUTH) {
            splitwiseAdvancedOAuthExpanded = true
        }
        applySplitwiseAdvancedOAuthVisibility()
        textSplitwiseGroups.text = getString(R.string.splitwise_groups_label, auth.groups.size)
        val defaultName = auth.groupById(auth.defaultGroupId)?.name
        buttonSplitwiseDefaultGroup.text = if (defaultName != null) {
            "${getString(R.string.splitwise_default_group)}: $defaultName"
        } else {
            getString(R.string.splitwise_default_group)
        }
    }

    private fun toggleSplitwiseAdvancedOAuth() {
        splitwiseAdvancedOAuthExpanded = !splitwiseAdvancedOAuthExpanded
        applySplitwiseAdvancedOAuthVisibility()
    }

    private fun applySplitwiseAdvancedOAuthVisibility() {
        val card = findViewById<View>(R.id.cardSplitwiseOAuthAuth)
        val toggle = findViewById<MaterialButton>(R.id.buttonSplitwiseAdvancedToggle)
        card.visibility = if (splitwiseAdvancedOAuthExpanded) View.VISIBLE else View.GONE
        toggle.setText(
            if (splitwiseAdvancedOAuthExpanded) {
                R.string.splitwise_auth_advanced_hide
            } else {
                R.string.splitwise_auth_advanced
            }
        )
    }

    private fun connectSplitwiseWithApiKey() {
        val key = editSplitwiseApiKey.text?.toString().orEmpty().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Paste your personal API key first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    splitwiseStore.saveAccessToken(key)
                    val api = SplitwiseApi.from(splitwiseStore)
                    val user = api.getCurrentUser()
                    splitwiseStore.saveCurrentUser(user.id, user.displayName)
                    val groups = api.getGroups()
                    splitwiseStore.saveGroups(groups)
                }
                bindSplitwiseUi()
                Toast.makeText(this@AdminActivity, "Connected with API key", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@AdminActivity,
                    "API key failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveSplitwiseCredentials(silent: Boolean = false) {
        val key = editSplitwiseConsumerKey.text?.toString().orEmpty().trim()
        val secret = editSplitwiseConsumerSecret.text?.toString().orEmpty().trim()
        if (key.isBlank() || secret.isBlank()) {
            Toast.makeText(this, "Consumer key and secret are required", Toast.LENGTH_SHORT).show()
            return
        }
        splitwiseStore.saveCredentials(key, secret)
        if (!silent) {
            Toast.makeText(this, "Splitwise credentials saved", Toast.LENGTH_SHORT).show()
        }
        bindSplitwiseUi()
    }

    private fun syncSplitwiseGroups() {
        val auth = splitwiseStore.load()
        if (!auth.isSignedIn) {
            Toast.makeText(this, R.string.splitwise_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val api = SplitwiseApi.from(splitwiseStore)
                    val groups = api.getGroups()
                    splitwiseStore.saveGroups(groups)
                    val user = api.getCurrentUser()
                    splitwiseStore.saveCurrentUser(user.id, user.displayName)
                }
                bindSplitwiseUi()
                Toast.makeText(this@AdminActivity, "Groups refreshed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (SplitwiseSessionEvents.isUnauthorized(e)) {
                    bindSplitwiseUi()
                    SplitwiseSessionEvents.promptReauth(this@AdminActivity)
                } else {
                    Toast.makeText(
                        this@AdminActivity,
                        "Refresh failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun pickDefaultSplitwiseGroup() {
        val auth = splitwiseStore.load()
        if (auth.groups.isEmpty()) {
            Toast.makeText(this, "Refresh groups first", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = listOf(getString(R.string.splitwise_group_none)) +
            auth.groups.map { it.name }
        val ids = listOf(0L) + auth.groups.map { it.id }
        val checked = ids.indexOf(auth.defaultGroupId).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.splitwise_default_group)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                splitwiseStore.saveDefaultGroupId(ids[which])
                bindSplitwiseUi()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectedLanDurationMinutes(): Int {
        val checkedId = chipGroupLanDuration.checkedChipId
        if (checkedId != View.NO_ID) {
            val tag = findViewById<Chip>(checkedId).tag?.toString()?.toIntOrNull()
            if (tag != null) return tag
        }
        return 30
    }

    private fun startLanServer() {
        val host = NetworkUtils.localIpv4(this)
        if (host.isNullOrBlank()) {
            Toast.makeText(this, R.string.lan_need_wifi, Toast.LENGTH_LONG).show()
            return
        }
        LanAccessService.start(
            this,
            selectedLanDurationMinutes(),
            requireAccessCode = switchLanRequireAccessCode.isChecked
        )
        Toast.makeText(this, R.string.lan_started, Toast.LENGTH_SHORT).show()
        lanUiHandler.postDelayed({
            refreshLanStatusUi()
            lanUiHandler.removeCallbacks(lanUiTicker)
            lanUiHandler.post(lanUiTicker)
        }, 400L)
    }

    private fun stopLanServer() {
        LanAccessService.stop(this)
        Toast.makeText(this, R.string.lan_stopped, Toast.LENGTH_SHORT).show()
        lanUiHandler.postDelayed({ refreshLanStatusUi() }, 300L)
    }

    private fun updateLanWarningText(requireCode: Boolean = switchLanRequireAccessCode.isChecked) {
        textLanWarning.setText(
            if (requireCode) R.string.lan_warning else R.string.lan_warning_open
        )
    }

    private fun refreshLanStatusUi() {
        val state = LanServerState.get()
        if (!state.running) {
            textLanStatus.setText(R.string.lan_status_stopped)
            textLanUrl.text = ""
            textLanCode.text = ""
            textLanCountdown.text = ""
            buttonLanStart.isEnabled = true
            buttonLanStop.isEnabled = false
            chipGroupLanDuration.isEnabled = true
            switchLanRequireAccessCode.isEnabled = true
            updateLanWarningText()
            return
        }
        textLanStatus.setText(R.string.lan_status_running)
        textLanUrl.text = getString(R.string.lan_url_label, state.baseUrl)
        textLanCode.text = if (state.accessCodeRequired) {
            getString(R.string.lan_code_label, state.accessCode)
        } else {
            getString(R.string.lan_code_off)
        }
        val mins = ceil(state.remainingMs / 60_000.0).toInt().coerceAtLeast(0)
        textLanCountdown.text = getString(R.string.lan_countdown, mins)
        buttonLanStart.isEnabled = false
        buttonLanStop.isEnabled = true
        chipGroupLanDuration.isEnabled = false
        switchLanRequireAccessCode.isEnabled = false
        switchLanRequireAccessCode.isChecked = state.accessCodeRequired
        updateLanWarningText(state.accessCodeRequired)
    }

    /** JSON last put in the editor; if the editor still shows it, the user hasn't edited it. */
    private var boundConfigJson: String? = null

    private fun setConfigJson(json: String) {
        boundConfigJson = json
        editConfigJson.setText(json)
    }

    override fun onResume() {
        super.onResume()
        // Another screen (rule wizard) may have saved config; refresh an unedited editor so a
        // later "Save rules" doesn't write the stale JSON back over it.
        if (::editConfigJson.isInitialized && editConfigJson.text?.toString() == boundConfigJson &&
            configStore.unreadableJson() == null
        ) {
            setConfigJson(configStore.exportJson())
        }
        if (::splitwiseStore.isInitialized) {
            bindSplitwiseUi()
        }
        refreshLanStatusUi()
        if (panelPermissions.visibility == View.VISIBLE) permissionsPanel.refresh()
        if (panelLaptop.visibility == View.VISIBLE && LanServerState.get().running) {
            lanUiHandler.removeCallbacks(lanUiTicker)
            lanUiHandler.post(lanUiTicker)
        }
    }

    override fun onPause() {
        lanUiHandler.removeCallbacks(lanUiTicker)
        super.onPause()
    }

    private fun bindFromStore() {
        val config = configStore.load()
        editAccounts.setText(config.accounts.joinToString(", "))
        switchMmFullSplitAsOwn.isChecked = config.mmFullSplitAsOwnExpense
        editDebtCategory.setText(config.debtCategory)
        editDebtSubcategory.setText(config.debtSubcategory)
        editDedupeMinutes.setText(config.dedupeWindowMinutes.toString())
        switchAutoSave.isChecked = config.autoSaveFromSms
        switchNightlyReminder.isChecked = config.nightlyReviewReminderEnabled
        nightlyHour = config.nightlyReviewHour
        nightlyMinute = config.nightlyReviewMinute
        selectThemeModeChip(config.themeMode)
        refreshNightlyTimeLabel()
        refreshBackfillDateLabels()
        val unreadable = configStore.unreadableJson()
        setConfigJson(unreadable ?: configStore.exportJson())
        if (unreadable != null) {
            Toast.makeText(
                this,
                "Saved config couldn't be read; using defaults. Fix the JSON under Rules and tap Save rules.",
                Toast.LENGTH_LONG
            ).show()
        }
        updateDebtCategoryEnabled()
        updateNightlyTimeEnabled()
        refreshThemeModeHint()
    }

    private fun selectThemeModeChip(mode: String) {
        val normalized = ThemeMode.normalize(mode)
        for (i in 0 until chipGroupThemeMode.childCount) {
            val chip = chipGroupThemeMode.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.tag?.toString() == normalized
        }
    }

    private fun selectedThemeMode(): String {
        val checkedId = chipGroupThemeMode.checkedChipId
        if (checkedId != View.NO_ID) {
            val tag = findViewById<Chip>(checkedId).tag?.toString()
            if (!tag.isNullOrBlank()) return ThemeMode.normalize(tag)
        }
        return ThemeMode.DYNAMIC
    }

    private fun refreshThemeModeHint() {
        textThemeModeHint.setText(
            when (selectedThemeMode()) {
                ThemeMode.LIGHT -> R.string.theme_light_hint
                ThemeMode.SYSTEM -> R.string.theme_system_hint
                else -> R.string.theme_dynamic_hint
            }
        )
    }

    private fun saveTheme() {
        val mode = selectedThemeMode()
        val current = configStore.load()
        configStore.save(current.copy(themeMode = mode))
        setConfigJson(configStore.exportJson())
        ThemeApplier.applyFromConfig(this)
        Toast.makeText(this, R.string.save_theme, Toast.LENGTH_SHORT).show()
        recreate()
    }

    private fun updateDebtCategoryEnabled() {
        // Debt category is only used for the original share+dues MM export.
        // When full-as-own is on, dues fields are unused → disable.
        // When full-as-own is off, dues fields are needed → enable.
        val debtUsable = !switchMmFullSplitAsOwn.isChecked
        layoutDebtCategory.isEnabled = debtUsable
        layoutDebtSubcategory.isEnabled = debtUsable
        editDebtCategory.isEnabled = debtUsable
        editDebtSubcategory.isEnabled = debtUsable
    }

    private fun updateNightlyTimeEnabled() {
        val enabled = switchNightlyReminder.isChecked
        layoutNightlyTime.isEnabled = enabled
        editNightlyTime.isEnabled = enabled
        layoutNightlyTime.isEndIconVisible = enabled
    }

    private fun refreshNightlyTimeLabel() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, nightlyHour)
            set(Calendar.MINUTE, nightlyMinute)
        }
        editNightlyTime.setText(
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
        )
    }

    private fun showNightlyTimePicker() {
        if (!switchNightlyReminder.isChecked) return
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(nightlyHour)
            .setMinute(nightlyMinute)
            .setTitleText(R.string.nightly_review_time)
            .build()
        picker.addOnPositiveButtonClickListener {
            nightlyHour = picker.hour
            nightlyMinute = picker.minute
            refreshNightlyTimeLabel()
        }
        picker.show(supportFragmentManager, "nightly_time_picker")
    }

    private fun saveBasics() {
        try {
            val current = configStore.load()
            configStore.save(
                current.copy(
                    accounts = splitCsv(editAccounts.text?.toString()),
                    mmFullSplitAsOwnExpense = switchMmFullSplitAsOwn.isChecked,
                    debtCategory = editDebtCategory.text?.toString()?.trim().orEmpty()
                        .ifBlank { current.debtCategory },
                    debtSubcategory = editDebtSubcategory.text?.toString()?.trim().orEmpty()
                        .ifBlank { current.debtSubcategory },
                    dedupeWindowMinutes = editDedupeMinutes.text?.toString()?.toIntOrNull()
                        ?: current.dedupeWindowMinutes,
                    autoSaveFromSms = switchAutoSave.isChecked,
                    nightlyReviewReminderEnabled = switchNightlyReminder.isChecked,
                    nightlyReviewHour = nightlyHour.coerceIn(0, 23),
                    nightlyReviewMinute = nightlyMinute.coerceIn(0, 59)
                )
            )
            setConfigJson(configStore.exportJson())
            NightlyReviewScheduler.applyFromConfig(this)
            Toast.makeText(this, "Basics saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveRules() {
        try {
            val jsonText = editConfigJson.text?.toString()?.trim().orEmpty()
            if (jsonText.isEmpty()) {
                Toast.makeText(this, "Config JSON is empty", Toast.LENGTH_SHORT).show()
                return
            }
            val previousTheme = configStore.load().themeMode
            configStore.importJson(jsonText)
            bindFromStore()
            NightlyReviewScheduler.applyFromConfig(this)
            val nextTheme = configStore.load().themeMode
            if (ThemeMode.normalize(previousTheme) != ThemeMode.normalize(nextTheme)) {
                ThemeApplier.applyFromConfig(this)
                recreate()
            }
            Toast.makeText(this, "Rules saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid config: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun runParseTest() {
        val sms = editTestSms.text?.toString().orEmpty()
        if (sms.isBlank()) {
            textParseResult.text = "Paste an SMS first."
            return
        }
        val parsed = SmsParser(configStore.load()).parse(sms)
        textParseResult.text = if (parsed == null) {
            "No match"
        } else {
            """
            Rule: ${parsed.matchedRuleName ?: "fallback"}
            Amount: ${parsed.amount}
            Merchant: ${parsed.merchantName}
            Account: ${parsed.account}
            Type: ${parsed.transactionType}
            Ref: ${parsed.referenceNumber ?: "-"}
            Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(parsed.timestamp))}
            """.trimIndent()
        }
    }

    private fun injectParsedSms() {
        val sms = editTestSms.text?.toString().orEmpty()
        if (sms.isBlank()) {
            Toast.makeText(this, "Paste an SMS first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                SmsIngest.ingest(
                    db = AppDatabase.getDatabase(applicationContext),
                    config = configStore.load(),
                    body = sms,
                    receivedAtMs = null,
                    sender = null
                )
            }
            val id = when (outcome) {
                is SmsIngest.Outcome.Accepted -> outcome.id
                is SmsIngest.Outcome.Duplicate -> {
                    Toast.makeText(this@AdminActivity, "Already in the list (duplicate)", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                else -> {
                    textParseResult.text = "No match — fix regex or SMS text before injecting."
                    Toast.makeText(this@AdminActivity, "Parse failed", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            textParseResult.text = "Injected as Needs review (id=$id)\n\n" + textParseResult.text
            Toast.makeText(this@AdminActivity, "Saved to review list", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this@AdminActivity, TransactionListActivity::class.java).apply {
                    putExtra(TransactionListActivity.EXTRA_FILTER, ListFilter.NEEDS_REVIEW.name)
                }
            )
        }
    }

    private fun exportRawCsv() {
        lifecycleScope.launch {
            val txs = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).transactionDao().getAllTransactionsList()
            }
            if (txs.isEmpty()) {
                Toast.makeText(this@AdminActivity, "No transactions", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val header =
                "ID,Amount,Merchant,Account,Category,Subcategory,Type,Status,Participants,Notes,Timestamp,Reference,OriginalSMS\n"
            val body = txs.joinToString("\n") { tx ->
                listOf(
                    tx.id.toString(),
                    java.math.BigDecimal.valueOf(tx.amount).toPlainString(),
                    q(tx.merchantName),
                    q(tx.account),
                    q(tx.category),
                    q(tx.subcategory),
                    q(tx.transactionType),
                    q(tx.reviewStatus),
                    q(tx.participants),
                    q(tx.notes.orEmpty()),
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(tx.timestamp)),
                    q(tx.referenceNumber.orEmpty()),
                    q(tx.originalSms.orEmpty())
                ).joinToString(",")
            }
            FileHandler().exportCsv(this@AdminActivity, "transactions_raw_$stamp.csv", header + body)
        }
    }

    private fun exportMoneyManager(markExported: Boolean) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val config = configStore.load()
            val txs = withContext(Dispatchers.IO) {
                db.transactionDao().getTransactionsByStatusList(ReviewStatus.REVIEWED)
            }
            if (txs.isEmpty()) {
                Toast.makeText(this@AdminActivity, "No reviewed transactions", Toast.LENGTH_SHORT).show()
                return@launch
            }
            MoneyManagerSync.syncToMoneyManager(
                activity = this@AdminActivity,
                transactions = txs,
                config = config,
                transactionDao = db.transactionDao(),
                markExported = markExported
            )
        }
    }

    private fun exportSplitwise(onlySplits: Boolean) {
        lifecycleScope.launch {
            val txs = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).transactionDao().getAllTransactionsList()
                    .filter { it.reviewStatus == ReviewStatus.REVIEWED || it.reviewStatus == ReviewStatus.EXPORTED }
            }
            val csv = SplitwiseExporter.toCsv(txs, onlySplits = onlySplits)
            if (csv.lines().size <= 1) {
                Toast.makeText(this@AdminActivity, "No split transactions to export", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            FileHandler().exportCsv(this@AdminActivity, "splitwise_import_$stamp.csv", csv)
        }
    }

    private fun exportSplitLedger() {
        lifecycleScope.launch {
            val txs = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).transactionDao().getAllTransactionsList()
            }
            val csv = SplitwiseExporter.toSplitLedgerCsv(txs)
            if (csv.lines().size <= 1) {
                Toast.makeText(this@AdminActivity, "No split transactions found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            FileHandler().exportCsv(this@AdminActivity, "split_ledger_$stamp.csv", csv)
        }
    }

    private fun initializeBackfillDates() {
        backfillToDate = startOfDay(System.currentTimeMillis())
        backfillFromDate = backfillToDate - TimeUnit.DAYS.toMillis(7)
    }

    private fun refreshBackfillDateLabels() {
        val format = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        buttonBackfillFromDate.text = getString(
            R.string.sms_backfill_from_date_value,
            format.format(Date(backfillFromDate))
        )
        buttonBackfillToDate.text = getString(
            R.string.sms_backfill_to_date_value,
            format.format(Date(backfillToDate))
        )
    }

    private fun showBackfillDatePicker(selectFrom: Boolean) {
        val selectedDate = if (selectFrom) backfillFromDate else backfillToDate
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedDate }
        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                val chosen = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (selectFrom) {
                    backfillFromDate = chosen
                } else {
                    backfillToDate = chosen
                }
                refreshBackfillDateLabels()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun requestPermissionAndStartBackfill() {
        if (backfillFromDate > backfillToDate) {
            Toast.makeText(this, R.string.sms_backfill_invalid_range, Toast.LENGTH_SHORT).show()
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestReadSmsLauncher.launch(Manifest.permission.READ_SMS)
        } else {
            confirmBackfill()
        }
    }

    private fun confirmBackfill() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sms_backfill_confirm_title)
            .setMessage(R.string.sms_backfill_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.proceed) { _, _ -> runBackfill() }
            .show()
    }

    private fun runBackfill() {
        findViewById<MaterialButton>(R.id.buttonBackfillSms).isEnabled = false
        textBackfillResult.setText(R.string.sms_backfill_in_progress)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    readAndParseSmsBackfill(backfillFromDate, backfillToDate)
                }
                textBackfillResult.text = getString(
                    R.string.sms_backfill_result,
                    result.scanned,
                    result.parsed,
                    result.inserted,
                    result.duplicates
                )
            } catch (e: SecurityException) {
                textBackfillResult.setText(R.string.sms_read_permission_required)
            } catch (e: Exception) {
                textBackfillResult.text = getString(
                    R.string.sms_backfill_failed,
                    e.message ?: "unknown error"
                )
            } finally {
                findViewById<MaterialButton>(R.id.buttonBackfillSms).isEnabled = true
            }
        }
    }

    private suspend fun readAndParseSmsBackfill(
        fromDate: Long,
        toDate: Long
    ): BackfillResult {
        val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
        val config = configStore.load()
        val existing = dao.getAllTransactionsList().toMutableList()
        var scanned = 0
        var parsedCount = 0
        var inserted = 0
        var duplicates = 0

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
        val selectionArgs = arrayOf(
            fromDate.toString(),
            (toDate + TimeUnit.DAYS.toMillis(1)).toString()
        )

        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} ASC"
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            val db = AppDatabase.getDatabase(applicationContext)
            while (cursor.moveToNext()) {
                val sender = cursor.getString(addressIndex).orEmpty()
                if (!config.isBankSender(sender)) continue

                scanned++
                val body = cursor.getString(bodyIndex).orEmpty()
                if (body.isBlank()) continue
                val outcome = SmsIngest.ingest(
                    db = db,
                    config = config,
                    body = body,
                    receivedAtMs = cursor.getLong(dateIndex),
                    sender = sender,
                    existing = existing
                )
                when (outcome) {
                    is SmsIngest.Outcome.Accepted -> {
                        parsedCount++
                        inserted++
                        existing.add(outcome.transaction)
                    }
                    is SmsIngest.Outcome.Duplicate -> {
                        parsedCount++
                        duplicates++
                    }
                    else -> Unit
                }
            }
        }

        return BackfillResult(scanned, parsedCount, inserted, duplicates)
    }

    private fun startOfDay(millis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun confirmClearStatus(status: String, label: String) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext)
                    .transactionDao()
                    .getTransactionsByStatusList(status)
                    .count { !it.isPendingSplitwisePush() }
            }
            if (count == 0) {
                Toast.makeText(this@AdminActivity, "No $label transactions", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@AdminActivity)
                .setTitle("$label?")
                .setMessage(
                    "Delete $count transactions? This cannot be undone." +
                        if (status == ReviewStatus.EXPORTED) {
                            "\n\nSplits still waiting to be pushed to Splitwise are kept."
                        } else {
                            ""
                        }
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            AppDatabase.getDatabase(applicationContext)
                                .transactionDao()
                                .deleteTransactionsByStatus(status)
                        }
                        Toast.makeText(
                            this@AdminActivity,
                            "Deleted $deleted $label transactions",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        }
    }

    private fun confirmClearExportedOlderThan30Days() {
        lifecycleScope.launch {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            val count = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext)
                    .transactionDao()
                    .getTransactionsByStatusList(ReviewStatus.EXPORTED)
                    .count { it.lastUpdatedTimestamp < cutoff && !it.isPendingSplitwisePush() }
            }
            if (count == 0) {
                Toast.makeText(
                    this@AdminActivity,
                    R.string.clear_mm_exported_older_none,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            AlertDialog.Builder(this@AdminActivity)
                .setTitle(R.string.clear_mm_exported_older_title)
                .setMessage(getString(R.string.clear_mm_exported_older_message, count))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            AppDatabase.getDatabase(applicationContext)
                                .transactionDao()
                                .deleteExportedOlderThan(ReviewStatus.EXPORTED, cutoff)
                        }
                        Toast.makeText(
                            this@AdminActivity,
                            getString(R.string.clear_mm_exported_older_done, deleted),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        }
    }

    private fun confirmClearAll() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext)
                    .transactionDao()
                    .getAllTransactionsList()
                    .size
            }
            if (count == 0) {
                Toast.makeText(this@AdminActivity, "No transactions", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@AdminActivity)
                .setTitle(R.string.clear_all_transactions)
                .setMessage("Delete all $count transactions? This cannot be undone.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> clearAll() }
                .show()
        }
    }

    private fun clearAll() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).transactionDao().deleteAllTransactions()
            }
            Toast.makeText(this@AdminActivity, "All transactions cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun splitCsv(raw: String?): List<String> =
        raw.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun q(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private data class BackfillResult(
        val scanned: Int,
        val parsed: Int,
        val inserted: Int,
        val duplicates: Int
    )
}
