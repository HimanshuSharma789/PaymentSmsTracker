package com.example.paymentsmstracker.splitwise

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class SplitwiseApi(
    private val store: SplitwiseAuthStore,
    private val httpClient: OkHttpClient = SplitwiseHttpClient.create(store)
) {

    fun buildAuthorizeUrl(state: String = UUID.randomUUID().toString()): Pair<String, String> {
        val auth = store.load()
        val params = linkedMapOf(
            "client_id" to auth.consumerKey,
            "response_type" to "code",
            "redirect_uri" to SplitwiseAuthStore.REDIRECT_URI,
            "state" to state
        )
        return "${AUTHORIZE_URL}?${encodeForm(params)}" to state
    }

    fun exchangeCodeForToken(code: String): String {
        val auth = store.load()
        val body = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to auth.consumerKey,
            "client_secret" to auth.consumerSecret,
            "redirect_uri" to SplitwiseAuthStore.REDIRECT_URI,
            "code" to code
        )
        val json = postForm(TOKEN_URL, body)
        val token = json.optString("access_token")
        if (token.isBlank()) {
            throw SplitwiseApiException("No access_token in response: $json")
        }
        return token
    }

    fun getCurrentUser(): SplitwiseUser {
        requireSignedIn()
        val json = get("$API_BASE/get_current_user")
        val user = json.getJSONObject("user")
        return parseUser(user)
    }

    fun getGroups(): List<SplitwiseGroup> {
        requireSignedIn()
        val json = get("$API_BASE/get_groups")
        val arr = json.optJSONArray("groups") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val id = g.optLong("id")
                // Splitwise includes a synthetic "Non-group expenses" entry with id 0
                if (id <= 0L) continue
                val membersArr = g.optJSONArray("members") ?: org.json.JSONArray()
                val members = buildList {
                    for (j in 0 until membersArr.length()) {
                        add(parseUser(membersArr.getJSONObject(j)))
                    }
                }
                add(
                    SplitwiseGroup(
                        id = id,
                        name = g.optString("name"),
                        members = members
                    )
                )
            }
        }
    }

    /**
     * Creates an expense with explicit shares. Caller must supply one entry per participant.
     * [paidByUserId] pays the full [cost]; each user owes [owedShares] amount.
     */
    fun createExpense(
        groupId: Long,
        description: String,
        cost: String,
        currencyCode: String,
        dateMillis: Long,
        paidByUserId: Long,
        owedShares: Map<Long, String>,
        details: String? = null
    ): Long {
        requireSignedIn()
        require(groupId > 0L) { "groupId required" }
        require(owedShares.isNotEmpty()) { "owedShares required" }

        val form = linkedMapOf(
            "group_id" to groupId.toString(),
            "description" to description,
            "cost" to cost,
            "currency_code" to currencyCode,
            "date" to isoDate(dateMillis)
        )
        if (!details.isNullOrBlank()) {
            form["details"] = details
        }

        var index = 0
        owedShares.forEach { (userId, owed) ->
            form["users__${index}__user_id"] = userId.toString()
            form["users__${index}__paid_share"] =
                if (userId == paidByUserId) cost else "0.00"
            form["users__${index}__owed_share"] = owed
            index++
        }

        val json = postForm("$API_BASE/create_expense", form)
        val errors = json.optJSONObject("errors")
        if (errors != null && errors.length() > 0) {
            throw SplitwiseApiException(formatErrors(errors))
        }
        val expenses = json.optJSONArray("expenses")
        if (expenses == null || expenses.length() == 0) {
            throw SplitwiseApiException("create_expense returned no expense: $json")
        }
        return expenses.getJSONObject(0).optLong("id")
    }

    private fun requireSignedIn() {
        if (!store.load().isSignedIn) throw SplitwiseApiException("Not signed in to Splitwise")
    }

    private fun parseUser(obj: JSONObject): SplitwiseUser =
        SplitwiseUser(
            id = obj.optLong("id"),
            firstName = obj.optString("first_name"),
            lastName = obj.optString("last_name"),
            email = obj.optString("email")
        )

    private fun get(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        return execute(request)
    }

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = encodeForm(form).toRequestBody(FORM_MEDIA)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            val code = response.code
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) {
                if (code == 401) throw SplitwiseUnauthorizedException("HTTP 401 empty body")
                throw SplitwiseApiException("HTTP $code empty body")
            }
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                if (code == 401) throw SplitwiseUnauthorizedException("HTTP 401 non-JSON")
                throw SplitwiseApiException("HTTP $code non-JSON: ${text.take(200)}")
            }
            if (code == 401) {
                val errors = json.optJSONObject("errors")
                val message = if (errors != null) formatErrors(errors) else json.toString()
                throw SplitwiseUnauthorizedException("HTTP 401: $message")
            }
            if (code !in 200..299) {
                val errors = json.optJSONObject("errors")
                val message = if (errors != null) formatErrors(errors) else json.toString()
                throw SplitwiseApiException("HTTP $code: $message")
            }
            return json
        }
    }

    private fun formatErrors(errors: JSONObject): String {
        val parts = mutableListOf<String>()
        errors.keys().forEach { key ->
            val value = errors.opt(key)
            parts.add(
                when (value) {
                    is org.json.JSONArray -> {
                        buildList {
                            for (i in 0 until value.length()) add(value.optString(i))
                        }.joinToString("; ").ifBlank { key }
                    }
                    else -> "$key: $value"
                }
            )
        }
        return parts.joinToString(" | ").ifBlank { errors.toString() }
    }

    private fun encodeForm(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun isoDate(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    companion object {
        private const val AUTHORIZE_URL = "https://secure.splitwise.com/oauth/authorize"
        private const val TOKEN_URL = "https://secure.splitwise.com/oauth/token"
        private const val API_BASE = "https://secure.splitwise.com/api/v3.0"
        private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

        const val LOGIN_URL = "https://secure.splitwise.com/login"

        fun from(store: SplitwiseAuthStore): SplitwiseApi = SplitwiseApi(store)
    }
}

open class SplitwiseApiException(message: String) : Exception(message)

class SplitwiseUnauthorizedException(message: String = "Splitwise session expired") :
    SplitwiseApiException(message)

object SplitwiseProfileSync {
    fun refresh(context: Context, store: SplitwiseAuthStore) {
        val api = SplitwiseApi.from(store)
        val user = api.getCurrentUser()
        store.saveCurrentUser(user.id, user.displayName)
        val groups = api.getGroups()
        store.saveGroups(groups)
    }
}
