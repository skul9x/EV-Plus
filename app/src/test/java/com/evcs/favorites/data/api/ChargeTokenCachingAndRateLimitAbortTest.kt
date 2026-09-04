package com.evcs.favorites.data.api

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.model.ChargingForecastResponse
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.util.RetryAfterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 02: Charge Token Session Caching, Forecast Pacing & Instant 429 Abort Test.
 *
 * Verifies:
 * 1. Consecutive [EvcsApiClient.fetchChargingForecast] calls reuse cached chargeToken,
 *    bypassing Step 1 (0 HTML requests for subsequent stations).
 * 2. When /charging returns 401/403 with a cached token, token cache is cleared,
 *    a fresh token is fetched via Step 1, and Step 2 is retried once.
 * 3. [RetryAfterParser] correctly parses integer seconds, RFC 1123 HTTP-date formats,
 *    and Cloudflare Error 1015 JSON response bodies, defaulting to 60s.
 * 4. When a station forecast encounters HTTP 429 / [RateLimitException],
 *    [EvcsRepository.globalRateLimitedUntil] is dynamically set to the parsed cooldown duration.
 * 5. In [EvcsRepository.enrichStationsWithForecast], encountering HTTP 429 instantly aborts
 *    the active batch scope, guaranteeing 0 further network requests for remaining stations.
 */
class ChargeTokenCachingAndRateLimitAbortTest {

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

        sessionManager = SessionManager(InMemorySessionStorage()).apply {
            phpSessionId = "test_sess"
            authCookie = "test_auth"
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

    private fun createTestStation(id: String, name: String): Station {
        return Station(
            id = id,
            name = name,
            address = "Hanoi, Vietnam",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "24/7",
            connectors = "120kW, 60kW",
            depotStatus = "Available"
        )
    }

    @Test
    fun retryAfterParser_correctlyParsesSeconds_httpDate_andCloudflareJson() {
        // 1. Integer seconds
        assertEquals(120L, RetryAfterParser.parseRetryAfter("120"))
        assertEquals(0L, RetryAfterParser.parseRetryAfter("0"))

        // 2. RFC 1123 HTTP-date
        val now = (System.currentTimeMillis() / 1000L) * 1000L
        val futureInstant = Instant.ofEpochMilli(now + 75_000L)
        val httpDateStr = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(futureInstant)
        val parsedDeltaSec = RetryAfterParser.parseRetryAfter(httpDateStr, currentTimeMs = now)
        assertEquals(75L, parsedDeltaSec)

        // 3. Cloudflare Error 1015 JSON response body
        val cloudflare1015Body = """
            {
                "success": false,
                "errors": [{"code": 1015, "message": "You are being rate limited"}],
                "retry_after": 42
            }
        """.trimIndent()
        assertEquals(42L, RetryAfterParser.parseRetryAfter(null, responseBody = cloudflare1015Body))
        assertEquals(42L, RetryAfterParser.parseRetryAfter("invalid_header", responseBody = cloudflare1015Body))

        // 4. Default fallback to 60s
        assertEquals(60L, RetryAfterParser.parseRetryAfter(null))
        assertEquals(60L, RetryAfterParser.parseRetryAfter(""))
        assertEquals(60L, RetryAfterParser.parseRetryAfter("not-a-number-or-date", responseBody = "plain text"))
    }

    @Test
    fun fetchChargingForecast_reusesCachedChargeToken_makesZeroStep1CallsForSubsequentStations() = runTest {
        val futureEpochSec = (System.currentTimeMillis() / 1000L) + 600L
        val testToken = "$futureEpochSec.none.testPayloadPart"

        // Enqueue responses for Station 1 (Step 1 + Step 2)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"$testToken"}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticker":"Station 1 Forecast","busyKw":{},"partial":false}""")
        )

        // Enqueue responses for Station 2 and Station 3 (Only Step 2 /charging requests!)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticker":"Station 2 Forecast","busyKw":{},"partial":false}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticker":"Station 3 Forecast","busyKw":{},"partial":false}""")
        )

