package com.example.paymentsmstracker.splitwise

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles OAuth redirect: paymentsmstracker://oauth/splitwise?code=...&state=...
 * when the system delivers the custom-scheme callback outside the in-app WebView.
 */
class SplitwiseOAuthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Toast.makeText(this, "Splitwise auth failed: $error", Toast.LENGTH_LONG).show()
            goHome()
            return
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Splitwise auth missing code", Toast.LENGTH_LONG).show()
            goHome()
            return
        }

        val store = SplitwiseAuthStore(this)
        val expected = store.consumeOAuthState()
        if (!SplitwiseAuthStore.isValidOAuthState(expected, state)) {
            Toast.makeText(this, "Splitwise auth state mismatch — try again", Toast.LENGTH_LONG).show()
            goHome()
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val access = SplitwiseApi.from(store).exchangeCodeForToken(code)
                    store.saveAccessToken(access, SplitwiseAuthMethod.OAUTH)
                    SplitwiseProfileSync.refresh(this@SplitwiseOAuthActivity, store)
                }
                Toast.makeText(
                    this@SplitwiseOAuthActivity,
                    "Signed in to Splitwise",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SplitwiseOAuthActivity,
                    "Splitwise sign-in failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            goHome()
        }
    }

    private fun goHome() {
        startActivity(
            Intent(this, com.example.paymentsmstracker.AdminActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_SPLITWISE_TAB, true)
            }
        )
        finish()
    }

    companion object {
        const val EXTRA_OPEN_SPLITWISE_TAB = "open_splitwise_tab"
    }
}
