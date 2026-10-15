package com.example.paymentsmstracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MerchantPreferenceDao {

    @Query("SELECT * FROM merchant_prefs WHERE merchantKey = :key LIMIT 1")
    suspend fun get(key: String): MerchantPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pref: MerchantPreference)

    @Query("DELETE FROM merchant_prefs WHERE merchantKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM merchant_prefs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM merchant_prefs")
    suspend fun count(): Int
}
