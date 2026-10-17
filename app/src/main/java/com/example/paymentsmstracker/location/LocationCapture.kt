package com.example.paymentsmstracker.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Best-effort device location for tagging SMS transactions. Null when permission is off or no fix.
 * Foreground permission only: SMS that arrive while the app is not in use get no location.
 */
object LocationCapture {

    private const val TAG = "LocationCapture"
    private val FRESH_MS = TimeUnit.MINUTES.toMillis(5)
    private const val CURRENT_FIX_TIMEOUT_MS = 5_000L

    fun hasForegroundPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    // ponytail: reuses a last-known fix up to 5 min old; request a fresh fix every time if that proves too stale.
    suspend fun current(context: Context): Location? {
        if (!hasForegroundPermission(context)) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = lm.getProviders(true)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time <= FRESH_MS) return last
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last
            val provider = listOf(
                LocationManager.FUSED_PROVIDER.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            ).firstOrNull { it != null && lm.isProviderEnabled(it) } ?: return last
            withTimeoutOrNull(CURRENT_FIX_TIMEOUT_MS) { currentFix(lm, provider, context) } ?: last
        } catch (e: SecurityException) {
            Log.w(TAG, "Location not allowed right now", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Location lookup failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("MissingPermission")
    private suspend fun currentFix(lm: LocationManager, provider: String, context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc -> cont.resume(loc) }
        }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
