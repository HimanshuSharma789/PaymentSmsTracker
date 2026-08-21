package com.example.paymentsmstracker.splitwise

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.paymentsmstracker.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object SplitwiseSessionEvents {
    private val expiredMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = expiredMutable.asSharedFlow()

    fun emitExpired() {
        expiredMutable.tryEmit(Unit)
    }

    fun observe(
        owner: LifecycleOwner,
        activity: Activity
    ) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                expired.collect {
                    promptReauth(activity)
                }
            }
        }
    }

    fun promptReauth(activity: Activity) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.splitwise_session_expired)
            .setMessage(R.string.splitwise_session_expired_message)
            .setPositiveButton(R.string.splitwise_sign_in) { _, _ ->
                val auth = SplitwiseAuthStore(activity).load()
                val oauthMode = auth.resolvedAuthMethod == SplitwiseAuthMethod.OAUTH &&
                    auth.isOAuthConfigured
                SplitwiseWebAuthActivity.start(activity, oauthMode = oauthMode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun isUnauthorized(error: Throwable?): Boolean =
        error is SplitwiseUnauthorizedException ||
            error?.message?.contains("HTTP 401") == true
}
