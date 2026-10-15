package com.example.paymentsmstracker.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.paymentsmstracker.AdminActivity
import com.example.paymentsmstracker.R

class LanAccessService : Service() {

    private var server: LocalHttpServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopAtElapsed: Long = 0L
    private val tickRunnable = object : Runnable {
        override fun run() {
            val remaining = stopAtElapsed - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                stopSelf()
                return
            }
            updateNotification()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val minutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 30).coerceIn(5, 240)
                val requireCode = intent.getBooleanExtra(EXTRA_REQUIRE_ACCESS_CODE, true)
                startServer(minutes, requireCode)
            }
        }
        return START_NOT_STICKY
    }

    private fun startServer(durationMinutes: Int, accessCodeRequired: Boolean) {
        if (server != null && LanServerState.get().running) {
            updateNotification()
            return
        }
        createChannel()
        // startForegroundService() requires startForeground() even if we then bail out,
        // otherwise Android crashes the app.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        val host = NetworkUtils.localIpv4(this)
        if (host.isNullOrBlank()) {
            Log.w(TAG, "No Wi-Fi address; not starting LAN server")
            LanServerState.markStopped()
            stopSelf()
            return
        }
        val code = if (accessCodeRequired) LanServerState.generateAccessCode() else ""
        val http = LocalHttpServer(applicationContext)
        try {
            val port = http.start(host)
            server = http
            LanServerState.markStarting(durationMinutes, code, host, port, accessCodeRequired)
            stopAtElapsed = SystemClock.elapsedRealtime() + durationMinutes * 60_000L
            updateNotification()
            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, 30_000L)
            handler.postDelayed({ stopSelf() }, durationMinutes * 60_000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LAN server", e)
            http.stop()
            server = null
            LanServerState.markStopped()
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        LanServerState.markStopped()
        super.onDestroy()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = LanServerState.get()
        val remainingMin = (state.remainingMs / 60_000L).coerceAtLeast(0L)
        val openAdmin = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AdminActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LanAccessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.outline_checklist_24)
            .setContentTitle(getString(R.string.lan_notification_title))
            .setContentText(
                getString(
                    R.string.lan_notification_text,
                    state.baseUrl.ifBlank { "…" },
                    remainingMin
                )
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (state.accessCodeRequired) {
                        getString(
                            R.string.lan_notification_big,
                            state.baseUrl.ifBlank { "…" },
                            state.accessCode,
                            remainingMin
                        )
                    } else {
                        getString(
                            R.string.lan_notification_big_open,
                            state.baseUrl.ifBlank { "…" },
                            remainingMin
                        )
                    }
                )
            )
            .setOngoing(true)
            .setContentIntent(openAdmin)
            .addAction(0, getString(R.string.lan_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.lan_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "LanAccessService"
        private const val CHANNEL_ID = "lan_access"
        private const val NOTIFICATION_ID = 4401
        const val ACTION_START = "com.example.paymentsmstracker.LAN_START"
        const val ACTION_STOP = "com.example.paymentsmstracker.LAN_STOP"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_REQUIRE_ACCESS_CODE = "require_access_code"

        fun start(context: Context, durationMinutes: Int, requireAccessCode: Boolean = true) {
            val intent = Intent(context, LanAccessService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
                .putExtra(EXTRA_REQUIRE_ACCESS_CODE, requireAccessCode)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LanAccessService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
