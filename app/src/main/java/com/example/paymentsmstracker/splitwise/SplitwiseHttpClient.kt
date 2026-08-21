package com.example.paymentsmstracker.splitwise

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object SplitwiseHttpClient {
    fun create(store: SplitwiseAuthStore): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(SplitwiseAuthInterceptor(loadState = { store.load() }))
            .addInterceptor(
                SplitwiseUnauthorizedInterceptor(
                    onUnauthorized = {
                        store.clearExpiredSession()
                        SplitwiseSessionEvents.emitExpired()
                    }
                )
            )
            .build()
}
