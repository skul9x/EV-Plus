package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.model.ChargingForecastResponse
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.StationForecast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive test verifying Phase 02: Repository Real-Time Forecast Pipeline.
 *
 * Verifies:
 * 1. EvcsRepository queries dynamic charging forecast via apiClient.fetchChargingForecast (with isVinFast = true).
 * 2. Real-time ticker snippet is parsed into StationForecast matching EVCS live values.
 * 3. Range minute ticker parsing (e.g. 15-29 phút) preserves minMinutes, maxMinutes, wattageKw, and isTeaser = true.
 * 4. Successful in-memory caching with 3-minute (180s) TTL and forceRefresh bypass.
 * 5. Failure cooldown (60s) prevents repeated requests after network errors.
 * 6. Transient errors trigger exponential backoff retry (up to 2 retries with jitter).
 * 7. Non-transient errors (HTTP 4xx) fail immediately without retry.
 * 8. Concurrency throttling with Semaphore(3) limits simultaneous network requests.
 * 9. Cancellation safety: Coroutine CancellationException is rethrown cleanly without cooldown poisoning.
 * 10. Blank or null tickers degrade silently to Result.success(null).
 */
class ChargingForecastRepositoryPipelineTest {

    private lateinit var sessionManager: SessionManager
    private var simulatedTimeMs = 1_000_000L
    private val recordedDelays = mutableListOf<Long>()

    private val liveTickerSampleSingle = """
        <div class="amd-ticker amd-hasmore" role="status" aria-label="Trụ sắp sạc xong">
            <span class="amd-item">Dự kiến <b>1</b> xe sạc trụ <b>150kW</b> sẽ xong trong <b>13</b> phút nữa</span>
        </div>
        <button type="button" class="amd-more amd-locked" data-lock="go" aria-label="Xem thêm dự báo - quà tặng EVCS Go">
            Xem thêm
        </button>
    """.trimIndent()

    private val liveTickerSampleRange = """
        <div class="amd-ticker amd-hasmore" role="status" aria-label="Trụ sắp sạc xong">
            <span class="amd-item">Dự kiến <b>2</b> xe sạc trụ <b>150kW</b> sẽ xong trong <b>15-29</b> phút nữa</span>
        </div>
        <button type="button" class="amd-more amd-locked" data-lock="go" aria-label="Xem thêm dự báo - quà tặng EVCS Go">
            Xem thêm
        </button>
    """.trimIndent()

    @Before
    fun setUp() {
        sessionManager = SessionManager(InMemorySessionStorage())
        simulatedTimeMs = 1_000_000L
        recordedDelays.clear()
    }