        // Station 1: Must execute Step 1 and Step 2
        val res1 = apiClient.fetchChargingForecast("Station Alpha", "LOC_ALPHA")
        assertTrue(res1.isSuccess)
        assertEquals("Station 1 Forecast", res1.getOrThrow().ticker)
        assertEquals(2, mockWebServer.requestCount)

        // Verify cached token state
        assertEquals(testToken, apiClient.cachedChargeToken)
        val expectedExpiry = (futureEpochSec * 1000L) - 30_000L
        assertEquals(expectedExpiry, apiClient.cachedChargeTokenExpiryMs)

        // Station 2: Bypasses Step 1, only calls /charging
        val res2 = apiClient.fetchChargingForecast("Station Beta", "LOC_BETA")
        assertTrue(res2.isSuccess)
        assertEquals("Station 2 Forecast", res2.getOrThrow().ticker)
        assertEquals("Total requests should be 3 (1 step1 + 2 step2)", 3, mockWebServer.requestCount)

        // Station 3: Bypasses Step 1, only calls /charging
        val res3 = apiClient.fetchChargingForecast("Station Gamma", "LOC_GAMMA")
        assertTrue(res3.isSuccess)
        assertEquals("Station 3 Forecast", res3.getOrThrow().ticker)
        assertEquals("Total requests should be 4 (1 step1 + 3 step2)", 4, mockWebServer.requestCount)

        // Verify request headers for Station 2 and 3 contained the cached chargeToken
        mockWebServer.takeRequest() // Station 1 Step 1
        mockWebServer.takeRequest() // Station 1 Step 2
        val req2Step2 = mockWebServer.takeRequest()
        val req3Step2 = mockWebServer.takeRequest()

        assertEquals("/charging", req2Step2.path)
        assertEquals(testToken, req2Step2.getHeader("x-t"))

