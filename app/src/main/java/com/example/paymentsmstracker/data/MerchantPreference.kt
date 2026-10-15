package com.example.paymentsmstracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Survives MM-export purge of old transactions. Last category/account per merchant key.
 */
@Entity(tableName = "merchant_prefs")
data class MerchantPreference(
    @PrimaryKey val merchantKey: String,
    val displayMerchant: String,
    val category: String,
    val subcategory: String,
    val account: String,
    val transactionType: String,
    val updatedAt: Long = System.currentTimeMillis()
)