    private fun createStation(id: String = "C.HNO15880", name: String = "VinFast - TS Cuc Dia Chat"): Station {
        return Station(
            id = id,
            name = name,
            address = "Số 6 Phạm Ngũ Lão, Hoàn Kiếm, Hà Nội",
            latitude = 21.0245,
            longitude = 105.8582,
            summary = "24/7",
            connectors = "150kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 150000, label = "150kW", availablePlugs = 1, totalPlugs = 2)
            ),
            totalAvailablePlugs = 1,
            totalPlugs = 2
        )
    }

    // -------------------------------------------------------------------------
    // 1. Dynamic query & Live ticker parse
    // -------------------------------------------------------------------------
    @Test
    fun testRepositoryQueriesDynamicForecastAndParsesLiveTicker() = runTest {
        val station = createStation()
        var requestedStationName: String? = null
        var requestedLocationId: String? = null
        var requestedIsVinFast: Boolean? = null

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                requestedStationName = stationName
                requestedLocationId = locationId
                requestedIsVinFast = isVinFast
                return Result.success(
                    ChargingForecastResponse(
                        ticker = liveTickerSampleSingle,
                        busyKw = mapOf("150" to 1),
                        partial = false
                    )
                )
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.fetchStationForecast(station)
        assertTrue("fetchStationForecast should succeed", result.isSuccess)

        // Verify dynamic parameters
        assertEquals(station.name, requestedStationName)
        assertEquals(station.id, requestedLocationId)
        assertEquals(true, requestedIsVinFast)

        // Verify parsed forecast domain model
        val forecast = result.getOrNull()
        assertNotNull("Forecast should not be null", forecast)
        forecast!!
        assertEquals(1, forecast.vehicleCount)
        assertEquals(150.0, forecast.wattageKw, 0.001)
        assertEquals(13, forecast.minMinutes)
        assertEquals(13, forecast.maxMinutes)
        assertTrue("isTeaser should be true for locked/hasmore ticker", forecast.isTeaser)
        assertEquals("⏱️ Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 13 phút nữa", forecast.displaySummary)
        assertEquals("Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 13 phút nữa", forecast.rawText)
    }

    // -------------------------------------------------------------------------
    // 2. Range minute ticker parsing
    // -------------------------------------------------------------------------
    @Test
    fun testRepositoryParsesRangeMinuteTickerPreservingMinMaxMinutes() = runTest {
        val station = createStation()

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                return Result.success(
                    ChargingForecastResponse(
                        ticker = liveTickerSampleRange,
                        busyKw = mapOf("150" to 2),
                        partial = false
                    )
                )
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.fetchStationForecast(station)
        assertTrue(result.isSuccess)
        val forecast = result.getOrNull()
        assertNotNull(forecast)
        forecast!!
        assertEquals(2, forecast.vehicleCount)
        assertEquals(150.0, forecast.wattageKw, 0.001)
        assertEquals(15, forecast.minMinutes)
        assertEquals(29, forecast.maxMinutes)
        assertTrue(forecast.isTeaser)
        assertEquals("⏱️ Dự kiến 2 xe sạc trụ 150kW sẽ xong trong 15-29 phút nữa", forecast.displaySummary)
    }

    // -------------------------------------------------------------------------
    // 3. In-memory caching with 3-minute TTL & forceRefresh
    // -------------------------------------------------------------------------
    @Test
    fun testSuccessfulInMemoryCachingWith3MinuteTtlAndForceRefresh() = runTest {
        val station = createStation()
        var apiCalls = 0

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                apiCalls++
                return Result.success(
                    ChargingForecastResponse(ticker = liveTickerSampleSingle)
                )
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        // 1st call: triggers network fetch
        val res1 = repo.fetchStationForecast(station)
        assertEquals(1, apiCalls)
        assertNotNull(res1.getOrNull())

        // Advance 120 seconds (within 180s TTL)
        simulatedTimeMs += 120_000L
        val res2 = repo.fetchStationForecast(station)
        assertEquals("Cached result returned within TTL without network call", 1, apiCalls)
        assertEquals(res1.getOrNull(), res2.getOrNull())

        // Advance past 180 seconds TTL (another 65 seconds -> total 185s)
        simulatedTimeMs += 65_000L
        val res3 = repo.fetchStationForecast(station)
        assertEquals("TTL expired: new network call should be triggered", 2, apiCalls)
        assertNotNull(res3.getOrNull())

        // Force refresh should bypass cache even within TTL
        simulatedTimeMs += 10_000L
        val res4 = repo.fetchStationForecast(station, forceRefresh = true)
        assertEquals("forceRefresh=true should bypass cache immediately", 3, apiCalls)
        assertNotNull(res4.getOrNull())
    }

    // -------------------------------------------------------------------------
    // 4. Failure cooldown (1 minute)
    // -------------------------------------------------------------------------
    @Test
    fun testFailureCooldownPreventsImmediateRetries() = runTest {
        val station = createStation()
        var apiCalls = 0

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                apiCalls++
                return Result.failure(IOException("HTTP 500 Internal Server Error"))
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        // 1st call: fails after 3 attempts (1 initial + 2 retries)
        val res1 = repo.fetchStationForecast(station)
        assertTrue("Silent degradation: result should be success(null)", res1.isSuccess)
        assertNull(res1.getOrNull())
        val callsAfterFailure = apiCalls
        assertTrue(callsAfterFailure >= 1)

        // Station is in cooldown (60s). Next call within 30s should return null without hitting API
        simulatedTimeMs += 30_000L
        val res2 = repo.fetchStationForecast(station)
        assertTrue(res2.isSuccess)
        assertNull(res2.getOrNull())
        assertEquals("During cooldown, no new network calls should occur", callsAfterFailure, apiCalls)

        // Advance past 60s cooldown (another 35s -> total 65s)
        simulatedTimeMs += 35_000L
        repo.fetchStationForecast(station)
        assertTrue("After cooldown expiry, network fetch attempted again", apiCalls > callsAfterFailure)
    }

    // -------------------------------------------------------------------------
    // 5. Transient error exponential backoff retry
    // -------------------------------------------------------------------------
    @Test
    fun testTransientErrorExponentialBackoffRetrySucceedsOnThirdAttempt() = runTest {
        val station = createStation()
        var attemptCount = 0

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                attemptCount++
                return when (attemptCount) {
                    1 -> Result.failure(IOException("Socket timeout"))
                    2 -> Result.failure(IOException("HTTP 503 Service Unavailable"))
                    else -> Result.success(ChargingForecastResponse(ticker = liveTickerSampleSingle))
                }
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.fetchStationForecast(station)
        assertTrue("Should eventually succeed after retries", result.isSuccess)
        assertEquals(3, attemptCount)
        assertEquals(2, recordedDelays.size)
        assertTrue("First retry delay around 1000ms", recordedDelays[0] in 1000L..1350L)
        assertTrue("Second retry delay around 2000ms", recordedDelays[1] in 2000L..2350L)

        val forecast = result.getOrNull()
        assertNotNull(forecast)
        assertEquals(1, forecast!!.vehicleCount)
    }

    // -------------------------------------------------------------------------
    // 6. Non-transient error immediately fails without retry
    // -------------------------------------------------------------------------
    @Test
    fun testNonTransientErrorFailsImmediatelyWithoutRetry() = runTest {
        val station = createStation()
        var attemptCount = 0

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                attemptCount++
                return Result.failure(IOException("HTTP 404 Not Found"))
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.fetchStationForecast(station)
        assertTrue("Silent degradation guaranteed", result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals("Non-transient HTTP 404 should not retry", 1, attemptCount)
        assertTrue("No backoff delays should be triggered", recordedDelays.isEmpty())
        assertTrue("Should be placed into failure cooldown", cache.isInCooldown(station.id))
    }

    // -------------------------------------------------------------------------
    // 7. Cancellation safety: CancellationException is rethrown cleanly
    // -------------------------------------------------------------------------
    @Test
    fun testCancellationSafetyPropagatesCancellationException() = runTest {
        val station = createStation()

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                throw CancellationException("Job was cancelled")
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        try {
            repo.fetchStationForecast(station)
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Job was cancelled", e.message)
        }

        // Cancellation should NOT place station in cooldown
        assertFalse(
            "Cancelled request should not poison failure cooldown",
            cache.isInCooldown(station.id)
        )
    }

    // -------------------------------------------------------------------------
    // 8. Concurrency throttling with Semaphore(3) & batch enrichment
    // -------------------------------------------------------------------------
    @Test
    fun testBatchEnrichmentConcurrencyThrottlingWithSemaphore3() = runTest {
        val stations = (1..6).map { idx ->
            createStation(id = "ST_$idx", name = "Trạm $idx")
        }

        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                val current = activeCount.incrementAndGet()
                maxConcurrent.updateAndGet { prev -> maxOf(prev, current) }
                delay(20)
                activeCount.decrementAndGet()

                return Result.success(
                    ChargingForecastResponse(
                        ticker = liveTickerSampleSingle,
                        busyKw = mapOf("150" to 1)
                    )
                )
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Default
        )

        val updatedStations = mutableListOf<Station>()
        val enrichedList = repo.enrichStationsWithForecast(
            stations = stations,
            forceRefresh = true,
            onStationUpdated = { updatedStations.add(it) }
        )

        assertEquals(6, enrichedList.size)
        assertEquals(6, updatedStations.size)
        assertTrue(
            "Concurrent network requests must never exceed 3 (was ${maxConcurrent.get()})",
            maxConcurrent.get() <= 3
        )

        for (st in enrichedList) {
            assertNotNull(st.forecast)
            assertEquals(150.0, st.forecast!!.wattageKw, 0.001)
            assertEquals(13, st.forecast!!.minMinutes)
        }
    }

    // -------------------------------------------------------------------------
    // 9. Null or blank ticker degrades gracefully
    // -------------------------------------------------------------------------
    @Test
    fun testNullOrBlankTickerDegradesGracefullyToNullForecast() = runTest {
        val station = createStation()

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                return Result.success(ChargingForecastResponse(ticker = null))
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repo.fetchStationForecast(station)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertNull("Cache should not store null forecasts", cache.get(station.id))
        assertFalse("Null ticker is not a failure, should not trigger cooldown", cache.isInCooldown(station.id))
    }

    // -------------------------------------------------------------------------
    // 10. HTTP 429 activates global rate limit circuit breaker without retry
    // -------------------------------------------------------------------------
    @Test
    fun testHttp429ActivatesGlobalRateLimitCircuitBreakerWithoutRetry() = runTest {
        val station1 = createStation("C.TEST01", "Station 1")
        val station2 = createStation("C.TEST02", "Station 2")
        var apiCalls = 0

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                apiCalls++
                return Result.failure(IOException("Charging forecast request failed with HTTP 429 (error code: 1015)"))
            }
        }

        val cache = ForecastCache(timeProvider = { simulatedTimeMs })
        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = cache,
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        // 1st station hits 429: must not retry, must trigger circuit breaker
        val res1 = repo.fetchStationForecast(station1)
        assertTrue(res1.isSuccess)
        assertNull(res1.getOrNull())
        assertEquals("HTTP 429 should fail immediately on first attempt without retry", 1, apiCalls)
        assertTrue("No backoff retry delays should be scheduled for HTTP 429", recordedDelays.isEmpty())
        assertTrue("Global rate limit circuit breaker should be active", repo.isGlobalRateLimited())

        // 2nd station called while circuit breaker is active: immediately bypassed
        val res2 = repo.fetchStationForecast(station2)
        assertTrue(res2.isSuccess)
        assertNull(res2.getOrNull())
        assertEquals("Subsequent requests must bypass network calls while rate limited", 1, apiCalls)

        // Reset circuit breaker allows network call again
        repo.resetRateLimitCooldown()
        assertFalse(repo.isGlobalRateLimited())
        repo.fetchStationForecast(station2)
        assertEquals("Network call attempted again after resetting rate limit", 2, apiCalls)
    }
}
