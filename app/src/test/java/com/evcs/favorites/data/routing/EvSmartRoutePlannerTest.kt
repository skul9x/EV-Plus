package com.evcs.favorites.data.routing

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification Test for Phase 03:
 * Smart EV Corridor Route Planner Engine (EvSmartRoutePlanner).
 *
 * Verifies all core requirements:
 * 1. Base polyline & cumulative prefix-sum distance calculation.
 * 2. Spatial corridor buffer filtering (<= 4.0 km retained, > 4.0 km excluded).
 * 3. Highway dual-carriageway anti-trap detour rejection (> 3.0 km).
 * 4. Charger power hierarchy (Ultra-Fast DC >= 60kW > Standard DC 30kW, exclude slow AC <= 11kW).
 * 5. Multi-stop greedy leapfrog scheduler (0 stops when within safe range; optimal multi-stop when needed).
 * 6. Dead zone detection and warning generation when gaps exceed safe operating range.
 * 7. Busy station detection (0 plugs) with estimated queue time and alternative station suggestions.
 * 8. Energy corridor profile generation (sawtooth depletion and replenishment jumps).
 * 9. Station swap ("Đổi trạm khác") dynamic leg recalculation.
 * 10. Performance benchmark (< 1500ms).
 */
class EvSmartRoutePlannerTest {

    private lateinit var planner: EvSmartRoutePlanner

    // Synthetic North-South Highway Route: Hanoi (21.0, 105.8) to Ha Tinh (~350 km south)
    // Polyline points spaced roughly 50-70 km apart along a southward trajectory
    private val hanoiLat = 21.0000
    private val hanoiLng = 105.8000
    private val haTinhLat = 18.3000
    private val haTinhLng = 105.8000

    private val samplePolyline = listOf(
        RouteCoordinate(21.0000, 105.8000), // km 0 (Hanoi)
        RouteCoordinate(20.4000, 105.8000), // ~km 66.7
        RouteCoordinate(19.8000, 105.8000), // ~km 133.4
        RouteCoordinate(19.2000, 105.8000), // ~km 200.1
        RouteCoordinate(18.7000, 105.8000), // ~km 255.7
        RouteCoordinate(18.3000, 105.8000)  // ~km 300.2 (Destination)
    )

    private val customRouteResult = RoutePathResult(
        coordinates = samplePolyline,
        distanceMeters = 300_200L,
        durationSeconds = 18_000L, // 5 hours
        engineUsed = RoutingEngineType.OSRM
    )

    @Before
    fun setUp() {
        planner = EvSmartRoutePlanner()
    }

