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
            fromJson(JSONObject(json))
        } catch (_: Exception) {
            AppConfig()
        }
    }

    fun save(config: AppConfig) {
        prefs.edit().putString(KEY_CONFIG, toJson(config).toString()).apply()
    }

    fun resetToDefaults() {
        prefs.edit().remove(KEY_CONFIG).apply()
    }

    fun exportJson(): String = toJson(load()).toString(2)

    fun importJson(raw: String) {
        save(fromJson(JSONObject(raw)))
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
    }

    private fun fromJson(obj: JSONObject): AppConfig {
        val loadedAccounts = obj.optJSONArray("accounts")?.toStringList()
            ?: AppConfig.DEFAULT_ACCOUNTS
        val existingAccountKeys = loadedAccounts.map { it.lowercase() }.toSet()
        val accounts = loadedAccounts + AppConfig.DEFAULT_ACCOUNTS.filter {
            it.lowercase() !in existingAccountKeys
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
                    val r = rulesArray.getJSONObject(i)
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
        // Keep user rules, append new built-ins, and refresh known built-in date patterns.
        val defaultsById = AppConfig.DEFAULT_SMS_RULES.associateBy { it.id }
        val refreshedLoaded = loadedRules.map { loaded ->
            val def = defaultsById[loaded.id] ?: return@map loaded
            // Keep custom edits unless this is a known stock date/time capture we improved.
            if (loaded.id == "hdfc_card_spend") {
                loaded.copy(pattern = def.pattern, dateFormat = def.dateFormat)
            } else {
                loaded
            }
        }
        val existingIds = refreshedLoaded.map { it.id }.toSet()
        val rules = if (refreshedLoaded.isEmpty()) {
            AppConfig.DEFAULT_SMS_RULES
        } else {
            refreshedLoaded + AppConfig.DEFAULT_SMS_RULES.filter { it.id !in existingIds }
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
            themeMode = ThemeMode.normalize(obj.optString("themeMode", ThemeMode.DYNAMIC))
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        buildList {
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotEmpty()) add(value)
            }
        }

    companion object {
        private const val PREFS_NAME = "expense_tracker_config"
        private const val KEY_CONFIG = "app_config_json"
    }
}
