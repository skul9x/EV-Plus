package com.evcs.favorites.data.routing

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.roundToLong

/**
 * Comprehensive verification test for Phase 02: Lookahead Corridor Routing Engine
 * with Power Filtering & Fallback Detection.
 *
 * Verifies:
 * 1. Low starting SoC (e.g. 40%) forces an earlier first charging stop than 100% SoC.
 * 2. Stations below minChargerPowerKw (e.g. 30 kW when 60 kW required) are excluded when Tier 1 stations exist in the window.
 * 3. Lookahead chooses a viable intermediate station over a greedy candidate that leads to an avoidable dead-end.
 * 4. When only < 60 kW stations exist in a window, a complete multi-stop route to destination is generated with InsufficientPowerWarning.
 * 5. True dead zone (no station of any power reachable) flags DeadZoneWarning.
 * 6. Helper planRouteWithRelaxedPower smoothly recalculates route with relaxed power criteria.
 * 7. Sawtooth energy profile accurately captures start SoC, arrival/departure SoC jumps, and destination arrival reserve.
 */
class EvSmartRoutePowerFilterPlannerTest {

    private lateinit var planner: EvSmartRoutePlanner

    private class StraightLineRoutingCoordinator : MultiTierRoutingCoordinator() {
        override suspend fun calculateRoutePath(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            settings: RoutingSettings
        ): RoutePathResult {
            val coords = listOf(
                RouteCoordinate(originLat, originLng),
                RouteCoordinate(destLat, destLng)
            )
            val distKm = (destLat - originLat) * RouteBoundingBox.KM_PER_DEGREE_LAT
            val distMeters = (distKm * 1000).roundToLong()
            val durationSec = (distKm / (60.0 / 3.6)).roundToLong().coerceAtLeast(60L)
            return RoutePathResult(
                coordinates = coords,
                distanceMeters = distMeters,
                durationSeconds = durationSec,
                engineUsed = RoutingEngineType.OSRM
            )
        }
    }

    @Before
    fun setUp() {
        planner = EvSmartRoutePlanner(
            coordinator = StraightLineRoutingCoordinator(),
            corridorBufferDistanceKm = 4.0,
            maxHighwayDetourKm = 3.0
        )
    }

    private fun coordAtDistanceKm(distKm: Double): RouteCoordinate {
        return RouteCoordinate(
            latitude = 10.0 + (distKm / RouteBoundingBox.KM_PER_DEGREE_LAT),
            longitude = 106.0
        )
    }

    private fun createCorridorPath(totalDistKm: Double): RoutePathResult {
        val startCoord = coordAtDistanceKm(0.0)
        val endCoord = coordAtDistanceKm(totalDistKm)
        return RoutePathResult(
            coordinates = listOf(startCoord, endCoord),
            distanceMeters = (totalDistKm * 1000).roundToLong(),
            durationSeconds = (totalDistKm * 60.0).roundToLong()
        )
    }

    private fun createStation(
        id: String,
        name: String,
        distKm: Double,
        powerKw: Double,
        availablePlugs: Int = 4,
        totalPlugs: Int = 4
    ): Station {
        val coord = coordAtDistanceKm(distKm)
        val port = PowerPort(
            typeWatts = (powerKw * 1000).toLong(),
            label = "${powerKw.toInt()}kW",
            availablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            displayString = "${powerKw.toInt()}kW: $availablePlugs/$totalPlugs"
        )
        return Station(
            id = id,
            name = name,
            address = "Corridor Km $distKm",
            latitude = coord.latitude,
            longitude = coord.longitude,
            summary = "Trạm sạc $name $powerKw kW",
            connectors = "${powerKw.toInt()}kW",
            depotStatus = "Normal",
            powers = listOf(port),
            totalPlugs = totalPlugs,
            totalAvailablePlugs = availablePlugs
        )
    }

