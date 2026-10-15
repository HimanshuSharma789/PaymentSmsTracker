package com.example.paymentsmstracker.config

import android.content.Context
import com.example.paymentsmstracker.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

class ConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppConfig {
        val json = prefs.getString(KEY_CONFIG, null) ?: return AppConfig()
        return try {
            parse(json)
        } catch (_: Exception) {
            // Keep the unreadable JSON so a later save of defaults never destroys it;
            // Admin shows it in the JSON editor for the user to fix.
            if (prefs.getString(KEY_UNREADABLE, null) == null) {
                prefs.edit().putString(KEY_UNREADABLE, json).apply()
            }
            AppConfig()
        }
    }

    /** Raw config JSON that failed to parse, or null when the saved config is fine. */
    fun unreadableJson(): String? = prefs.getString(KEY_UNREADABLE, null)

    fun save(config: AppConfig) {
        prefs.edit().putString(KEY_CONFIG, toJson(config).toString()).apply()
    }

    fun resetToDefaults() {
        prefs.edit().remove(KEY_CONFIG).apply()
    }

    fun exportJson(): String = toJson(load()).toString(2)

    fun importJson(raw: String) {
        save(parse(raw))
        prefs.edit().remove(KEY_UNREADABLE).apply()
    }

    private fun toJson(config: AppConfig): JSONObject {
        val categories = JSONObject()
        config.expenseCategories.forEach { (key, values) ->
            categories.put(key, JSONArray(values))
        }
        val rules = JSONArray()
        config.smsParseRules.forEach { rule ->
            rules.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("pattern", rule.pattern)
                    .put("dateFormat", rule.dateFormat)
                    .put("defaultAccount", rule.defaultAccount)
                    .put("isExpense", rule.isExpense)
                    .put("isTransfer", rule.isTransfer)
            )
        }
        return JSONObject()
            .put("configVersion", CONFIG_VERSION)
            .put("accounts", JSONArray(config.accounts))
            .put("incomeCategories", JSONArray(config.incomeCategories))
            .put("expenseCategories", categories)
            .put("debtCategory", config.debtCategory)
            .put("debtSubcategory", config.debtSubcategory)
            .put("mmFullSplitAsOwnExpense", config.mmFullSplitAsOwnExpense)
            .put("smsParseRules", rules)
            .put("dedupeWindowMinutes", config.dedupeWindowMinutes)
            .put("autoSaveFromSms", config.autoSaveFromSms)
            .put("nightlyReviewReminderEnabled", config.nightlyReviewReminderEnabled)
            .put("nightlyReviewHour", config.nightlyReviewHour)
            .put("nightlyReviewMinute", config.nightlyReviewMinute)
            .put("themeMode", config.themeMode)
            .put("bankSenderTokens", JSONArray(config.bankSenderTokens))
    }

    companion object {
        private const val PREFS_NAME = "expense_tracker_config"
        private const val KEY_CONFIG = "app_config_json"
        private const val KEY_UNREADABLE = "app_config_json_unreadable"

        /**
         * Bump when a release must merge new defaults into saved configs, and add a
         * `version < N` block in [parse]. Merges run once: the next save stamps this version,
         * so accounts/rules the user deleted afterwards stay deleted.
         */
        const val CONFIG_VERSION = 2

        fun parse(raw: String): AppConfig = fromJson(JSONObject(raw))

        private fun fromJson(obj: JSONObject): AppConfig {
            val version = obj.optInt("configVersion", 0)
            val loadedAccounts = obj.optJSONArray("accounts")?.toStringList()
                ?: AppConfig.DEFAULT_ACCOUNTS
            val accounts = if (version < 1) {
                val existingAccountKeys = loadedAccounts.map { it.lowercase() }.toSet()
                loadedAccounts + AppConfig.DEFAULT_ACCOUNTS.filter {
                    it.lowercase() !in existingAccountKeys
                }
            } else {
                loadedAccounts
            }
            val income = obj.optJSONArray("incomeCategories")?.toStringList()
                ?: AppConfig.DEFAULT_INCOME_CATEGORIES

            val categoriesObj = obj.optJSONObject("expenseCategories")
            val categories = if (categoriesObj != null) {
                buildMap {
                    categoriesObj.keys().forEach { key ->
                        put(key, categoriesObj.optJSONArray(key)?.toStringList() ?: emptyList())
                    }
                }
            } else {
                AppConfig.DEFAULT_EXPENSE_CATEGORIES
            }

            val rulesArray = obj.optJSONArray("smsParseRules")
            val loadedRules = if (rulesArray != null && rulesArray.length() > 0) {
                buildList {
                    for (i in 0 until rulesArray.length()) {
                        // Skip a broken rule instead of failing (and resetting) the whole config.
                        val r = rulesArray.optJSONObject(i) ?: continue
                        if (r.optString("pattern").isBlank()) continue
                        add(
                            SmsParseRule(
                                id = r.optString("id", "rule_$i"),
                                name = r.optString("name", "Rule $i"),
                                enabled = r.optBoolean("enabled", true),
                                pattern = r.getString("pattern"),
                                dateFormat = r.optString("dateFormat", ""),
                                defaultAccount = r.optString("defaultAccount", ""),
                                isExpense = r.optBoolean("isExpense", true),
                                isTransfer = r.optBoolean("isTransfer", false)
                            )
                        )
                    }
                }
            } else {
                emptyList()
            }
            val rules = when {
                rulesArray == null -> AppConfig.DEFAULT_SMS_RULES
                version < 1 && loadedRules.isEmpty() -> AppConfig.DEFAULT_SMS_RULES
                version < 1 -> {
                    // v0 → v1: append built-ins and refresh the improved HDFC date capture, once.
                    val defaultsById = AppConfig.DEFAULT_SMS_RULES.associateBy { it.id }
                    val refreshed = loadedRules.map { loaded ->
                        val def = defaultsById[loaded.id]
                        if (def != null && loaded.id == "hdfc_card_spend") {
                            loaded.copy(pattern = def.pattern, dateFormat = def.dateFormat)
                        } else {
                            loaded
                        }
                    }
                    val existingIds = refreshed.map { it.id }.toSet()
                    refreshed + AppConfig.DEFAULT_SMS_RULES.filter { it.id !in existingIds }
                }
                else -> loadedRules
            }.map { rule ->
                // v1 → v2: fix the stock generic rule's dead reference capture (only if unedited).
                if (version < 2 && rule.id == "generic_paid_to_on" &&
                    rule.pattern == AppConfig.GENERIC_PAID_TO_ON_PATTERN_V1
                ) {
                    rule.copy(pattern = AppConfig.GENERIC_PAID_TO_ON_PATTERN)
                } else {
                    rule
                }
            }

            return AppConfig(
                accounts = accounts,
                expenseCategories = categories,
                incomeCategories = income,
                debtCategory = obj.optString("debtCategory", "Social Life"),
                debtSubcategory = obj.optString("debtSubcategory", "Dues"),
                mmFullSplitAsOwnExpense = obj.optBoolean("mmFullSplitAsOwnExpense", false),
                smsParseRules = rules,
                dedupeWindowMinutes = obj.optInt("dedupeWindowMinutes", 30),
                autoSaveFromSms = obj.optBoolean("autoSaveFromSms", true),
                nightlyReviewReminderEnabled = obj.optBoolean("nightlyReviewReminderEnabled", true),
                nightlyReviewHour = obj.optInt("nightlyReviewHour", 21).coerceIn(0, 23),
                nightlyReviewMinute = obj.optInt("nightlyReviewMinute", 0).coerceIn(0, 59),
                themeMode = ThemeMode.normalize(obj.optString("themeMode", ThemeMode.DYNAMIC)),
                bankSenderTokens = obj.optJSONArray("bankSenderTokens")?.toStringList()
                    ?: AppConfig.DEFAULT_BANK_SENDER_TOKENS
            )
        }

        private fun JSONArray.toStringList(): List<String> =
            buildList {
                for (i in 0 until length()) {
                    val value = optString(i).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
    }
}
