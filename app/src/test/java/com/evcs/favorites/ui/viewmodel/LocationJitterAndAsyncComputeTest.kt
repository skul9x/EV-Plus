package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.ui.state.FavoritesUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single comprehensive test file verifying Phase 05:
 * 1. PERF-LOC-01: GPS jitter filtering (20m threshold) & cache-valid displacement skipping (<= 200m).
 * 2. PERF-ASYNC-02: Async compute dispatching to defaultDispatcher for sorting and filtering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationJitterAndAsyncComputeTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var trackingDefaultDispatcher: TrackingDispatcher
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private var currentTime: Long = 1_000_000L

    class TrackingDispatcher(
        private val delegate: CoroutineDispatcher
    ) : CoroutineDispatcher() {
        val dispatchCount = AtomicInteger(0)

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatchCount.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount = 0
        var lastOriginLat: Double? = null
        var lastOriginLng: Double? = null
        var lastDestinations: List<RoutingDestination> = emptyList()

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            lastOriginLat = originLat
            lastOriginLng = originLng
            lastDestinations = destinations
            return destinations.associate { dest ->
                dest.id to DrivingMetrics(
                    distanceMeters = 2000L,
                    durationSeconds = 180L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    class FakeLocationService : LocationService() {
        var locationToReturn: Location? = null
        override fun hasLocationPermission(): Boolean = true
        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var stationsToReturn: List<Station> = emptyList()

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return Result.success(stationsToReturn)
        }

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return Result.success(stationsToReturn)
        }
    }

    @Before
    fun setUp() {
        trackingDefaultDispatcher = TrackingDispatcher(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_auth_cookie"
            phpSessionId = "phpsess_123"
        }
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        fakeCoordinator = FakeRoutingCoordinator()
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService()
        currentTime = 1_000_000L
    }

    private fun createTestStations(count: Int = 15): List<Station> {
        return (1..count).map { i ->
            val lat = 10.7769 + (i * 0.005)
            val lon = 106.7009 + (i * 0.005)
            Station(
                id = "station_$i",
                name = "Trạm sạc $i",
                address = "Địa chỉ $i",
                latitude = lat,
                longitude = lon,
                summary = "24/7",
                connectors = "60kW, 250kW",
                depotStatus = "Normal",
                totalAvailablePlugs = 3,
                totalPlugs = 6,
                powers = listOf(
                    PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 2, totalPlugs = 4, displayString = "60kW: trống 2/4"),
                    PowerPort(typeWatts = 250_000L, label = "250kW", availablePlugs = 1, totalPlugs = 2, displayString = "250kW: trống 1/2")
                )
            )
        }
    }

    private fun createFavoritesViewModel(
        stations: List<Station> = createTestStations()
    ): FavoritesViewModel {
        fakeRepository.stationsToReturn = stations
        val authEngine = AuthEngine(sessionManager, OkHttpClient(), "https://test.evcs.vn")
        return FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = null,
            dispatcher = testDispatcher,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher,
            defaultDispatcher = trackingDefaultDispatcher
        ).apply {
            timeProvider = { currentTime }
        }
    }

    private fun createNearbyViewModel(
        stations: List<Station> = createTestStations()
    ): NearbyViewModel {
        fakeRepository.stationsToReturn = stations
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            filterPreferences = NearbyFilterPreferences(sessionStorage),
            smartFilterPreferences = SmartFilterPreferences(sessionStorage),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = trackingDefaultDispatcher
        )
    }

    // =========================================================================
    // 1. Stationary GPS Drift & Sub-Threshold Movement Tests (PERF-LOC-01)
    // =========================================================================

    @Test
    fun testStationaryGpsDrift_Under20Meters_EarlyReturnsAndPreservesReference() = runTest(testDispatcher) {
        val initialLat = 10.776900
        val initialLon = 106.700900
        val stations = createTestStations(12)
        val viewModel = createFavoritesViewModel(stations)

        // Seed initial favorites list and initial user location
        val fetchJob = viewModel.fetchFavorites()
        fetchJob.join()
        advanceUntilIdle()

        viewModel.updateUserLocation(initialLat, initialLon)
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        val initialCoordinatorCalls = fakeCoordinator.callCount
        assertTrue("Initial routing must have occurred", initialCoordinatorCalls >= 1)
        val stateAfterInitial = viewModel.uiState.value as FavoritesUiState.Success
        val initialStations = stateAfterInitial.stations

        // 1. Minor drift: ~5 meters (deltaLat ~0.000045 degrees ~5m)
        val driftLat1 = 10.776940
        val driftLon1 = 106.700920
        val displacement1 = DistanceCalculator.calculateDistanceMeters(initialLat, initialLon, driftLat1, driftLon1)
        assertTrue("Displacement should be <= 20m (was ${displacement1}m)", displacement1 <= 20.0)

        val noOpJob1 = viewModel.updateUserLocation(driftLat1, driftLon1)
        assertTrue("Jitter <= 20m must return an immediately completed Job", noOpJob1.isCompleted)
        advanceUntilIdle()
        assertEquals("Coordinator must NOT be called on jitter <= 20m", initialCoordinatorCalls, fakeCoordinator.callCount)

        // 2. Cumulative sub-threshold movements: ~15 meters from initial
        val driftLat2 = 10.777000
        val driftLon2 = 106.700950
        val displacement2 = DistanceCalculator.calculateDistanceMeters(initialLat, initialLon, driftLat2, driftLon2)
        assertTrue("Displacement from initial must be <= 20m (was ${displacement2}m)", displacement2 <= 20.0)

        val noOpJob2 = viewModel.updateUserLocation(driftLat2, driftLon2)
        assertTrue("Sub-threshold movement <= 20m must return completed job", noOpJob2.isCompleted)
        advanceUntilIdle()
        assertEquals("Coordinator must still not be called", initialCoordinatorCalls, fakeCoordinator.callCount)

        // Verify UI state stations reference position did not mutate
        val currentState = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals("Stations list must remain unchanged during minor jitter", initialStations, currentState.stations)
    }

    @Test
    fun testGpsDisplacement_Between20mAnd200m_SkipsRoutingWhenCacheValid_AndRefetchesWhenExpired() = runTest(testDispatcher) {
        val initialLat = 10.776900
        val initialLon = 106.700900
        val stations = createTestStations(12)
        val viewModel = createFavoritesViewModel(stations)

        viewModel.fetchFavorites().join()
        advanceUntilIdle()
        viewModel.updateUserLocation(initialLat, initialLon)
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        val baseCalls = fakeCoordinator.callCount

        // 1. Move by ~60 meters (20m < displacement <= 200m) while cache is fresh
        val midLat = 10.777400
        val midLon = 106.701100
        val midDisplacement = DistanceCalculator.calculateDistanceMeters(initialLat, initialLon, midLat, midLon)
        assertTrue("Displacement must be > 20m (was ${midDisplacement}m)", midDisplacement > 20.0)
        assertTrue("Displacement must be <= 200m (was ${midDisplacement}m)", midDisplacement <= 200.0)

        val midJob = viewModel.updateUserLocation(midLat, midLon)
        assertTrue("Displacement <= 200m with valid cache must return completed job immediately", midJob.isCompleted)
        advanceUntilIdle()
        assertEquals("Coordinator must NOT be called when cache is valid within 200m", baseCalls, fakeCoordinator.callCount)

        // 2. Expire the cache (TTL is 3 minutes = 180_000ms; advance by 4 minutes = 240_000ms)
        currentTime += 240_000L

        // Move again by another ~50m from mid coordinates (still <= 200m from cache origin, but cache expired)
        val expiredLat = 10.777800
        val expiredLon = 106.701300
        val expiredDisplacementFromMid = DistanceCalculator.calculateDistanceMeters(midLat, midLon, expiredLat, expiredLon)
        assertTrue("Displacement from mid must be > 20m (was ${expiredDisplacementFromMid}m)", expiredDisplacementFromMid > 20.0)

        viewModel.updateUserLocation(expiredLat, expiredLon)
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        assertEquals("Coordinator MUST be called when displacement > 20m and cache has expired", baseCalls + 1, fakeCoordinator.callCount)
    }

    @Test
    fun testMajorDisplacement_Over200Meters_TriggersCacheInvalidationAndResorts() = runTest(testDispatcher) {
        val initialLat = 10.776900
        val initialLon = 106.700900
        val stations = createTestStations(12)
        val viewModel = createFavoritesViewModel(stations)

        viewModel.fetchFavorites().join()
        advanceUntilIdle()
        viewModel.updateUserLocation(initialLat, initialLon)
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        val baseCalls = fakeCoordinator.callCount

        // Move user by ~500 meters
        val shiftedLat = 10.781500
        val shiftedLon = 106.703500
        val displacement = DistanceCalculator.calculateDistanceMeters(initialLat, initialLon, shiftedLat, shiftedLon)
        assertTrue("Displacement must be > 200m (was ${displacement}m)", displacement > 200.0)

        viewModel.updateUserLocation(shiftedLat, shiftedLon)
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        assertEquals("Displacement > 200m must trigger fresh routing coordinator query", baseCalls + 1, fakeCoordinator.callCount)
        assertEquals(shiftedLat, fakeCoordinator.lastOriginLat!!, 0.0001)
        assertEquals(shiftedLon, fakeCoordinator.lastOriginLng!!, 0.0001)
    }

    // =========================================================================
    // 2. Async Compute Dispatching Tests (PERF-ASYNC-02)
    // =========================================================================

    @Test
    fun testFavoritesViewModel_OffloadsSortingToDefaultDispatcher() = runTest(testDispatcher) {
        val stations = createTestStations(15)
        val viewModel = createFavoritesViewModel(stations)

        val beforeCount = trackingDefaultDispatcher.dispatchCount.get()

        // Fetch favorites with location coordinates triggers Step 1 Haversine sort on defaultDispatcher
        viewModel.updateUserLocation(10.7769, 106.7009)
        val job = viewModel.fetchFavorites()
        job.join()
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        val afterCount = trackingDefaultDispatcher.dispatchCount.get()
        assertTrue(
            "Candidate sorting and station ordering in FavoritesViewModel must dispatch to defaultDispatcher (dispatched $afterCount times, was $beforeCount)",
            afterCount > beforeCount
        )
    }

    @Test
    fun testNearbyViewModel_OffloadsFilteringAndSortingToDefaultDispatcher() = runTest(testDispatcher) {
        val stations = createTestStations(15)
        val viewModel = createNearbyViewModel(stations)

        val mockLocation = Location("test_provider").apply {
            latitude = 10.7769
            longitude = 106.7009
        }
        fakeLocationService.locationToReturn = mockLocation

        val beforeCount = trackingDefaultDispatcher.dispatchCount.get()

        // Scan nearby stations executes filter and routing pipeline
        val scanJob = viewModel.scanNearbyStations()
        scanJob.join()
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        val afterScanCount = trackingDefaultDispatcher.dispatchCount.get()
        assertTrue(
            "NearbyViewModel executeFilterAndRoutingPipeline must dispatch heavy filtering and sorting to defaultDispatcher (dispatched $afterScanCount times, before: $beforeCount)",
            afterScanCount > beforeCount
        )

        // Verify top10 stations are populated and sorted
        val uiState = viewModel.uiState.value
        assertEquals(10, uiState.top10DisplayStations.size)
        assertTrue(uiState.top10DisplayStations.all { it.drivingMetrics != null })

        // Apply smart filter: DC mode
        val filterBeforeCount = trackingDefaultDispatcher.dispatchCount.get()
        viewModel.selectDcTier(DcWattageTier.GE_60KW)
        advanceUntilIdle()
        viewModel.routingJob?.join()
        advanceUntilIdle()

        val filterAfterCount = trackingDefaultDispatcher.dispatchCount.get()
        assertTrue(
            "Smart filtering in NearbyViewModel must execute via defaultDispatcher",
            filterAfterCount > filterBeforeCount
        )
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
    }
}
