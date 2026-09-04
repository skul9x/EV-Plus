package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.model.ChargingForecastResponse
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.parser.StationForecastParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Dedicated test suite verifying:
 * 1. Compound multi-clause ticker parser enhancement for multi-wattage stations.
 * 2. Safe null return on locked/teaser tickers.
 * 3. Cloudflare HTTP 429 non-transient handling (zero immediate retries).
 * 4. Circuit breaker activation and silent network bypass during rate-limit cooldown.
 * 5. Batch enrichment pacing and rate limit respect.
 */
class ForecastRateLimitAndCompoundParserTest {

    private lateinit var sessionManager: SessionManager
    private val recordedDelays = mutableListOf<Long>()

    @Before
    fun setUp() {
        sessionManager = SessionManager(InMemorySessionStorage())
        recordedDelays.clear()
    }

    private fun createStation(id: String = "C.BNI0324", name: String = "VinFast Son Quang Huy"): Station {
        return Station(
            id = id,
            name = name,
            address = "Km7 QL18, Giang Lieu, Bac Ninh",
            latitude = 21.149,
            longitude = 106.153,
            summary = "24/7",
            connectors = "120kW, 60kW",
            depotStatus = "Available",
            powers = listOf(
                PowerPort(typeWatts = 120000, label = "120kW", availablePlugs = 0, totalPlugs = 3),
                PowerPort(typeWatts = 60000, label = "60kW", availablePlugs = 0, totalPlugs = 1)
            ),
            totalAvailablePlugs = 0,
            totalPlugs = 4
        )
    }

    // =========================================================================
    // PART 1: StationForecastParser Tests
    // =========================================================================

    @Test
    fun testParseCompoundMultiClauseTickerFromRealDebugLog() {
        // Real HTML snippet captured in debug-log.txt item #21 (station C.BNI0324)
        val liveCompoundHtml = """
            <div class="amd-ticker amd-hasmore" role="status" aria-label="Trụ sắp sạc xong"> <span class="amd-item">Dự kiến <b>3</b> xe sạc trụ <b>120kW</b> sẽ xong trong <b>5-21</b> phút, <b>1</b> xe sạc trụ <b>60kW</b> sẽ xong trong <b>1</b> phút nữa</span> </div> <button type="button" class="amd-more amd-locked" data-lock="go" aria-label="Xem thêm dự báo - quà tặng EVCS Go">Xem thêm <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"> <path d="M5 19L19 5M19 19V5H5"></path> </svg></button>
        """.trimIndent()

        val forecast = StationForecastParser.parseForecastFromHtml(liveCompoundHtml)
        assertNotNull("Compound multi-clause ticker must be parsed successfully", forecast)

        // Primary clause values
        assertEquals(3, forecast!!.vehicleCount)
        assertEquals(120.0, forecast.wattageKw, 0.001)
        assertEquals(5, forecast.minMinutes)
        assertEquals(21, forecast.maxMinutes)
        assertTrue(forecast.isTeaser)
        assertTrue("Must be identified as multi-session", forecast.isMultiSession)

        // Clean rawText verification
        assertEquals(
            "Dự kiến 3 xe sạc trụ 120kW sẽ xong trong 5-21 phút, 1 xe sạc trụ 60kW sẽ xong trong 1 phút nữa",
            forecast.rawText
        )

        // Power groups breakdown
        val groups = forecast.getGroupedPowerForecasts(descending = true)
        assertEquals(2, groups.size)

        val g120 = groups[0]
        assertEquals(120.0, g120.kw, 0.001)
        assertEquals(3, g120.vehicleCount)
        assertEquals(5, g120.minMinutes)
        assertEquals(21, g120.maxMinutes)
        assertEquals("• 120kW: ~5-21 phút (3 xe)", g120.formatBulletLine())

        val g60 = groups[1]
        assertEquals(60.0, g60.kw, 0.001)
        assertEquals(1, g60.vehicleCount)
        assertEquals(1, g60.minMinutes)
        assertEquals(1, g60.maxMinutes)
        assertEquals("• 60kW:  ~1 phút (1 xe)", g60.formatBulletLine())

        // Summary format
        assertEquals(
            "⏱️ Dự kiến 3 xe sạc trụ 120kW sẽ xong trong 5-21 phút nữa",
            forecast.formatSingleSummary()
        )
    }

