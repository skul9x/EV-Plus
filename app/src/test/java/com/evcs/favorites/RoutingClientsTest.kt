package com.evcs.favorites

import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.GoogleRoutesClient
import com.evcs.favorites.data.routing.OsrmRoutingClient
import com.evcs.favorites.data.routing.RoutingApiException
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import kotlinx.coroutines.runBlocking
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

/**
 * Comprehensive verification test for Phase 01: Core Routing Clients & Data Models.
 *
 * Validates:
 * 1. DrivingMetrics domain models, formattedDuration, formattedDistance, and TrafficCondition delay ratio / speed heuristic.
 * 2. GoogleRoutesClient Tier 1 client: headers (X-Goog-Api-Key, X-Goog-FieldMask, X-Android-Package), JSON payload,
 *    response parsing with staticDuration delay ratio, and resilient HTTP 400/403/429 error mapping.
 * 3. OsrmRoutingClient Tier 2 client: {lon},{lat} coordinate URL format, User-Agent header, matrix offset mapping (i + 1),
 *    handling of null matrix cells, custom server URL routing, and resilient HTTP 500 error mapping.
 */
class RoutingClientsTest {

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // =================================================================
    // 1. Domain Models & Traffic Condition Tests
    // =================================================================

    @Test
    fun testTrafficCondition_delayRatioCalculations() {
        // Ratio >= 1.35 -> HEAVY_CONGESTION (Ùn tắc)
        val heavy = TrafficCondition.computeCondition(durationSeconds = 405, staticDurationSeconds = 300, distanceMeters = 3000)
        assertEquals(TrafficCondition.HEAVY_CONGESTION, heavy)
        assertEquals("Ùn tắc", heavy.displayName)

        // 1.15 <= Ratio < 1.35 -> MODERATE_CONGESTION (Đông đúc)
        val moderate = TrafficCondition.computeCondition(durationSeconds = 360, staticDurationSeconds = 300, distanceMeters = 3000)
        assertEquals(TrafficCondition.MODERATE_CONGESTION, moderate)
        assertEquals("Đông đúc", moderate.displayName)

        // Ratio < 1.15 -> FREE_FLOW (Thông thoáng)
        val freeFlow = TrafficCondition.computeCondition(durationSeconds = 300, staticDurationSeconds = 300, distanceMeters = 3000)
        assertEquals(TrafficCondition.FREE_FLOW, freeFlow)
        assertEquals("Thông thoáng", freeFlow.displayName)

        // Boundary condition: exactly 1.14 -> FREE_FLOW
        val justFree = TrafficCondition.computeCondition(durationSeconds = 342, staticDurationSeconds = 300, distanceMeters = 3000)
        assertEquals(TrafficCondition.FREE_FLOW, justFree)
    }

    @Test
    fun testTrafficCondition_speedFallbackWhenStaticDurationAbsent() {
        // 60 km/h >= 30 -> FREE_FLOW (10km in 10min)
        val fast = TrafficCondition.computeCondition(durationSeconds = 600, staticDurationSeconds = null, distanceMeters = 10000)
        assertEquals(TrafficCondition.FREE_FLOW, fast)

        // 20 km/h in [15..30) -> MODERATE_CONGESTION (2km in 6min)
        val mid = TrafficCondition.computeCondition(durationSeconds = 360, staticDurationSeconds = null, distanceMeters = 2000)
        assertEquals(TrafficCondition.MODERATE_CONGESTION, mid)

        // 6 km/h < 15 -> HEAVY_CONGESTION (1km in 10min)
        val slow = TrafficCondition.computeCondition(durationSeconds = 600, staticDurationSeconds = null, distanceMeters = 1000)
        assertEquals(TrafficCondition.HEAVY_CONGESTION, slow)

        // Invalid / Non-positive duration or distance -> UNKNOWN
        val unknown = TrafficCondition.computeCondition(durationSeconds = 0, staticDurationSeconds = null, distanceMeters = 1000)
        assertEquals(TrafficCondition.UNKNOWN, unknown)
    }

