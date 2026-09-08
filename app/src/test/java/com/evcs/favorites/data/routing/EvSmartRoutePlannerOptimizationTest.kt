package com.evcs.favorites.data.routing

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Algorithmic Optimization & Bounding Box Filtering.
 *
 * Verifies:
 * 1. Spatial bounding box calculation and latitude-dependent longitude scaling.
 * 2. O(1) early candidate discard with zero segment iterations for out-of-corridor stations.
 * 3. Numerical accuracy of localized Euclidean segment snapping and Haversine perpendicular distance against ground truth.
 * 4. Behavioral parity for highway anti-trap detection and charger hierarchy filtering.
 * 5. High-volume performance benchmark (10,000 polyline points, 2,000 candidate stations completing in < 100ms).
 */
class EvSmartRoutePlannerOptimizationTest {

    private lateinit var planner: EvSmartRoutePlanner

    private val originLat = 21.0000
    private val originLng = 105.8000
    private val destLat = 18.3000
    private val destLng = 105.8000

    private val simplePolyline = listOf(
        RouteCoordinate(21.0000, 105.8000),
        RouteCoordinate(20.4000, 105.8000),
        RouteCoordinate(19.8000, 105.8000),
        RouteCoordinate(19.2000, 105.8000),
        RouteCoordinate(18.7000, 105.8000),
        RouteCoordinate(18.3000, 105.8000)
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
            displayString = "${powerKw.toInt()}kW: trống $availablePlugs/$totalPlugs"
        )
        return Station(
            id = id,
            name = name,
            address = address,
            latitude = lat,
            longitude = lng,
            summary = summary,
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = listOf(port),
            totalPlugs = totalPlugs,
            totalAvailablePlugs = availablePlugs,
            drivingMetrics = drivingMetrics
        )
    }

    @Test
    fun testRouteBoundingBox_expansionAndContainment() {
        val bufferKm = 4.0
        val bbox = RouteBoundingBox.fromPolyline(simplePolyline, bufferKm)

        val minLatExpected = 18.3000 - (bufferKm / RouteBoundingBox.KM_PER_DEGREE_LAT)
        val maxLatExpected = 21.0000 + (bufferKm / RouteBoundingBox.KM_PER_DEGREE_LAT)
        val midLatRad = Math.toRadians((18.3000 + 21.0000) * 0.5)
        val lngBuffer = bufferKm / (RouteBoundingBox.KM_PER_DEGREE_LAT * cos(midLatRad))
        val minLngExpected = 105.8000 - lngBuffer
        val maxLngExpected = 105.8000 + lngBuffer

        assertEquals(minLatExpected, bbox.minLat, 1e-4)
        assertEquals(maxLatExpected, bbox.maxLat, 1e-4)
        assertEquals(minLngExpected, bbox.minLng, 1e-4)
        assertEquals(maxLngExpected, bbox.maxLng, 1e-4)

        // Points inside corridor bounding box
        assertTrue("Point on route line must be contained", bbox.contains(20.0, 105.800))
        assertTrue("Point within buffer must be contained", bbox.contains(20.0, 105.800 + lngBuffer * 0.5))

        // Points outside corridor bounding box
        assertFalse("Point far east must not be contained", bbox.contains(20.0, 106.500))
        assertFalse("Point far south must not be contained", bbox.contains(17.0, 105.800))
        assertFalse("Point far north must not be contained", bbox.contains(22.0, 105.800))
    }

    @Test
    fun testEarlyCandidateDiscard_zeroSegmentIterationsForOutOfCorridorStations() {
        val cumulativeDistances = planner.computePrefixSumDistances(simplePolyline)
        val totalDistanceKm = cumulativeDistances.last()

        // 5 stations far away (Da Nang, HCMC, Hai Phong, offshore, etc.)
        val outOfCorridorStations = listOf(
            createStation("far_1", "Trạm Đà Nẵng", 16.0544, 108.2022, 60.0),
            createStation("far_2", "Trạm TP.HCM", 10.7769, 106.7009, 180.0),
            createStation("far_3", "Trạm Hải Phòng", 20.8449, 106.6881, 60.0),
            createStation("far_4", "Trạm Cần Thơ", 10.0452, 105.7469, 30.0),
            createStation("far_5", "Trạm Biển Đông", 19.5000, 107.5000, 60.0)
        )

        // 2 stations right along the corridor
        val inCorridorStations = listOf(
            createStation("near_1", "Trạm Ninh Bình", 20.2500, 105.8010, 60.0),
            createStation("near_2", "Trạm Thanh Hóa", 19.8000, 105.8005, 180.0)
        )

        val allStations = outOfCorridorStations + inCorridorStations

        val candidates = planner.filterAndProjectCandidates(
            stations = allStations,
            polyline = simplePolyline,
            cumulativeDistances = cumulativeDistances,
            totalDistanceKm = totalDistanceKm
        )

        // Exactly 2 stations evaluated for polyline projection
        assertEquals("Only candidates inside bounding box must trigger polyline projection", 2, planner.projectionCallCount)
        assertEquals("Candidates list must only contain the 2 in-corridor stations", 2, candidates.size)
        val candidateIds = candidates.map { it.station.id }
        assertTrue(candidateIds.contains("near_1"))
        assertTrue(candidateIds.contains("near_2"))
        assertFalse(candidateIds.contains("far_1"))
    }

    @Test
    fun testNumericalAccuracy_snapCoordinatesAndPerpendicularDistanceMatchGroundTruth() {
        // Linear segment from (21.0000, 105.8000) to (20.0000, 105.8000) - Due South
        val segmentPolyline = listOf(
            RouteCoordinate(21.0000, 105.8000),
            RouteCoordinate(20.0000, 105.8000)
        )
        val cumulative = planner.computePrefixSumDistances(segmentPolyline)
        val totalKm = cumulative.last()

        // 1. Point directly on the line midway
        val onLinePoint = planner.projectPointOntoPolyline(20.5000, 105.8000, segmentPolyline, cumulative)
        org.junit.Assert.assertNotNull(onLinePoint)
        assertEquals(20.5000, onLinePoint!!.snapCoordinate.latitude, 1e-5)
        assertEquals(105.8000, onLinePoint.snapCoordinate.longitude, 1e-5)
        assertEquals(0.0, onLinePoint.perpendicularDistanceKm, 1e-4)
        assertEquals(totalKm * 0.5, onLinePoint.distanceAlongRouteKm, 1e-2)

        // 2. Point offset to the East at lat 20.5000, lng 105.8200
        val offsetPoint = planner.projectPointOntoPolyline(20.5000, 105.8200, segmentPolyline, cumulative)
        org.junit.Assert.assertNotNull(offsetPoint)
        assertEquals(20.5000, offsetPoint!!.snapCoordinate.latitude, 1e-5)
        assertEquals(105.8000, offsetPoint.snapCoordinate.longitude, 1e-5)

        val groundTruthDistanceKm = DistanceCalculator.calculateDistanceKm(20.5000, 105.8200, 20.5000, 105.8000)
        assertEquals(groundTruthDistanceKm, offsetPoint.perpendicularDistanceKm, 1e-4)

        // 3. Point beyond the start (clamped to t=0)
        val beforeStart = planner.projectPointOntoPolyline(21.1000, 105.8000, segmentPolyline, cumulative)
        org.junit.Assert.assertNotNull(beforeStart)
        assertEquals(21.0000, beforeStart!!.snapCoordinate.latitude, 1e-5)
        assertEquals(105.8000, beforeStart.snapCoordinate.longitude, 1e-5)
        assertEquals(0.0, beforeStart.distanceAlongRouteKm, 1e-4)

        // 4. Point beyond the end (clamped to t=1)
        val afterEnd = planner.projectPointOntoPolyline(19.9000, 105.8000, segmentPolyline, cumulative)
        org.junit.Assert.assertNotNull(afterEnd)
        assertEquals(20.0000, afterEnd!!.snapCoordinate.latitude, 1e-5)
        assertEquals(105.8000, afterEnd.snapCoordinate.longitude, 1e-5)
        assertEquals(totalKm, afterEnd.distanceAlongRouteKm, 1e-4)

        // 5. East-West segment: (20.0000, 105.0000) to (20.0000, 106.0000)
        val ewPolyline = listOf(
            RouteCoordinate(20.0000, 105.0000),
            RouteCoordinate(20.0000, 106.0000)
        )
        val ewCumulative = planner.computePrefixSumDistances(ewPolyline)
        val ewPoint = planner.projectPointOntoPolyline(20.0150, 105.5000, ewPolyline, ewCumulative)
        org.junit.Assert.assertNotNull(ewPoint)
        assertEquals(20.0000, ewPoint!!.snapCoordinate.latitude, 1e-4)
        assertEquals(105.5000, ewPoint.snapCoordinate.longitude, 1e-4)
        val ewGroundTruthKm = DistanceCalculator.calculateDistanceKm(20.0150, 105.5000, 20.0000, 105.5000)
        assertEquals(ewGroundTruthKm, ewPoint.perpendicularDistanceKm, 1e-4)
    }

    @Test
    fun testBehavioralParity_highwayAntiTrapAndChargerHierarchy() = runBlocking {
        val cumulative = planner.computePrefixSumDistances(simplePolyline)
        val totalKm = cumulative.last()

        val slowAc = createStation("st_ac", "Trạm AC Chậm 11kW", 20.000, 105.800, 11.0)
        val standardDc = createStation("st_30kw", "Trạm DC Tiêu Chuẩn 30kW", 20.000, 105.801, 30.0)
        val ultraFastDc = createStation("st_180kw", "Trạm Siêu Nhanh 180kW", 20.000, 105.802, 180.0)
        val oppositeTrap = createStation(
            id = "st_trap",
            name = "Trạm Dừng Cao Tốc Hướng Ngược Lại",
            lat = 20.100,
            lng = 105.801,
            powerKw = 180.0,
            address = "Cao tốc, làn ngược chiều"
        )

        val candidates = planner.filterAndProjectCandidates(
            stations = listOf(slowAc, standardDc, ultraFastDc, oppositeTrap),
            polyline = simplePolyline,
            cumulativeDistances = cumulative,
            totalDistanceKm = totalKm
        )

        val candidateIds = candidates.map { it.station.id }
        assertFalse("Slow AC (<= 11kW) must be excluded", candidateIds.contains("st_ac"))
        assertFalse("Opposite carriageway highway trap must be rejected", candidateIds.contains("st_trap"))
        assertTrue("Standard DC (30kW) must be retained", candidateIds.contains("st_30kw"))
        assertTrue("Ultra-fast DC (180kW) must be retained", candidateIds.contains("st_180kw"))
    }

    @Test
    fun testHighVolumeRoutePlanning_10000Points2000Stations_completesUnder100ms() = runBlocking {
        val pointCount = 10_000
        val stationCount = 2_000

        // 1. Generate 10,000 polyline points spanning from 21.0 to 11.0 (~1000 km North-South highway route)
        val longPolyline = ArrayList<RouteCoordinate>(pointCount)
        val latStep = (21.0 - 11.0) / (pointCount - 1)
        for (i in 0 until pointCount) {
            val lat = 21.0 - (i * latStep)
            val lng = 105.8000 + (0.01 * kotlin.math.sin(i * 0.05)) // realistic slight curve
            longPolyline.add(RouteCoordinate(lat, lng))
        }

        // 2. Generate 2,000 stations:
        // - 1,940 stations scattered far outside corridor (different provinces/off-route)
        // - 60 stations placed along the corridor
        val testStations = ArrayList<Station>(stationCount)
        for (i in 0 until 1_940) {
            // Points outside corridor: e.g. longitude 107.0..109.0 or offshore
            val lat = 11.0 + (i % 100) * 0.1
            val lng = 107.5000 + (i % 20) * 0.05
            testStations.add(createStation("far_stat_$i", "Trạm Xa $i", lat, lng, 60.0))
        }

        for (i in 0 until 60) {
            val progress = (i + 1) / 62.0
            val lat = 21.0 - progress * 10.0
            val lng = 105.8000 + (0.005 * (i % 2))
            val kw = if (i % 3 == 0) 180.0 else 60.0
            testStations.add(createStation("corridor_stat_$i", "Trạm Tuyến $i", lat, lng, kw))
        }

        val customRoute = RoutePathResult(
            coordinates = longPolyline,
            distanceMeters = 1_050_000L,
            durationSeconds = 45_000L,
            engineUsed = RoutingEngineType.OSRM
        )

        val evSettings = EvRoutingSettings(vehicleSafeRangeKm = 150)

        // Warm up JIT compiler once
        planner.planRoute(
            originLat = 21.0, originLng = 105.8,
            destLat = 11.0, destLng = 105.8,
            stations = testStations.take(100),
            evSettings = evSettings,
            customRoutePath = customRoute
        )

        // Benchmark high-volume route planning
        val startTime = System.currentTimeMillis()
        val plan = planner.planRoute(
            originLat = 21.0, originLng = 105.8,
            destLat = 11.0, destLng = 105.8,
            stations = testStations,
            evSettings = evSettings,
            customRoutePath = customRoute
        )
        val elapsedMs = System.currentTimeMillis() - startTime

        println("High-volume route planning benchmark (10,000 points, 2,000 stations): ${elapsedMs}ms")

        assertTrue("Execution took ${elapsedMs}ms; must complete in < 100ms", elapsedMs < 100)
        assertTrue("Plan must succeed", plan.isSuccess)
        assertTrue("Should schedule charging stops along the 1000km route", plan.stops.isNotEmpty())
        assertTrue("Out-of-corridor stations should have been pruned by bounding box", planner.projectionCallCount <= 100)
    }
}
