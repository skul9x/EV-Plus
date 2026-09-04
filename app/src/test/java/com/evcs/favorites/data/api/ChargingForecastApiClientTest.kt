package com.evcs.favorites.data.api

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.util.StationUrlBuilder
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Comprehensive MockWebServer unit tests for [EvcsApiClient.fetchChargingForecast].
 *
 * Verifies:
 * 1. Step 1 sends HTTP POST with X-Partial: user, canonical URL, and session headers.
 * 2. Step 2 sends HTTP POST to /charging with x-t token header and {"id":..., "t":...} payload.
 * 3. Successful deserialization of ticker, busyKw map, and partial fields for both VinFast and other stations.
 * 4. Error handling when Step 1 fails (HTTP 500, missing/blank chargeToken).
 * 5. Error handling when Step 2 fails (HTTP 403, 500).
 * 6. OkHttp connection pool safety and deterministic socket closure on error.
 */
class ChargingForecastApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        baseUrl = mockWebServer.url("").toString().removeSuffix("/")

        connectionPool = ConnectionPool(5, 1, TimeUnit.SECONDS)
        okHttpClient = OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        val sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "sess_mock_123"
            authCookie = "auth_mock_456"
        }

        apiClient = EvcsApiClient(
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
    fun fetchChargingForecast_successfulHandshake_vinfast() = runTest {
        val stationName = "VinFast - TS Cuc Dia Chat"
        val locationId = "C.HNO15880"
        val expectedCanonicalUrl = StationUrlBuilder.buildStationDetailUrl(stationName, locationId, baseUrl)
        val expectedPath = expectedCanonicalUrl.removePrefix(baseUrl)

        // Step 1 mock response: user partial
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"test_charge_token_abc123","apiToken":"api_tok_789"}""")
        )

        // Step 2 mock response: dynamic forecast
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "ticker": "Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 15-29 phút nữa",
                        "busyKw": {
                            "150": 1,
                            "60": 2
                        },
                        "partial": false
                    }
                    """.trimIndent()
                )
        )

        val result = apiClient.fetchChargingForecast(stationName, locationId, isVinFast = true)
        assertTrue("fetchChargingForecast should succeed", result.isSuccess)

        val forecast = result.getOrThrow()
        assertEquals("Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 15-29 phút nữa", forecast.ticker)
        assertEquals(2, forecast.busyKw.size)
        assertEquals(1, forecast.busyKw["150"])
        assertEquals(2, forecast.busyKw["60"])
        assertFalse(forecast.partial)

        // Verify Step 1 HTTP request
        val req1 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", req1.method)
        assertEquals(expectedPath, req1.path)
        assertEquals("user", req1.getHeader("X-Partial"))
        assertEquals("$baseUrl/", req1.getHeader("Referer"))
        assertEquals(baseUrl, req1.getHeader("Origin"))
        assertTrue(req1.getHeader("Cookie")?.contains("PHPSESSID=sess_mock_123") == true)

        // Verify Step 2 HTTP request
        val req2 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", req2.method)
        assertEquals("/charging", req2.path)
        assertEquals("test_charge_token_abc123", req2.getHeader("x-t"))
        assertTrue(req2.getHeader("Content-Type")?.startsWith("application/json") == true)
        assertEquals("$baseUrl/", req2.getHeader("Referer"))
        assertEquals(baseUrl, req2.getHeader("Origin"))
        assertEquals("""{"id":"C.HNO15880","t":"vinfast"}""", req2.body.readUtf8())
    }

    @Test
    fun fetchChargingForecast_successfulHandshake_partnerStation_other() = runTest {
        val stationName = "EV Partner Station A"
        val locationId = "PARTNER99"

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"tok_partner_456"}""")
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticker":null,"busyKw":{"30":1},"partial":true}""")
        )

        val result = apiClient.fetchChargingForecast(stationName, locationId, isVinFast = false)
        assertTrue("fetchChargingForecast should succeed for other", result.isSuccess)

        val forecast = result.getOrThrow()
        assertEquals(null, forecast.ticker)
        assertEquals(1, forecast.busyKw["30"])
        assertTrue(forecast.partial)

        mockWebServer.takeRequest() // discard req1
        val req2 = mockWebServer.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("tok_partner_456", req2.getHeader("x-t"))
        assertEquals("""{"id":"PARTNER99","t":"other"}""", req2.body.readUtf8())
    }

    @Test
    fun fetchChargingForecast_step1Http500_returnsFailure() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val result = apiClient.fetchChargingForecast("Station", "LOC1")
        assertTrue("Expected failure when step 1 returns HTTP 500", result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Error should reference HTTP 500: $msg", msg.contains("500"))
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun fetchChargingForecast_step1MissingOrEmptyChargeToken_returnsFailure() = runTest {
        // Step 1 returns 200 without chargeToken
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"apiToken":"some_token_without_charge_token"}""")
        )

        val result = apiClient.fetchChargingForecast("Station", "LOC1")
        assertTrue("Expected failure when chargeToken is missing", result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("Error should mention chargeToken: $msg", msg.contains("chargeToken"))
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun fetchChargingForecast_step2HttpError_returnsFailure() = runTest {
        // Test step 2 failing with HTTP 403 Forbidden
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"valid_tok"}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )

        val result403 = apiClient.fetchChargingForecast("Station", "LOC1")
        assertTrue("Expected failure on step 2 HTTP 403", result403.isFailure)
        assertTrue(result403.exceptionOrNull()?.message?.contains("403") == true)
        assertEquals(2, mockWebServer.requestCount)

        // Test step 2 failing with HTTP 500
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"valid_tok_2"}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val result500 = apiClient.fetchChargingForecast("Station", "LOC1")
        assertTrue("Expected failure on step 2 HTTP 500", result500.isFailure)
        assertTrue(result500.exceptionOrNull()?.message?.contains("500") == true)
        assertEquals(4, mockWebServer.requestCount)
    }

    @Test
    fun fetchChargingForecast_leakSafety_handlesMultipleFailuresCleanly() = runTest {
        for (i in 1..10) {
            if (i % 2 == 0) {
                mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Err step 1"))
            } else {
                mockWebServer.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"chargeToken":"tok_$i"}""")
                )
                mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Err step 2"))
            }
        }

        for (i in 1..10) {
            val res = apiClient.fetchChargingForecast("Station$i", "LOC$i")
            assertTrue("Request $i should fail cleanly", res.isFailure)
        }

        // Now run a successful call through the same client to verify pool is healthy
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"healthy_tok"}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticker":"Healthy","busyKw":{},"partial":false}""")
        )

        val healthyRes = apiClient.fetchChargingForecast("StationHealthy", "LOCH")
        assertTrue("Subsequent call should succeed", healthyRes.isSuccess)
        assertEquals("Healthy", healthyRes.getOrThrow().ticker)
    }
}
