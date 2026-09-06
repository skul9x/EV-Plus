package com.evcs.favorites.data.routing

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.filter.NearbyStationFilter
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
import java.net.URLDecoder

/**
 * Single Comprehensive Verification Test for Phase 03:
 * Station Deduplication & Coordinate Aggregation for OSRM Routing.
 *
 * Verifies:
 * 1. `NearbyStationFilter.extractTopNearest` deduplicates candidates by station ID.
 * 2. `MultiTierRoutingCoordinator.calculateRoutes` sanitizes destinations by unique ID.
 * 3. `OsrmRoutingClient.computeTable` groups destinations by coordinate pair, ensuring no duplicate
 *    coordinates in query URL.
 * 4. Matrix columns equal unique coordinates + 1, and driving metrics fan out to all stations
 *    sharing identical coordinates.
 * 5. Backwards compatibility for single destination queries, empty destination lists, and unreachable stations.
 */
class OsrmCoordinateDeduplicationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        okHttpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun createTestStation(
        id: String,
        latitude: Double,
        longitude: Double,
        name: String = "Station $id"
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Address $id",
            latitude = latitude,
            longitude = longitude,
            summary = "Summary",
            connectors = "60kW DC",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(
                    typeWatts = 60000L,
                    availablePlugs = 2,
                    totalPlugs = 2,
                    label = "60kW"
                )
            ),
            totalAvailablePlugs = 2,
            totalPlugs = 2
        )
    }

    @Test
    fun testNearbyStationFilter_extractTopNearest_deduplicatesById() {
        val userLat = 21.0285
        val userLon = 105.8542

        // List containing duplicate station IDs
        val stations = listOf(
            createTestStation(id = "station_1", latitude = 21.0300, longitude = 105.8500),
            createTestStation(id = "station_1", latitude = 21.0300, longitude = 105.8500), // Duplicate ID
            createTestStation(id = "station_2", latitude = 21.0400, longitude = 105.8600),
            createTestStation(id = "station_3", latitude = 21.0500, longitude = 105.8700),
            createTestStation(id = "station_2", latitude = 21.0400, longitude = 105.8600)  // Duplicate ID
        )

        val topNearest = NearbyStationFilter.extractTopNearest(
            userLat = userLat,
            userLon = userLon,
            stations = stations,
            limit = 10
        )

        assertEquals(3, topNearest.size)
        val uniqueIds = topNearest.map { it.id }.toSet()
        assertEquals(setOf("station_1", "station_2", "station_3"), uniqueIds)
    }

    @Test
    fun testMultiTierRoutingCoordinator_sanitizesDuplicateDestinationIds() = runBlocking {
        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )
        val coordinator = MultiTierRoutingCoordinator(
            osrmClient = osrmClient
        )

        val mockResponseBody = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 500.0, 800.0]
              ],
              "distances": [
                [0.0, 4000.0, 7000.0]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody)
        )

        val destinationsWithDuplicates = listOf(
            RoutingDestination(id = "st_1", latitude = 21.0245, longitude = 105.8575),
            RoutingDestination(id = "st_1", latitude = 21.0245, longitude = 105.8575), // Duplicate
            RoutingDestination(id = "st_2", latitude = 21.0168, longitude = 105.7839),
            RoutingDestination(id = "st_2", latitude = 21.0168, longitude = 105.7839)  // Duplicate
        )

        val settings = RoutingSettings(
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = false
        )

        val resultMap = coordinator.calculateRoutes(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinationsWithDuplicates,
            settings = settings
        )

        assertEquals(2, resultMap.size)
        assertTrue(resultMap.containsKey("st_1"))
        assertTrue(resultMap.containsKey("st_2"))

        val recorded = mockServer.takeRequest()
        val decodedPath = URLDecoder.decode(recorded.path.orEmpty(), "UTF-8")

        // Should only query for 2 unique destinations (+ 1 origin = 3 coordinate points in URL)
        val pathCoords = decodedPath.substringAfter("/table/v1/driving/").substringBefore("?")
        val coordPairs = pathCoords.split(";")
        assertEquals(3, coordPairs.size)
    }

    @Test
    fun testOsrmRoutingClient_coordinateAggregationAndMetricFanOut() = runBlocking {
        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )

        // Matrix response:
        // Column 0: origin -> origin
        // Column 1: origin -> (106.089996, 21.1675) [Shared by station_post_1, station_post_2, station_post_3]
        // Column 2: origin -> (105.800000, 21.0000) [station_post_4]
        val mockResponseBody = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 600.0, 1200.0]
              ],
              "distances": [
                [0.0, 5000.0, 10000.0]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody)
        )

        // 3 stations share identical coordinates (e.g. multiple charging posts at the same hub)
        val destinations = listOf(
            RoutingDestination(id = "station_post_1", latitude = 21.1675, longitude = 106.089996),
            RoutingDestination(id = "station_post_2", latitude = 21.1675, longitude = 106.089996),
            RoutingDestination(id = "station_post_3", latitude = 21.1675, longitude = 106.089996),
            RoutingDestination(id = "station_post_4", latitude = 21.0000, longitude = 105.800000)
        )

        val result = osrmClient.computeTable(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinations
        )

        assertTrue(result.isSuccess)
        val metricsMap = result.getOrThrow()

        // 1. Verify HTTP Request URL has strictly unique coordinates
        val recordedRequest = mockServer.takeRequest()
        val decodedPath = URLDecoder.decode(recordedRequest.path.orEmpty(), "UTF-8")
        val coordsSegment = decodedPath.substringAfter("/table/v1/driving/").substringBefore("?")
        val coordsList = coordsSegment.split(";")

        // Total coordinate count in URL: 1 origin + 2 unique destination coordinates = 3
        assertEquals(3, coordsList.size)
        assertEquals("105.8542,21.0285", coordsList[0])
        assertEquals("106.089996,21.1675", coordsList[1])
        assertEquals("105.8,21.0", coordsList[2])

        // Verify "106.089996,21.1675" appears only once in the entire path
        val firstIdx = decodedPath.indexOf("106.089996,21.1675")
        val lastIdx = decodedPath.lastIndexOf("106.089996,21.1675")
        assertTrue(firstIdx != -1)
        assertEquals(firstIdx, lastIdx)

        // 2. Verify all 4 station posts received driving metrics (fan-out)
        assertEquals(4, metricsMap.size)

        val post1Metrics = metricsMap["station_post_1"]
        val post2Metrics = metricsMap["station_post_2"]
        val post3Metrics = metricsMap["station_post_3"]
        val post4Metrics = metricsMap["station_post_4"]

        assertNotNull(post1Metrics)
        assertNotNull(post2Metrics)
        assertNotNull(post3Metrics)
        assertNotNull(post4Metrics)

        // Posts 1, 2, and 3 must share the exact metrics from column 1
        assertEquals(5000L, post1Metrics!!.distanceMeters)
        assertEquals(600L, post1Metrics.durationSeconds)

        assertEquals(5000L, post2Metrics!!.distanceMeters)
        assertEquals(600L, post2Metrics.durationSeconds)

        assertEquals(5000L, post3Metrics!!.distanceMeters)
        assertEquals(600L, post3Metrics.durationSeconds)

        // Post 4 received metrics from column 2
        assertEquals(10000L, post4Metrics!!.distanceMeters)
        assertEquals(1200L, post4Metrics.durationSeconds)
    }

    @Test
    fun testOsrmRoutingClient_singleDestination_backwardsCompatible() = runBlocking {
        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )

        val mockResponseBody = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 300.0]
              ],
              "distances": [
                [0.0, 2500.0]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody)
        )

        val destinations = listOf(
            RoutingDestination(id = "single_station", latitude = 21.0500, longitude = 105.8000)
        )

        val result = osrmClient.computeTable(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinations
        )

        assertTrue(result.isSuccess)
        val metricsMap = result.getOrThrow()
        assertEquals(1, metricsMap.size)
        assertEquals(2500L, metricsMap["single_station"]?.distanceMeters)
        assertEquals(300L, metricsMap["single_station"]?.durationSeconds)

        val recorded = mockServer.takeRequest()
        val decodedPath = URLDecoder.decode(recorded.path.orEmpty(), "UTF-8")
        val coordsSegment = decodedPath.substringAfter("/table/v1/driving/").substringBefore("?")
        val coordsList = coordsSegment.split(";")
        assertEquals(2, coordsList.size) // origin + 1 destination
    }

    @Test
    fun testOsrmRoutingClient_emptyDestinations_returnsEmptyMapWithoutHttpCall() = runBlocking {
        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )

        val result = osrmClient.computeTable(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = emptyList()
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun testOsrmRoutingClient_unreachableStation_safelySkipped() = runBlocking {
        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )

        // Column 1 is null (unreachable e.g. island without ferry), Column 2 is reachable
        val mockResponseBody = """
            {
              "code": "Ok",
              "durations": [
                [0.0, null, 400.0]
              ],
              "distances": [
                [0.0, null, 3000.0]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody)
        )

        val destinations = listOf(
            RoutingDestination(id = "unreachable_station", latitude = 20.0000, longitude = 107.0000),
            RoutingDestination(id = "reachable_station", latitude = 21.0000, longitude = 105.8000)
        )

        val result = osrmClient.computeTable(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinations
        )

        assertTrue(result.isSuccess)
        val metricsMap = result.getOrThrow()
        assertEquals(1, metricsMap.size)
        assertNull(metricsMap["unreachable_station"])
        assertNotNull(metricsMap["reachable_station"])
        assertEquals(3000L, metricsMap["reachable_station"]?.distanceMeters)
    }
}
