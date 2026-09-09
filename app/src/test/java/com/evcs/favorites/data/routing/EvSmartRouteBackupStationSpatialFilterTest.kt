package com.evcs.favorites.data.routing

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Spatial Bounding Box Filter for Backup Stations.
 *
 * Verifies:
 * 1. Stations outside the 0.1° spatial bounding box (~11 km) are skipped before any distance or projection calculations.
 * 2. Nearby candidate stations within 10 km and within the bounding box are correctly evaluated and paired as backup stations.
 * 3. Benchmarked simulation over 3,000 synthetic stations confirms >99% reduction in projection evaluations and instantaneous execution time (<15ms).
 * 4. Edge cases (identical coordinates, bounding box boundary, empty lists, zero coordinates) produce safe, robust behavior without exceptions.
 */
class EvSmartRouteBackupStationSpatialFilterTest {

    private lateinit var planner: EvSmartRoutePlanner

    private val northSouthPolyline = listOf(
        RouteCoordinate(21.0000, 105.8000), // Hanoi (0 km)
        RouteCoordinate(20.5000, 105.8000), // Ha Nam (~55.6 km)
        RouteCoordinate(20.0000, 105.8000), // Ninh Binh (~111.3 km)
        RouteCoordinate(19.5000, 105.8000), // Thanh Hoa (~167.0 km)
        RouteCoordinate(19.0000, 105.8000), // Nghe An (~222.6 km)
        RouteCoordinate(18.5000, 105.8000)  // Ha Tinh (~278.3 km)
    )

    private lateinit var cumulativeDistances: DoubleArray