    @Test
    fun testDrivingMetrics_formattingProperties() {
        val metricUnderKm = DrivingMetrics(
            distanceMeters = 850,
            durationSeconds = 900, // 15 min
            staticDurationSeconds = 900,
            trafficCondition = TrafficCondition.FREE_FLOW,
            engineUsed = RoutingEngineType.GOOGLE
        )
        assertEquals("850 m", metricUnderKm.formattedDistance)
        assertEquals("15 phút", metricUnderKm.formattedDuration)

        val metricOverHour = DrivingMetrics(
            distanceMeters = 4200,
            durationSeconds = 4200, // 1 hr 10 min
            staticDurationSeconds = 3000,
            trafficCondition = TrafficCondition.HEAVY_CONGESTION,
            engineUsed = RoutingEngineType.GOOGLE
        )
        assertEquals("4.2 km", metricOverHour.formattedDistance)
        assertEquals("1 giờ 10 phút", metricOverHour.formattedDuration)

        val metricExactHour = DrivingMetrics(
            distanceMeters = 1000,
            durationSeconds = 3600, // 1 hr exact
            trafficCondition = TrafficCondition.MODERATE_CONGESTION,
            engineUsed = RoutingEngineType.OSRM
        )
        assertEquals("1.0 km", metricExactHour.formattedDistance)
        assertEquals("1 giờ", metricExactHour.formattedDuration)

        val metricZero = DrivingMetrics(
            distanceMeters = 0,
            durationSeconds = 0,
            trafficCondition = TrafficCondition.UNKNOWN,
            engineUsed = RoutingEngineType.HAVERSINE
        )
        assertEquals("0 m", metricZero.formattedDistance)
        assertEquals("0 phút", metricZero.formattedDuration)
    }

    // =================================================================
    // 2. GoogleRoutesClient (Tier 1) Tests
    // =================================================================