    @Test
    fun testLowStartingSoc_forcesEarlierFirstChargingStop() = runTest {
        // Safe range: 200 km, buffer: 10%, target: 85%
        // Total distance: 300 km
        val totalDistKm = 300.0
        val corridor = createCorridorPath(totalDistKm)
        val start = coordAtDistanceKm(0.0)
        val dest = coordAtDistanceKm(totalDistKm)

        // Stations at 50 km and 120 km (both 120 kW)
        val st50 = createStation("st_50", "Trạm Km 50", 50.0, 120.0)
        val st120 = createStation("st_120", "Trạm Km 120", 120.0, 120.0)
        val st220 = createStation("st_220", "Trạm Km 220", 220.0, 120.0)
        val stations = listOf(st50, st120, st220)

        // 1. With 100% SoC: first leg usable range is 200 * (100 - 10)/100 = 180 km.
        // Station at 120 km is reachable and provides more progress than 50 km.
        val planFullSoc = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(
                vehicleSafeRangeKm = 200,
                startBatteryPercent = 100,
                arrivalBufferSocPercent = 10,
                targetChargingSocPercent = 85
            ),
            customRoutePath = corridor
        )
        assertTrue(planFullSoc.isSuccess)
        assertEquals("st_120", planFullSoc.stops.first().station.id)

