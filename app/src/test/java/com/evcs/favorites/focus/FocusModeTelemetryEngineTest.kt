package com.evcs.favorites.focus

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Comprehensive verification test for Phase 03:
 * Focus Mode Telemetry Engine & Polling.
 *
 * Requirements covered:
 * 1. Dynamic interval calculation (15s at 4.2km, 10s at 2.1km, 5s at 0.8km).
 * 2. Strict exclusion of AC ports (11kW, 7.4kW, motorcycle) from DC count calculations (>= 30kW).
 * 3. Offline state transition with formatted timestamp upon network failure (underground basement).
 * 4. Auto-reroute algorithm selecting the closest equivalent-power DC station with available ports.
 * 5. Lifecycle start/stop coroutine cancellation safety.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusModeTelemetryEngineTest {

    private val fixedTimeZone = TimeZone.getTimeZone("GMT+7")
    private val fixedLocale = Locale.US

    private fun createSampleStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powers: List<PowerPort>,
        depotStatus: String = "Normal"
    ): Station {
        val (availDc, totalDc) = FocusModeDcFilter.calculateDcSlots(powers)
        return Station(
            id = id,
            name = name,
            address = "Test Address $name",
            latitude = lat,
            longitude = lon,
            summary = "Trống $availDc/$totalDc cổng sạc DC",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs },
            evse = "VinFast"
        )
    }

    // =========================================================================
    // 1. Dynamic Polling Interval Calculation Tests
    // =========================================================================

    @Test
    fun dynamicPollingInterval_calculatesExactIntervalsBasedOnDistanceThresholds() {
        // > 3.0km -> 15s (15,000ms)
        assertEquals(15_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(4.2))
        assertEquals(15_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(10.0))
        assertEquals(15_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(3.001))

        // 1.5km - 3.0km -> 10s (10,000ms)
        assertEquals(10_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(3.0))
        assertEquals(10_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(2.1))
        assertEquals(10_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(1.5))

        // < 1.5km -> 5s (5,000ms)
        assertEquals(5_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(1.499))
        assertEquals(5_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(0.8))
        assertEquals(5_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(0.1))
        assertEquals(5_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(0.0))

        // Fallbacks for null or NaN
        assertEquals(15_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(null))
        assertEquals(15_000L, FocusModeTelemetryEngine.calculatePollingIntervalMs(Double.NaN))
    }

    // =========================================================================
    // 2. Strict DC-Only Filter & AC Port Exclusion Tests
    // =========================================================================

    @Test
    fun strictDcFilter_strictlyExcludesAcAndMotorcyclePortsUnder30kW() {
        // Port configurations: 150kW DC, 60kW DC, 11kW AC, 7.4kW AC, 3.3kW Motorcycle
        val p150 = PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 2, totalPlugs = 2)
        val p60 = PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 1, totalPlugs = 2)
        val p30 = PowerPort(typeWatts = 30_000L, label = "30kW", availablePlugs = 1, totalPlugs = 1)
        val p11Ac = PowerPort(typeWatts = 11_000L, label = "11kW AC", availablePlugs = 4, totalPlugs = 4)
        val p7Ac = PowerPort(typeWatts = 7_400L, label = "7.4kW AC", availablePlugs = 2, totalPlugs = 2)
        val pMoto = PowerPort(typeWatts = 3_300L, label = "3.3kW Xe máy", availablePlugs = 10, totalPlugs = 10)

        // Verify individual port filter boolean
        assertTrue("150kW must be DC", FocusModeDcFilter.isDcPort(p150))
        assertTrue("60kW must be DC", FocusModeDcFilter.isDcPort(p60))
        assertTrue("30kW must be DC", FocusModeDcFilter.isDcPort(p30))
        assertFalse("11kW AC must NOT be DC", FocusModeDcFilter.isDcPort(p11Ac))
        assertFalse("7.4kW AC must NOT be DC", FocusModeDcFilter.isDcPort(p7Ac))
        assertFalse("3.3kW Motorcycle must NOT be DC", FocusModeDcFilter.isDcPort(pMoto))

        // Mixed power ports list
        val mixedPorts = listOf(p150, p60, p11Ac, p7Ac, pMoto)
        val (availDc, totalDc) = FocusModeDcFilter.calculateDcSlots(mixedPorts)

        // Only p150 (2/2) and p60 (1/2) are DC: 2 + 1 = 3 avail, 2 + 2 = 4 total
        assertEquals(3, availDc)
        assertEquals(4, totalDc)

        // Station with mixed ports
        val station = createSampleStation(
            id = "st_mixed",
            name = "Vincom Center",
            lat = 10.7769,
            lon = 106.7009,
            powers = mixedPorts
        )

        val initialState = FocusModeState.createInitial(station)
        assertEquals(3, initialState.availableDcSlots)
        assertEquals(4, initialState.totalDcSlots)
        assertEquals(150_000L, initialState.maxDcPowerWatts)
        assertEquals(150, initialState.maxDcPowerKw)
        assertEquals("🟢 3/4 Trống (150kW)", initialState.statusBadgeText)
    }

    // =========================================================================
    // 3. Offline State Transition & Timestamp Formatting Tests
    // =========================================================================

    @Test
    fun offlineStateTransition_retainsLastValidTelemetryAndFormatsTimestampOnNetworkFailure() = runTest {
        // Setup fixed timestamp: 14:35:00 GMT+7 (e.g. 2026-09-06 14:35:00)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", fixedLocale).apply { timeZone = fixedTimeZone }
        val baseTimeMs = sdf.parse("2026-09-06 14:35:00")!!.time

        var currentClock = baseTimeMs
        var shouldNetworkFail = false

        val initialStation = createSampleStation(
            id = "st_underground",
            name = "VinFast Landmark 81 B2",
            lat = 10.7950,
            lon = 106.7218,
            powers = listOf(
                PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 2, totalPlugs = 4)
            )
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = initialStation,
            fetchStationTelemetry = { _, _, _ ->
                if (shouldNetworkFail) {
                    Result.failure(IOException("No network in underground basement"))
                } else {
                    Result.success(initialStation)
                }
            },
            locationProvider = { Pair(10.7950, 106.7218) },
            clock = { currentClock },
            timeZone = fixedTimeZone,
            locale = fixedLocale
        )

        // 1. First poll succeeds when outside
        val state1 = engine.pollOnce()
        assertEquals(FocusConnectionStatus.CONNECTED, state1.connectionStatus)
        assertFalse(state1.isOffline)
        assertNull(state1.offlineMessage)
        assertEquals(2, state1.availableDcSlots)
        assertEquals(4, state1.totalDcSlots)
        assertEquals("🟢 2/4 Trống (150kW)", state1.statusBadgeText)

        // 2. Driver enters underground parking basement -> network fails
        shouldNetworkFail = true
        currentClock += 60_000L // 1 minute later: 14:36

        val state2 = engine.pollOnce()
        assertEquals(FocusConnectionStatus.OFFLINE, state2.connectionStatus)
        assertTrue(state2.isOffline)
        assertNotNull(state2.offlineMessage)

        // Formatted timestamp string MUST display the last valid data timestamp: 14:35
        val expectedOfflineString = "⚠️ Mất kết nối - Dữ liệu lúc 14:35"
        assertEquals(expectedOfflineString, state2.offlineMessage)
        assertEquals(expectedOfflineString, state2.statusBadgeText)

        // Crucial requirement: retains last valid telemetry, slot counts NOT reset to 0
        assertEquals(2, state2.availableDcSlots)
        assertEquals(4, state2.totalDcSlots)
        assertEquals(initialStation.id, state2.targetStation.id)

        // 3. Driver emerges from basement -> network restores
        shouldNetworkFail = false
        currentClock += 120_000L // 14:38

        val state3 = engine.pollOnce()
        assertEquals(FocusConnectionStatus.CONNECTED, state3.connectionStatus)
        assertFalse(state3.isOffline)
        assertNull(state3.offlineMessage)
        assertEquals("🟢 2/4 Trống (150kW)", state3.statusBadgeText)
    }

    // =========================================================================
    // 4. Auto-Reroute Resolver Tests
    // =========================================================================

    @Test
    fun autoRerouteResolver_selectsClosestEquivalentPowerDcStationWithAvailablePorts() = runTest {
        val driverLat = 10.7769
        val driverLon = 106.7009

        // Target station: 150kW DC, currently 0 available (saturated!)
        val targetStation = createSampleStation(
            id = "target_station",
            name = "Vincom Dong Khoi",
            lat = 10.7780,
            lon = 106.7020,
            powers = listOf(
                PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 0, totalPlugs = 4)
            )
        )

        // Candidate 1: Closer (0.2km), but 60kW DC (NOT equivalent power tier to 150kW)
        val cand1 = createSampleStation(
            id = "cand_60kw",
            name = "Trạm 60kW Gần",
            lat = 10.7785,
            lon = 106.7025,
            powers = listOf(
                PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 2, totalPlugs = 2)
            )
        )

        // Candidate 2: Equivalent 150kW, but 0 available slots (also full)
        val cand2 = createSampleStation(
            id = "cand_150kw_full",
            name = "Trạm 150kW Đầy",
            lat = 10.7800,
            lon = 106.7030,
            powers = listOf(
                PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 0, totalPlugs = 4)
            )
        )

        // Candidate 3: Equivalent 150kW, 3 available slots, distance ~3.5km
        val cand3 = createSampleStation(
            id = "cand_150kw_far",
            name = "Trạm 150kW Xa",
            lat = 10.8000,
            lon = 106.7250,
            powers = listOf(
                PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 3, totalPlugs = 4)
            )
        )

        // Candidate 4: Equivalent 150kW, 1 available slot, distance ~0.9km (Closest eligible!)
        val cand4 = createSampleStation(
            id = "cand_150kw_closest_available",
            name = "VinFast Thao Dien",
            lat = 10.7840,
            lon = 106.7050,
            powers = listOf(
                PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 1, totalPlugs = 4)
            )
        )

        // Candidate 5: Equivalent 150kW, 2 available, but closed for maintenance
        val cand5 = createSampleStation(
            id = "cand_maintaining",
            name = "Trạm Bảo Trì",
            lat = 10.7790,
            lon = 106.7022,
            powers = listOf(
                PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 2, totalPlugs = 4)
            ),
            depotStatus = "Maintaining"
        )

        val candidates = listOf(cand1, cand2, cand3, cand4, cand5, targetStation)

        // Test pure reroute resolver directly
        val recommendation = FocusModeTelemetryEngine.findAlternativeStation(
            targetStation = targetStation,
            candidates = candidates,
            driverLat = driverLat,
            driverLon = driverLon
        )

        assertNotNull("Should find an alternative recommendation", recommendation)
        assertEquals("cand_150kw_closest_available", recommendation!!.station.id)
        assertEquals("VinFast Thao Dien", recommendation.station.name)
        assertEquals(150_000L, recommendation.matchingPowerWatts)
        assertEquals(150, recommendation.matchingPowerKw)
        assertEquals(1, recommendation.availableDcSlots)
        assertEquals(4, recommendation.totalDcSlots)
        assertTrue("Distance should be approximately 0.9km", recommendation.distanceKm in 0.7..1.2)
        assertTrue(
            "Display label should format correctly",
            recommendation.displayRerouteLabel.startsWith("Đổi trạm: VinFast Thao Dien")
        )

        // Test integrated behavior in engine
        val engine = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetStation) },
            fetchNearbyCandidates = { _, _ -> Result.success(candidates) },
            locationProvider = { Pair(driverLat, driverLon) }
        )

        val polledState = engine.pollOnce()
        assertTrue("Target station should be full (0 DC available)", polledState.isDcFull)
        assertEquals("🔴 HẾT CHỖ!", polledState.statusBadgeText)
        assertNotNull("Alternative station recommendation must be attached", polledState.alternativeStation)
        assertEquals("cand_150kw_closest_available", polledState.alternativeStation?.station?.id)

        // When target station becomes available again on next poll, recommendation clears
        val targetNowAvailable = targetStation.copy(
            powers = listOf(PowerPort(typeWatts = 150_000L, label = "150kW", availablePlugs = 1, totalPlugs = 4))
        )
        val engineWithFreeSlot = FocusModeTelemetryEngine(
            initialStation = targetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(targetNowAvailable) },
            fetchNearbyCandidates = { _, _ -> Result.success(candidates) },
            locationProvider = { Pair(driverLat, driverLon) }
        )

        val stateAvailable = engineWithFreeSlot.pollOnce()
        assertFalse(stateAvailable.isDcFull)
        assertNull(stateAvailable.alternativeStation)
        assertEquals("🟢 1/4 Trống (150kW)", stateAvailable.statusBadgeText)
    }

    // =========================================================================
    // 5. Coroutine Lifecycle & Clean Cancellation Safety
    // =========================================================================

    @Test
    fun engineLifecycle_startAndStopHandleCoroutinesCleanlyWithoutLeaks() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var pollCount = 0
        val station = createSampleStation(
            id = "st_lifecycle",
            name = "Test Lifecycle",
            lat = 10.77,
            lon = 106.70,
            powers = listOf(PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 2, totalPlugs = 2))
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = station,
            fetchStationTelemetry = { _, _, _ ->
                pollCount++
                Result.success(station)
            },
            locationProvider = { Pair(10.77, 106.70) }, // 0km distance -> 5s interval
            defaultDispatcher = testDispatcher,
            coroutineScope = testScope
        )

        assertFalse(engine.isRunning)
        engine.start()
        assertTrue(engine.isRunning)

        // Advance 1ms for initial poll
        testScheduler.advanceTimeBy(1)
        assertEquals(1, pollCount)

        // Advance by 5000ms (5s interval for near distance)
        testScheduler.advanceTimeBy(5000)
        assertEquals(2, pollCount)

        // Advance another 5000ms
        testScheduler.advanceTimeBy(5000)
        assertEquals(3, pollCount)

        // Stop engine cleanly
        engine.stop()
        assertFalse(engine.isRunning)

        // Advance further time; verify no further polling occurs
        testScheduler.advanceTimeBy(20000)
        assertEquals(3, pollCount)
    }
}