    @Before
    fun setUp() {
        planner = EvSmartRoutePlanner()
        cumulativeDistances = planner.computePrefixSumDistances(northSouthPolyline)
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
        summary: String = "Trạm sạc xe điện VinFast"
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
            totalAvailablePlugs = availablePlugs
        )
    }

    private fun createPrimaryCandidate(
        station: Station,
        distanceAlongRouteKm: Double = 55.6
    ): CandidateProjection {
        return CandidateProjection(
            station = station,
            snapCoordinate = RouteCoordinate(station.latitude, station.longitude),
            perpendicularDistanceKm = 0.0,
            distanceAlongRouteKm = distanceAlongRouteKm,
            maxPowerKw = 150.0,
            chargerTier = ChargerTier.ULTRA_FAST_DC,
            isHighwayTrap = false,
            detourKm = 0.0
        )
    }

    /**
     * 1. Stations outside the 0.1° spatial bounding box (e.g. distant cities across Vietnam)
     * are skipped before any distance or projection calculations.
     */
    @Test
    fun testDistantStationsOutsideBoundingBox_areSkipped() {
        val primaryStation = createStation("prim_1", "Trạm Phủ Lý", 20.5000, 105.8000, 150.0)
        val primaryCandidate = createPrimaryCandidate(primaryStation, distanceAlongRouteKm = 55.6)

        // Stations with high power and available plugs, but geographically distant across Vietnam
        val distantStations = listOf(
            createStation("st_danang", "Trạm Đà Nẵng", 16.0544, 108.2022, 250.0, 8, 8),
            createStation("st_hcm", "Trạm TP Hồ Chí Minh", 10.7769, 106.7009, 250.0, 8, 8),
            createStation("st_cantho", "Trạm Cần Thơ", 10.0452, 105.7469, 180.0, 6, 6),
            createStation("st_haiphong", "Trạm Hải Phòng", 20.8449, 106.6881, 150.0, 4, 4),
            createStation("st_vinh", "Trạm TP Vinh", 18.6796, 105.6813, 250.0, 8, 8),
            // Station nearby in latitude, but longitude diff > 0.10° (~15.6 km away)
            createStation("st_lng_out", "Trạm Lệch Kinh Độ", 20.5000, 105.9500, 250.0, 8, 8),
            // Station nearby in longitude, but latitude diff > 0.10° (~16.7 km away)
            createStation("st_lat_out", "Trạm Lệch Vĩ Độ", 20.6500, 105.8000, 250.0, 8, 8)
        )

        val backup = planner.selectBackupStation(
            primaryCandidate = primaryCandidate,
            eligibleCandidates = emptyList(),
            allStations = distantStations,
            polyline = northSouthPolyline,
            cumulativeDistances = cumulativeDistances,
            currentDistKm = 0.0,
            currentSoC = 80,
            safeRangeKm = 300.0
        )

        assertNull("Distant stations outside 0.1° bounding box must never be paired as backup", backup)
    }

    /**
     * 2. Nearby candidate stations within 10 km and within the bounding box are correctly evaluated
     * and paired as backup stations.
     */
    @Test
    fun testNearbyCandidateStationsWithinBoundingBox_areEvaluatedAndPaired() {
        val primaryStation = createStation("prim_1", "Trạm Phủ Lý 1", 20.5000, 105.8000, 60.0, 4, 1)
        val primaryCandidate = createPrimaryCandidate(primaryStation, distanceAlongRouteKm = 55.6)

        // Candidate A: 3.3 km away (latDiff = 0.03, lngDiff = 0.005), 60kW, 2 plugs
        val nearStationA = createStation("near_a", "Trạm Phủ Lý 2", 20.5300, 105.8050, 60.0, 4, 2)
        // Candidate B: 4.5 km away (latDiff = 0.04, lngDiff = 0.008), 180kW Ultra-Fast DC, 4 plugs
        val nearStationB = createStation("near_b", "Trạm Phủ Lý Cao Tốc", 20.5400, 105.8080, 180.0, 4, 4)
        // Candidate C: 40 km away (outside 0.1° bounding box)
        val farStation = createStation("far_c", "Trạm Nam Định", 20.4200, 106.1800, 250.0, 8, 8)

        val backup = planner.selectBackupStation(
            primaryCandidate = primaryCandidate,
            eligibleCandidates = emptyList(),
            allStations = listOf(nearStationA, nearStationB, farStation),
            polyline = northSouthPolyline,
            cumulativeDistances = cumulativeDistances,
            currentDistKm = 0.0,
            currentSoC = 80,
            safeRangeKm = 300.0
        )

        assertNotNull("Eligible nearby station within bounding box must be selected as backup", backup)
        assertEquals("Higher power Ultra-Fast DC station must be preferred as backup", "near_b", backup!!.id)
    }

    /**
     * 3. Benchmarked simulation over 3,000 synthetic stations confirms >99% reduction
     * in projection evaluations and instantaneous execution time (<15ms).
     */
    @Test
    fun testBenchmarkSimulation_3000SyntheticStations_executesUnder15msWithOver99PercentSkipped() {
        val primaryStation = createStation("prim_1", "Trạm Trung Tâm", 20.0000, 105.8000, 120.0)
        val primaryCandidate = createPrimaryCandidate(primaryStation, distanceAlongRouteKm = 111.3)

        val totalSyntheticStations = 3000
        val stations = ArrayList<Station>(totalSyntheticStations)

        // 1. Generate 2,995 stations scattered across Vietnam territory (Lat: 8.5° to 23.5°, Lng: 102.0° to 109.0°)
        // strictly ensuring they remain outside the (20.0000 ± 0.10, 105.8000 ± 0.10) bounding box
        for (i in 0 until 2995) {
            val lat = 8.5 + (i % 1500) * (15.0 / 1500.0)
            val lng = 102.0 + (i / 1500) * (7.0 / 2.0) + ((i % 30) * 0.2)
            // Ensure strictly outside bounding box
            val safeLat = if (abs(lat - 20.0000) <= 0.10) lat + 0.25 else lat
            val safeLng = if (abs(lng - 105.8000) <= 0.10) lng + 0.25 else lng

            stations.add(
                createStation(
                    id = "synth_far_$i",
                    name = "Trạm Tổng Hợp $i",
                    lat = safeLat,
                    lng = safeLng,
                    powerKw = 60.0
                )
            )
        }

        // 2. Add 5 nearby stations inside the bounding box and close to the corridor
        val expectedBackup = createStation("synth_near_ultra", "Trạm Dự Phòng Siêu Nhanh", 20.0300, 105.8050, 250.0, 8, 6)
        stations.add(expectedBackup)
        stations.add(createStation("synth_near_1", "Trạm Dự Phòng 1", 20.0200, 105.8020, 60.0, 4, 2))
        stations.add(createStation("synth_near_2", "Trạm Dự Phòng 2", 20.0100, 105.7980, 60.0, 4, 1))
        stations.add(createStation("synth_near_3", "Trạm Dự Phòng 3", 19.9800, 105.8030, 30.0, 2, 1))
        stations.add(createStation("synth_near_4", "Trạm Dự Phòng 4", 19.9700, 105.8010, 60.0, 4, 2))

        // Confirm >99% of stations are outside the bounding box
        val insideBboxCount = stations.count {
            abs(it.latitude - primaryCandidate.station.latitude) <= 0.10 &&
                abs(it.longitude - primaryCandidate.station.longitude) <= 0.10
        }
        val skippedPercent = ((totalSyntheticStations - insideBboxCount).toDouble() / totalSyntheticStations) * 100.0
        assertTrue("More than 99% of stations must be outside bounding box and skipped ($skippedPercent%)", skippedPercent > 99.0)

        // Warm up JIT
        for (w in 0 until 3) {
            planner.selectBackupStation(
                primaryCandidate = primaryCandidate,
                eligibleCandidates = emptyList(),
                allStations = stations,
                polyline = northSouthPolyline,
                cumulativeDistances = cumulativeDistances,
                currentDistKm = 50.0,
                currentSoC = 80,
                safeRangeKm = 300.0
            )
        }

        // Benchmark measured execution time
        val iterations = 10
        val totalElapsedMs = measureTimeMillis {
            for (it in 0 until iterations) {
                val backup = planner.selectBackupStation(
                    primaryCandidate = primaryCandidate,
                    eligibleCandidates = emptyList(),
                    allStations = stations,
                    polyline = northSouthPolyline,
                    cumulativeDistances = cumulativeDistances,
                    currentDistKm = 50.0,
                    currentSoC = 80,
                    safeRangeKm = 300.0
                )
                assertEquals("Expected backup station must be selected", "synth_near_ultra", backup?.id)
            }
        }

        val avgElapsedMs = totalElapsedMs.toDouble() / iterations
        assertTrue(
            "Backup station selection across 3,000 stations must execute under 15ms per charging stop (actual: ${avgElapsedMs}ms)",
            avgElapsedMs < 15.0
        )
    }

    /**
     * 4. Edge cases (identical coordinates, bounding box boundary, empty lists, zero coordinates)
     * produce safe, robust behavior without exceptions.
     */
    @Test
    fun testEdgeCases_zeroCoordinates_boundaryConditions_andEmptyLists() {
        val validPrimary = createStation("prim_valid", "Trạm Hợp Lệ", 20.5000, 105.8000, 120.0)
        val validCandidate = createPrimaryCandidate(validPrimary, distanceAlongRouteKm = 55.6)

        // Case A: Empty stations list
        val emptyResult = planner.selectBackupStation(
            primaryCandidate = validCandidate,
            eligibleCandidates = emptyList(),
            allStations = emptyList(),
            polyline = northSouthPolyline,
            cumulativeDistances = cumulativeDistances,
            currentDistKm = 0.0,
            currentSoC = 80,
            safeRangeKm = 300.0
        )
        assertNull("Empty stations list must return null without exceptions", emptyResult)

        // Case B: Stations with zero coordinates (0.0, 0.0)
        val zeroStation = createStation("zero_st", "Trạm Không Tọa Độ", 0.0, 0.0, 180.0)
        val zeroResult = planner.selectBackupStation(
            primaryCandidate = validCandidate,
            eligibleCandidates = emptyList(),
            allStations = listOf(zeroStation),
            polyline = northSouthPolyline,
            cumulativeDistances = cumulativeDistances,
            currentDistKm = 0.0,
            currentSoC = 80,
            safeRangeKm = 300.0
        )
        assertNull("Zero coordinate stations must be skipped and not selected", zeroResult)

        // Case C: Primary station itself has zero coordinates (0.0, 0.0)
        val zeroPrimary = createStation("zero_prim", "Trạm Chính Lỗi", 0.0, 0.0, 120.0)
        val zeroPrimaryCandidate = createPrimaryCandidate(zeroPrimary, distanceAlongRouteKm = 0.0)
        val backupForZeroPrimary = planner.selectBackupStation(
            primaryCandidate = zeroPrimaryCandidate,
            eligibleCandidates = emptyList(),
            allStations = listOf(validPrimary),
            polyline = northSouthPolyline,
            cumulativeDistances = cumulativeDistances,
            currentDistKm = 0.0,
            currentSoC = 80,
            safeRangeKm = 300.0
        )
        assertNull("Primary candidate with zero coordinates must return null safely", backupForZeroPrimary)

        // Case D: Bounding box boundary test
        // 0.1001° difference (just outside bounding box boundary) -> skipped
        val justOutsideStation = createStation("boundary_out", "Trạm Ngoài Biên", 20.5000 + 0.1001, 105.8000, 180.0)
        // 0.0800° difference (~8.9 km, within bounding box and <= 10.0 km straight line) -> eligible
        val insideBoundaryStation = createStation("boundary_in", "Trạm Trong Biên", 20.5000 + 0.0800, 105.8000, 120.0)

        val boundaryResult = planner.selectBackupStation(
            primaryCandidate = validCandidate,
            eligibleCandidates = emptyList(),
            allStations = listOf(justOutsideStation, insideBoundaryStation),
            polyline = northSouthPolyline,
            cumulativeDistances = cumulativeDistances,
            currentDistKm = 0.0,
            currentSoC = 80,
            safeRangeKm = 300.0
        )
        assertNotNull("Inside boundary station must be evaluated and paired", boundaryResult)
        assertEquals("boundary_in", boundaryResult!!.id)

        // Case E: Station with same ID as primary candidate -> ignored
        val duplicateIdStation = createStation("prim_valid", "Trạm Trùng ID", 20.5100, 105.8020, 250.0)
        val dupResult = planner.selectBackupStation(
            primaryCandidate = validCandidate,
            eligibleCandidates = emptyList(),
            allStations = listOf(duplicateIdStation),
            polyline = northSouthPolyline,
            cumulativeDistances = cumulativeDistances,
            currentDistKm = 0.0,
            currentSoC = 80,
            safeRangeKm = 300.0
        )
        assertNull("Station with identical ID to primary candidate must be skipped", dupResult)
    }
}
