package com.example.paymentsmstracker.splitwise

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

class SplitwiseAuthInterceptor(
    private val loadState: () -> SplitwiseAuthState,
    private val isTargetHost: (HttpUrl) -> Boolean = { isSplitwiseHost(it) }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isTargetHost(original.url)) {
            return chain.proceed(original)
        }
        val builder = original.newBuilder()
            .header("Accept", "application/json")
        SplitwiseAuthHeaders.apply(builder, loadState())
        return chain.proceed(builder.build())
    }
}

/**
 * On HTTP 401, drop cached session material and notify listeners so the UI
 * can prompt for in-app re-authentication. Token exchange is excluded.
 */
class SplitwiseUnauthorizedInterceptor(
    private val onUnauthorized: () -> Unit,
    private val shouldIgnore: (HttpUrl) -> Boolean = { url ->
        url.encodedPath.contains("/oauth/token")
    },
    private val isTargetHost: (HttpUrl) -> Boolean = { isSplitwiseHost(it) }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 401 && isTargetHost(request.url) && !shouldIgnore(request.url)) {
            onUnauthorized()
        }
        return response
    }
}

internal fun isSplitwiseHost(url: HttpUrl): Boolean =
    url.host.equals("secure.splitwise.com", ignoreCase = true) ||
        url.host.equals("www.splitwise.com", ignoreCase = true)