        assertEquals("/charging", req3Step2.path)
        assertEquals(testToken, req3Step2.getHeader("x-t"))
    }

    @Test
    fun fetchChargingForecast_step2Returns401or403_clearsCacheAndFetchesFreshToken() = runTest {
        val expiredToken = "1700000000.none.expiredToken"
        val freshEpochSec = (System.currentTimeMillis() / 1000L) + 600L
        val refreshedToken = "$freshEpochSec.none.refreshedToken"

        // Seed apiClient cache with expiredToken
        apiClient.cachedChargeToken = expiredToken
        apiClient.cachedChargeTokenExpiryMs = System.currentTimeMillis() + 300_000L

        // Sequence:
        // 1. Step 2 with cached token -> returns 401 Unauthorized
        // 2. Step 1 fresh token fetch -> returns refreshedToken
        // 3. Step 2 retry with refreshedToken -> returns 200 OK
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized Token")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chargeToken":"$refreshedToken"}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticker":"Refreshed Success","busyKw":{},"partial":false}""")
        )

        val result = apiClient.fetchChargingForecast("Station Delta", "LOC_DELTA")
        assertTrue(result.isSuccess)
        assertEquals("Refreshed Success", result.getOrThrow().ticker)

        // Verify cache was updated to refreshedToken
        assertEquals(refreshedToken, apiClient.cachedChargeToken)

        // Verify request flow
        assertEquals(3, mockWebServer.requestCount)
        val initialStep2 = mockWebServer.takeRequest()
        assertEquals("/charging", initialStep2.path)
        assertEquals(expiredToken, initialStep2.getHeader("x-t"))

        val step1Fresh = mockWebServer.takeRequest()
        assertTrue(step1Fresh.path?.contains("LOC_DELTA") == true)
        assertEquals("user", step1Fresh.getHeader("X-Partial"))

        val retriedStep2 = mockWebServer.takeRequest()
        assertEquals("/charging", retriedStep2.path)
        assertEquals(refreshedToken, retriedStep2.getHeader("x-t"))
    }

    @Test
    fun rateLimitException_dynamicallySetsRepositoryCooldown() = runTest {
        val station = createTestStation("C.HNO001", "VinFast Thang Long")
        var networkAttemptCount = 0

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                networkAttemptCount++
                return Result.failure(RateLimitException(retryAfterSeconds = 90L))
            }
        }

        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = ForecastCache(),
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.resetRateLimitCooldown()
        assertFalse("Initial state should not be rate limited", repo.isGlobalRateLimited())

        val nowBefore = System.currentTimeMillis()
        val result = repo.fetchStationForecast(station)

        // Graceful degradation: returns Result.success(null)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals(1, networkAttemptCount)

        // Verify dynamic cooldown set to 90 seconds
        assertTrue("Global rate limit must be active", repo.isGlobalRateLimited())
        val cooldownUntil = repo.globalRateLimitedUntil.get()
        val diffMs = cooldownUntil - nowBefore
        assertTrue("Cooldown should be ~90 seconds (actual diff: ${diffMs}ms)", diffMs in 85_000L..95_000L)

        // Subsequent call must be completely bypassed without network attempt
        val nextStation = createTestStation("C.HNO002", "VinFast Royal City")
        val bypassedResult = repo.fetchStationForecast(nextStation)
        assertTrue(bypassedResult.isSuccess)
        assertNull(bypassedResult.getOrNull())
        assertEquals("Network call count must remain 1 (bypassed)", 1, networkAttemptCount)
    }

    @Test
    fun enrichStationsWithForecast_http429InstantlyAbortsBatch_zeroFurtherNetworkRequests() = runTest {
        val stations = (1..5).map { i ->
            createTestStation("LOC_$i", "Station $i")
        }

        val networkCallCount = AtomicInteger(0)
        val completedStations = mutableListOf<Station>()

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                networkCallCount.incrementAndGet()
                return if (locationId == "LOC_1") {
                    // First station succeeds
                    Result.success(
                        ChargingForecastResponse(
                            ticker = "Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 15 phút nữa",
                            busyKw = emptyMap(),
                            partial = false
                        )
                    )
                } else if (locationId == "LOC_2") {
                    // Second station triggers HTTP 429
                    Result.failure(RateLimitException(retryAfterSeconds = 60L))
                } else {
                    // Remaining stations should NOT be reached!
                    Result.success(ChargingForecastResponse(ticker = "Unexpected"))
                }
            }
        }

        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = ForecastCache(),
            delayProvider = { kotlinx.coroutines.delay(it) },
            ioDispatcher = Dispatchers.IO
        )
        repo.resetRateLimitCooldown()

        val enrichedResult = repo.enrichStationsWithForecast(
            stations = stations,
            forceRefresh = true,
            onStationUpdated = { completedStations.add(it) }
        )

        // Assert that the batch aborted immediately:
        // Network should only have been called for LOC_1 and LOC_2 (at most 2 calls, not 5!)
        assertTrue(
            "Network calls must not exceed 2 (was ${networkCallCount.get()})",
            networkCallCount.get() <= 2
        )

        // Rate limit circuit breaker must be active
        assertTrue(repo.isGlobalRateLimited())

        // UI must not crash: all 5 stations returned
        assertEquals(5, enrichedResult.size)

        // Station 1 was enriched before abort
        assertNotNull("Station 1 should have forecast", enrichedResult[0].forecast)

        // Remaining stations were safely returned without forecast
        assertNull("Station 2 should not have forecast", enrichedResult[1].forecast)
        assertNull("Station 3 should not have forecast", enrichedResult[2].forecast)
        assertNull("Station 4 should not have forecast", enrichedResult[3].forecast)
        assertNull("Station 5 should not have forecast", enrichedResult[4].forecast)
    }
}
