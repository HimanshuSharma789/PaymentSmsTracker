package com.example.paymentsmstracker

import com.example.paymentsmstracker.splitwise.SplitwiseAuthHeaders
import com.example.paymentsmstracker.splitwise.SplitwiseAuthInterceptor
import com.example.paymentsmstracker.splitwise.SplitwiseAuthState
import com.example.paymentsmstracker.splitwise.SplitwiseUnauthorizedInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SplitwiseInterceptorTest {

    @Test
    fun authInterceptorAttachesBearerCookieAndCsrf() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{}"))
            val state = SplitwiseAuthState(
                accessToken = "tok-1",
                sessionCookie = "sess-1",
                userCredentialsCookie = "cred-1",
                csrfToken = "csrf-1"
            )
            val client = OkHttpClient.Builder()
                .addInterceptor(SplitwiseAuthInterceptor({ state }, isTargetHost = { true }))
                .build()
            client.newCall(Request.Builder().url(server.url("/create_expense")).build()).execute()
            val recorded = server.takeRequest()
            assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
            assertEquals(
                "_splitwise_session=sess-1; user_credentials=cred-1",
                recorded.getHeader("Cookie")
            )
            assertEquals("csrf-1", recorded.getHeader("X-CSRF-Token"))
            assertEquals("application/json", recorded.getHeader("Accept"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unauthorizedInterceptorClearsOn401() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"errors\":{}}"))
            val cleared = AtomicBoolean(false)
            val client = OkHttpClient.Builder()
                .addInterceptor(
                    SplitwiseUnauthorizedInterceptor(
                        onUnauthorized = { cleared.set(true) },
                        shouldIgnore = { false },
                        isTargetHost = { true }
                    )
                )
                .build()
            val response = client.newCall(
                Request.Builder().url(server.url("/api/v3.0/get_current_user")).build()
            ).execute()
            assertEquals(401, response.code)
            assertTrue(cleared.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unauthorizedInterceptorIgnoresTokenEndpoint() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val cleared = AtomicBoolean(false)
            val client = OkHttpClient.Builder()
                .addInterceptor(
                    SplitwiseUnauthorizedInterceptor(
                        onUnauthorized = { cleared.set(true) },
                        isTargetHost = { true }
                    )
                )
                .build()
            client.newCall(Request.Builder().url(server.url("/oauth/token")).build()).execute()
            assertFalse(cleared.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authInterceptorSkipsNonSplitwiseHosts() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("ok"))
            val state = SplitwiseAuthState(accessToken = "tok-1")
            val client = OkHttpClient.Builder()
                .addInterceptor(SplitwiseAuthInterceptor(loadState = { state }))
                .build()
            val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            assertEquals(200, response.code)
            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
