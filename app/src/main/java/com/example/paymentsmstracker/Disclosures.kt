package com.example.paymentsmstracker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Play "prominent disclosure" dialogs and small policy helpers. Copy lives in strings.xml. */
object Disclosures {
    private const val PREFS = "disclosure_prefs"
    private const val KEY_AI_CONSENT = "ai_consent_accepted"

    fun sms(activity: Activity, onContinue: () -> Unit) = permission(
        activity, R.string.disclosure_sms_title, R.string.disclosure_sms_body, onContinue
    )

    fun location(activity: Activity, onContinue: () -> Unit) = permission(
        activity, R.string.disclosure_location_title, R.string.perm_location_reason, onContinue
    )

    private fun permission(activity: Activity, title: Int, body: Int, onContinue: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val bodyView = TextView(activity).apply {
            setText(body)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        val link = TextView(activity).apply {
            setText(R.string.privacy_policy)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            setOnClickListener { openPrivacyPolicy(activity) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(bodyView)
            addView(link)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.disclosure_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.disclosure_not_now, null)
            .show()
    }

    /** Runs [onAccepted] at once if consent was given before; otherwise asks first. Cancel runs [onCancel]. */
    fun aiConsent(activity: Activity, onCancel: () -> Unit, onAccepted: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AI_CONSENT, false)) return onAccepted()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disclosure_ai_title)
            .setMessage(R.string.ai_consent_body)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setPositiveButton(R.string.disclosure_turn_on) { _, _ ->
                prefs.edit().putBoolean(KEY_AI_CONSENT, true).apply()
                onAccepted()
            }
            .show()
    }

    fun openPrivacyPolicy(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.privacy_policy_url)))
            )
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Emails (or shares, when no report address is set) only the visible reply text. */
    fun reportResponse(context: Context, reply: String) {
        val subject = context.getString(R.string.report_subject)
        val email = context.getString(R.string.report_email)
        if (email.isNotBlank()) {
            val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, reply)
            try {
                context.startActivity(mail)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, reply)
        context.startActivity(Intent.createChooser(send, subject))
    }
}
