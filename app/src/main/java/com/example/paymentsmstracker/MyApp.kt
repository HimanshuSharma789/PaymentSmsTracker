package com.example.paymentsmstracker

import android.app.Application
import com.example.paymentsmstracker.reminder.NightlyReviewScheduler
import com.example.paymentsmstracker.theme.ThemeApplier

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ThemeApplier.applyFromConfig(this)
        ThemeApplier.applyDynamicColors(this)
        NightlyReviewScheduler.applyFromConfig(this)
    }
}
