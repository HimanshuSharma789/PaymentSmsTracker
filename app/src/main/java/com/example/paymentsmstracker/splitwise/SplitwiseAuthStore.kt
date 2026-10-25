package com.example.paymentsmstracker.splitwise

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class SplitwiseAuthStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secure: SharedPreferences = createSecurePrefs(appContext)

    init {
        migrateLegacySecrets()
    }

    fun load(): SplitwiseAuthState {
        val groupsJson = prefs.getString(KEY_GROUPS, null)
        val groups = if (groupsJson.isNullOrBlank()) emptyList() else parseGroups(groupsJson)
        return SplitwiseAuthState(
            consumerKey = secret(KEY_CONSUMER_KEY),
            consumerSecret = secret(KEY_CONSUMER_SECRET),
            accessToken = secret(KEY_ACCESS_TOKEN),
            currentUserId = prefs.getLong(KEY_USER_ID, 0L),
            currentUserName = prefs.getString(KEY_USER_NAME, "").orEmpty(),
            groups = groups,
            defaultGroupId = prefs.getLong(KEY_DEFAULT_GROUP_ID, 0L),
            sessionCookie = secret(KEY_SESSION_COOKIE),
            userCredentialsCookie = secret(KEY_USER_CREDENTIALS),
            csrfToken = secret(KEY_CSRF_TOKEN),
            authMethod = parseAuthMethod(prefs.getString(KEY_AUTH_METHOD, null))
        )
    }

    fun saveCredentials(consumerKey: String, consumerSecret: String) {
        secure.edit()
            .putString(KEY_CONSUMER_KEY, consumerKey.trim())
            .putString(KEY_CONSUMER_SECRET, consumerSecret.trim())
            .apply()
    }

    fun saveAccessToken(token: String, method: SplitwiseAuthMethod = SplitwiseAuthMethod.TOKEN) {
        secure.edit().putString(KEY_ACCESS_TOKEN, token.trim()).apply()
        saveAuthMethod(method)
    }

    fun saveAuthMethod(method: SplitwiseAuthMethod) {
        prefs.edit().putString(KEY_AUTH_METHOD, method.name).apply()
    }

    fun saveWebSession(
        sessionCookie: String,
        userCredentialsCookie: String,
        csrfToken: String = load().csrfToken
    ) {
        secure.edit()
            .putString(KEY_SESSION_COOKIE, sessionCookie.trim())
            .putString(KEY_USER_CREDENTIALS, userCredentialsCookie.trim())
            .putString(KEY_CSRF_TOKEN, csrfToken.trim())
            .apply()
        saveAuthMethod(SplitwiseAuthMethod.SESSION)
    }

    fun saveCsrfToken(token: String) {
        secure.edit().putString(KEY_CSRF_TOKEN, token.trim()).apply()
    }

    fun saveCurrentUser(userId: Long, name: String) {
        prefs.edit()
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_USER_NAME, name)
            .apply()
    }

    fun saveGroups(groups: List<SplitwiseGroup>) {
        prefs.edit().putString(KEY_GROUPS, encodeGroups(groups)).apply()
    }

    fun saveDefaultGroupId(groupId: Long) {
        prefs.edit().putLong(KEY_DEFAULT_GROUP_ID, groupId).apply()
    }

    fun saveOAuthState(state: String) {
        secure.edit().putString(KEY_OAUTH_STATE, state).apply()
    }

    fun consumeOAuthState(): String? {
        val state = secret(KEY_OAUTH_STATE).ifBlank { null }
        secure.edit().remove(KEY_OAUTH_STATE).apply()
        return state
    }

    /**
     * Drops access tokens and web cookies after HTTP 401. Keeps OAuth app
     * credentials, and cached groups for a faster re-login.
     */
    fun clearExpiredSession() {
        secure.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_SESSION_COOKIE)
            .remove(KEY_USER_CREDENTIALS)
            .remove(KEY_CSRF_TOKEN)
            .remove(KEY_OAUTH_STATE)
            .apply()
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_AUTH_METHOD)
            .apply()
        clearWebViewCookies()
    }

    /** Drops only the stored web session (cookies + CSRF); keeps OAuth app credentials and tokens. */
    fun clearWebSession() {
        secure.edit()
            .remove(KEY_SESSION_COOKIE)
            .remove(KEY_USER_CREDENTIALS)
            .remove(KEY_CSRF_TOKEN)
            .apply()
    }

    fun signOut() {
        clearExpiredSession()
        prefs.edit()
            .remove(KEY_GROUPS)
            .apply()
    }

    fun clearAll() {
        secure.edit().clear().apply()
        prefs.edit().clear().apply()
        clearWebViewCookies()
    }

    private fun secret(key: String): String = secure.getString(key, "").orEmpty()

    private fun migrateLegacySecrets() {
        val legacyToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val legacyKey = prefs.getString(KEY_CONSUMER_KEY, null)
        val legacySecret = prefs.getString(KEY_CONSUMER_SECRET, null)
        if (legacyToken.isNullOrBlank() && legacyKey.isNullOrBlank() && legacySecret.isNullOrBlank()) {
            return
        }
        val editor = secure.edit()
        if (secret(KEY_ACCESS_TOKEN).isBlank() && !legacyToken.isNullOrBlank()) {
            editor.putString(KEY_ACCESS_TOKEN, legacyToken)
        }
        if (secret(KEY_CONSUMER_KEY).isBlank() && !legacyKey.isNullOrBlank()) {
            editor.putString(KEY_CONSUMER_KEY, legacyKey)
        }
        if (secret(KEY_CONSUMER_SECRET).isBlank() && !legacySecret.isNullOrBlank()) {
            editor.putString(KEY_CONSUMER_SECRET, legacySecret)
        }
        val legacyState = prefs.getString(KEY_OAUTH_STATE, null)
        if (secret(KEY_OAUTH_STATE).isBlank() && !legacyState.isNullOrBlank()) {
            editor.putString(KEY_OAUTH_STATE, legacyState)
        }
        editor.apply()
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_CONSUMER_KEY)
            .remove(KEY_CONSUMER_SECRET)
            .remove(KEY_OAUTH_STATE)
            .apply()
    }

    companion object {
        /** Fails closed: with no saved state this app never started the sign-in, so a code
         *  arriving on the exported deep link may be an attacker's (login CSRF). */
        fun isValidOAuthState(expected: String?, received: String?): Boolean =
            !expected.isNullOrBlank() && expected == received

        const val REDIRECT_URI = "paymentsmstracker://oauth/splitwise"
        private const val TAG = "SplitwiseAuthStore"
        private const val PREFS_NAME = "splitwise_auth"
        private const val PREFS_SECURE = "splitwise_auth_secure"
        private const val KEY_CONSUMER_KEY = "consumer_key"
        private const val KEY_CONSUMER_SECRET = "consumer_secret"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_GROUPS = "groups_json"
        private const val KEY_DEFAULT_GROUP_ID = "default_group_id"
        private const val KEY_OAUTH_STATE = "oauth_state"
        private const val KEY_SESSION_COOKIE = "cookie_splitwise_session"
        private const val KEY_USER_CREDENTIALS = "cookie_user_credentials"
        private const val KEY_CSRF_TOKEN = "csrf_token"
        private const val KEY_AUTH_METHOD = "auth_method"

        private fun parseAuthMethod(raw: String?): SplitwiseAuthMethod {
            if (raw.isNullOrBlank()) return SplitwiseAuthMethod.NONE
            return runCatching { SplitwiseAuthMethod.valueOf(raw) }
                .getOrDefault(SplitwiseAuthMethod.NONE)
        }

        /** True once a secrets store had to fall back to unencrypted prefs; Admin warns about it. */
        @Volatile
        var plaintextFallbackActive = false
            private set

        fun createSecurePrefs(context: Context, name: String = PREFS_SECURE): SharedPreferences {
            fun open(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
            return try {
                open()
            } catch (first: Exception) {
                // Usual cause: the file's keyset no longer matches the Keystore key (restore,
                // key invalidation). The secrets are re-enterable, so reset the file and retry.
                Log.e(TAG, "Encrypted prefs $name unreadable; resetting", first)
                context.deleteSharedPreferences(name)
                try {
                    open()
                } catch (e: Exception) {
                    // Separate file name, so a later successful encrypted open never reads plaintext.
                    Log.e(TAG, "Encrypted prefs unavailable; using private plaintext prefs", e)
                    plaintextFallbackActive = true
                    context.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
                }
            }
        }

        fun clearWebViewCookies() {
            try {
                val manager = CookieManager.getInstance()
                manager.removeAllCookies(null)
                manager.flush()
            } catch (_: Exception) {
                // CookieManager is unavailable in some JVM unit tests.
            }
        }

        fun encodeGroups(groups: List<SplitwiseGroup>): String {
            val arr = JSONArray()
            groups.forEach { g ->
                val members = JSONArray()
                g.members.forEach { m ->
                    members.put(
                        JSONObject()
                            .put("id", m.id)
                            .put("firstName", m.firstName)
                            .put("lastName", m.lastName)
                            .put("email", m.email)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", g.id)
                        .put("name", g.name)
                        .put("members", members)
                )
            }
            return arr.toString()
        }

        fun parseGroups(raw: String): List<SplitwiseGroup> {
            val arr = JSONArray(raw)
            return buildList {
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONObject(i)
                    val membersArr = g.optJSONArray("members") ?: JSONArray()
                    val members = buildList {
                        for (j in 0 until membersArr.length()) {
                            val m = membersArr.getJSONObject(j)
                            add(
                                SplitwiseUser(
                                    id = m.optLong("id"),
                                    firstName = m.optString("firstName"),
                                    lastName = m.optString("lastName"),
                                    email = m.optString("email")
                                )
                            )
                        }
                    }
                    add(
                        SplitwiseGroup(
                            id = g.optLong("id"),
                            name = g.optString("name"),
                            members = members
                        )
                    )
                }
            }
        }
    }
}
