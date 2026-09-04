package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Comprehensive verification unit test for Phase 02:
 * 1. Cache hit: second request within 180s does not trigger network call.
 * 2. Cache invalidation: forceRefresh clears cache and triggers fresh network call.
 * 3. Retry mechanism: simulates transient network failure on attempt 1, verifies successful retry on attempt 2.
 * 4. Silent failure & cooldown: verifies unrecoverable network failure returns null without exception and respects 60s cooldown.
 * 5. Concurrency limit: verifies batch fetching never exceeds 3 concurrent calls simultaneously.
 * 6. EvcsApiClient.fetchStationHtml canonical URL & browser headers verification with MockWebServer.
 */
class StationForecastRepositoryTest {

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var mockServer: MockWebServer

    private val sampleValidHtml = """
        <div id="stationTicker" class="ticker-box">
            Dự kiến 2 xe sạc trụ 60kW sẽ xong trong 7-14 phút nữa
        </div>
        <script data-charging-sessions='[{"kw":60.0,"min":7,"soc":82},{"kw":60.0,"min":14,"soc":75}]'></script>
    """.trimIndent()

    private val sampleUpdatedHtml = """
        <div id="stationTicker" class="ticker-box">
            Dự kiến 1 xe sạc trụ 250kW sẽ xong trong 5 phút nữa
        </div>
        <script data-charging-sessions='[{"kw":250.0,"min":5,"soc":90}]'></script>
    """.trimIndent()