    @Test
    fun testParseLockedTeaserBannerSafelyReturnsNull() {
        // Real HTML snippet captured in debug-log.txt item #15, #18
        val lockedHtml = """
            <div class="amd-ticker amd-locked" role="button" tabindex="0" data-lock="go" aria-label="Dự báo xe sắp sạc xong - quà tặng EVCS Go"> <span class="amd-item"><span>Xem dự báo cổng sạc trống của trạm</span> <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" style="opacity:0.6;margin-top:2px" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"> <path d="M5 19L19 5M19 19V5H5"></path> </svg></span> </div>
        """.trimIndent()

        val forecast = StationForecastParser.parseForecastFromHtml(lockedHtml)
        assertNull("Locked teaser without active charging forecast should return null", forecast)
    }

    // =========================================================================
    // PART 2: EvcsRepository Rate Limit & Circuit Breaker Tests
    // =========================================================================

    @Test
    fun testHttp429FailsImmediatelyWithoutRetryAndActivatesCircuitBreaker() = runTest {
        val station = createStation("C.BNI0287", "VinFast Nguyen Thi Lan")
        var callCount = 0

        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                callCount++
                return Result.failure(IOException("Charging forecast request failed with HTTP 429 (error code: 1015)"))
            }
        }

        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = ForecastCache(),
            delayProvider = { recordedDelays.add(it) },
            ioDispatcher = Dispatchers.Unconfined
        )

        // Reset state before test
        repo.resetRateLimitCooldown()
        assertFalse(repo.isGlobalRateLimited())

        // Act: fetch station forecast
        val result = repo.fetchStationForecast(station)

        // Assert: Silent degradation
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())

        // Assert: HTTP 429 is treated as non-transient, must not perform 1s/2s retries
        assertEquals("Must fail on attempt 1 without spamming retry", 1, callCount)
        assertTrue("No backoff retry delays scheduled", recordedDelays.isEmpty())

        // Assert: Circuit breaker is activated
        assertTrue("Global rate limit circuit breaker must be active", repo.isGlobalRateLimited())

        // Subsequent call while circuit breaker is active must bypass network completely
        val nextStation = createStation("C.BNI0012", "VinFast Dabaco")
        val bypassedResult = repo.fetchStationForecast(nextStation)
        assertTrue(bypassedResult.isSuccess)
        assertNull(bypassedResult.getOrNull())
        assertEquals("Network call count must remain 1 (bypassed)", 1, callCount)

        // When circuit breaker is reset, network calls resume
        repo.resetRateLimitCooldown()
        assertFalse(repo.isGlobalRateLimited())
        repo.fetchStationForecast(nextStation)
        assertEquals("Network call attempted after circuit breaker reset", 2, callCount)
    }

    @Test
    fun testBatchEnrichmentBypassesNetworkWhenGlobalRateLimited() = runTest {
        var callCount = 0
        val fakeClient = object : EvcsApiClient(sessionManager) {
            override suspend fun fetchChargingForecast(
                stationName: String,
                locationId: String,
                isVinFast: Boolean
            ): Result<ChargingForecastResponse> {
                callCount++
                return Result.success(ChargingForecastResponse(ticker = null))
            }
        }

        val repo = EvcsRepository(
            apiClient = fakeClient,
            forecastCache = ForecastCache(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val stations = listOf(
            createStation("st1", "Station 1"),
            createStation("st2", "Station 2"),
            createStation("st3", "Station 3")
        )

        // Trigger rate limit
        repo.globalRateLimitedUntil.set(System.currentTimeMillis() + 60_000L)
        assertTrue(repo.isGlobalRateLimited())

        val enriched = repo.enrichStationsWithForecast(stations)
        assertEquals(3, enriched.size)
        assertEquals("Zero network calls when rate limited", 0, callCount)

        // Reset rate limit
        repo.resetRateLimitCooldown()
        assertFalse(repo.isGlobalRateLimited())
        repo.enrichStationsWithForecast(stations)
        assertEquals("Calls proceed normally when not rate limited", 3, callCount)
    }
}
