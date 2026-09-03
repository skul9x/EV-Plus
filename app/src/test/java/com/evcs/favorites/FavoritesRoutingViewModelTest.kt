package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Comprehensive verification test for Phase 04:
 * ViewModel Hybrid Pipeline & Shortest-ETA Sorting.
 *
 * Verifies:
 * 1. Station model domain extensions: `effectiveDistanceKm` and `effectiveDurationSeconds`.
 * 2. 2-Step Hybrid Pipeline:
 *    - Step 1 (Immediate 0ms): Haversine distance rendered before async routing completes.
 *    - Step 2 (Async coordinator): candidate stations enriched with `DrivingMetrics`.
 * 3. Shortest Driving ETA Sorting:
 *    - Stations ordered by driving duration (`durationSeconds` ascending) rather than purely straight-line distance.
 * 4. Candidate count rule & coordinate sanitization:
 *    - Extracts `minOf(validStations.size, 10)` nearest candidates by Haversine.
 *    - Excludes unresolved stations with `(lat == 0.0 && lon == 0.0)`.
 *    - Unrouted stations (> 10) retain `null` driving metrics and appear after candidates, sorted by Haversine.
 * 5. In-Memory Routing Cache:
 *    - Cache hit: second call with identical location within 3 minutes avoids re-querying the coordinator.
 *    - Cache invalidation 1: GPS displacement > 200m triggers fresh routing request.
 *    - Cache invalidation 2: User updates `RoutingSettings` triggers fresh routing request.
 *    - Cache invalidation 3: TTL expiry (> 180,000 ms) triggers fresh routing request.
 *    - Cache invalidation 4: Pull-to-refresh (`refresh()`) invalidates cache.
 * 6. Location unavailable fallback: skips coordinator routing and retains Step 1 baseline.
 * 7. Key validation & `routingSettings` StateFlow observation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRoutingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var fakeCoordinator: FakeRoutingCoordinator

    private var currentTime: Long = 1_000_000L

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount = 0
        var lastOriginLat: Double? = null
        var lastOriginLng: Double? = null
        var lastDestinations: List<RoutingDestination> = emptyList()
        var lastSettings: RoutingSettings? = null
        var beforeCalculateRouteHook: (suspend () -> Unit)? = null

        var customMetricsProvider: (destinations: List<RoutingDestination>) -> Map<String, DrivingMetrics> = { dests ->
            dests.associate { d ->
                d.id to DrivingMetrics(
                    distanceMeters = 3000L,
                    durationSeconds = 300L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            beforeCalculateRouteHook?.invoke()
            callCount++
            lastOriginLat = originLat
            lastOriginLng = originLng
            lastDestinations = destinations
            lastSettings = settings
            return customMetricsProvider(destinations)
        }
    }

    // 12 valid stations + 1 station with (0.0, 0.0) invalid coordinates = 13 total stations
    private val favoritesResponseJson = buildString {
        append("""{"sync":true,"csrf":"csrf_token_123","server":[""")
        val items = mutableListOf<String>()
        for (i in 1..12) {
            items.add("""{"locationId":"station_$i","name":"Trạm Sạc $i","address":"Địa chỉ $i","summary":"24/7","connectors":"60kW"}""")
        }
        items.add("""{"locationId":"station_invalid_coords","name":"Trạm Lỗi Tọa Độ","address":"Không rõ","summary":"24/7","connectors":"11kW"}""")
        append(items.joinToString(","))
        append("""]}""")
    }

    private val searchResponseJson = buildString {
        append("""{"code":200000,"data":[""")
        val items = mutableListOf<String>()
        for (i in 1..12) {
            // Gradually increasing latitude so Haversine distances increase progressively
            val lat = 10.7700 + (i * 0.0050)
            val lon = 106.6900 + (i * 0.0050)
            items.add("""{"locationId":"station_$i","stationName":"Trạm Sạc $i","latitude":$lat,"longitude":$lon,"depotStatus":"Normal","evsePowers":[{"type":60000,"numberOfAvailableEvse":2,"totalEvse":2}]}""")
        }
        items.add("""{"locationId":"station_invalid_coords","stationName":"Trạm Lỗi Tọa Độ","latitude":0.0,"longitude":0.0,"depotStatus":"Unknown","evsePowers":[]}""")
        append(items.joinToString(","))
        append("""]}""")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("favorite.html") -> {
                        MockResponse().setResponseCode(200).setBody(favoritesResponseJson)
                    }
                    path.contains("search") -> {
                        MockResponse().setResponseCode(200).setBody(searchResponseJson)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_auth_cookie"
            phpSessionId = "phpsess_123"
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        apiClient = EvcsApiClient(sessionManager, okHttpClient, baseUrl)
        repository = EvcsRepository(apiClient = apiClient, cacheStorage = sessionStorage, autoResolveCoordinates = false)
        authEngine = AuthEngine(sessionManager, okHttpClient, baseUrl)
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        fakeCoordinator = FakeRoutingCoordinator()
        currentTime = 1_000_000L
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): FavoritesViewModel {
        return FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            locationService = null,
            dispatcher = testDispatcher,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher
        ).apply {
            timeProvider = { currentTime }
        }
    }

    // =========================================================================
    // 1. Domain Model Extension: effectiveDistanceKm & effectiveDurationSeconds
    // =========================================================================

    @Test
    fun testStationModelDomainAccessors() {
        val baseStation = Station(
            id = "st-test",
            name = "Test Station",
            address = "Test Address",
            latitude = 10.77,
            longitude = 106.70,
            summary = "",
            connectors = "",
            depotStatus = "Normal",
            distanceKm = 4.5,
            drivingMetrics = null
        )

        // Without driving metrics: falls back to distanceKm and null duration
        assertEquals(4.5, baseStation.effectiveDistanceKm!!, 0.001)
        assertNull(baseStation.effectiveDurationSeconds)

        // With driving metrics: uses driving distance in km (meters / 1000.0) and duration
        val enriched = baseStation.copy(
            drivingMetrics = DrivingMetrics(
                distanceMeters = 5200L,
                durationSeconds = 720L,
                trafficCondition = TrafficCondition.MODERATE_CONGESTION,
                engineUsed = RoutingEngineType.OSRM
            )
        )
        assertEquals(5.2, enriched.effectiveDistanceKm!!, 0.001)
        assertEquals(720L, enriched.effectiveDurationSeconds)
    }

    // =========================================================================
    // 2. 2-Step Hybrid Pipeline & Shortest Driving ETA Sorting
    // =========================================================================

    @Test
    fun testTwoStepHybridPipeline_ImmediateHaversineThenAsyncCoordinator() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        val routingGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fakeCoordinator.beforeCalculateRouteHook = {
            routingGate.await()
        }

        fakeCoordinator.customMetricsProvider = { dests ->
            dests.associate { d ->
                d.id to DrivingMetrics(
                    distanceMeters = 3500L,
                    durationSeconds = 420L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }

        val viewModel = createViewModel()
        viewModel.updateUserLocation(userLat, userLon)

        // Trigger fetch favorites and await Step 1 completion
        val fetchJob = viewModel.fetchFavorites()
        fetchJob.join()

        val stateStep1 = viewModel.uiState.value
        assertTrue("Expected Success state after Step 1", stateStep1 is FavoritesUiState.Success)
        val step1Stations = (stateStep1 as FavoritesUiState.Success).stations

        // Verify Step 1: stations render immediate Haversine distance, drivingMetrics is null
        val station1Step1 = step1Stations.find { it.id == "station_1" }
        assertNotNull(station1Step1)
        assertNotNull(station1Step1?.distanceKm)
        assertNull("Step 1 must have null drivingMetrics before async routing finishes", station1Step1?.drivingMetrics)

        // Open routing gate to allow Step 2 async coordinator calculation to proceed
        routingGate.complete(Unit)
        viewModel.routingJob?.join()

        val stateStep2 = viewModel.uiState.value as FavoritesUiState.Success
        val step2Stations = stateStep2.stations

        val station1Step2 = step2Stations.find { it.id == "station_1" }
        assertNotNull(station1Step2)
        assertNotNull("Step 2 must enrich candidate stations with drivingMetrics", station1Step2?.drivingMetrics)
        assertEquals(3500L, station1Step2?.drivingMetrics?.distanceMeters)
        assertEquals(420L, station1Step2?.drivingMetrics?.durationSeconds)
        assertTrue(fakeCoordinator.callCount >= 1)
    }

    @Test
    fun testShortestDrivingEtaSorting_DualComparator() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        // Station 1: closer straight-line distance, but heavy traffic congestion (1200 seconds / 20 mins)
        // Station 2: further straight-line distance, but highway free-flow (300 seconds / 5 mins)
        fakeCoordinator.customMetricsProvider = { _ ->
            mapOf(
                "station_1" to DrivingMetrics(
                    distanceMeters = 2000L,
                    durationSeconds = 1200L,
                    trafficCondition = TrafficCondition.HEAVY_CONGESTION,
                    engineUsed = RoutingEngineType.GOOGLE
                ),
                "station_2" to DrivingMetrics(
                    distanceMeters = 6000L,
                    durationSeconds = 300L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.GOOGLE
                )
            )
        }

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val state = viewModel.uiState.value as FavoritesUiState.Success
        val stations = state.stations

        // Dual comparator asserts:
        // 1st place: station_2 (duration = 300s) even though straight-line distance is greater!
        assertEquals("station_2", stations[0].id)
        assertEquals(300L, stations[0].drivingMetrics?.durationSeconds)

        // 2nd place: station_1 (duration = 1200s)
        assertEquals("station_1", stations[1].id)
        assertEquals(1200L, stations[1].drivingMetrics?.durationSeconds)

        // Stations without drivingMetrics (station_3 onwards) fall back to Haversine distanceKm
        assertNull(stations[2].drivingMetrics)
        assertNotNull(stations[2].distanceKm)

        // Invalid coords station (0.0, 0.0) is placed at the end
        val lastStation = stations.last()
        assertEquals("station_invalid_coords", lastStation.id)
    }

    // =========================================================================
    // 3. Candidate Count Rule & Sanitization of Invalid Coordinates
    // =========================================================================

    @Test
    fun testCandidateRules_PrunesZeroZeroAndLimitsToTopTenCandidates() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        // Verify coordinator received candidate destinations
        val routedDestinations = fakeCoordinator.lastDestinations

        // Station with (0.0, 0.0) coordinates must be strictly excluded
        assertFalse(
            "Station with (0.0, 0.0) coordinates must not be routed by coordinator",
            routedDestinations.any { it.id == "station_invalid_coords" }
        )

        // Total valid stations = 12. Candidate count rule: minOf(validStations.size, 10) = 10
        assertEquals("Coordinator must receive exactly 10 candidate destinations", 10, routedDestinations.size)

        // The remaining valid stations (> 10: station_11, station_12) must NOT be routed
        assertFalse(routedDestinations.any { it.id == "station_11" })
        assertFalse(routedDestinations.any { it.id == "station_12" })

        // In the UI state, the 10 candidates have drivingMetrics, while unrouted stations have null
        val state = viewModel.uiState.value as FavoritesUiState.Success
        val st11 = state.stations.find { it.id == "station_11" }
        assertNotNull(st11)
        assertNull("Unrouted stations beyond top 10 must retain null drivingMetrics", st11?.drivingMetrics)
    }

    // =========================================================================
    // 4. In-Memory Routing Cache: Hit & Invalidation Scenarios
    // =========================================================================

    @Test
    fun testRoutingCacheHit_AvoidsRequeryingCoordinatorWithinTTL() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val initialCallCount = fakeCoordinator.callCount
        assertTrue("First fetch must query coordinator", initialCallCount >= 1)

        // Advance clock by 1 minute (within 3-minute TTL) with identical GPS coordinates
        currentTime += 60_000L

        // Execute routing again without location change
        viewModel.executeRoutingPipeline(
            stations = (viewModel.uiState.value as FavoritesUiState.Success).stations,
            userLat = userLat,
            userLon = userLon,
            forceRefresh = false
        ).join()

        // Assert cache hit: callCount does not increment
        assertEquals("Cache hit must avoid re-querying the coordinator", initialCallCount, fakeCoordinator.callCount)

        val state = viewModel.uiState.value as FavoritesUiState.Success
        assertNotNull("Stations must still retain cached metrics", state.stations.first().drivingMetrics)
    }

    @Test
    fun testCacheInvalidation_GpsDisplacementGreaterThan200Meters() = runTest(testDispatcher) {
        val initialLat = 10.7769
        val initialLon = 106.7009

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(initialLat, initialLon)
        viewModel.routingJob?.join()

        val baseCallCount = fakeCoordinator.callCount

        // Move user by ~500 meters
        val shiftedLat = 10.7815
        val shiftedLon = 106.7035

        viewModel.updateUserLocation(shiftedLat, shiftedLon)
        viewModel.routingJob?.join()

        // Displacement > 200m invalidates cache and triggers fresh coordinator routing
        assertEquals("GPS displacement > 200m must trigger fresh routing query", baseCallCount + 1, fakeCoordinator.callCount)
        assertEquals(shiftedLat, fakeCoordinator.lastOriginLat!!, 0.0001)
    }

    @Test
    fun testCacheInvalidation_SettingsUpdate() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val baseCallCount = fakeCoordinator.callCount

        // Update settings to GOOGLE_ONLY with new key
        val newSettings = RoutingSettings(
            preferredEngine = RoutingEngineMode.GOOGLE_ONLY,
            googleApiKey = "AIzaSyTestUpdatedKey"
        )
        viewModel.updateRoutingSettings(newSettings)
        viewModel.routingJob?.join()

        // Setting update must invalidate cache and trigger fresh routing request
        assertEquals("Settings update must invalidate cache and trigger re-route", baseCallCount + 1, fakeCoordinator.callCount)
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, fakeCoordinator.lastSettings?.preferredEngine)
        assertEquals("AIzaSyTestUpdatedKey", fakeCoordinator.lastSettings?.googleApiKey)
        assertEquals(newSettings, viewModel.routingSettings.value)
    }

    @Test
    fun testCacheInvalidation_TtlExpiry() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val baseCallCount = fakeCoordinator.callCount

        // Advance time by 3.5 minutes (> 180,000 ms TTL)
        currentTime += 210_000L

        // Re-execute routing
        viewModel.executeRoutingPipeline(
            stations = (viewModel.uiState.value as FavoritesUiState.Success).stations,
            userLat = userLat,
            userLon = userLon,
            forceRefresh = false
        ).join()

        // TTL expired must trigger fresh coordinator query
        assertEquals("TTL expiry (> 3 min) must invalidate cache and trigger fresh query", baseCallCount + 1, fakeCoordinator.callCount)
    }

    @Test
    fun testCacheInvalidation_PullToRefresh() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val baseCallCount = fakeCoordinator.callCount

        // User triggers pull-to-refresh
        val refreshJob = viewModel.refresh()
        refreshJob.join()
        viewModel.routingJob?.join()

        // refresh() must invalidate cache and query coordinator freshly
        assertEquals("Pull-to-refresh must force fresh coordinator query", baseCallCount + 1, fakeCoordinator.callCount)
    }

    // =========================================================================
    // 5. Fallback without Location
    // =========================================================================

    @Test
    fun testUserLocationUnavailable_RetainsStepOneWithoutCoordinator() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        val fetchJob = viewModel.fetchFavorites()
        fetchJob.join()
        viewModel.routingJob?.join()

        val state = viewModel.uiState.value
        assertTrue(state is FavoritesUiState.Success)
        val stations = (state as FavoritesUiState.Success).stations

        // Coordinator is never queried if coordinates are null
        assertEquals(0, fakeCoordinator.callCount)
        assertTrue(stations.all { it.drivingMetrics == null })
    }

    // =========================================================================
    // 6. Proactive BYOK Google Key Validation
    // =========================================================================

    @Test
    fun testValidateGoogleApiKey_DelegatesToPreferencesManager() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        // Blank key validation fails immediately with 400
        val blankResult = viewModel.validateGoogleApiKey("")
        assertTrue(blankResult.isFailure)
    }
}
