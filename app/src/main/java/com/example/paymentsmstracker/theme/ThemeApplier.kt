package com.example.paymentsmstracker.theme

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.example.paymentsmstracker.config.ConfigStore
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

object ThemeMode {
    const val LIGHT = "light"
    const val SYSTEM = "system"
    /** Follow system light/dark and use Material You wallpaper colors when available. */
    const val DYNAMIC = "dynamic"

    val ALL = listOf(LIGHT, SYSTEM, DYNAMIC)

    fun normalize(raw: String?): String = when (raw?.lowercase()?.trim()) {
        LIGHT -> LIGHT
        SYSTEM -> SYSTEM
        DYNAMIC -> DYNAMIC
        else -> DYNAMIC
    }

    fun usesDynamicColor(mode: String): Boolean =
        normalize(mode) == DYNAMIC
}

object ThemeApplier {

    fun applyFromConfig(context: Context) {
        val mode = ThemeMode.normalize(ConfigStore(context).load().themeMode)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applyDynamicColors(application: Application) {
        DynamicColors.applyToActivitiesIfAvailable(
            application,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ ->
                    ThemeMode.usesDynamicColor(ConfigStore(application).load().themeMode)
                }
                .build()
        )
    }
}
