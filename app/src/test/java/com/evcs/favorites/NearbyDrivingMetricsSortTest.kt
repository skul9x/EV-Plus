package com.evcs.favorites

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.filter.NearbyStationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NearbyStationFilter.sortByDrivingDistance].
 *
 * Verifies:
 * 1. Stations with driving metrics are ordered by road distance ascending.
 * 2. Inversion case: corrects Haversine discrepancy where smaller straight-line distance has longer road distance.
 * 3. Tie-breaking by driving duration (ETA) when road distances are equal.
 * 4. Fallback to Haversine straight-line distance (distanceKm * 1000.0) when driving metrics are null or <= 0.
 * 5. Mixed metrics (some with driving metrics, some with null) sort gracefully and correctly.
 * 6. Edge cases (empty list and single element list) return as-is without exceptions.
 * 7. Stability tie-breaking by station id when distance and duration are identical.
 */
class NearbyDrivingMetricsSortTest {

    private fun createTestStation(
        id: String,
        name: String = "Station $id",
        distanceKm: Double? = null,
        drivingDistanceMeters: Long? = null,
        durationSeconds: Long? = null
    ): Station {
        val drivingMetrics = if (drivingDistanceMeters != null && durationSeconds != null) {
            DrivingMetrics(
                distanceMeters = drivingDistanceMeters,
                durationSeconds = durationSeconds,
                trafficCondition = TrafficCondition.FREE_FLOW,
                engineUsed = RoutingEngineType.OSRM
            )
        } else null

        return Station(
            id = id,
            name = name,
            address = "Address $id",
            latitude = 21.0,
            longitude = 105.0,
            summary = "Summary $id",
            connectors = "DC 60kW",
            depotStatus = "Normal",
            distanceKm = distanceKm,
            drivingMetrics = drivingMetrics
        )
    }

    @Test
    fun testSortByDrivingDistance_whenMetricsPresent_ordersByRoadDistanceAscending() {
        val stationA = createTestStation(id = "A", drivingDistanceMeters = 3500L, durationSeconds = 600L)
        val stationB = createTestStation(id = "B", drivingDistanceMeters = 1200L, durationSeconds = 250L)
        val stationC = createTestStation(id = "C", drivingDistanceMeters = 5000L, durationSeconds = 900L)

        val input = listOf(stationA, stationC, stationB)
        val result = NearbyStationFilter.sortByDrivingDistance(input)

        assertEquals(listOf("B", "A", "C"), result.map { it.id })
    }

    @Test
    fun testSortByDrivingDistance_inversionCase_correctsHaversineDiscrepancy() {
        // Station A: Closer straight-line (2.0 km), but longer driving detour (5.5 km / 5500m)
        val stationA = createTestStation(
            id = "station_detour",
            distanceKm = 2.0,
            drivingDistanceMeters = 5500L,
            durationSeconds = 800L
        )

        // Station B: Farther straight-line (2.5 km), but shorter direct road (2.8 km / 2800m)
        val stationB = createTestStation(
            id = "station_direct",
            distanceKm = 2.5,
            drivingDistanceMeters = 2800L,
            durationSeconds = 400L
        )

        val input = listOf(stationA, stationB)
        val result = NearbyStationFilter.sortByDrivingDistance(input)

        // Station B must rank ahead of Station A despite larger straight-line Haversine
        assertEquals(listOf("station_direct", "station_detour"), result.map { it.id })
    }

    @Test
    fun testSortByDrivingDistance_whenRoadDistancesEqual_tieBreaksByDuration() {
        val stationFast = createTestStation(id = "fast", drivingDistanceMeters = 3000L, durationSeconds = 300L)
        val stationSlow = createTestStation(id = "slow", drivingDistanceMeters = 3000L, durationSeconds = 700L)

        val input = listOf(stationSlow, stationFast)
        val result = NearbyStationFilter.sortByDrivingDistance(input)

        assertEquals(listOf("fast", "slow"), result.map { it.id })
    }

    @Test
    fun testSortByDrivingDistance_whenMetricsNull_fallsBackToHaversineKm() {
        val stationNearHaversine = createTestStation(id = "near", distanceKm = 1.2, drivingDistanceMeters = null)
        val stationFarHaversine = createTestStation(id = "far", distanceKm = 3.5, drivingDistanceMeters = null)

        val input = listOf(stationFarHaversine, stationNearHaversine)
        val result = NearbyStationFilter.sortByDrivingDistance(input)

        assertEquals(listOf("near", "far"), result.map { it.id })
    }

    @Test
    fun testSortByDrivingDistance_mixedMetrics_handlesGracefully() {
        // Station 1: Driving metric 4000m
        val s1 = createTestStation(id = "s1", distanceKm = 3.0, drivingDistanceMeters = 4000L, durationSeconds = 500L)
        // Station 2: Null driving metric, fallback Haversine 1.5 km -> 1500m
        val s2 = createTestStation(id = "s2", distanceKm = 1.5, drivingDistanceMeters = null)
        // Station 3: Driving metric 2000m
        val s3 = createTestStation(id = "s3", distanceKm = 1.8, drivingDistanceMeters = 2000L, durationSeconds = 300L)
        // Station 4: Null driving metric and null distanceKm -> fallback to Long.MAX_VALUE
        val s4 = createTestStation(id = "s4", distanceKm = null, drivingDistanceMeters = null)

        val input = listOf(s1, s4, s2, s3)
        val result = NearbyStationFilter.sortByDrivingDistance(input)

        // Expected order: s2 (1500m fallback) < s3 (2000m driving) < s1 (4000m driving) < s4 (MAX_VALUE)
        assertEquals(listOf("s2", "s3", "s1", "s4"), result.map { it.id })
    }

    @Test
    fun testSortByDrivingDistance_emptyAndSingleElement_returnsAsIs() {
        val emptyResult = NearbyStationFilter.sortByDrivingDistance(emptyList())
        assertTrue(emptyResult.isEmpty())

        val single = createTestStation(id = "single", distanceKm = 2.0, drivingDistanceMeters = 1500L, durationSeconds = 200L)
        val singleResult = NearbyStationFilter.sortByDrivingDistance(listOf(single))
        assertEquals(1, singleResult.size)
        assertEquals("single", singleResult.first().id)
    }

    @Test
    fun testSortByDrivingDistance_stabilityTieBreakById() {
        val s1 = createTestStation(id = "station_b", drivingDistanceMeters = 2000L, durationSeconds = 300L)
        val s2 = createTestStation(id = "station_a", drivingDistanceMeters = 2000L, durationSeconds = 300L)

        val input = listOf(s1, s2)
        val result = NearbyStationFilter.sortByDrivingDistance(input)

        assertEquals(listOf("station_a", "station_b"), result.map { it.id })
    }
}
