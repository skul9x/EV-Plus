package com.evcs.favorites.data.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Centralized OkHttpClient and ConnectionPool provider.
 *
 * Establishes a unified connection pool and worker dispatcher to prevent
 * socket exhaustion, duplicate TLS handshakes, and thread pool proliferation
 * across independent network clients.
 */
object AppOkHttpClientProvider {

    val connectionPool: ConnectionPool = ConnectionPool(
        maxIdleConnections = 10,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    val dispatcher: Dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 10
    }

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .build()
    }

    @Volatile
    private var testClient: OkHttpClient? = null

    /**
     * Returns the shared base [OkHttpClient] instance (or test override if set).
     */
    fun getSharedClient(): OkHttpClient {
        return testClient ?: baseClient
    }

    /**
     * Returns a new [OkHttpClient.Builder] derived from the shared base client,
     * preserving the shared [ConnectionPool] and [Dispatcher].
     */
    fun newSharedClientBuilder(): OkHttpClient.Builder {
        return getSharedClient().newBuilder()
    }

    /**
     * Injects a test client override for unit tests.
     */
    fun setTestClient(client: OkHttpClient?) {
        testClient = client
    }

    /**
     * Resets any test client override.
     */
    fun reset() {
        testClient = null
    }
}
