package com.example.paymentsmstracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.paymentsmstracker.location.LocationCapture
import com.google.android.material.button.MaterialButton

/**
 * Settings → Permissions tab. Nothing is requested until the user taps a button here
 * (SMS + notifications are also asked once on first launch by [MainActivity]).
 * Must be constructed in the activity's onCreate so the launcher registers in time.
 */
class PermissionsPanel(private val activity: AppCompatActivity, root: View) {

    private val smsStatus: TextView = root.findViewById(R.id.textPermSmsStatus)
    private val smsButton: MaterialButton = root.findViewById(R.id.buttonPermSms)
    private val notificationsStatus: TextView = root.findViewById(R.id.textPermNotificationsStatus)
    private val notificationsButton: MaterialButton = root.findViewById(R.id.buttonPermNotifications)
    private val locationStatus: TextView = root.findViewById(R.id.textPermLocationStatus)
    private val locationButton: MaterialButton = root.findViewById(R.id.buttonPermLocation)

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val blocked = result.any { (permission, granted) ->
                !granted && !activity.shouldShowRequestPermissionRationale(permission)
            }
            if (blocked) {
                Toast.makeText(activity, R.string.perm_open_settings_hint, Toast.LENGTH_LONG).show()
                openAppSettings()
            }
            refresh()
        }

    init {
        smsButton.setOnClickListener { requestOrManage(SMS_PERMISSIONS) }
        notificationsButton.setOnClickListener { requestOrManage(notificationPermissions()) }
        locationButton.setOnClickListener {
            when {
                !LocationCapture.hasForegroundPermission(activity) -> launcher.launch(FOREGROUND_LOCATION)
                // On API 30+ Android shows its own settings screen for "Allow all the time".
                !LocationCapture.hasBackgroundPermission(activity) ->
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                else -> openAppSettings()
            }
        }
    }

    fun refresh() {
        bind(smsStatus, smsButton, allGranted(SMS_PERMISSIONS))
        bind(notificationsStatus, notificationsButton, allGranted(notificationPermissions()))

        val foreground = LocationCapture.hasForegroundPermission(activity)
        val background = foreground && LocationCapture.hasBackgroundPermission(activity)
        locationStatus.setText(
            when {
                background -> R.string.perm_status_location_always
                foreground -> R.string.perm_status_location_foreground
                else -> R.string.perm_status_denied
            }
        )
        locationButton.setText(
            when {
                background -> R.string.perm_action_manage
                foreground -> R.string.perm_action_allow_always
                else -> R.string.perm_action_allow
            }
        )
    }

    private fun bind(status: TextView, button: MaterialButton, granted: Boolean) {
        status.setText(if (granted) R.string.perm_status_granted else R.string.perm_status_denied)
        button.setText(if (granted) R.string.perm_action_manage else R.string.perm_action_allow)
    }

    private fun requestOrManage(permissions: Array<String>) {
        if (allGranted(permissions)) openAppSettings() else launcher.launch(permissions)
    }

    private fun allGranted(permissions: Array<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppSettings() {
        activity.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)
            )
        )
    }

    companion object {
        private val SMS_PERMISSIONS =
            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        private val FOREGROUND_LOCATION = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        private fun notificationPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        /** Asked once on a clean install; location is never part of this. */
        fun corePermissions(): List<String> = SMS_PERMISSIONS.toList() + notificationPermissions()
    }
}
