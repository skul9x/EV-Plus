package com.evcs.favorites

import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.GoogleRoutesClient
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.OsrmRoutingClient
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingSettings
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
 * Comprehensive verification test for Phase 02: Multi-Tier Coordinator & Fallback Arbitration.
 *
 * Validates:
 * 1. Mode Arbitration:
 *    - AUTO mode with valid Google API key -> routes to Tier 1 Google Routes v2.
 *    - AUTO mode with Google error (403/429/network) -> cascades to Tier 2 OSRM when autoFallbackEnabled = true.
 *    - AUTO mode with blank Google key -> routes directly to Tier 2 OSRM.
 *    - AUTO mode with OSRM error -> cascades to Tier 3 Haversine when autoFallbackEnabled = true.
 *    - AUTO mode double cascade (Google fails -> OSRM fails -> Haversine baseline).
 *    - Disabling autoFallbackEnabled terminates cascades immediately and returns emptyMap.
 * 2. Strict Single-Engine Modes:
 *    - GOOGLE_ONLY: calls Google only; strictly no fallback to OSRM or Haversine on failure or blank key.
 *    - OSRM_ONLY: calls OSRM directly (skips Google even with key); cascades to Haversine only if autoFallbackEnabled = true.
 *    - HAVERSINE_ONLY: computes instant local straight-line metrics with zero network calls.
 * 3. Input Sanitization & Resilient Validation:
 *    - Unresolved/invalid origin coordinates (0.0, 0.0 or lat/lng out of range) return emptyMap without network calls.
 *    - Unresolved/invalid destination coordinates (0.0, 0.0 or out of range) are pruned from matrix queries.
 *    - If all destinations are invalid, returns emptyMap immediately.
 */
class MultiTierRoutingCoordinatorTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var coordinator: MultiTierRoutingCoordinator

    private val sampleOriginLat = 10.7769
    private val sampleOriginLng = 106.7009

    private val sampleDestinations = listOf(
        RoutingDestination(id = "station_vincom", latitude = 10.7780, longitude = 106.7020),
        RoutingDestination(id = "station_landmark", latitude = 10.7950, longitude = 106.7218)
    )

    private val googleMockResponseJson = """
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

    private val osrmMockResponseJson = """
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

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        val mockUrl = mockServer.url("/").toString()
        val googleClient = GoogleRoutesClient(baseUrl = mockUrl)
        val osrmClient = OsrmRoutingClient(defaultBaseUrl = mockUrl)

        coordinator = MultiTierRoutingCoordinator(
            googleClient = googleClient,
            osrmClient = osrmClient
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // =================================================================
    // 1. AUTO Mode Tests & Fallback Cascades
    // =================================================================

    @Test
    fun testAutoMode_withValidGoogleKey_routesToGoogle() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(googleMockResponseJson)
        )

        val settings = RoutingSettings(
            googleApiKey = "valid_google_key",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(1, mockServer.requestCount)

        val req = mockServer.takeRequest()
        assertTrue(req.path.orEmpty().contains("/distanceMatrix/v2:computeRouteMatrix"))
        assertEquals("valid_google_key", req.getHeader("X-Goog-Api-Key"))

        val vincom = results["station_vincom"]
        assertNotNull(vincom)
        assertEquals(4200L, vincom!!.distanceMeters)
        assertEquals(405L, vincom.durationSeconds)
        assertEquals(TrafficCondition.HEAVY_CONGESTION, vincom.trafficCondition)
        assertEquals(RoutingEngineType.GOOGLE, vincom.engineUsed)
    }

    @Test
    fun testAutoMode_withGoogleError403_cascadesToOsrmWhenAutoFallbackEnabled() = runBlocking {
        // First call (Google) fails with HTTP 403 Forbidden
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error": {"code": 403, "message": "API Key restricted"}}""")
        )
        // Second call (OSRM) succeeds with HTTP 200
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(osrmMockResponseJson)
        )

        val settings = RoutingSettings(
            googleApiKey = "restricted_key",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(2, mockServer.requestCount)

        val req1 = mockServer.takeRequest()
        assertTrue("Request 1 must be Google", req1.path.orEmpty().contains("/distanceMatrix/v2:computeRouteMatrix"))

        val req2 = mockServer.takeRequest()
        assertTrue("Request 2 must cascade to OSRM", req2.path.orEmpty().contains("/table/v1/driving/"))

        val stationA = results["station_vincom"]
        assertNotNull(stationA)
        assertEquals(3200L, stationA!!.distanceMeters)
        assertEquals(480L, stationA.durationSeconds)
        assertEquals(RoutingEngineType.OSRM, stationA.engineUsed)
    }

    @Test
    fun testAutoMode_withBlankGoogleKey_routesDirectlyToOsrm() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(osrmMockResponseJson)
        )

        val settings = RoutingSettings(
            googleApiKey = "", // Blank key
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(1, mockServer.requestCount)

        val req = mockServer.takeRequest()
        assertTrue("Must query OSRM directly", req.path.orEmpty().contains("/table/v1/driving/"))

        val stationA = results["station_vincom"]
        assertNotNull(stationA)
        assertEquals(RoutingEngineType.OSRM, stationA!!.engineUsed)
    }

    @Test
    fun testAutoMode_cascadesToHaversineWhenBothGoogleAndOsrmFail() = runBlocking {
        // Tier 1 Google fails
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("Bad Request")
        )
        // Tier 2 OSRM fails
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val settings = RoutingSettings(
            googleApiKey = "bad_key",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(2, mockServer.requestCount)

        val vincom = results["station_vincom"]
        assertNotNull(vincom)
        assertEquals(RoutingEngineType.HAVERSINE, vincom!!.engineUsed)
        assertEquals(0L, vincom.durationSeconds)
        assertEquals(TrafficCondition.UNKNOWN, vincom.trafficCondition)
        assertTrue("Straight-line distance should be positive", vincom.distanceMeters > 0)
    }

    @Test
    fun testAutoMode_cascadesToHaversineWhenOsrmFailsAndNoGoogleKey() = runBlocking {
        // Tier 2 OSRM fails
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("Service Unavailable")
        )

        val settings = RoutingSettings(
            googleApiKey = "",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(1, mockServer.requestCount)

        val landmark = results["station_landmark"]
        assertNotNull(landmark)
        assertEquals(RoutingEngineType.HAVERSINE, landmark!!.engineUsed)
        assertTrue(landmark.distanceMeters > 0)
    }

    @Test
    fun testAutoMode_disablingAutoFallback_stopsCascadeOnTier1Error() = runBlocking {
        // Tier 1 Google fails
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("Quota Exceeded")
        )

        val settings = RoutingSettings(
            googleApiKey = "quota_key",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = false // Fallback disabled
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertTrue("When autoFallbackEnabled is false, Tier 1 failure returns emptyMap", results.isEmpty())
        assertEquals(1, mockServer.requestCount) // OSRM was not queried
    }

    @Test
    fun testAutoMode_disablingAutoFallback_stopsCascadeOnTier2Error() = runBlocking {
        // Tier 2 OSRM fails
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Server Error")
        )

        val settings = RoutingSettings(
            googleApiKey = "",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = false
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertTrue("When autoFallbackEnabled is false, Tier 2 failure returns emptyMap", results.isEmpty())
        assertEquals(1, mockServer.requestCount)
    }

    // =================================================================
    // 2. Strict Engine Modes: GOOGLE_ONLY, OSRM_ONLY, HAVERSINE_ONLY
    // =================================================================

    @Test
    fun testGoogleOnlyMode_failsWithoutFallbackOnHttpError() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )

        val settings = RoutingSettings(
            googleApiKey = "some_key",
            preferredEngine = RoutingEngineMode.GOOGLE_ONLY,
            autoFallbackEnabled = true // Even if fallback enabled in settings, mode overrides it
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertTrue("GOOGLE_ONLY must not fall back to OSRM or Haversine", results.isEmpty())
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun testGoogleOnlyMode_returnsEmptyMapWhenApiKeyIsBlank() = runBlocking {
        val settings = RoutingSettings(
            googleApiKey = "",
            preferredEngine = RoutingEngineMode.GOOGLE_ONLY
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertTrue(results.isEmpty())
        assertEquals(0, mockServer.requestCount) // Zero network requests
    }

    @Test
    fun testOsrmOnlyMode_skipsGoogleEvenWhenApiKeyIsConfigured() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(osrmMockResponseJson)
        )

        val settings = RoutingSettings(
            googleApiKey = "valid_google_key_present",
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(1, mockServer.requestCount)

        val req = mockServer.takeRequest()
        assertTrue("Must query OSRM directly, skipping Google", req.path.orEmpty().contains("/table/v1/driving/"))
        assertEquals(RoutingEngineType.OSRM, results["station_vincom"]?.engineUsed)
    }

    @Test
    fun testOsrmOnlyMode_cascadesToHaversineOnFailureWhenAutoFallbackEnabled() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("OSRM Server Down")
        )

        val settings = RoutingSettings(
            googleApiKey = "key",
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(1, mockServer.requestCount)
        assertEquals(RoutingEngineType.HAVERSINE, results["station_vincom"]?.engineUsed)
    }

    @Test
    fun testOsrmOnlyMode_returnsEmptyMapOnFailureWhenAutoFallbackDisabled() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("OSRM Server Down")
        )

        val settings = RoutingSettings(
            googleApiKey = "key",
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = false
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertTrue(results.isEmpty())
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun testHaversineOnlyMode_computesInstantMetricsWithZeroNetworkCalls() = runBlocking {
        val settings = RoutingSettings(
            googleApiKey = "valid_key",
            preferredEngine = RoutingEngineMode.HAVERSINE_ONLY,
            autoFallbackEnabled = true
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, sampleDestinations, settings)

        assertEquals(2, results.size)
        assertEquals(0, mockServer.requestCount) // Zero network requests

        val vincom = results["station_vincom"]
        assertNotNull(vincom)
        assertEquals(RoutingEngineType.HAVERSINE, vincom!!.engineUsed)
        assertEquals(0L, vincom.durationSeconds)
        assertNull(vincom.staticDurationSeconds)
        assertEquals(TrafficCondition.UNKNOWN, vincom.trafficCondition)
        assertTrue("Distance in meters should be > 0", vincom.distanceMeters > 0)
    }

    // =================================================================
    // 3. Input Sanitization & Coordinate Validation
    // =================================================================

    @Test
    fun testInputSanitization_invalidOriginCoordinates_returnsEmptyMapWithoutNetworkCalls() = runBlocking {
        val settings = RoutingSettings(
            googleApiKey = "key",
            preferredEngine = RoutingEngineMode.AUTO
        )

        // 1. (0.0, 0.0)
        val resZero = coordinator.calculateRoutes(0.0, 0.0, sampleDestinations, settings)
        assertTrue(resZero.isEmpty())

        // 2. Latitude out of range
        val resBadLat = coordinator.calculateRoutes(95.0, 106.7, sampleDestinations, settings)
        assertTrue(resBadLat.isEmpty())

        // 3. Longitude out of range
        val resBadLng = coordinator.calculateRoutes(10.7, -195.0, sampleDestinations, settings)
        assertTrue(resBadLng.isEmpty())

        // 4. NaN coordinates
        val resNan = coordinator.calculateRoutes(Double.NaN, 106.7, sampleDestinations, settings)
        assertTrue(resNan.isEmpty())

        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun testInputSanitization_filtersInvalidDestinations() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {
                        "originIndex": 0,
                        "destinationIndex": 0,
                        "status": {},
                        "condition": "ROUTE_EXISTS",
                        "distanceMeters": 4200,
                        "duration": "405s",
                        "staticDuration": "300s"
                      }
                    ]
                    """.trimIndent()
                )
        )

        val mixedDestinations = listOf(
            RoutingDestination(id = "valid_station", latitude = 10.7780, longitude = 106.7020),
            RoutingDestination(id = "unresolved_station", latitude = 0.0, longitude = 0.0),
            RoutingDestination(id = "out_of_bounds_station", latitude = 95.0, longitude = 106.0)
        )

        val settings = RoutingSettings(
            googleApiKey = "valid_key",
            preferredEngine = RoutingEngineMode.AUTO
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, destinations = mixedDestinations, settings = settings)

        assertEquals(1, results.size)
        assertNotNull("Valid station present", results["valid_station"])
        assertNull("Unresolved (0,0) station pruned", results["unresolved_station"])
        assertNull("Out of bounds station pruned", results["out_of_bounds_station"])

        // Inspect request body: should contain only 1 destination
        val req = mockServer.takeRequest()
        val requestBody = req.body.readUtf8()
        assertTrue(requestBody.contains("10.778"))
        assertFalse("Request should not send 0.0 coordinates", requestBody.contains(""""latitude":0.0,"longitude":0.0"""))
    }

    @Test
    fun testInputSanitization_allDestinationsInvalid_returnsEmptyMapImmediately() = runBlocking {
        val allInvalidDestinations = listOf(
            RoutingDestination(id = "station_zero", latitude = 0.0, longitude = 0.0),
            RoutingDestination(id = "station_nan", latitude = Double.NaN, longitude = 106.0)
        )

        val settings = RoutingSettings(
            googleApiKey = "valid_key",
            preferredEngine = RoutingEngineMode.AUTO
        )

        val results = coordinator.calculateRoutes(sampleOriginLat, sampleOriginLng, allInvalidDestinations, settings)

        assertTrue(results.isEmpty())
        assertEquals(0, mockServer.requestCount)
    }
}
