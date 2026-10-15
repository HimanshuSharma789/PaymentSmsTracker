package com.example.paymentsmstracker.agent

import com.example.paymentsmstracker.config.AppConfig
import com.example.paymentsmstracker.data.Transaction
import com.example.paymentsmstracker.split.SplitCalculator
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the model may read. Each flag is a toggle in Admin → AI Assist. Reference numbers and tokens are never sent. */
data class AgentPrivacy(
    val amounts: Boolean = true,
    val merchants: Boolean = true,
    val dates: Boolean = true,
    val categories: Boolean = true,
    val notes: Boolean = true,
    val friends: Boolean = true,
    val merchantMemory: Boolean = true,
    /** Chat may search other transactions. */
    val history: Boolean = true,
    val sms: Boolean = false,
    val location: Boolean = false
)

/** The only place that turns app data into JSON for the model. */
object AgentData {

    fun txJson(tx: Transaction, p: AgentPrivacy): JSONObject {
        val o = JSONObject().put("id", tx.id).put("status", tx.reviewStatus).put("transactionType", tx.transactionType)
        if (p.amounts) o.put("amount", tx.amount)
        if (p.merchants) o.put("merchantName", tx.merchantName)
        if (p.dates) o.put("date", SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.US).format(Date(tx.timestamp)))
        if (p.categories) o.put("account", tx.account).put("category", tx.category).put("subcategory", tx.subcategory)
        if (p.notes) o.put("notes", tx.notes.orEmpty())
        if (p.friends) {
            o.put("participants", JSONArray(tx.participantList())).put("splitMode", tx.splitMode)
            val allocations = SplitCalculator.parseAllocations(tx.splitAllocations)
            if (allocations.isNotEmpty()) o.put("splitAllocations", JSONObject(allocations.mapValues { it.value.toDouble() }))
        }
        if (p.sms && !tx.originalSms.isNullOrBlank()) o.put("originalSms", tx.originalSms)
        if (p.location && tx.latitude != null && tx.longitude != null) {
            o.put("location", JSONObject().put("lat", tx.latitude).put("lng", tx.longitude))
        }
        return o
    }

    fun optionsJson(config: AppConfig, memberLabels: List<String>, p: AgentPrivacy): JSONObject {
        val o = JSONObject()
        if (p.categories) {
            o.put("allowedAccounts", JSONArray(config.accounts))
                .put("allowedExpenseCategories", JSONObject(config.expenseCategories.mapValues { JSONArray(it.value) }))
                .put("allowedIncomeCategories", JSONArray(config.incomeCategories))
        }
        if (p.friends) o.put("splitMembers", JSONArray(memberLabels))
        return o
    }
}
