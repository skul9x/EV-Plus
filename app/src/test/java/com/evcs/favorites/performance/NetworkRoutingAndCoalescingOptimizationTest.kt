package com.evcs.favorites.performance

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.FavoritesResponse
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.GoogleRoutesClient
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.OsrmRoutingClient
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verification test for Phase 04:
 * Network Routing & Coalescing Resilience (PERF-002, PERF-005, PERF-013).
 *
 * Verifies:
 * 1. Concurrent resolution: Resolving 5 unknown station coordinates completes concurrently
 *    using bounded parallelism (Semaphore 4) rather than sequentially in series.
 * 2. GPS jitter coalescing: Two `searchNearbyVinFast` calls with micro GPS differences
 *    (e.g. 21.0285114 vs 21.0285189) generate identical rounded SingleFlight keys and coalesce
 *    into a single network execution.
 * 3. Timeout fallback: When both Google and OSRM endpoints hang or simulate 10s latency,
 *    `MultiTierRoutingCoordinator.executeAuto` aborts after the 5s overall arbitration timeout
 *    and gracefully returns valid Tier 3 Haversine baseline metrics.
 */
class NetworkRoutingAndCoalescingOptimizationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
    }

    @After
    fun tearDown() {
        try {
            mockServer.shutdown()
        } catch (e: Exception) {
            // Ignored: pending delayed mock responses may cause Gave up waiting for queue to shut down
        }
    }

    // =========================================================================
    // 1. Concurrent Station Coordinate Resolution (PERF-002)
    // =========================================================================

    @Test
    fun testConcurrentCoordinateResolution_resolvesConcurrentlyWithBoundedParallelism() = runBlocking {
        val activeCallers = AtomicInteger(0)
        val peakConcurrency = AtomicInteger(0)
        val callCount = AtomicInteger(0)

        val rawFavoritesList = (1..5).map { i ->
            FavoriteStationRaw(
                locationId = "STATION_$i",
                name = "VinFast Station $i",
                address = "Address $i",
                summary = "24/7",
                connectors = "60kW",
                image = null
            )
        }

        val testApiClient = object : EvcsApiClient(sessionManager, baseUrl = "https://evcs.vn") {
            override suspend fun fetchFavorites(): Result<FavoritesResponse> {
                return Result.success(
                    FavoritesResponse(
                        server = rawFavoritesList
                    )
                )
            }

            override suspend fun fetchStationHtml(
                stationName: String,
                locationId: String,
                evse: String
            ): Result<String> {
                callCount.incrementAndGet()
                val current = activeCallers.incrementAndGet()
                peakConcurrency.accumulateAndGet(current) { c, max -> maxOf(c, max) }

                // Simulate HTML network fetch delay
                delay(100L)

                activeCallers.decrementAndGet()
                val idNum = locationId.removePrefix("STATION_")
                val html = """
                    <html>
                    <head>
                        <meta name="place:location:latitude" content="21.03$idNum" />
                        <meta name="place:location:longitude" content="105.85$idNum" />
                    </head>
                    <body></body>
                    </html>
                """.trimIndent()
                return Result.success(html)
            }

            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String
            ): Result<List<SearchStationRaw>> {
                return Result.success(emptyList())
            }
        }

        val repository = EvcsRepository(
            apiClient = testApiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = Dispatchers.Default
        )

        val startTime = System.currentTimeMillis()
        val result = repository.getFavorites(
            userLat = 21.0285,
            userLon = 105.8542,
            autoResolveUnknownCoordinates = true
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("getFavorites must succeed", result.isSuccess)
        val stations = result.getOrThrow()
        assertEquals(5, stations.size)
        assertEquals(5, callCount.get())

        // Peak concurrency should reach 4 (the bounded semaphore limit)
        assertTrue(
            "Peak concurrency (${peakConcurrency.get()}) must be >= 3 indicating concurrent resolution",
            peakConcurrency.get() >= 3
        )
        assertTrue(
            "Peak concurrency (${peakConcurrency.get()}) must not exceed bounded limit of 4",
            peakConcurrency.get() <= 4
        )

        // If sequential: 5 * 100ms = 500ms+. With 4 concurrent workers: ceil(5/4) * 100ms = ~200ms
        assertTrue(
            "Total elapsed time ($elapsed ms) must be significantly faster than sequential series (500ms+)",
            elapsed < 420L
        )

        // Verify coordinates are cached in memory and in persistent cache
        val cachedCoords = repository.getCachedCoordinates()
        assertEquals(5, cachedCoords.size)
        for (i in 1..5) {
            val coord = cachedCoords["station_$i"]
            assertNotNull(coord)
            assertTrue(coord!!.first > 21.0)
            assertTrue(coord.second > 105.0)
        }
    }

    // =========================================================================
    // 2. SingleFlight Request Coalescing with GPS Jitter Rounding (PERF-013)
    // =========================================================================

    @Test
    fun testSingleFlightGpsJitterCoalescing_mergesMicroGpsVariationsIntoSingleRequest() = runBlocking {
        val searchExecutions = AtomicInteger(0)

        val testApiClient = object : EvcsApiClient(sessionManager, baseUrl = "https://evcs.vn") {
            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String
            ): Result<List<SearchStationRaw>> {
                searchExecutions.incrementAndGet()
                delay(120L) // Simulate network transit
                return Result.success(
                    listOf(
                        SearchStationRaw(
                            id = "STATION_TEST",
                            stationName = "VinFast Near Hoan Kiem",
                            latitude = 21.0285,
                            longitude = 105.8542
                        )
                    )
                )
            }
        }

        val repository = EvcsRepository(
            apiClient = testApiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = Dispatchers.Default
        )

        // Two GPS points that differ by ~0.8 meters (jitter in 6th/7th decimal places)
        // Both round to 21.0285 and 105.8542
        val lat1 = 21.0285114
        val lon1 = 105.8542111

        val lat2 = 21.0285189
        val lon2 = 105.8542188

        val deferred1 = async(Dispatchers.Default) {
            repository.searchNearbyVinFast(lat1, lon1)
        }
        val deferred2 = async(Dispatchers.Default) {
            repository.searchNearbyVinFast(lat2, lon2)
        }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals(1, res1.getOrThrow().size)
        assertEquals(1, res2.getOrThrow().size)

        // Coalesced into a single HTTP query due to 4-decimal place SingleFlight key matching
        assertEquals(
            "searchNearbyVinFast should coalesce micro GPS jitter into 1 execution",
            1,
            searchExecutions.get()
        )
    }

    @Test
    fun testSingleFlightFavorites_mergesMicroGpsVariationsIntoSingleRequest() = runBlocking {
        val favoritesExecutions = AtomicInteger(0)

        val testApiClient = object : EvcsApiClient(sessionManager, baseUrl = "https://evcs.vn") {
            override suspend fun fetchFavorites(): Result<FavoritesResponse> {
                favoritesExecutions.incrementAndGet()
                delay(120L)
                return Result.success(
                    FavoritesResponse(
                        server = listOf(
                            FavoriteStationRaw(
                                locationId = "STATION_FAV_1",
                                name = "VinFast Fav 1",
                                address = "Address 1"
                            )
                        )
                    )
                )
            }

            override suspend fun searchStations(
                latitude: Double,
                longitude: Double,
                token: String
            ): Result<List<SearchStationRaw>> {
                return Result.success(emptyList())
            }
        }

        val repository = EvcsRepository(
            apiClient = testApiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = Dispatchers.Default
        )

        val lat1 = 21.0285002
        val lon1 = 105.8542003
        val lat2 = 21.0285009
        val lon2 = 105.8542008

        val deferred1 = async(Dispatchers.Default) {
            repository.getFavorites(userLat = lat1, userLon = lon1, autoResolveUnknownCoordinates = false)
        }
        val deferred2 = async(Dispatchers.Default) {
            repository.getFavorites(userLat = lat2, userLon = lon2, autoResolveUnknownCoordinates = false)
        }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals(
            "getFavorites should coalesce micro GPS jitter into 1 execution",
            1,
            favoritesExecutions.get()
        )
    }

    // =========================================================================
    // 3. Multi-Tier Routing Bounded Timeouts & 5s Overall Fallback (PERF-005)
    // =========================================================================

    @Test
    fun testExecuteAuto_fallsBackToHaversineWithin5SecondsWhenEndpointsHang() = runBlocking {
        // Dispatcher that simulates a 10s hang on both Google and OSRM endpoints
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setBodyDelay(10, TimeUnit.SECONDS)
                    .setBody("{}")
            }
        }

        val mockUrl = mockServer.url("/").toString()
        val googleClient = GoogleRoutesClient(baseUrl = mockUrl)
        val osrmClient = OsrmRoutingClient(defaultBaseUrl = mockUrl)
        val coordinator = MultiTierRoutingCoordinator(googleClient, osrmClient)

        val destinations = listOf(
            RoutingDestination(id = "dest_1", latitude = 21.0300, longitude = 105.8500),
            RoutingDestination(id = "dest_2", latitude = 21.0400, longitude = 105.8600)
        )
        val settings = RoutingSettings(
            googleApiKey = "test_key",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true
        )

        val startTime = System.currentTimeMillis()
        val results = coordinator.calculateRoutes(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinations,
            settings = settings
        )
        val elapsed = System.currentTimeMillis() - startTime

        // Should return within 5.0s (+ small test harness buffer < 7000ms), NOT 20-30s
        assertTrue(
            "Routing arbitration should abort at ~5000ms overall timeout (actual: $elapsed ms)",
            elapsed in 4800L..7000L
        )

        assertEquals(2, results.size)
        val metric1 = results["dest_1"]
        assertNotNull(metric1)
        assertEquals("Must fall back to Tier 3 Haversine baseline", RoutingEngineType.HAVERSINE, metric1!!.engineUsed)
        assertEquals(0L, metric1.durationSeconds)
        assertEquals(TrafficCondition.UNKNOWN, metric1.trafficCondition)
        assertTrue("Calculated straight-line distance must be positive", metric1.distanceMeters > 0)
    }

    @Test
    fun testExecuteAuto_cascadesToOsrmWhenGoogleTimesOutAt3500ms() = runBlocking {
        // Google hangs (10s delay), but OSRM responds quickly (200 OK)
        val osrmSuccessResponse = """
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

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.contains("computeRouteMatrix")) {
                    MockResponse()
                        .setBodyDelay(10, TimeUnit.SECONDS)
                        .setBody("{}")
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(osrmSuccessResponse)
                }
            }
        }

        val mockUrl = mockServer.url("/").toString()
        val googleClient = GoogleRoutesClient(baseUrl = mockUrl)
        val osrmClient = OsrmRoutingClient(defaultBaseUrl = mockUrl)
        val coordinator = MultiTierRoutingCoordinator(googleClient, osrmClient)

        val destinations = listOf(
            RoutingDestination(id = "dest_1", latitude = 21.0300, longitude = 105.8500)
        )
        val settings = RoutingSettings(
            googleApiKey = "test_key",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true
        )

        val startTime = System.currentTimeMillis()
        val results = coordinator.calculateRoutes(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinations,
            settings = settings
        )
        val elapsed = System.currentTimeMillis() - startTime

        // Tier 1 Google times out at ~3500ms, cascades to OSRM which succeeds immediately
        // Total time ~3500ms - 4700ms (< 5000ms overall limit)
        assertTrue(
            "Google timeout cascade to OSRM should complete in ~3500-4700ms (actual: $elapsed ms)",
            elapsed in 3300L..4900L
        )

        assertEquals(1, results.size)
        val metric = results["dest_1"]
        assertNotNull(metric)
        assertEquals("Must successfully cascade to OSRM", RoutingEngineType.OSRM, metric!!.engineUsed)
        assertEquals(2500L, metric.distanceMeters)
        assertEquals(300L, metric.durationSeconds)
    }

    @Test
    fun testExecuteAuto_whenAutoFallbackDisabled_returnsEmptyMapOnTimeout() = runBlocking {
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setBodyDelay(10, TimeUnit.SECONDS)
                    .setBody("{}")
            }
        }

        val mockUrl = mockServer.url("/").toString()
        val googleClient = GoogleRoutesClient(baseUrl = mockUrl)
        val osrmClient = OsrmRoutingClient(defaultBaseUrl = mockUrl)
        val coordinator = MultiTierRoutingCoordinator(googleClient, osrmClient)

        val destinations = listOf(
            RoutingDestination(id = "dest_1", latitude = 21.0300, longitude = 105.8500)
        )
        val settings = RoutingSettings(
            googleApiKey = "test_key",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = false
        )

        val results = coordinator.calculateRoutes(
            originLat = 21.0285,
            originLng = 105.8542,
            destinations = destinations,
            settings = settings
        )

        assertTrue("Should return empty map when autoFallbackEnabled is false and Tier 1 times out", results.isEmpty())
    }
}
