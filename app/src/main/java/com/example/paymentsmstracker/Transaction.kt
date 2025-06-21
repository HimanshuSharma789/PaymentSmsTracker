package com.example.paymentsmstracker.data // Or your preferred package for data models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions") // Defines the table name
data class Transaction(
    @PrimaryKey(autoGenerate = true) // Makes 'id' the primary key and auto-increments it
    val id: Long = 0L, // Use Long for auto-generated IDs

    val amount: Double,
    val merchantName: String,
    val category: String,
    val notes: String?, // Nullable if notes are optional
    val timestamp: Long, // Store time as milliseconds (Unix time)
    val originalSms: String? // Optional: to store the original SMS for reference
)