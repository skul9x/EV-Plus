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

    @Volatile
    private var httpCache: okhttp3.Cache? = null

    @Volatile
    private var baseClient: OkHttpClient? = null

    @Volatile
    private var testClient: OkHttpClient? = null

    /**
     * Configures and installs a persistent HTTP disk cache on the shared OkHttpClient base.
     * Can be safely executed on Dispatchers.IO without thread contention.
     */
    @Synchronized
    fun installDiskCache(cacheDir: java.io.File, maxSizeBytes: Long = 20L * 1024 * 1024) {
        if (httpCache == null) {
            try {
                val cache = okhttp3.Cache(cacheDir, maxSizeBytes)
                httpCache = cache
                val current = baseClient
                baseClient = if (current != null) {
                    current.newBuilder().cache(cache).build()
                } else {
                    OkHttpClient.Builder()
                        .connectionPool(connectionPool)
                        .dispatcher(dispatcher)
                        .cache(cache)
                        .build()
                }
            } catch (e: Throwable) {
                // Fail-safe degrade without disk cache
            }
        }
    }

    /**
     * Returns the shared base [OkHttpClient] instance (or test override if set).
     */
    fun getSharedClient(): OkHttpClient {
        return testClient ?: baseClient ?: synchronized(this) {
            baseClient ?: OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .dispatcher(dispatcher)
                .apply {
                    if (httpCache != null) {
                        cache(httpCache)
                    }
                }
                .build().also { baseClient = it }
        }
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

    /**
     * Resets any test client override and cleans up the HTTP disk cache for unit tests.
     */
    @Synchronized
    fun resetForTesting() {
        testClient = null
        runCatching { httpCache?.close() }
        httpCache = null
        baseClient = null
    }
}
