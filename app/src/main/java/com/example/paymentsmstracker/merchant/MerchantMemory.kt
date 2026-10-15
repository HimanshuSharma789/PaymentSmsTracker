package com.example.paymentsmstracker.merchant

import com.example.paymentsmstracker.data.AppDatabase
import com.example.paymentsmstracker.data.MerchantPreference
import com.example.paymentsmstracker.export.MoneyManagerExporter

data class MerchantMemoryHint(
    val merchantKey: String,
    val category: String,
    val subcategory: String,
    val account: String,
    val transactionType: String
)

data class AppliedMerchantFields(
    val category: String,
    val subcategory: String,
    val account: String
)

/**
 * Last category/account per merchant. Prefs table first; falls back to recent reviewed txs
 * and bootstraps prefs when a history match is found.
 */
object MerchantMemory {

    suspend fun lookup(db: AppDatabase, merchant: String): MerchantMemoryHint? {
        if (!MerchantKeys.isLearnable(merchant)) return null
        val key = MerchantKeys.normalize(merchant)

        db.merchantPreferenceDao().get(key)?.let { pref ->
            return pref.toHint()
        }

        val recent = db.transactionDao().getRecentCategorizedForMemory(300)
        val match = recent.firstOrNull {
            MerchantKeys.normalize(it.merchantName) == key && it.category.isNotBlank()
        } ?: return null

        remember(
            db = db,
            merchant = match.merchantName,
            category = match.category,
            subcategory = match.subcategory,
            account = match.account,
            transactionType = match.transactionType
        )
        return MerchantMemoryHint(
            merchantKey = key,
            category = match.category,
            subcategory = match.subcategory,
            account = match.account,
            transactionType = MoneyManagerExporter.normalizeType(match.transactionType)
        )
    }

    /**
     * Persist classification when the user saves with a category (or transfer destination).
     */
    suspend fun remember(
        db: AppDatabase,
        merchant: String,
        category: String,
        subcategory: String,
        account: String,
        transactionType: String
    ) {
        if (!MerchantKeys.isLearnable(merchant)) return
        if (category.isBlank()) return

        val type = MoneyManagerExporter.normalizeType(transactionType)
        val key = MerchantKeys.normalize(merchant)
        db.merchantPreferenceDao().upsert(
            MerchantPreference(
                merchantKey = key,
                displayMerchant = merchant.trim(),
                category = category.trim(),
                subcategory = subcategory.trim(),
                account = account.trim(),
                transactionType = type,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Fill blank category/subcategory/account from memory. Never overwrites non-blank fields.
     * Category/subcategory only applied when types are compatible (same normalized type,
     * or memory type blank).
     */
    fun applyToBlankFields(
        category: String,
        subcategory: String,
        account: String,
        transactionType: String,
        hint: MerchantMemoryHint?
    ): AppliedMerchantFields {
        if (hint == null) {
            return AppliedMerchantFields(category, subcategory, account)
        }
        val type = MoneyManagerExporter.normalizeType(transactionType)
        val hintType = MoneyManagerExporter.normalizeType(hint.transactionType)
        val typeOk = hintType.isBlank() || hintType.equals(type, ignoreCase = true)

        val nextCategory =
            if (category.isBlank() && typeOk && hint.category.isNotBlank()) hint.category else category
        val nextSubcategory =
            if (subcategory.isBlank() && typeOk && hint.subcategory.isNotBlank()) {
                hint.subcategory
            } else {
                subcategory
            }
        val nextAccount =
            if (account.isBlank() && hint.account.isNotBlank()) hint.account else account

        return AppliedMerchantFields(nextCategory, nextSubcategory, nextAccount)
    }

    private fun MerchantPreference.toHint() = MerchantMemoryHint(
        merchantKey = merchantKey,
        category = category,
        subcategory = subcategory,
        account = account,
        transactionType = transactionType
    )
}
