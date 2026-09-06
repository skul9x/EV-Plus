package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.logging.DebugLoggingInterceptor
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.routing.GoogleRoutesClient
import com.evcs.favorites.data.routing.OsrmRoutingClient
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Phase 04 Comprehensive Test:
 * 1. AppOkHttpClientProvider provides clients sharing the same ConnectionPool and Dispatcher.
 * 2. Derived clients (EvcsApiClient, AuthEngine, OsrmRoutingClient, GoogleRoutesClient, RoutingPreferencesManager)
 *    retain customized timeouts and interceptors while sharing socket pools and dispatchers.
 * 3. DebugLoggingInterceptor in enabled mode captures request/response snippets into AppDebugLogger.
 * 4. DebugLoggingInterceptor in disabled mode cleanly bypasses body buffering and logging overhead.
 * 5. Backward compatibility with custom OkHttpClient injection is fully preserved.
 */
class SharedOkHttpPoolAndLoggingInterceptorTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        AppDebugLogger.clear()
        AppOkHttpClientProvider.reset()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        AppDebugLogger.clear()
        AppOkHttpClientProvider.reset()
    }

    private fun getPrivateField(target: Any, fieldName: String): Any {
        var clazz: Class<*>? = target::class.java
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(target)!!
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field '$fieldName' not found on ${target::class.java.name}")
    }

    @Test
    fun testAppOkHttpClientProvider_sharedPoolAndDispatcher() {
        val sharedClient = AppOkHttpClientProvider.getSharedClient()
        val derivedClient1 = AppOkHttpClientProvider.newSharedClientBuilder().build()
        val derivedClient2 = AppOkHttpClientProvider.newSharedClientBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()

        // 1. Both share the singleton ConnectionPool and Dispatcher
        assertSame(AppOkHttpClientProvider.connectionPool, sharedClient.connectionPool)
        assertSame(AppOkHttpClientProvider.dispatcher, sharedClient.dispatcher)

        assertSame(AppOkHttpClientProvider.connectionPool, derivedClient1.connectionPool)
        assertSame(AppOkHttpClientProvider.dispatcher, derivedClient1.dispatcher)

        assertSame(AppOkHttpClientProvider.connectionPool, derivedClient2.connectionPool)
        assertSame(AppOkHttpClientProvider.dispatcher, derivedClient2.dispatcher)

        // 2. Custom override for testing
        val customTestClient = OkHttpClient.Builder().build()
        AppOkHttpClientProvider.setTestClient(customTestClient)
        assertSame(customTestClient, AppOkHttpClientProvider.getSharedClient())

        // 3. Reset restores shared singleton client
        AppOkHttpClientProvider.reset()
        assertSame(sharedClient, AppOkHttpClientProvider.getSharedClient())
    }

    @Test
    fun testDerivedClients_shareConnectionPoolAndDispatcherWhileRetainingCustomizations() {
        val evcsApiClient = EvcsApiClient(sessionManager = sessionManager)
        val authEngine = AuthEngine(sessionManager = sessionManager)
        val osrmClient = OsrmRoutingClient()
        val googleRoutesClient = GoogleRoutesClient()
        val routingPrefsManager = RoutingPreferencesManager(storage = sessionStorage)

        val evcsOkHttp = getPrivateField(evcsApiClient, "client") as OkHttpClient
        val authOkHttp = getPrivateField(authEngine, "client") as OkHttpClient
        val osrmOkHttp = getPrivateField(osrmClient, "okHttpClient") as OkHttpClient
        val googleOkHttp = getPrivateField(googleRoutesClient, "okHttpClient") as OkHttpClient
        val prefsOkHttp = getPrivateField(routingPrefsManager, "okHttpClient") as OkHttpClient

        val expectedPool = AppOkHttpClientProvider.connectionPool
        val expectedDispatcher = AppOkHttpClientProvider.dispatcher

        // Verify all 5 clients share the exact same ConnectionPool
        assertSame("EvcsApiClient must share ConnectionPool", expectedPool, evcsOkHttp.connectionPool)
        assertSame("AuthEngine must share ConnectionPool", expectedPool, authOkHttp.connectionPool)
        assertSame("OsrmRoutingClient must share ConnectionPool", expectedPool, osrmOkHttp.connectionPool)
        assertSame("GoogleRoutesClient must share ConnectionPool", expectedPool, googleOkHttp.connectionPool)
        assertSame("RoutingPreferencesManager must share ConnectionPool", expectedPool, prefsOkHttp.connectionPool)

        // Verify all 5 clients share the exact same Dispatcher
        assertSame("EvcsApiClient must share Dispatcher", expectedDispatcher, evcsOkHttp.dispatcher)
        assertSame("AuthEngine must share Dispatcher", expectedDispatcher, authOkHttp.dispatcher)
        assertSame("OsrmRoutingClient must share Dispatcher", expectedDispatcher, osrmOkHttp.dispatcher)
        assertSame("GoogleRoutesClient must share Dispatcher", expectedDispatcher, googleOkHttp.dispatcher)
        assertSame("RoutingPreferencesManager must share Dispatcher", expectedDispatcher, prefsOkHttp.dispatcher)

        // Verify customizations are preserved
        assertEquals(15_000, evcsOkHttp.connectTimeoutMillis)
        assertEquals(15_000, evcsOkHttp.readTimeoutMillis)
        assertTrue(evcsOkHttp.followRedirects)
        assertTrue(evcsOkHttp.interceptors.any { it is DebugLoggingInterceptor })

        assertEquals(15_000, authOkHttp.connectTimeoutMillis)
        assertEquals(15_000, authOkHttp.readTimeoutMillis)
        assertTrue(authOkHttp.followRedirects)

        assertEquals(15_000, osrmOkHttp.connectTimeoutMillis)
        assertEquals(15_000, osrmOkHttp.readTimeoutMillis)
        assertTrue(osrmOkHttp.interceptors.any { it is DebugLoggingInterceptor })

        // Verify backward compatibility: custom OkHttpClient injection
        val customClient = OkHttpClient.Builder().build()
        val customEvcs = EvcsApiClient(sessionManager = sessionManager, client = customClient)
        val customAuth = AuthEngine(sessionManager = sessionManager, client = customClient)
        val customOsrm = OsrmRoutingClient(okHttpClient = customClient)
        val customGoogle = GoogleRoutesClient(okHttpClient = customClient)
        val customPrefs = RoutingPreferencesManager(storage = sessionStorage, okHttpClient = customClient)

        assertSame(customClient, getPrivateField(customEvcs, "client"))
        assertSame(customClient, getPrivateField(customAuth, "client"))
        assertSame(customClient, getPrivateField(customOsrm, "okHttpClient"))
        assertSame(customClient, getPrivateField(customGoogle, "okHttpClient"))
        assertSame(customClient, getPrivateField(customPrefs, "okHttpClient"))
    }

    @Test
    fun testDebugLoggingInterceptor_enabledModeCapturesSnippets() {
        val loggingInterceptor = DebugLoggingInterceptor(enabled = true)
        val client = AppOkHttpClientProvider.newSharedClientBuilder()
            .addInterceptor(loggingInterceptor)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","stations_count":42}""")
        )

        val requestUrl = mockServer.url("/search?t=eepe5dp9zpipl102").toString()
        val requestBody = """{"latitude":21.0285,"longitude":105.8542}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertEquals("""{"status":"ok","stations_count":42}""", response.body?.string())
        }

        val logs = AppDebugLogger.getLogs()
        assertEquals(1, logs.size)
        val entry = logs.first()
        assertEquals(DebugLogTag.SEARCH, entry.tag)
        assertEquals(200, entry.statusCode)
        assertNotNull(entry.requestSnippet)
        assertTrue("Request snippet must contain coordinates", entry.requestSnippet!!.contains("21.0285"))
        assertNotNull(entry.responseSnippet)
        assertTrue("Response snippet must contain stations_count", entry.responseSnippet!!.contains("stations_count"))
    }

    @Test
    fun testDebugLoggingInterceptor_disabledModeBypassesBuffering() {
        val loggingInterceptor = DebugLoggingInterceptor(enabled = false)
        val client = AppOkHttpClientProvider.newSharedClientBuilder()
            .addInterceptor(loggingInterceptor)
            .build()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"bypassed_body"}""")
        )

        val requestUrl = mockServer.url("/search?t=eepe5dp9zpipl102").toString()
        val requestBody = """{"latitude":21.0285,"longitude":105.8542}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            // Verify body stream remains fully readable and untouched
            assertEquals("""{"status":"bypassed_body"}""", response.body?.string())
        }

        // Verify no logs were captured when disabled
        assertTrue("No logs should be recorded when interceptor is disabled", AppDebugLogger.getLogs().isEmpty())

        // Dynamic toggle test: enable interceptor and ensure logging resumes
        loggingInterceptor.enabled = true
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"now_logged"}""")
        )

        client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertEquals("""{"status":"now_logged"}""", response.body?.string())
        }

        val logsAfterEnable = AppDebugLogger.getLogs()
        assertEquals(1, logsAfterEnable.size)
        assertTrue(logsAfterEnable.first().responseSnippet?.contains("now_logged") == true)
    }
}