    @Test
    fun testGoogleRoutesClient_requestHeadersAndPayload() = runBlocking {
        val googleClient = GoogleRoutesClient(baseUrl = mockServer.url("/").toString())

        val mockResponseBody = """
            [
              {
                "originIndex": 0,
                "destinationIndex": 0,
                "status": {},
                "condition": "ROUTE_EXISTS",
                "distanceMeters": 4200,
                "duration": "405s",
                "staticDuration": "300s"
              },
              {
                "originIndex": 0,
                "destinationIndex": 1,
                "status": {},
                "condition": "ROUTE_EXISTS",
                "distanceMeters": 1500,
                "duration": "300s",
                "staticDuration": "300s"
              }
            ]
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody)
        )

        val destinations = listOf(
            RoutingDestination(id = "station_vincom", latitude = 10.7780, longitude = 106.7020),
            RoutingDestination(id = "station_landmark81", latitude = 10.7950, longitude = 106.7218)
        )

        val result = googleClient.computeRouteMatrix(
            apiKey = "AIzaSyTestKey_12345",
            originLat = 10.7769,
            originLng = 106.7009,
            destinations = destinations
        )

        assertTrue("Expected computeRouteMatrix to succeed", result.isSuccess)
        val metricsMap = result.getOrThrow()
        assertEquals(2, metricsMap.size)

        // Inspect HTTP Request dispatched to MockWebServer
        val recordedRequest = mockServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertEquals("/distanceMatrix/v2:computeRouteMatrix", recordedRequest.path)
        assertEquals("AIzaSyTestKey_12345", recordedRequest.getHeader("X-Goog-Api-Key"))
        assertEquals("com.evcs.favorites", recordedRequest.getHeader("X-Android-Package"))
        assertEquals(
            "originIndex,destinationIndex,status,condition,distanceMeters,duration,staticDuration",
            recordedRequest.getHeader("X-Goog-FieldMask")
        )

        val requestBody = recordedRequest.body.readUtf8()
        assertTrue(requestBody.contains(""""travelMode":"DRIVE""""))
        assertTrue(requestBody.contains(""""routingPreference":"TRAFFIC_AWARE""""))
        assertTrue(requestBody.contains("10.7769"))

        // Station 0: 405s / 300s = 1.35 -> HEAVY_CONGESTION
        val station1Metrics = metricsMap["station_vincom"]
        assertNotNull(station1Metrics)
        assertEquals(4200L, station1Metrics!!.distanceMeters)
        assertEquals(405L, station1Metrics.durationSeconds)
        assertEquals(300L, station1Metrics.staticDurationSeconds)
        assertEquals(TrafficCondition.HEAVY_CONGESTION, station1Metrics.trafficCondition)
        assertEquals("Ùn tắc", station1Metrics.trafficCondition.displayName)
        assertEquals(RoutingEngineType.GOOGLE, station1Metrics.engineUsed)

        // Station 1: 300s / 300s = 1.0 -> FREE_FLOW
        val station2Metrics = metricsMap["station_landmark81"]
        assertNotNull(station2Metrics)
        assertEquals(1500L, station2Metrics!!.distanceMeters)
        assertEquals(300L, station2Metrics.durationSeconds)
        assertEquals(300L, station2Metrics.staticDurationSeconds)
        assertEquals(TrafficCondition.FREE_FLOW, station2Metrics.trafficCondition)
        assertEquals("Thông thoáng", station2Metrics.trafficCondition.displayName)
        assertEquals(RoutingEngineType.GOOGLE, station2Metrics.engineUsed)
    }

    @Test
    fun testGoogleRoutesClient_gracefulHttpErrors() = runBlocking {
        val googleClient = GoogleRoutesClient(baseUrl = mockServer.url("/").toString())
        val destinations = listOf(
            RoutingDestination(id = "station_1", latitude = 10.7, longitude = 106.7)
        )

        // 1. HTTP 400 - Invalid API Key
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error": {"code": 400, "message": "API key not valid."}}""")
        )
        val res400 = googleClient.computeRouteMatrix("bad_key", 10.7, 106.7, destinations)
        assertTrue("HTTP 400 must return failure", res400.isFailure)
        val err400 = res400.exceptionOrNull()
        assertTrue(err400 is RoutingApiException)
        assertEquals(400, (err400 as RoutingApiException).statusCode)

        // 2. HTTP 403 - Billing / API not enabled / Key restriction
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error": {"code": 403, "message": "API not enabled."}}""")
        )
        val res403 = googleClient.computeRouteMatrix("restricted_key", 10.7, 106.7, destinations)
        assertTrue("HTTP 403 must return failure", res403.isFailure)
        assertEquals(403, (res403.exceptionOrNull() as RoutingApiException).statusCode)

        // 3. HTTP 429 - Quota limit exceeded
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error": {"code": 429, "message": "Resource has been exhausted."}}""")
        )
        val res429 = googleClient.computeRouteMatrix("quota_key", 10.7, 106.7, destinations)
        assertTrue("HTTP 429 must return failure", res429.isFailure)
        assertEquals(429, (res429.exceptionOrNull() as RoutingApiException).statusCode)

        // 4. Blank API key returns immediate failure
        val blankKeyRes = googleClient.computeRouteMatrix("   ", 10.7, 106.7, destinations)
        assertTrue("Blank key must fail immediately", blankKeyRes.isFailure)
    }

    // =================================================================
    // 3. OsrmRoutingClient (Tier 2) Tests
    // =================================================================

    @Test
    fun testOsrmRoutingClient_requestFormatAndMatrixOffset() = runBlocking {
        val osrmClient = OsrmRoutingClient(defaultBaseUrl = mockServer.url("/").toString())

        // OSRM table response:
        // row 0: source 0 (origin).
        // column 0: origin -> origin (0.0).
        // column 1: origin -> station_a (duration 480.0, distance 3200.0).
        // column 2: origin -> station_b (duration 960.0, distance 7500.0).
        val mockResponseBody = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 480.0, 960.0]
              ],
              "distances": [
                [0.0, 3200.0, 7500.0]
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
            RoutingDestination(id = "station_a", latitude = 21.0245, longitude = 105.8575),
            RoutingDestination(id = "station_b", latitude = 21.0168, longitude = 105.7839)
        )

        val result = osrmClient.computeTable(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinations
        )

        assertTrue("Expected OSRM computeTable to succeed", result.isSuccess)
        val metricsMap = result.getOrThrow()
        assertEquals(2, metricsMap.size)

        // Inspect HTTP Request dispatched to MockWebServer
        val recordedRequest = mockServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertEquals("EVCSFavorites-Android/1.0", recordedRequest.getHeader("User-Agent"))

        // Verify URL coordinate ordering: {lon},{lat} semicolon-delimited
        // Origin: 105.8542,21.0285 ; Dest1: 105.8575,21.0245 ; Dest2: 105.7839,21.0168
        val path = recordedRequest.path.orEmpty()
        assertTrue("Path must match /table/v1/driving/", path.startsWith("/table/v1/driving/"))
        assertTrue("Path must contain origin lon,lat", path.contains("105.8542,21.0285"))
        assertTrue("Path must contain dest1 lon,lat", path.contains("105.8575,21.0245"))
        assertTrue("Path must contain dest2 lon,lat", path.contains("105.7839,21.0168"))
        assertTrue("Path must specify sources=0", path.contains("sources=0"))
        assertTrue("Path must specify annotations=duration,distance", path.contains("annotations=duration,distance"))

        // Verify destination 0 maps to column index 1
        val stationA = metricsMap["station_a"]
        assertNotNull(stationA)
        assertEquals(3200L, stationA!!.distanceMeters)
        assertEquals(480L, stationA.durationSeconds)
        assertNull(stationA.staticDurationSeconds)
        assertEquals(RoutingEngineType.OSRM, stationA.engineUsed)
        // 3.2km in 8min = 24 km/h -> MODERATE_CONGESTION
        assertEquals(TrafficCondition.MODERATE_CONGESTION, stationA.trafficCondition)

        // Verify destination 1 maps to column index 2
        val stationB = metricsMap["station_b"]
        assertNotNull(stationB)
        assertEquals(7500L, stationB!!.distanceMeters)
        assertEquals(960L, stationB.durationSeconds)
        assertEquals(RoutingEngineType.OSRM, stationB.engineUsed)
        // 7.5km in 16min = 28.125 km/h -> MODERATE_CONGESTION
        assertEquals(TrafficCondition.MODERATE_CONGESTION, stationB.trafficCondition)
    }

    @Test
    fun testOsrmRoutingClient_nullMatrixCellsAndServerError() = runBlocking {
        val osrmClient = OsrmRoutingClient(defaultBaseUrl = mockServer.url("/").toString())

        // 1. Matrix with null cell for unreachable destination (e.g. column 2 is null)
        val responseWithNullCell = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 300.0, null]
              ],
              "distances": [
                [0.0, 2000.0, null]
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseWithNullCell)
        )

        val destinations = listOf(
            RoutingDestination(id = "reachable_station", latitude = 21.0, longitude = 105.8),
            RoutingDestination(id = "unreachable_island", latitude = 20.0, longitude = 107.0)
        )

        val resultNullCell = osrmClient.computeTable(21.0, 105.8, destinations)
        mockServer.takeRequest() // Consume request 1
        assertTrue("Result with partial null cells should succeed for reachable stations", resultNullCell.isSuccess)
        val metrics = resultNullCell.getOrThrow()
        assertEquals(1, metrics.size)
        assertNotNull("Reachable station should have metrics", metrics["reachable_station"])
        assertNull("Unreachable station should be safely omitted", metrics["unreachable_island"])

        // 2. HTTP 500 server error
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val result500 = osrmClient.computeTable(21.0, 105.8, destinations)
        mockServer.takeRequest() // Consume request 2
        assertTrue("HTTP 500 must return failure Result without throwing uncaught exceptions", result500.isFailure)
        val err500 = result500.exceptionOrNull()
        assertTrue(err500 is RoutingApiException)
        assertEquals(500, (err500 as RoutingApiException).statusCode)

        // 3. Custom Base URL support
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":"Ok","durations":[[0.0, 100.0]],"distances":[[0.0, 500.0]]}""")
        )

        val customServerUrl = mockServer.url("/custom-osrm/").toString()
        val customResult = osrmClient.computeTable(
            originLat = 21.0,
            originLng = 105.8,
            destinations = listOf(RoutingDestination(id = "station_custom", latitude = 21.01, longitude = 105.81)),
            customBaseUrl = customServerUrl
        )
        assertTrue(customResult.isSuccess)
        val customReq = mockServer.takeRequest()
        assertTrue("Custom URL path must be used", customReq.path.orEmpty().startsWith("/custom-osrm/table/v1/driving/"))
    }
}
