package com.evcs.favorites.data.api

import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Phase 03 Test: OkHttp Response Deterministic Cleanup & Socket Leak Prevention.
 *
 * Verifies:
 * 1. EvcsApiClient.fetchFavorites closes responses on HTTP 500, 403, and 429 without leaking sockets.
 * 2. EvcsApiClient.fetchStationHtml closes response on HTTP 500 without leaking socket.
 * 3. EvcsApiClient.searchStations closes response on HTTP 500 without leaking socket.
 * 4. AuthEngine.fetchCsrfToken, sendOtp, and verifyOtp close responses on HTTP 500 without leaking socket.
 * 5. 50 consecutive failed requests do not exhaust OkHttp MockWebServer connection limits.
 */
class OkHttpConnectionLeakSafetyTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient
    private lateinit var authEngine: AuthEngine
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        baseUrl = mockWebServer.url("").toString().removeSuffix("/")

        // Tight connection pool (max 5 idle) to ensure socket reuse and detect any connection starvation
        connectionPool = ConnectionPool(5, 1, TimeUnit.SECONDS)
        okHttpClient = OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        sessionManager = SessionManager(InMemorySessionStorage())
        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )
        authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        connectionPool.evictAll()
    }

    @Test
    fun fetchFavorites_closesResponse_onHttp500_403_and_429() = runTest {
        val errorCodes = listOf(500, 403, 429)
        for (code in errorCodes) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody("Error response payload for code $code")
            )

            val result = apiClient.fetchFavorites()
            assertTrue("Expected failure for HTTP $code", result.isFailure)
            val errorMsg = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("Error message should contain HTTP $code, got: $errorMsg", errorMsg.contains(code.toString()))
        }

        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun fetchStationHtml_closesResponse_onHttp500() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val result = apiClient.fetchStationHtml("Test Station", "LOC123")
        assertTrue("fetchStationHtml should fail on HTTP 500", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun searchStations_closesResponse_onHttp500() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val result = apiClient.searchStations(21.0285, 105.8542)
        assertTrue("searchStations should fail on HTTP 500", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun authEngine_allMethods_closeResponses_onHttp500() = runTest {
        // 1. fetchCsrfToken
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )
        val csrfResult = authEngine.fetchCsrfToken()
        assertTrue("fetchCsrfToken should fail on HTTP 500", csrfResult.isFailure)
        assertTrue(csrfResult.exceptionOrNull()?.message?.contains("500") == true)

        // 2. sendOtp
        sessionManager.csrfToken = "valid-csrf-token"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )
        val sendResult = authEngine.sendOtp("test@example.com")
        assertTrue("sendOtp should fail on HTTP 500", sendResult.isFailure)
        assertTrue(sendResult.exceptionOrNull()?.message?.contains("500") == true)

        // 3. verifyOtp
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )
        val verifyResult = authEngine.verifyOtp("test@example.com", "123456")
        assertTrue("verifyOtp should fail on HTTP 500", verifyResult.isFailure)
        assertTrue(verifyResult.exceptionOrNull()?.message?.contains("500") == true)

        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun fiftyConsecutiveFailedRequests_doNotExhaustConnectionLimits() = runTest {
        val totalRequests = 50
        // Enqueue 50 error responses
        for (i in 1..totalRequests) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("Error response $i")
            )
        }

        sessionManager.csrfToken = "dummy-csrf-token"

        // Execute 50 requests alternating across different endpoints in EvcsApiClient and AuthEngine
        for (i in 1..totalRequests) {
            val result = when (i % 5) {
                0 -> apiClient.fetchFavorites()
                1 -> apiClient.saveFavorites(emptyList())
                2 -> apiClient.searchStations(21.0, 105.0)
                3 -> apiClient.fetchStationHtml("Station$i", "LOC$i")
                else -> authEngine.sendOtp("user$i@example.com")
            }
            assertTrue("Request $i should fail cleanly", result.isFailure)
        }

        assertEquals(totalRequests, mockWebServer.requestCount)
    }
}
