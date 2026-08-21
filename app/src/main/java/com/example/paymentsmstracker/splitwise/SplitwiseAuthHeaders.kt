package com.example.paymentsmstracker.splitwise

import okhttp3.Request

object SplitwiseAuthHeaders {
    const val COOKIE_SESSION = "_splitwise_session"
    const val COOKIE_USER_CREDENTIALS = "user_credentials"
    const val CSRF_HEADER = "X-CSRF-Token"

    fun cookieHeader(sessionCookie: String, userCredentialsCookie: String): String =
        listOfNotNull(
            sessionCookie.trim().takeIf { it.isNotEmpty() }?.let { "$COOKIE_SESSION=$it" },
            userCredentialsCookie.trim().takeIf { it.isNotEmpty() }
                ?.let { "$COOKIE_USER_CREDENTIALS=$it" }
        ).joinToString("; ")

    fun parseNamedCookie(cookieHeader: String?, name: String): String {
        if (cookieHeader.isNullOrBlank()) return ""
        val prefix = "$name="
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substring(prefix.length)
            .orEmpty()
    }

    fun apply(builder: Request.Builder, state: SplitwiseAuthState): Request.Builder {
        if (state.accessToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${state.accessToken}")
        }
        val cookie = cookieHeader(state.sessionCookie, state.userCredentialsCookie)
        if (cookie.isNotBlank()) {
            builder.header("Cookie", cookie)
        }
        if (state.csrfToken.isNotBlank()) {
            builder.header(CSRF_HEADER, state.csrfToken)
        }
        return builder
    }
}
