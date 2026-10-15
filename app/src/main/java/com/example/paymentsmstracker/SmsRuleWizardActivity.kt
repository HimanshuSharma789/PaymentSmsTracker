package com.example.paymentsmstracker

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.paymentsmstracker.config.ConfigStore
import com.example.paymentsmstracker.sms.SmsParser
import com.example.paymentsmstracker.sms.SmsRuleBuilder
import com.example.paymentsmstracker.ui.OptionPickerBottomSheet
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SmsRuleWizardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SMS = "sms_rule_wizard_sms"
    }

    private lateinit var configStore: ConfigStore
    private lateinit var editSms: TextInputEditText
    private lateinit var editAmount: TextInputEditText
    private lateinit var editMerchant: TextInputEditText
    private lateinit var editDate: TextInputEditText
    private lateinit var editReference: TextInputEditText
    private lateinit var editDateFormat: TextInputEditText
    private lateinit var editRuleName: TextInputEditText
    private lateinit var buttonPickAccount: MaterialButton
    private lateinit var switchTransfer: MaterialSwitch
    private lateinit var textPreview: TextView

    private var selectedAccount: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_rule_wizard)

        configStore = ConfigStore(this)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbarSmsRuleWizard)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        editSms = findViewById(R.id.editWizardSms)
        editAmount = findViewById(R.id.editWizardAmount)
        editMerchant = findViewById(R.id.editWizardMerchant)
        editDate = findViewById(R.id.editWizardDate)
        editReference = findViewById(R.id.editWizardReference)
        editDateFormat = findViewById(R.id.editWizardDateFormat)
        editRuleName = findViewById(R.id.editWizardRuleName)
        buttonPickAccount = findViewById(R.id.buttonWizardPickAccount)
        switchTransfer = findViewById(R.id.switchWizardTransfer)
        textPreview = findViewById(R.id.textWizardPreview)

        val initialSms = intent.getStringExtra(EXTRA_SMS).orEmpty()
        if (initialSms.isNotBlank()) {
            editSms.setText(initialSms)
            prefillFromParse(initialSms)
        }

        buttonPickAccount.setOnClickListener {
            val accounts = configStore.load().accounts
            OptionPickerBottomSheet.show(
                this,
                getString(R.string.sms_rule_default_account),
                accounts,
                selectedAccount.ifBlank { null }
            ) { value ->
                selectedAccount = value
                buttonPickAccount.text =
                    "${getString(R.string.sms_rule_default_account)}: $value"
            }
        }

        findViewById<MaterialButton>(R.id.buttonWizardVerify).setOnClickListener { verifyRule() }
        findViewById<MaterialButton>(R.id.buttonWizardSave).setOnClickListener { saveRule() }
    }

    private fun prefillFromParse(sms: String) {
        val parsed = SmsParser(configStore.load()).parse(
            sms,
            requirePaymentHeuristic = false
        ) ?: return
        editAmount.setText(formatAmountCapture(parsed.amount, sms))
        editMerchant.setText(findMerchantInSms(sms, parsed.merchantName))
        parsed.referenceNumber?.takeIf { it.isNotBlank() }?.let { ref ->
            if (sms.contains(ref)) editReference.setText(ref)
        }
        if (editRuleName.text.isNullOrBlank()) {
            editRuleName.setText(parsed.matchedRuleName?.let { "Custom: $it" } ?: "Custom SMS rule")
        }
        if (parsed.account.isNotBlank()) {
            selectedAccount = parsed.account
            buttonPickAccount.text =
                "${getString(R.string.sms_rule_default_account)}: ${parsed.account}"
        }
        switchTransfer.isChecked = parsed.transactionType == "Transfer-Out"
    }

    private fun formatAmountCapture(amount: Double, sms: String): String {
        val plain = if (amount % 1.0 == 0.0) {
            amount.toLong().toString()
        } else {
            amount.toString()
        }
        // Prefer the literal as written in the SMS (with commas if present).
        val withComma = "%,.2f".format(java.util.Locale.US, amount).trimEnd('0').trimEnd('.')
        return when {
            sms.contains(withComma) -> withComma
            sms.contains(plain) -> plain
            else -> plain
        }
    }

    private fun findMerchantInSms(sms: String, cleanedMerchant: String): String {
        if (cleanedMerchant.isBlank()) return ""
        if (sms.contains(cleanedMerchant)) return cleanedMerchant
        // Try without cleanup — find longest substring match of words
        val words = cleanedMerchant.split(' ').filter { it.length > 2 }
        if (words.isEmpty()) return cleanedMerchant
        val first = words.first()
        val idx = sms.indexOf(first, ignoreCase = true)
        if (idx < 0) return cleanedMerchant
        // Extend to cover remaining words if present nearby
        var end = idx + first.length
        for (w in words.drop(1)) {
            val next = sms.indexOf(w, end, ignoreCase = true)
            if (next in end..(end + 24)) {
                end = next + w.length
            } else break
        }
        return sms.substring(idx, end)
    }

    private fun buildCurrentRule(): Pair<com.example.paymentsmstracker.config.SmsParseRule?, String?> {
        val sms = editSms.text?.toString().orEmpty()
        if (sms.isBlank()) return null to getString(R.string.sms_rule_need_sms)
        val amountText = editAmount.text?.toString().orEmpty()
        val merchantText = editMerchant.text?.toString().orEmpty()
        val id = "user_${System.currentTimeMillis()}"
        val name = editRuleName.text?.toString().orEmpty().ifBlank { "Custom SMS rule" }
        val isTransfer = switchTransfer.isChecked
        return SmsRuleBuilder.buildRule(
            id = id,
            name = name,
            sms = sms,
            amountText = amountText,
            merchantText = merchantText,
            dateText = editDate.text?.toString().orEmpty(),
            referenceText = editReference.text?.toString().orEmpty(),
            dateFormat = editDateFormat.text?.toString().orEmpty(),
            defaultAccount = selectedAccount,
            isExpense = !isTransfer,
            isTransfer = isTransfer
        )
    }

    private fun verifyRule() {
        val sms = editSms.text?.toString().orEmpty()
        val (rule, error) = buildCurrentRule()
        if (rule == null) {
            textPreview.text = error
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        val parsed = SmsParser(configStore.load()).parseWithRulePublic(sms, rule)
        if (parsed == null) {
            textPreview.text = getString(R.string.sms_rule_verify_fail, rule.pattern)
            Toast.makeText(this, R.string.sms_rule_verify_failed_toast, Toast.LENGTH_LONG).show()
            return
        }
        textPreview.text = buildString {
            appendLine(getString(R.string.sms_rule_verify_ok))
            appendLine("Amount: ${parsed.amount}")
            appendLine("Merchant: ${parsed.merchantName}")
            appendLine("Account: ${parsed.account.ifBlank { "—" }}")
            appendLine("Type: ${parsed.transactionType}")
            appendLine("Ref: ${parsed.referenceNumber ?: "—"}")
            appendLine()
            appendLine("Pattern:")
            append(rule.pattern)
        }
        Toast.makeText(this, R.string.sms_rule_verify_ok, Toast.LENGTH_SHORT).show()
    }

    private fun saveRule() {
        val sms = editSms.text?.toString().orEmpty()
        val (rule, error) = buildCurrentRule()
        if (rule == null) {
            textPreview.text = error
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        val parsed = SmsParser(configStore.load()).parseWithRulePublic(sms, rule)
        if (parsed == null) {
            textPreview.text = getString(R.string.sms_rule_verify_fail, rule.pattern)
            Toast.makeText(this, R.string.sms_rule_save_needs_verify, Toast.LENGTH_LONG).show()
            return
        }
        val config = configStore.load()
        val withoutDupName = config.smsParseRules.filter { it.id != rule.id }
        configStore.save(config.copy(smsParseRules = listOf(rule) + withoutDupName))
        Toast.makeText(this, R.string.sms_rule_saved, Toast.LENGTH_LONG).show()
        finish()
    }
}
