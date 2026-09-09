package com.evcs.favorites.performance

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
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
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification Test for Phase 03: P1 Centralized Routing Cache & Quota Conservation.
 *
 * Verifies:
 * 1. Cache hit when origin moves by 10 meters (< 50m threshold) -> 0 network calls, instant cached metrics.
 * 2. Cache miss when origin moves by 100 meters (> 50m threshold) -> executes remote routing, updates cache.
 * 3. TTL expiry after 60,001 ms -> re-queries network even when coordinates are identical.
 * 4. Force refresh bypasses valid cache -> executes remote routing immediately.
 * 5. Explicit cache clearing via [MultiTierRoutingCoordinator.clearRoutingCache].
 * 6. Partial destination cache miss handling (adding destination causes remote query).
 * 7. NearbyViewModel default routing debounce increased to 600ms and USER_REFRESH forces refresh.
 * 8. FavoritesViewModel delegation to centralized coordinator routing cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase03P1RoutingCoordinatorCacheTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var coordinator: MultiTierRoutingCoordinator
    private var simulatedTime: Long = 1_000_000L

    private val originLat = 10.776900
    private val originLng = 106.700900

    private val destinations = listOf(
        RoutingDestination(id = "station_1", latitude = 10.778000, longitude = 106.702000),
        RoutingDestination(id = "station_2", latitude = 10.795000, longitude = 106.721800)
    )

    private val osrmMockResponseJson = """
        {
          "code": "Ok",
          "durations": [
            [0.0, 300.0, 600.0]
          ],
          "distances": [
            [0.0, 2500.0, 5000.0]
          ]
        }
    """.trimIndent()

    private val osrmSettings = RoutingSettings(
        preferredEngine = RoutingEngineMode.OSRM_ONLY,
        googleApiKey = "",
        autoFallbackEnabled = false
    )

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        val mockUrl = mockServer.url("/").toString()
        val googleClient = GoogleRoutesClient(baseUrl = mockUrl)
        val osrmClient = OsrmRoutingClient(defaultBaseUrl = mockUrl)

        coordinator = MultiTierRoutingCoordinator(
            googleClient = googleClient,
            osrmClient = osrmClient,
            ioDispatcher = Dispatchers.Unconfined
        ).apply {
            timeProvider = { simulatedTime }
        }
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun testCacheHit_whenOriginMovesBy10Meters() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))

        // Initial query: Cache miss -> network request 1
        val initialResult = coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals(1, mockServer.requestCount)
        assertEquals(2, initialResult.size)
        assertEquals(2500L, initialResult["station_1"]?.distanceMeters)

        // Move origin by ~10 meters north (deltaLat ~0.00009 degrees)
        val movedLat10m = originLat + 0.00009
        val movedLng10m = originLng
        val displacement = DistanceCalculator.calculateDistanceMeters(originLat, originLng, movedLat10m, movedLng10m)
        assertTrue("Displacement must be < 50m (was ${displacement}m)", displacement < 50.0)

        // Query within 50m and within TTL: Cache hit -> zero network calls
        val cachedResult = coordinator.calculateRoutes(movedLat10m, movedLng10m, destinations, osrmSettings)
        assertEquals("Network request count must remain 1 on cache hit", 1, mockServer.requestCount)
        assertEquals(initialResult, cachedResult)
    }

    @Test
    fun testCacheMiss_whenOriginMovesBy100Meters() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))

        // Initial query -> network request 1
        coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals(1, mockServer.requestCount)

        // Move origin by ~100 meters north (deltaLat ~0.00090 degrees)
        val movedLat100m = originLat + 0.00090
        val movedLng100m = originLng
        val displacement = DistanceCalculator.calculateDistanceMeters(originLat, originLng, movedLat100m, movedLng100m)
        assertTrue("Displacement must be > 50m (was ${displacement}m)", displacement > 50.0)

        // Query with > 50m displacement -> Cache miss -> network request 2
        val freshResult = coordinator.calculateRoutes(movedLat100m, movedLng100m, destinations, osrmSettings)
        assertEquals("Network request count must increment to 2 on > 50m displacement", 2, mockServer.requestCount)
        assertEquals(2, freshResult.size)
        assertEquals(movedLat100m, coordinator.cachedOriginLat!!, 0.00001)
    }

    @Test
    fun testTtlExpiry_after60001Ms() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))

        // Initial query at t = 1_000_000L -> network request 1
        coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals(1, mockServer.requestCount)

        // Advance simulated time by 60,001 ms (exceeding CACHE_TTL_MS = 60,000 ms)
        simulatedTime += 60_001L

        // Query at identical coordinates after TTL expiry -> Cache miss -> network request 2
        coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals("Network request count must increment to 2 after 60,001 ms TTL expiry", 2, mockServer.requestCount)
    }

    @Test
    fun testForceRefresh_bypassesValidCache() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))

        // Initial query -> network request 1
        coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals(1, mockServer.requestCount)

        // Second query with identical origin, fresh cache, but forceRefresh = true -> network request 2
        val freshResult = coordinator.calculateRoutes(
            originLat = originLat,
            originLng = originLng,
            destinations = destinations,
            settings = osrmSettings,
            forceRefresh = true
        )
        assertEquals("Network request count must increment to 2 when forceRefresh = true", 2, mockServer.requestCount)
        assertEquals(2, freshResult.size)
    }

    @Test
    fun testClearRoutingCache_invalidatesCacheState() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))

        // Initial query -> caches entries
        coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals(1, mockServer.requestCount)
        assertEquals(originLat, coordinator.cachedOriginLat!!, 0.00001)

        // Clear cache explicitly
        coordinator.clearRoutingCache()
        assertEquals(0, coordinator.cachedMetrics.size)
        assertEquals(null, coordinator.cachedOriginLat)
        assertEquals(null, coordinator.cachedOriginLng)
        assertEquals(0L, coordinator.cacheTimestamp)

        // Subsequent query must re-hit network
        coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals("Network request count must increment to 2 after clearRoutingCache()", 2, mockServer.requestCount)
    }

    @Test
    fun testPartialDestinationCacheMiss_triggersCalculationForMissingDestinations() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrmMockResponseJson))
        val osrm3DestJson = """
            {
              "code": "Ok",
              "durations": [
                [0.0, 300.0, 600.0, 900.0]
              ],
              "distances": [
                [0.0, 2500.0, 5000.0, 7500.0]
              ]
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(osrm3DestJson))

        // Query with 2 destinations
        coordinator.calculateRoutes(originLat, originLng, destinations, osrmSettings)
        assertEquals(1, mockServer.requestCount)

        // Add 3rd destination at same origin
        val expandedDestinations = destinations + RoutingDestination("station_3", 10.8000, 106.7100)
        val expandedResult = coordinator.calculateRoutes(originLat, originLng, expandedDestinations, osrmSettings)

        // Must re-query because station_3 was not present in cache
        assertEquals("Adding new destination must trigger routing calculation", 2, mockServer.requestCount)
        assertEquals(3, expandedResult.size)
    }

    @Test
    fun testNearbyViewModel_routingDebounceAndUserRefreshIntegration() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sessionStorage = InMemorySessionStorage()
        val repo = FakeEvcsRepository(sessionStorage)
        val sessionMgr = SessionManager(sessionStorage)
        val locService = FakeLocationService().apply {
            locationToReturn = Location("fake").apply {
                latitude = originLat
                longitude = originLng
            }
        }

        var calculateRoutesCallCount = 0
        var lastForceRefresh: Boolean? = null

        val trackingCoordinator = object : MultiTierRoutingCoordinator(ioDispatcher = testDispatcher) {
            override suspend fun calculateRoutes(
                originLat: Double,
                originLng: Double,
                destinations: List<RoutingDestination>,
                settings: RoutingSettings,
                forceRefresh: Boolean
            ): Map<String, DrivingMetrics> {
                calculateRoutesCallCount++
                lastForceRefresh = forceRefresh
                return destinations.associate {
                    it.id to DrivingMetrics(
                        distanceMeters = 1200L,
                        durationSeconds = 150L,
                        staticDurationSeconds = null,
                        trafficCondition = TrafficCondition.FREE_FLOW,
                        engineUsed = RoutingEngineType.OSRM
                    )
                }
            }
        }

        val viewModel = NearbyViewModel(
            repository = repo,
            sessionManager = sessionMgr,
            locationService = locService,
            routingCoordinator = trackingCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            routingDebounceMs = 600L
        )

        // Scan nearby stations (simulate passive/scan load)
        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val initialCalls = calculateRoutesCallCount
        assertTrue("Routing must be called during scan", initialCalls > 0)
        assertEquals("Initial scan or background routing must have forceRefresh = false", false, lastForceRefresh)

        // User pull-to-refresh
        viewModel.refreshNearbyStations(isUserRefresh = true)
        testScheduler.advanceUntilIdle()

        assertEquals("USER_REFRESH must pass forceRefresh = true", true, lastForceRefresh)
    }

    @Test
    fun testFavoritesViewModel_delegatesRoutingCacheToCoordinator() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sessionStorage = InMemorySessionStorage()
        val repo = FakeEvcsRepository(sessionStorage)
        val sessionMgr = SessionManager(sessionStorage).apply {
            authCookie = "valid_cookie"
        }
        val authEngine = AuthEngine(sessionMgr, OkHttpClient(), "https://test.evcs.vn")

        var coordinatorRoutingCalls = 0
        var lastForceRefresh: Boolean? = null

        val trackingCoordinator = object : MultiTierRoutingCoordinator(ioDispatcher = testDispatcher) {
            override suspend fun calculateRoutes(
                originLat: Double,
                originLng: Double,
                destinations: List<RoutingDestination>,
                settings: RoutingSettings,
                forceRefresh: Boolean
            ): Map<String, DrivingMetrics> {
                coordinatorRoutingCalls++
                lastForceRefresh = forceRefresh
                return destinations.associate {
                    it.id to DrivingMetrics(
                        distanceMeters = 800L,
                        durationSeconds = 100L,
                        staticDurationSeconds = null,
                        trafficCondition = TrafficCondition.FREE_FLOW,
                        engineUsed = RoutingEngineType.OSRM
                    )
                }
            }
        }

        val viewModel = FavoritesViewModel(
            repository = repo,
            authEngine = authEngine,
            routingCoordinator = trackingCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        repo.stationsToReturn = listOf(
            Station(
                id = "fav_1",
                name = "VinFast Station 1",
                address = "District 1",
                latitude = 10.7780,
                longitude = 106.7020,
                summary = "24/7",
                connectors = "60kW",
                depotStatus = "Normal",
                totalAvailablePlugs = 2,
                totalPlugs = 4
            )
        )

        // Initial location fix and routing
        viewModel.updateUserLocation(originLat, originLng)
        testScheduler.advanceUntilIdle()
        viewModel.routingJob?.join()
        testScheduler.advanceUntilIdle()

        assertTrue("Routing must be called for favorites candidates", coordinatorRoutingCalls > 0)

        // Trigger user pull-to-refresh -> must pass forceRefresh = true
        viewModel.refresh()
        testScheduler.advanceUntilIdle()
        viewModel.routingJob?.join()
        testScheduler.advanceUntilIdle()

        assertEquals("User pull to refresh in FavoritesViewModel must pass forceRefresh = true", true, lastForceRefresh)

        // Invalidate routing cache in FavoritesViewModel must clear coordinator cache
        trackingCoordinator.cachedMetrics["sample"] = DrivingMetrics(100L, 10L, null, TrafficCondition.FREE_FLOW, RoutingEngineType.OSRM)
        viewModel.invalidateRoutingCache()
        assertTrue("Coordinator cache must be empty after viewModel.invalidateRoutingCache()", trackingCoordinator.cachedMetrics.isEmpty())
    }

    // Fake helpers
    private class FakeLocationService : LocationService() {
        var locationToReturn: Location? = null
        override fun hasLocationPermission(): Boolean = true
        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    private class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var stationsToReturn: List<Station> = listOf(
            Station(
                id = "station_1",
                name = "Trạm VinFast Landmark",
                address = "Bình Thạnh",
                latitude = 10.7780,
                longitude = 106.7020,
                summary = "24/7",
                connectors = "60kW, 250kW",
                depotStatus = "Normal",
                totalAvailablePlugs = 2,
                totalPlugs = 4
            )
        )

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return Result.success(stationsToReturn)
        }

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return Result.success(stationsToReturn)
        }
    }
}
