package com.example.paymentsmstracker.splitwise

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.paymentsmstracker.AdminActivity
import com.example.paymentsmstracker.R
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app WebView sign-in so MFA and CAPTCHA stay in the standard Splitwise
 * web flow. Session cookies and CSRF are stored in EncryptedSharedPreferences.
 */
class SplitwiseWebAuthActivity : AppCompatActivity() {

    private lateinit var store: SplitwiseAuthStore
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var completing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splitwise_web_auth)

        store = SplitwiseAuthStore(this)
        progress = findViewById(R.id.progressSplitwiseWebAuth)
        webView = findViewById(R.id.webViewSplitwiseAuth)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbarSplitwiseWebAuth)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleUrl(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUrl(Uri.parse(url))

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                harvestSession(url)
            }
        }

        val oauthMode = intent.getBooleanExtra(EXTRA_OAUTH_MODE, false)
        val startUrl = if (oauthMode && store.load().isOAuthConfigured) {
            val api = SplitwiseApi.from(store)
            val (url, state) = api.buildAuthorizeUrl()
            store.saveOAuthState(state)
            url
        } else {
            SplitwiseApi.LOGIN_URL
        }
        webView.loadUrl(startUrl)
    }

    private fun handleUrl(uri: Uri): Boolean {
        if (uri.scheme == "paymentsmstracker" && uri.host == "oauth") {
            completeOAuth(uri)
            return true
        }
        return false
    }

    private fun harvestSession(url: String) {
        if (completing) return
        val cookies = CookieManager.getInstance().getCookie("https://secure.splitwise.com").orEmpty()
        val session = SplitwiseAuthHeaders.parseNamedCookie(
            cookies,
            SplitwiseAuthHeaders.COOKIE_SESSION
        )
        val credentials = SplitwiseAuthHeaders.parseNamedCookie(
            cookies,
            SplitwiseAuthHeaders.COOKIE_USER_CREDENTIALS
        )
        if (session.isNotBlank()) {
            store.saveWebSession(session, credentials, store.load().csrfToken)
        }
        extractCsrfToken()
        val oauthMode = intent.getBooleanExtra(EXTRA_OAUTH_MODE, false)
        if (looksLoggedIn(url, session) && !oauthMode) {
            completeWithCachedSession()
        }
    }

    private fun looksLoggedIn(url: String, session: String): Boolean {
        if (session.isBlank()) return false
        val lower = url.lowercase()
        if (!lower.contains("splitwise.com")) return false
        val blocked = listOf("/login", "/oauth/authorize", "/password", "/forgot")
        return blocked.none { lower.contains(it) }
    }

    private fun extractCsrfToken() {
        val js = """
            (function() {
              var m = document.querySelector('meta[name="csrf-token"]');
              if (m && m.content) return m.content;
              var i = document.querySelector('input[name="authenticity_token"]');
              if (i && i.value) return i.value;
              return '';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val token = raw?.trim()?.removeSurrounding("\"")
                ?.replace("\\u003C", "<")
                .orEmpty()
            if (token.isNotBlank() && token != "null") {
                store.saveCsrfToken(token)
            }
        }
    }

    private fun completeOAuth(uri: Uri) {
        if (completing) return
        harvestSession(uri.toString())
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
        val expected = store.consumeOAuthState()
        if (!SplitwiseAuthStore.isValidOAuthState(expected, state)) {
            Toast.makeText(this, "Splitwise auth state mismatch — try again", Toast.LENGTH_LONG).show()
            goHome()
            return
        }
        completing = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val access = SplitwiseApi.from(store).exchangeCodeForToken(code)
                    store.saveAccessToken(access, SplitwiseAuthMethod.OAUTH)
                    SplitwiseProfileSync.refresh(this@SplitwiseWebAuthActivity, store)
                }
                Toast.makeText(this@SplitwiseWebAuthActivity, "Signed in to Splitwise", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SplitwiseWebAuthActivity,
                    "Splitwise sign-in failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            goHome()
        }
    }

    private fun completeWithCachedSession() {
        if (completing) return
        completing = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SplitwiseProfileSync.refresh(this@SplitwiseWebAuthActivity, store)
                }
                Toast.makeText(this@SplitwiseWebAuthActivity, "Signed in to Splitwise", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (store.load().isSignedIn) {
                    Toast.makeText(
                        this@SplitwiseWebAuthActivity,
                        "Signed in. Profile sync failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    completing = false
                    Toast.makeText(
                        this@SplitwiseWebAuthActivity,
                        "Splitwise sign-in failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }
            goHome()
        }
    }

    private fun goHome() {
        startActivity(
            Intent(this, AdminActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(SplitwiseOAuthActivity.EXTRA_OPEN_SPLITWISE_TAB, true)
            }
        )
        finish()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OAUTH_MODE = "oauth_mode"

        fun start(activity: Activity, oauthMode: Boolean = false) {
            activity.startActivity(
                Intent(activity, SplitwiseWebAuthActivity::class.java).apply {
                    putExtra(EXTRA_OAUTH_MODE, oauthMode)
                }
            )
        }
    }
}
