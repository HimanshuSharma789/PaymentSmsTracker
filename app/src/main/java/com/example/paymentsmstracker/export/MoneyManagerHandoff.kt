package com.example.paymentsmstracker.export

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.paymentsmstracker.R

/**
 * Money Manager (Realbyte) exposes no exported activity that can receive a TSV for bulk import.
 * Its Excel/TSV import screens are not exported, and the only exported components that accept our
 * data are the single-transaction input screen (ACTION_SEND with a text MIME type) and a full
 * database restore that matches only .sqlite/.mmbak paths. So we save the file where Money
 * Manager's own document picker can reach it and guide the user through the import.
 */
object MoneyManagerHandoff {

    private val MONEY_MANAGER_PACKAGES = listOf(
        "com.realbyteapps.moneymanagerfree",
        "com.realbyteapps.moneya"
    )

    private const val TSV_MIME = "text/tab-separated-values"

    enum class Result {
        PROMPTED_WITH_MONEY_MANAGER,
        PROMPTED_SAVED_ONLY
    }

    fun promptImport(
        activity: Activity,
        shareUri: Uri?,
        filename: String,
        transactionCount: Int,
        splitCount: Int
    ): Result {
        val moneyManagerPackage = installedMoneyManagerPackage(activity)
        val message = buildString {
            append(activity.getString(R.string.mm_sync_steps_message, transactionCount, filename))
            if (splitCount > 0) {
                append("\n\n")
                append(activity.getString(R.string.mm_sync_steps_splits, splitCount))
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.mm_sync_steps_title)
            .setMessage(message)
            .setNegativeButton(R.string.mm_sync_close, null)

        if (moneyManagerPackage != null) {
            builder.setPositiveButton(R.string.mm_sync_open_money_manager) { _, _ ->
                launchMoneyManager(activity, moneyManagerPackage)
            }
        }
        if (shareUri != null) {
            builder.setNeutralButton(R.string.mm_sync_share_file) { _, _ ->
                shareFile(activity, shareUri, filename)
            }
        }
        builder.show()

        return if (moneyManagerPackage != null) {
            Result.PROMPTED_WITH_MONEY_MANAGER
        } else {
            Result.PROMPTED_SAVED_ONLY
        }
    }

    private fun installedMoneyManagerPackage(activity: Activity): String? =
        MONEY_MANAGER_PACKAGES.firstOrNull { packageName ->
            activity.packageManager.getLaunchIntentForPackage(packageName) != null
        }

    private fun launchMoneyManager(activity: Activity, packageName: String) {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(activity, R.string.mm_sync_launch_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            activity.startActivity(launchIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.mm_sync_launch_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareFile(activity: Activity, uri: Uri, filename: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = TSV_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            activity.getString(R.string.mm_sync_chooser_title)
        ).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.mm_sync_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun showFailureToast(activity: Activity) {
        Toast.makeText(activity, R.string.mm_sync_save_failed, Toast.LENGTH_LONG).show()
    }
}