    private fun createStation(
        id: String,
        name: String,
        lat: Double,
        lng: Double,
        powerKw: Double,
        totalPlugs: Int = 4,
        availablePlugs: Int = 2,
        address: String = "Quốc lộ 1A",
        summary: String = "Trạm sạc xe điện VinFast",
        drivingMetrics: DrivingMetrics? = null
    ): Station {
        val watts = (powerKw * 1000).toLong()
        val port = PowerPort(
            typeWatts = watts,
            label = "${powerKw.toInt()}kW",
            availablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            displayString = "${powerKw.toInt()}kW: trống $availablePlugs/$totalPlugs cổng"
        )
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = lat,
            longitude = lng,
            summary = summary,
            connectors = "CCS2, Type 2",
            depotStatus = "Normal",
            powers = listOf(port),
            totalPlugs = totalPlugs,
            totalAvailablePlugs = availablePlugs,
            drivingMetrics = drivingMetrics
        )
    }

    @Test
    fun testPrefixSumDistances_isMonotonicAndAccurate() {
        val prefixSums = planner.computePrefixSumDistances(samplePolyline)

        assertEquals(samplePolyline.size, prefixSums.size)
        assertEquals(0.0, prefixSums[0], 0.001)

        // Verify strictly monotonic increase
        for (i in 1 until prefixSums.size) {
            assertTrue("Distance must increase at index $i", prefixSums[i] > prefixSums[i - 1])
        }

        // Total distance from 21.0 to 18.3 latitude along longitude 105.8 is roughly 300 km
        val totalKm = prefixSums.last()
        assertTrue("Total distance should be ~300 km, got $totalKm", totalKm in 290.0..310.0)
    }

    @Test
    fun testCorridorBufferFiltering_retainsNearAndRejectsFar() = runBlocking {
        // Station A: Exactly on route line (lat 20.0, lng 105.80) -> Perp distance ~0 km
        val nearStation = createStation("st_near", "Trạm Trên Tuyến", 20.0, 105.800, 60.0)

        // Station B: 2 km east of route line (lat 20.0, lng 105.82) -> Perp distance ~2.1 km (within 4km buffer)
        val bufferStation = createStation("st_buffer", "Trạm Trong Hành Lang", 20.0, 105.820, 60.0)

        // Station C: 15 km east of route line (lat 20.0, lng 105.95) -> Perp distance ~15.6 km (exceeds 4km buffer)
        val farStation = createStation("st_far", "Trạm Ngoài Hành Lang", 20.0, 105.950, 60.0)

        val evSettings = EvRoutingSettings(vehicleSafeRangeKm = 150)
        val plan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = haTinhLat, destLng = haTinhLng,
            stations = listOf(nearStation, bufferStation, farStation),
            evSettings = evSettings,
            customRoutePath = customRouteResult
        )

        val selectedIds = plan.stops.map { it.station.id }
        assertTrue("Near station or buffer station should be selected", selectedIds.contains("st_near") || selectedIds.contains("st_buffer"))
        assertFalse("Far station (> 4.0 km buffer) must never be selected", selectedIds.contains("st_far"))
    }

    @Test
    fun testHighwayAntiTrap_rejectsOppositeCarriagewayStation() = runBlocking {
        // Station 1: On the highway, but tagged / annotated with opposite direction trap (> 3km detour)
        val trappedStation = createStation(
            id = "st_trapped",
            name = "Trạm Trạm Dừng Nghỉ Cao Tốc (Chiều ngược lại)",
            lat = 20.1000,
            lng = 105.8050,
            powerKw = 180.0, // High power but opposite direction
            address = "Cao tốc Bắc Nam, hướng ngược lại (u-turn cấm)"
        )

        // Station 2: Standard station on the correct carriageway slightly earlier
        val accessibleStation = createStation(
            id = "st_accessible",
            name = "Trạm Dịch Vụ Hướng Nam",
            lat = 20.1500,
            lng = 105.8010,
            powerKw = 60.0,
            address = "Cao tốc Bắc Nam, hướng Nam"
        )

        val evSettings = EvRoutingSettings(vehicleSafeRangeKm = 150)
        val plan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = haTinhLat, destLng = haTinhLng,
            stations = listOf(trappedStation, accessibleStation),
            evSettings = evSettings,
            customRoutePath = customRouteResult
        )

        val selectedIds = plan.stops.map { it.station.id }
        assertTrue("Accessible station must be selected", selectedIds.contains("st_accessible"))
        assertFalse("Opposite carriageway trapped station must be rejected", selectedIds.contains("st_trapped"))
    }

    @Test
    fun testChargerPowerHierarchy_prefersUltraFastDCAndExcludesSlowAC() = runBlocking {
        // Candidate 1: Slow AC (11 kW) - should be strictly excluded
        val slowAcStation = createStation("st_ac", "Trạm AC Chậm", 20.0, 105.800, 11.0)

        // Candidate 2: Standard DC (30 kW)
        val standardDcStation = createStation("st_30kw", "Trạm DC Tiêu Chuẩn 30kW", 20.0, 105.801, 30.0)

        // Candidate 3: Ultra-Fast DC (180 kW)
        val ultraFastStation = createStation("st_180kw", "Trạm Siêu Nhanh 180kW", 20.0, 105.802, 180.0)

        val evSettings = EvRoutingSettings(vehicleSafeRangeKm = 150)
        val plan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = haTinhLat, destLng = haTinhLng,
            stations = listOf(slowAcStation, standardDcStation, ultraFastStation),
            evSettings = evSettings,
            customRoutePath = customRouteResult
        )

        assertEquals("Should select exactly 1 charging stop for leg", 1, plan.stops.size)
        val selected = plan.stops.first()

        assertEquals("Ultra-Fast DC 180kW should be chosen over 30kW and 11kW", "st_180kw", selected.station.id)
        assertEquals(ChargerTier.ULTRA_FAST_DC, selected.chargerTier)
        assertEquals(180.0, selected.maxPowerKw, 0.1)

        // Verify alternatives include 30kW DC but NOT 11kW AC
        val altIds = selected.alternativeStations.map { it.id }
        assertTrue("Alternatives should contain 30kW DC station", altIds.contains("st_30kw"))
        assertFalse("Alternatives must NOT contain 11kW slow AC station", altIds.contains("st_ac"))
    }

    @Test
    fun testShortTrip_requiresZeroStops() = runBlocking {
        // Short trip: 30 km (from km 0 to km 0.27 deg lat ~ 30 km)
        val shortPolyline = listOf(
            RouteCoordinate(21.000, 105.800),
            RouteCoordinate(20.730, 105.800)
        )
        val shortRoute = RoutePathResult(
            coordinates = shortPolyline,
            distanceMeters = 30_000L,
            durationSeconds = 1800L,
            engineUsed = RoutingEngineType.OSRM
        )

        val stationAlong = createStation("st_along", "Trạm Dọc Đường", 20.850, 105.800, 60.0)

        val evSettings = EvRoutingSettings(
            vehicleSafeRangeKm = 200,
            startBatteryPercent = 100,
            arrivalBufferSocPercent = 10
        )

        val plan = planner.planRoute(
            originLat = 21.000, originLng = 105.800,
            destLat = 20.730, destLng = 105.800,
            stations = listOf(stationAlong),
            evSettings = evSettings,
            customRoutePath = shortRoute
        )

        assertTrue("Short trip within safe range must have 0 charging stops", plan.stops.isEmpty())
        assertTrue("Plan should be successful", plan.isSuccess)
        assertNull("No dead zone warning should be present", plan.deadZoneWarning)
        assertTrue("Final battery percent should be > arrivalBufferSoc (85%)", plan.finalBatteryPercent >= 80)
    }

    @Test
    fun testMultiStopGreedyLeapfrog_schedulesOptimalStopsAndDurationBuffer() = runBlocking {
        // Total trip: ~300 km
        // Vehicle range: 120 km safe range, start battery 100%, reserve 10% -> usable range = 120 * 0.9 = 108 km
        // Station 1 at ~km 70 (lat 20.370)
        val station1 = createStation("st_1", "Trạm Dừng 1", 20.370, 105.800, 120.0)
        // Station 2 at ~km 150 (lat 19.650)
        val station2 = createStation("st_2", "Trạm Dừng 2", 19.650, 105.800, 180.0)
        // Station 3 at ~km 225 (lat 18.975)
        val station3 = createStation("st_3", "Trạm Dừng 3", 18.975, 105.800, 60.0)

        val evSettings = EvRoutingSettings(
            vehicleSafeRangeKm = 120,
            startBatteryPercent = 100,
            arrivalBufferSocPercent = 10,
            targetChargingSocPercent = 85,
            safetyDurationBufferEnabled = true,
            safetyDurationBufferRatio = 0.25f
        )

        val plan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = haTinhLat, destLng = haTinhLng,
            stations = listOf(station1, station2, station3),
            evSettings = evSettings,
            customRoutePath = customRouteResult
        )

        assertTrue("Plan should be successful", plan.isSuccess)
        assertEquals("Should schedule exactly 3 charging stops", 3, plan.stops.size)

        for ((index, stop) in plan.stops.withIndex()) {
            assertEquals(index + 1, stop.stopIndex)
            assertTrue("Arrival battery should be >= 10% reserve buffer", stop.arrivalBatteryPercent >= 10)
            assertEquals(85, stop.targetBatteryPercent)
            assertTrue("Estimated charging minutes should be > 0", stop.estimatedChargingMinutes > 0)
            // With +25% buffer enabled, padded minutes >= raw minutes
            assertTrue("Padded minutes should reflect safety buffer", stop.estimatedChargingMinutes >= stop.rawChargingMinutes)
        }

        // Verify Energy Corridor Profile Sawtooth Structure
        assertTrue("Energy profile should have waypoints", plan.energyProfile.isNotEmpty())
        assertEquals(0.0, plan.energyProfile.first().distanceKm, 0.001)
        assertEquals(100, plan.energyProfile.first().batteryPercent)

        val chargingEvents = plan.energyProfile.filter { it.isChargingStop }
        assertEquals("Each stop should generate a replenishment energy jump", plan.stops.size, chargingEvents.size)
        for (event in chargingEvents) {
            assertEquals("Replenishment jump must reach target SoC (85%)", 85, event.batteryPercent)
        }
    }

    @Test
    fun testDeadZoneDetection_raisesHighVisibilityWarning() = runBlocking {
        // Total trip: ~300 km
        // Only one station at km 50, then a massive 250 km gap
        // Vehicle range: 100 km (usable = 90 km) -> cannot reach km 300 after charging at km 50
        val earlyStation = createStation("st_early", "Trạm Đầu Tuyến", 20.550, 105.800, 60.0)

        val evSettings = EvRoutingSettings(
            vehicleSafeRangeKm = 100,
            startBatteryPercent = 100,
            arrivalBufferSocPercent = 10
        )

        val plan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = haTinhLat, destLng = haTinhLng,
            stations = listOf(earlyStation),
            evSettings = evSettings,
            customRoutePath = customRouteResult
        )

        assertFalse("Plan with dead zone must be flagged as unsuccessful", plan.isSuccess)
        assertNotNull("Dead zone warning must be raised", plan.deadZoneWarning)

        val warning = plan.deadZoneWarning!!
        assertTrue("Missing range must be positive", warning.missingRangeKm > 0)
        assertTrue("Warning message should contain Vietnamese alert", warning.message.contains("Cảnh báo vùng trắng sạc"))
        assertEquals(100, warning.safeRangeKm)
    }

    @Test
    fun testBusyStation_flagsBusyWithEstimatedQueueTimeAndProvidesAlternatives() = runBlocking {
        // Destination at lat 19.500 (~166 km from Hanoi)
        val testPolyline = listOf(
            RouteCoordinate(21.000, 105.800),
            RouteCoordinate(20.400, 105.800),
            RouteCoordinate(19.500, 105.800)
        )
        val testRouteResult = RoutePathResult(
            coordinates = testPolyline,
            distanceMeters = 166_000L,
            durationSeconds = 9_000L,
            engineUsed = RoutingEngineType.OSRM
        )

        // Best station has 180kW Ultra-Fast DC, but 0 available plugs (4 total, 0 available)
        val busyBestStation = createStation(
            id = "st_busy",
            name = "Trạm Đang Kín Cổng",
            lat = 20.0,
            lng = 105.800,
            powerKw = 180.0,
            totalPlugs = 4,
            availablePlugs = 0
        )

        // Alternate station has 30kW Standard DC with 2 available plugs
        val availableAltStation = createStation(
            id = "st_available",
            name = "Trạm Còn Cổng Trống",
            lat = 20.01,
            lng = 105.801,
            powerKw = 30.0,
            totalPlugs = 4,
            availablePlugs = 2
        )

        val evSettings = EvRoutingSettings(vehicleSafeRangeKm = 150)
        val plan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = 19.500, destLng = 105.800,
            stations = listOf(busyBestStation, availableAltStation),
            evSettings = evSettings,
            customRoutePath = testRouteResult
        )

        assertEquals("Should schedule 1 charging stop", 1, plan.stops.size)
        val stop = plan.stops.first()
        assertEquals("st_busy", stop.station.id)
        assertEquals(StopAvailabilityStatus.STATION_BUSY, stop.availabilityStatus)
        assertTrue("Estimated queue time should be > 0", stop.estimatedQueueMinutes > 0)
        assertTrue("Badge should display busy message", stop.liveStatusBadge.contains("Đang kín"))
        assertTrue("Alternatives should include available station", stop.alternativeStations.any { it.id == "st_available" })
    }


    @Test
    fun testRecalculateWithAlternateStop_updatesLegsAndEnergyCorridor() = runBlocking {
        val stationA = createStation("st_a", "Trạm A", 20.0, 105.800, 180.0)
        val stationB = createStation("st_b", "Trạm B (Thay thế)", 20.05, 105.802, 60.0)

        val evSettings = EvRoutingSettings(vehicleSafeRangeKm = 150)
        val initialPlan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = haTinhLat, destLng = haTinhLng,
            stations = listOf(stationA, stationB),
            evSettings = evSettings,
            customRoutePath = customRouteResult
        )

        assertEquals("st_a", initialPlan.stops.first().station.id)

        // Swap Stop 1 to Station B
        val swappedPlan = planner.recalculateWithAlternateStop(
            originalPlan = initialPlan,
            stopIndex = 1,
            alternateStation = stationB,
            evSettings = evSettings
        )

        assertEquals("Station B should now be the stop station", "st_b", swappedPlan.stops.first().station.id)
        assertEquals(60.0, swappedPlan.stops.first().maxPowerKw, 0.1)
        assertTrue("Alternatives should contain original station A", swappedPlan.stops.first().alternativeStations.any { it.id == "st_a" })
        assertEquals("Total stops count should remain unchanged", initialPlan.stops.size, swappedPlan.stops.size)
    }

    @Test
    fun testPerformance_runsUnder1500ms() = runBlocking {
        // Create 20 synthetic stations along corridor
        val stations = (1..20).map { i ->
            val lat = 20.9 - (i * 0.12)
            val kw = if (i % 2 == 0) 180.0 else 60.0
            createStation("st_perf_$i", "Trạm Hiệu Năng $i", lat, 105.800, kw)
        }

        val startTime = System.currentTimeMillis()

        val plan = planner.planRoute(
            originLat = hanoiLat, originLng = hanoiLng,
            destLat = haTinhLat, destLng = haTinhLng,
            stations = stations,
            evSettings = EvRoutingSettings(vehicleSafeRangeKm = 120),
            customRoutePath = customRouteResult
        )

        val elapsedMs = System.currentTimeMillis() - startTime

        assertTrue("Execution took ${elapsedMs}ms; must be under 1500ms", elapsedMs < 1500)
        assertTrue("Plan should succeed", plan.isSuccess)
    }
}
