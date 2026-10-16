package com.example.paymentsmstracker.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.example.paymentsmstracker.AgentChatActivity
import com.example.paymentsmstracker.MainActivity
import com.example.paymentsmstracker.R
import com.example.paymentsmstracker.SplitsActivity
import com.example.paymentsmstracker.TransactionListActivity
import com.example.paymentsmstracker.viewmodel.ListFilter

/** Wires the floating pill nav (layout/view_mono_nav). The current tab gets the yellow circle. */
object MonoNav {

    enum class Tab { HOME, ALL, ASK, SPLITS, SEND }

    fun bind(activity: Activity, current: Tab) {
        val buttons = mapOf(
            Tab.HOME to R.id.navHome,
            Tab.ALL to R.id.navAll,
            Tab.ASK to R.id.navAsk,
            Tab.SPLITS to R.id.navSplits,
            Tab.SEND to R.id.navSend
        )
        val onInk = ContextCompat.getColor(activity, R.color.mono_on_ink)
        val onYellow = ContextCompat.getColor(activity, R.color.mono_on_yellow)
        for ((tab, id) in buttons) {
            val button = activity.findViewById<ImageButton>(id) ?: continue
            val active = tab == current
            button.isSelected = active
            button.setBackgroundResource(if (active) R.drawable.bg_mono_circle_yellow else android.R.color.transparent)
            button.imageTintList = ColorStateList.valueOf(if (active) onYellow else onInk)
            button.setOnClickListener { if (!active) open(activity, tab) }
        }
    }

    private fun open(activity: Activity, tab: Tab) {
        val intent = when (tab) {
            Tab.HOME -> Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            Tab.ALL -> listIntent(activity, ListFilter.ALL)
            Tab.SEND -> listIntent(activity, ListFilter.REVIEWED)
            Tab.ASK -> Intent(activity, AgentChatActivity::class.java)
            Tab.SPLITS -> Intent(activity, SplitsActivity::class.java)
        }
        activity.startActivity(intent)
        if (activity !is MainActivity) activity.finish()
        activity.overridePendingTransition(0, 0)
    }

    private fun listIntent(activity: Activity, filter: ListFilter) =
        Intent(activity, TransactionListActivity::class.java)
            .putExtra(TransactionListActivity.EXTRA_FILTER, filter.name)
}