        // 2. With 40% SoC: first leg usable range is 200 * (40 - 10)/100 = 60 km.
        // Station at 120 km is unreachable (> 60 km), forcing stop at 50 km.
        val planLowSoc = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(
                vehicleSafeRangeKm = 200,
                startBatteryPercent = 40,
                arrivalBufferSocPercent = 10,
                targetChargingSocPercent = 85
            ),
            customRoutePath = corridor
        )
        assertTrue(planLowSoc.isSuccess)
        assertEquals("st_50", planLowSoc.stops.first().station.id)
        assertTrue(
            "Low SoC plan first stop distance must be earlier than full SoC plan",
            planLowSoc.stops.first().distanceFromOriginKm < planFullSoc.stops.first().distanceFromOriginKm
        )
    }

    @Test
    fun testPowerFiltering_excludesBelowThresholdWhenTier1StationExists() = runTest {
        // Safe range: 200 km, buffer: 10%, target: 85%
        // Total distance: 250 km. Window for stop 1: (1, 180] km.
        val totalDistKm = 250.0
        val corridor = createCorridorPath(totalDistKm)
        val start = coordAtDistanceKm(0.0)
        val dest = coordAtDistanceKm(totalDistKm)

        // S1 is 30 kW at 130 km (Tier 2, more progress)
        // S2 is 60 kW at 100 km (Tier 1, meeting threshold)
        val st30 = createStation("st_30kw", "Trạm 30kW", 130.0, 30.0)
        val st60 = createStation("st_60kw", "Trạm 60kW", 100.0, 60.0)
        val stations = listOf(st30, st60)

        val plan = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(
                vehicleSafeRangeKm = 200,
                startBatteryPercent = 100,
                arrivalBufferSocPercent = 10,
                targetChargingSocPercent = 85,
                minChargerPowerKw = 60.0
            ),
            customRoutePath = corridor
        )

        assertTrue(plan.isSuccess)
        assertNull("No insufficient power warning when Tier 1 station exists", plan.insufficientPowerWarning)
        assertEquals("Must select 60kW station over 30kW station", "st_60kw", plan.stops.first().station.id)
        assertEquals(60.0, plan.stops.first().maxPowerKw, 0.001)
    }

    @Test
    fun testLookaheadCorridor_avoidsGreedyDeadEndTrap() = runTest {
        // Corridor: Total distance: 380 km.
        // Safe range: 160 km, buffer: 10%, target: 85%.
        // D1 = 160 * (100 - 10)/100 = 144 km. First window: (1, 144] km.
        // Dk = 160 * (85 - 10)/100 = 120 km.
        val totalDistKm = 380.0
        val corridor = createCorridorPath(totalDistKm)
        val start = coordAtDistanceKm(0.0)
        val dest = coordAtDistanceKm(totalDistKm)

        // Candidates:
        // - st_viable at 90 km (60 kW). From 90 km, reach limit is 90 + 120 = 210 km.
        //   Subsequent station st_mid at 130 km is reachable (130 <= 210).
        // - st_mid at 130 km (60 kW).
        // - st_trap at 140 km (60 kW). Greedy candidate with more progress (140 > 90).
        //   From st_trap (140 km), reach limit is 140 + 120 = 260 km.
        //   Next station along route is st_next at 270 km (> 260 km).
        //   st_mid (130 km) is BEHIND st_trap (< 140 km).
        //   Therefore, from st_trap, ZERO forward stations are reachable! Trap is a dead-end!
        // - st_next at 270 km (60 kW).
        val stViable = createStation("st_viable", "Trạm Viable", 90.0, 60.0)
        val stMid = createStation("st_mid", "Trạm Mid", 130.0, 60.0)
        val stTrap = createStation("st_trap", "Trạm Trap", 140.0, 60.0)
        val stFar = createStation("st_far", "Trạm Far", 265.0, 60.0)
        val stations = listOf(stViable, stMid, stTrap, stFar)

        // Verify hasForwardConnectivity directly
        val projections = planner.filterAndProjectCandidates(
            stations = stations,
            polyline = corridor.coordinates,
            cumulativeDistances = planner.computePrefixSumDistances(corridor.coordinates),
            totalDistanceKm = totalDistKm
        )
        val projTrap = projections.first { it.station.id == "st_trap" }
        val projMid = projections.first { it.station.id == "st_mid" }

        val nextLegCapacityKm = 120.0
        val trapConnectivity = planner.hasForwardConnectivity(projTrap, projections, totalDistKm, nextLegCapacityKm)
        val midConnectivity = planner.hasForwardConnectivity(projMid, projections, totalDistKm, nextLegCapacityKm)

        assertFalse("st_trap must have no forward connectivity because next station is beyond reach", trapConnectivity)
        assertTrue("st_mid must have forward connectivity to intermediate stations", midConnectivity)

        // Verify planner chooses viable st_mid over greedy trap st_trap
        val plan = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(
                vehicleSafeRangeKm = 160,
                startBatteryPercent = 100,
                arrivalBufferSocPercent = 10,
                targetChargingSocPercent = 85,
                minChargerPowerKw = 60.0
            ),
            customRoutePath = corridor
        )

        assertEquals("Lookahead must choose st_mid over dead-end greedy trap st_trap", "st_mid", plan.stops.first().station.id)
    }

    @Test
    fun testInsufficientPowerFallback_seamlesslyCompletesRouteWithWarning() = runTest {
        // Safe range: 200 km, buffer: 10%, target: 85%
        // Total distance: 300 km
        val totalDistKm = 300.0
        val corridor = createCorridorPath(totalDistKm)
        val start = coordAtDistanceKm(0.0)
        val dest = coordAtDistanceKm(totalDistKm)

        // Only 30 kW stations available along the corridor (Tier 2 fallback)
        val stF1 = createStation("st_f1", "Trạm Fallback 1", 120.0, 30.0)
        val stF2 = createStation("st_f2", "Trạm Fallback 2", 220.0, 30.0)
        val stations = listOf(stF1, stF2)

        val plan = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(
                vehicleSafeRangeKm = 200,
                startBatteryPercent = 100,
                arrivalBufferSocPercent = 10,
                targetChargingSocPercent = 85,
                minChargerPowerKw = 60.0
            ),
            customRoutePath = corridor
        )

        // Drivable route must successfully complete to destination
        assertTrue("Route must be successful despite fallback", plan.isSuccess)
        assertNull("No dead zone warning should be present", plan.deadZoneWarning)

        // InsufficientPowerWarning must be populated
        assertNotNull("InsufficientPowerWarning must be attached", plan.insufficientPowerWarning)
        val warning = plan.insufficientPowerWarning!!
        assertEquals(60.0, warning.requiredPowerKw, 0.001)
        assertEquals(30.0, warning.fallbackPowerKw, 0.001)
        assertEquals(1, warning.stopIndex)
        assertEquals("st_f1", warning.fallbackStation.id)
        assertEquals(120.0, warning.legDistanceKm, 1.0)
        assertTrue(warning.message.contains("60 kW"))
        assertTrue(warning.message.contains("30 kW"))

        // Complete route features
        assertEquals(2, plan.stops.size)
        assertEquals("st_f1", plan.stops[0].station.id)
        assertEquals("st_f2", plan.stops[1].station.id)
        assertTrue("Final arrival battery SoC must be >= arrival buffer (10%)", plan.finalBatteryPercent >= 10)
    }

    @Test
    fun testTrueDeadZone_flagsDeadZoneWarningWhenNoStationReachable() = runTest {
        // Safe range: 200 km, buffer: 10%, start: 100%
        // Reach limit: 180 km.
        // Stations exist only at 250 km and 380 km (beyond reach).
        val totalDistKm = 500.0
        val corridor = createCorridorPath(totalDistKm)
        val start = coordAtDistanceKm(0.0)
        val dest = coordAtDistanceKm(totalDistKm)

        val stFar1 = createStation("st_far1", "Trạm Xa 1", 250.0, 120.0)
        val stFar2 = createStation("st_far2", "Trạm Xa 2", 380.0, 120.0)
        val stations = listOf(stFar1, stFar2)

        val plan = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(
                vehicleSafeRangeKm = 200,
                startBatteryPercent = 100,
                arrivalBufferSocPercent = 10,
                targetChargingSocPercent = 85,
                minChargerPowerKw = 60.0
            ),
            customRoutePath = corridor
        )

        assertFalse("Route with gap exceeding safe range must not succeed", plan.isSuccess)
        assertNotNull("DeadZoneWarning must be present", plan.deadZoneWarning)
        val deadZone = plan.deadZoneWarning!!
        assertEquals(180.0, deadZone.gapStartKm, 0.5)
        assertEquals(250.0, deadZone.gapEndKm, 0.5)
        assertEquals(70.0, deadZone.missingRangeKm, 0.5)
        assertEquals(200, deadZone.safeRangeKm)
        assertNull("No insufficient power warning on true dead zone", plan.insufficientPowerWarning)
    }

    @Test
    fun testPlanRouteWithRelaxedPower_recalculatesAndClearsWarning() = runTest {
        val totalDistKm = 300.0
        val corridor = createCorridorPath(totalDistKm)
        val start = coordAtDistanceKm(0.0)
        val dest = coordAtDistanceKm(totalDistKm)

        val stF1 = createStation("st_f1", "Trạm 30kW-1", 120.0, 30.0)
        val stF2 = createStation("st_f2", "Trạm 30kW-2", 220.0, 30.0)
        val stations = listOf(stF1, stF2)

        // 1. Initial plan with minChargerPowerKw = 60.0 triggers fallback warning
        val originalPlan = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(minChargerPowerKw = 60.0),
            customRoutePath = corridor
        )
        assertNotNull(originalPlan.insufficientPowerWarning)

        // 2. Recalculate with relaxed threshold = 30.0 kW
        val relaxedPlan = planner.planRouteWithRelaxedPower(
            originalPlan = originalPlan,
            stations = stations,
            relaxedPowerKw = 30.0,
            evSettings = EvRoutingSettings(minChargerPowerKw = 60.0)
        )

        assertTrue(relaxedPlan.isSuccess)
        assertNull("Relaxing threshold to 30kW clears InsufficientPowerWarning", relaxedPlan.insufficientPowerWarning)
        assertEquals(2, relaxedPlan.stops.size)
    }

    @Test
    fun testEnergyProfile_sawtoothWaypointsReflectSoCTransitionsAccurately() = runTest {
        val totalDistKm = 220.0
        val corridor = createCorridorPath(totalDistKm)
        val start = coordAtDistanceKm(0.0)
        val dest = coordAtDistanceKm(totalDistKm)

        val st1 = createStation("st_1", "Trạm 1", 100.0, 120.0)
        val stations = listOf(st1)

        val plan = planner.planRoute(
            originLat = start.latitude,
            originLng = start.longitude,
            destLat = dest.latitude,
            destLng = dest.longitude,
            stations = stations,
            evSettings = EvRoutingSettings(
                vehicleSafeRangeKm = 200,
                startBatteryPercent = 90,
                arrivalBufferSocPercent = 10,
                targetChargingSocPercent = 85
            ),
            customRoutePath = corridor
        )

        assertTrue(plan.isSuccess)
        val waypoints = plan.energyProfile

        // Waypoint 0: Origin (0 km, 90%, not charging)
        assertEquals(0.0, waypoints[0].distanceKm, 0.001)
        assertEquals(90, waypoints[0].batteryPercent)
        assertFalse(waypoints[0].isChargingStop)

        // Waypoint 1: Arrival at Stop 1 (100 km, used 50% SoC from 90% -> 40%)
        assertEquals(100.0, waypoints[1].distanceKm, 1.0)
        assertEquals(40, waypoints[1].batteryPercent)
        assertFalse(waypoints[1].isChargingStop)

        // Waypoint 2: Replenishment jump at Stop 1 (100 km, charged to targetSoc 85%)
        assertEquals(100.0, waypoints[2].distanceKm, 1.0)
        assertEquals(85, waypoints[2].batteryPercent)
        assertTrue(waypoints[2].isChargingStop)

        // Waypoint 3: Destination arrival (220 km, remaining 120 km uses 60% SoC from 85% -> 25%)
        assertEquals(220.0, waypoints.last().distanceKm, 1.0)
        assertTrue("Destination SoC must not drop below arrival buffer", waypoints.last().batteryPercent >= 10)
    }
}