    @Before
    fun setUp() {
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_php_session"
            authCookie = "test_auth_cookie"
            csrfToken = "test_csrf_token"
        }
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun createTestStation(id: String = "st_001", name: String = "Trạm Sạc VinFast Thăng Long"): Station {
        return Station(
            id = id,
            name = name,
            address = "Hà Nội",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000, label = "60kW", availablePlugs = 0, totalPlugs = 2)
            ),
            totalAvailablePlugs = 0,
            totalPlugs = 2
        )
    }

    // -------------------------------------------------------------------------
    // 1. EvcsApiClient fetchStationHtml canonical URL & mobile headers verification
    // -------------------------------------------------------------------------
    @Test
    fun testEvcsApiClientFetchStationHtmlBuildsCanonicalUrlAndHeaders() = runBlocking {
        val serverUrl = mockServer.url("/").toString().removeSuffix("/")
        val client = EvcsApiClient(
            sessionManager = sessionManager,
            client = OkHttpClient(),
            baseUrl = serverUrl
        )

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sampleValidHtml)
        )

        val result = client.fetchStationHtml("VinFast Ocean Park", "ocean_123")

        assertTrue("fetchStationHtml should succeed", result.isSuccess)
        assertEquals(sampleValidHtml, result.getOrNull())

        val recordedRequest = mockServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertEquals("/tram-sac-vinfast-ocean-park-ocean_123.html", recordedRequest.path)
        assertEquals(EvcsApiClient.USER_AGENT_BROWSER, recordedRequest.getHeader("User-Agent"))
        assertEquals("$serverUrl/", recordedRequest.getHeader("Referer"))
        assertEquals(serverUrl, recordedRequest.getHeader("Origin"))
        val cookieHeader = recordedRequest.getHeader("Cookie")
        assertNotNull("Cookie header should be present", cookieHeader)
        assertTrue("Cookie should contain PHPSESSID", cookieHeader!!.contains("PHPSESSID=test_php_session"))
    }

    // -------------------------------------------------------------------------
    // 2. Cache hit: second request within 180s does not trigger network call
    // -------------------------------------------------------------------------
    @Test
    fun testCacheHitWithinTtlDoesNotTriggerNetworkCall() = runBlocking {
        var currentTime = 1000000L
        val cache = ForecastCache(timeProvider = { currentTime })
        var networkCalls = 0

        val fakeApiClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                networkCalls++
                return Result.success(sampleValidHtml)
            }
        }

        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            forecastCache = cache,
            delayProvider = {}
        )

        val station = createTestStation("st_cache")

        // 1st call -> Network call triggered
        val result1 = repository.fetchStationForecast(station)
        assertTrue(result1.isSuccess)
        val forecast1 = result1.getOrNull()
        assertNotNull(forecast1)
        assertEquals(1, networkCalls)
        assertEquals(60.0, forecast1!!.wattageKw, 0.001)
        assertEquals(2, forecast1.vehicleCount)

        // Advance time by 120 seconds (within 180s TTL)
        currentTime += 120_000L

        // 2nd call -> Served from cache, no network call
        val result2 = repository.fetchStationForecast(station)
        assertTrue(result2.isSuccess)
        val forecast2 = result2.getOrNull()
        assertNotNull(forecast2)
        assertEquals("Network call count must remain 1 on cache hit", 1, networkCalls)
        assertEquals(forecast1.rawText, forecast2!!.rawText)

        // Advance time past 180s (e.g. +70s -> total 190s)
        currentTime += 70_000L

        // 3rd call -> Cache expired, network call triggered again
        val result3 = repository.fetchStationForecast(station)
        assertTrue(result3.isSuccess)
        assertEquals("Network call count must increment to 2 after TTL expires", 2, networkCalls)
    }

    // -------------------------------------------------------------------------
    // 3. Cache invalidation: forceRefresh clears cache and triggers fresh network call
    // -------------------------------------------------------------------------
    @Test
    fun testCacheInvalidationOnForceRefreshTriggersFreshNetworkCall() = runBlocking {
        var currentTime = 1000000L
        val cache = ForecastCache(timeProvider = { currentTime })
        var networkCalls = 0
        var currentHtml = sampleValidHtml

        val fakeApiClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                networkCalls++
                return Result.success(currentHtml)
            }
        }

        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            forecastCache = cache,
            delayProvider = {}
        )

        val station = createTestStation("st_force")

        // 1st call -> populates cache with initial HTML (60kW)
        val res1 = repository.fetchStationForecast(station, forceRefresh = false)
        assertTrue(res1.isSuccess)
        assertEquals(1, networkCalls)
        assertEquals(60.0, res1.getOrNull()?.wattageKw ?: 0.0, 0.001)

        // Update server response to updated HTML (250kW)
        currentHtml = sampleUpdatedHtml

        // 2nd call with forceRefresh = true -> invalidates cache and triggers fresh network call
        val res2 = repository.fetchStationForecast(station, forceRefresh = true)
        assertTrue(res2.isSuccess)
        assertEquals("Force refresh must trigger a second network call", 2, networkCalls)
        val forecast2 = res2.getOrNull()
        assertNotNull(forecast2)
        assertEquals("Updated forecast should reflect fresh response (250kW)", 250.0, forecast2!!.wattageKw, 0.001)
    }

    // -------------------------------------------------------------------------
    // 4. Retry mechanism: simulates transient network failure on attempt 1,
    //    verifies successful retry on attempt 2
    // -------------------------------------------------------------------------
    @Test
    fun testRetryMechanismOnTransientFailureSucceedsOnRetry() = runBlocking {
        var networkCalls = 0
        val delaysExecuted = mutableListOf<Long>()

        val fakeApiClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                networkCalls++
                return if (networkCalls == 1) {
                    // Attempt 0 fails transiently
                    Result.failure(IOException("Failed to fetch station detail HTML: HTTP 503"))
                } else {
                    // Attempt 1 (first retry) succeeds
                    Result.success(sampleValidHtml)
                }
            }
        }

        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            delayProvider = { delayMs -> delaysExecuted.add(delayMs) }
        )

        val station = createTestStation("st_retry")
        val result = repository.fetchStationForecast(station)

        assertTrue("Result should succeed on retry", result.isSuccess)
        assertNotNull("Forecast should be parsed successfully after retry", result.getOrNull())
        assertEquals("Should have made exactly 2 attempts", 2, networkCalls)
        assertEquals("Should have executed 1 delay before retry", 1, delaysExecuted.size)
        assertTrue(
            "Retry delay should be ~1000ms (+ 0-300ms jitter)",
            delaysExecuted[0] in 1000L..1300L
        )
    }

    // -------------------------------------------------------------------------
    // 5. Silent failure & cooldown: verifies unrecoverable network failure
    //    returns null without exception and respects 60s cooldown
    // -------------------------------------------------------------------------
    @Test
    fun testSilentFailureAndCooldownOnUnrecoverableFailure() = runBlocking {
        var currentTime = 5000000L
        val cache = ForecastCache(timeProvider = { currentTime })
        var networkCalls = 0
        val delaysExecuted = mutableListOf<Long>()

        val fakeApiClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                networkCalls++
                return Result.failure(IOException("Server unavailable: HTTP 500"))
            }
        }

        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            forecastCache = cache,
            delayProvider = { delayMs -> delaysExecuted.add(delayMs) }
        )

        val station = createTestStation("st_fail")

        // 1st call -> All 3 attempts fail (initial + 2 retries)
        val result1 = repository.fetchStationForecast(station)

        // Verify silent failure: zero exceptions thrown, returns Result.success(null)
        assertTrue("Silent failure must return Result.success", result1.isSuccess)
        assertNull("Payload should be null on failure", result1.getOrNull())
        assertEquals("Must execute 3 attempts before exhausting retries", 3, networkCalls)
        assertEquals("Must have executed 2 retry delays", 2, delaysExecuted.size)
        assertTrue("Attempt 1 delay in [1000..1300]", delaysExecuted[0] in 1000L..1300L)
        assertTrue("Attempt 2 delay in [2000..2300]", delaysExecuted[1] in 2000L..2300L)

        // Verify cooldown is active
        assertTrue("Station should be in failure cooldown", cache.isInCooldown(station.id))

        // 2nd call immediately (t + 10s) -> within 60s cooldown, returns immediately without network call
        currentTime += 10_000L
        val result2 = repository.fetchStationForecast(station)
        assertTrue(result2.isSuccess)
        assertNull(result2.getOrNull())
        assertEquals("Network call count must remain 3 during cooldown", 3, networkCalls)

        // Advance time past 60s cooldown (t + 55s -> total 65s since failure)
        currentTime += 55_000L
        assertFalse("Cooldown should expire after 60s", cache.isInCooldown(station.id))

        // 3rd call -> cooldown expired, network call is attempted again
        val result3 = repository.fetchStationForecast(station)
        assertTrue(result3.isSuccess)
        assertNull(result3.getOrNull())
        assertEquals("Should attempt network again once cooldown expires", 6, networkCalls)
    }

    // -------------------------------------------------------------------------
    // 6. Concurrency limit: verifies batch fetching never exceeds 3 concurrent calls simultaneously
    // -------------------------------------------------------------------------
    @Test
    fun testConcurrencyLimitThrottlesBatchFetchingToMaxThreeSimultaneousCalls() = runBlocking {
        val activeConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val totalCalls = AtomicInteger(0)

        val fakeApiClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                totalCalls.incrementAndGet()
                val current = activeConcurrent.incrementAndGet()
                peakConcurrent.updateAndGet { prev -> max(prev, current) }

                // Simulate realistic async network latency
                delay(30)

                activeConcurrent.decrementAndGet()
                return Result.success(sampleValidHtml)
            }
        }

        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            delayProvider = {}
        )

        // Create 9 test stations
        val stations = (1..9).map { createTestStation("st_concurrent_$it", "Trạm Sạc $it") }

        // Execute batch enrichment
        val enriched = repository.enrichStationsWithForecast(stations)

        assertEquals("All 9 stations should be enriched", 9, enriched.size)
        assertEquals("All 9 network calls should have executed", 9, totalCalls.get())
        assertTrue(
            "Peak concurrent network calls should be at least 2 under load",
            peakConcurrent.get() >= 2
        )
        assertTrue(
            "Peak concurrent network calls must NEVER exceed 3 (throttled by Semaphore(3)). Actual: ${peakConcurrent.get()}",
            peakConcurrent.get() <= 3
        )

        // Verify each enriched station has valid forecast attached
        for (st in enriched) {
            assertNotNull("Station ${st.id} should have attached forecast", st.forecast)
            assertEquals(60.0, st.forecast!!.wattageKw, 0.001)
        }
    }

    // -------------------------------------------------------------------------
    // 7. Non-transient errors (HTTP 404) degrade silently without retries
    // -------------------------------------------------------------------------
    @Test
    fun testNonTransientErrorFailsSilentlyWithoutRetrying() = runBlocking {
        var networkCalls = 0
        val delaysExecuted = mutableListOf<Long>()

        val fakeApiClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                networkCalls++
                return Result.failure(IOException("Failed to fetch station detail HTML: HTTP 404"))
            }
        }

        val repository = EvcsRepository(
            apiClient = fakeApiClient,
            delayProvider = { delayMs -> delaysExecuted.add(delayMs) }
        )

        val station = createTestStation("st_404")
        val result = repository.fetchStationForecast(station)

        assertTrue("Silent degradation on 404 returns Result.success", result.isSuccess)
        assertNull("Payload should be null on 404", result.getOrNull())
        assertEquals("HTTP 404 is non-transient and must NOT be retried", 1, networkCalls)
        assertEquals("No delays should be executed on non-transient error", 0, delaysExecuted.size)
    }
}
