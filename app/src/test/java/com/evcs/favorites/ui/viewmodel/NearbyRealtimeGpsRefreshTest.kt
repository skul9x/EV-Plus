package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.state.NearbyUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive test file verifying Phase 02:
 * - Real-Time GPS Refresh & Location Error Handling in NearbyViewModel.
 *
 * Test Criteria:
 * 1. refresh() always calls locationService.getFreshLocation() and does not reuse stale in-memory coordinates.
 * 2. When getFreshLocation() succeeds with new coordinates (21.05, 105.85), userLatitude/userLongitude update in uiState,
 *    and repository is queried with the new coordinates.
 * 3. When getFreshLocation() returns null, uiState.errorMessage contains
 *    "Không thể lấy vị trí hiện tại. Vui lòng kiểm tra GPS và thử lại.",
 *    isSearching is false, isLocating is false, and stale coordinates are not silently re-queried.
 * 4. Calling refresh() while an existing scan job is running cancels the previous job cleanly.
 * 5. When location permission is missing, refresh() emits RequestLocationPermission event and aborts without fetching GPS.
 * 6. When repository search fails after acquiring fresh location, isSearching is false and errorMessage is populated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyRealtimeGpsRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var viewModel: NearbyViewModel

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null
        var freshLocationCallCount: Int = 0

        override fun hasLocationPermission(): Boolean = permissionGranted

        override suspend fun getFreshLocation(): Location? {
            freshLocationCallCount++
            return locationToReturn
        }
    }

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount: Int = 0

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            return destinations.associate { d ->
                d.id to DrivingMetrics(
                    distanceMeters = 3000L,
                    durationSeconds = 300L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var searchCallCount: Int = 0
        var lastSearchLat: Double? = null
        var lastSearchLon: Double? = null
        var nearbyResult: Result<List<Station>> = Result.success(emptyList())

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            searchCallCount++
            lastSearchLat = lat
            lastSearchLon = lon
            return nearbyResult
        }
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    private fun createTestStation(id: String, name: String, lat: Double, lon: Double): Station {
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $id",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = "250kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(
                    typeWatts = 250_000L,
                    label = "250kW",
                    availablePlugs = 2,
                    totalPlugs = 2,
                    displayString = "250kW: trống 2/2"
                )
            ),
            totalAvailablePlugs = 2,
            totalPlugs = 2
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_cookie"
        }
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeCoordinator = FakeRoutingCoordinator()
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)

        viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            routingDebounceMs = 0L
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRefreshAlwaysFetchesFreshGpsAndDoesNotReuseStaleCoordinates() = runTest {
        // Initial scan at Hanoi old quarter (21.0285, 105.8542)
        fakeLocationService.locationToReturn = createMockLocation(21.0285, 105.8542)
        fakeRepository.nearbyResult = Result.success(listOf(createTestStation("st1", "Old Station", 21.03, 105.85)))
        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        assertEquals(1, fakeLocationService.freshLocationCallCount)
        assertEquals(1, fakeRepository.searchCallCount)
        assertEquals(21.0285, viewModel.uiState.value.userLatitude!!, 0.0001)
        assertEquals(105.8542, viewModel.uiState.value.userLongitude!!, 0.0001)

        // User moves to (21.05, 105.85) and triggers refresh
        fakeLocationService.locationToReturn = createMockLocation(21.05, 105.85)
        fakeRepository.nearbyResult = Result.success(listOf(createTestStation("st2", "New Station", 21.051, 105.851)))

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        // Verify getFreshLocation() was called again (total 2 calls, stale coordinates NOT reused)
        assertEquals(2, fakeLocationService.freshLocationCallCount)
        // Verify UI state updated with new coordinates
        assertEquals(21.05, viewModel.uiState.value.userLatitude!!, 0.0001)
        assertEquals(105.85, viewModel.uiState.value.userLongitude!!, 0.0001)
        // Verify repository search was invoked with new coordinates
        assertEquals(2, fakeRepository.searchCallCount)
        assertEquals(21.05, fakeRepository.lastSearchLat!!, 0.0001)
        assertEquals(105.85, fakeRepository.lastSearchLon!!, 0.0001)
        // Verify search status flags
        assertFalse(viewModel.uiState.value.isLocating)
        assertFalse(viewModel.uiState.value.isSearching)
        assertTrue(viewModel.uiState.value.hasSearched)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testRefreshFailsWhenGpsUnavailableAndReportsErrorWithoutSilentlyReusingStaleCoordinates() = runTest {
        // Initial scan succeeds with (21.0285, 105.8542)
        fakeLocationService.locationToReturn = createMockLocation(21.0285, 105.8542)
        fakeRepository.nearbyResult = Result.success(listOf(createTestStation("st1", "Old Station", 21.03, 105.85)))
        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        assertEquals(1, fakeLocationService.freshLocationCallCount)
        assertEquals(1, fakeRepository.searchCallCount)

        // GPS becomes unavailable during refresh
        fakeLocationService.locationToReturn = null

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        // Verify getFreshLocation was called on refresh
        assertEquals(2, fakeLocationService.freshLocationCallCount)
        // Verify repository search was NOT called again (stale coordinates not reused)
        assertEquals(1, fakeRepository.searchCallCount)
        // Verify UI state flags and error message per user Option B
        val state = viewModel.uiState.value
        assertFalse(state.isLocating)
        assertFalse(state.isSearching)
        assertEquals("Không thể lấy vị trí hiện tại. Vui lòng kiểm tra GPS và thử lại.", state.errorMessage)
    }

    @Test
    fun testRefreshCancelsPreviousJobCleanly() = runTest {
        // Start an initial scan
        val initialJob = viewModel.scanNearbyStations()
        assertTrue(initialJob.isActive)

        // Immediately trigger refresh while scan is still running
        fakeLocationService.locationToReturn = createMockLocation(21.05, 105.85)
        val refreshJob = viewModel.refresh()

        // Old scan job must be cancelled
        assertTrue(initialJob.isCancelled)
        assertTrue(refreshJob.isActive)

        testScheduler.advanceUntilIdle()
        assertTrue(refreshJob.isCompleted)
        assertFalse(viewModel.uiState.value.isSearching)
        assertFalse(viewModel.uiState.value.isLocating)
    }

    @Test
    fun testRefreshWithoutPermissionEmitsPermissionEventAndDoesNotCallGps() = runTest {
        fakeLocationService.permissionGranted = false
        val emittedEvents = mutableListOf<NearbyUiEvent>()
        val collectJob = launch {
            viewModel.events.toList(emittedEvents)
        }

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(0, fakeLocationService.freshLocationCallCount)
        assertEquals(0, fakeRepository.searchCallCount)
        assertEquals(1, emittedEvents.size)
        assertTrue(emittedEvents[0] is NearbyUiEvent.RequestLocationPermission)

        collectJob.cancel()
    }

    @Test
    fun testRefreshRepositoryFailureSetsErrorMessage() = runTest {
        fakeLocationService.locationToReturn = createMockLocation(21.05, 105.85)
        fakeRepository.nearbyResult = Result.failure(RuntimeException("Mất kết nối mạng trạm sạc"))

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(1, fakeLocationService.freshLocationCallCount)
        assertEquals(1, fakeRepository.searchCallCount)
        assertFalse(viewModel.uiState.value.isLocating)
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals("Mất kết nối mạng trạm sạc", viewModel.uiState.value.errorMessage)
    }
}
