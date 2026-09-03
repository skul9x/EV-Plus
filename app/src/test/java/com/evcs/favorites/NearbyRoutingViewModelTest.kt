package com.evcs.favorites

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
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Comprehensive verification test for Phase 02:
 * - Top 10 Multi-Tier Routing Pipeline & Nearby ViewModel
 *
 * Verifies:
 * 1. Initial state defaults: hasSearched=false, all loading flags false, empty lists, empty wattages.
 * 2. Location permission guard: RequestLocationPermission event emitted when permission is absent.
 * 3. Scanning state transitions: isLocating -> isSearching -> isRoutingLoading -> idle.
 * 4. Strict <= 10 destinations policy: Routing coordinator receives strictly <= 10 destinations from large lists (e.g. 25-50 stations).
 * 5. Nearest-first Haversine ordering of extracted Top 10 destinations.
 * 6. Wattage filter toggle: recomputes Top 10 and initiates fresh routing requests.
 * 7. Unauthenticated favorite click: emits ShowLoginRequired without modifying repository.
 * 8. Authenticated favorite toggle: adds and removes station via repository, updates favoriteStationIds, and emits ShowToast.
 * 9. Refresh behavior: uses existing GPS coordinates if valid, or requests fresh GPS fix if invalid/null.
 * 10. Error handling: GPS failure or network search failure sets user-facing errorMessage; clearError() resets it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyRoutingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var viewModel: NearbyViewModel

    // Fake Location Service
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

    // Fake Routing Coordinator
    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount: Int = 0
        var lastOriginLat: Double? = null
        var lastOriginLng: Double? = null
        var lastDestinations: List<RoutingDestination> = emptyList()
        var lastSettings: RoutingSettings? = null

        var metricsProvider: (destinations: List<RoutingDestination>) -> Map<String, DrivingMetrics> = { dests ->
            dests.associate { d ->
                d.id to DrivingMetrics(
                    distanceMeters = 3500L,
                    durationSeconds = 420L,
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
            callCount++
            lastOriginLat = originLat
            lastOriginLng = originLng
            lastDestinations = destinations
            lastSettings = settings
            return metricsProvider(destinations)
        }
    }

    // Fake EVCS Repository
    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var nearbyResult: Result<List<Station>> = Result.success(emptyList())
        var lastSearchLat: Double? = null
        var lastSearchLon: Double? = null
        var searchCallCount: Int = 0

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            searchCallCount++
            lastSearchLat = lat
            lastSearchLon = lon
            return nearbyResult
        }

        override suspend fun addFavoriteStation(station: Station): Result<Unit> {
            val current = favoritesState.value.toMutableList()
            if (current.none { it.id == station.id }) {
                current.add(station)
            }
            saveCachedFavorites(current)
            // Reflection or property update for test state
            val favField = EvcsRepository::class.java.getDeclaredField("_favoritesState")
            favField.isAccessible = true
            val favFlow = favField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<List<Station>>
            favFlow.value = current

            val idField = EvcsRepository::class.java.getDeclaredField("_favoriteIdsState")
            idField.isAccessible = true
            val idFlow = idField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<Set<String>>
            idFlow.value = current.map { it.id }.toSet()

            return Result.success(Unit)
        }

        override suspend fun removeFavoriteStation(locationId: String): Result<Unit> {
            val current = favoritesState.value.toMutableList()
            current.removeAll { it.id == locationId }
            saveCachedFavorites(current)

            val favField = EvcsRepository::class.java.getDeclaredField("_favoritesState")
            favField.isAccessible = true
            val favFlow = favField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<List<Station>>
            favFlow.value = current

            val idField = EvcsRepository::class.java.getDeclaredField("_favoriteIdsState")
            idField.isAccessible = true
            val idFlow = idField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<Set<String>>
            idFlow.value = current.map { it.id }.toSet()

            return Result.success(Unit)
        }
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powerWatts: Long = 250_000L,
        powerLabel: String = "250kW",
        availablePlugs: Int = 2,
        totalPlugs: Int = 2,
        depotStatus: String = "Normal"
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerWatts,
                label = powerLabel,
                availablePlugs = availablePlugs,
                totalPlugs = totalPlugs,
                displayString = "$powerLabel: trống $availablePlugs/$totalPlugs"
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $id",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powerLabel,
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_auth_cookie"
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
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =============================================================
    // 1. INITIAL STATE VERIFICATION
    // =============================================================

    @Test
    fun testInitialUiState() = runTest {
        val state = viewModel.uiState.value
        assertFalse(state.hasSearched)
        assertFalse(state.isLocating)
        assertFalse(state.isSearching)
        assertFalse(state.isRoutingLoading)
        assertNull(state.userLatitude)
        assertNull(state.userLongitude)
        assertTrue(state.selectedWattages.isEmpty())
        assertTrue(state.rawStations.isEmpty())
        assertTrue(state.top10DisplayStations.isEmpty())
        assertTrue(state.routingMetrics.isEmpty())
        assertTrue(state.favoriteStationIds.isEmpty())
        assertNull(state.errorMessage)
    }

    // =============================================================
    // 2. LOCATION PERMISSION GUARD
    // =============================================================

    @Test
    fun testScanNearbyStationsWithoutPermissionEmitsEvent() = runTest {
        fakeLocationService.permissionGranted = false

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val event = viewModel.events.first()
        assertTrue(event is NearbyUiEvent.RequestLocationPermission)

        val state = viewModel.uiState.value
        assertFalse(state.hasSearched)
        assertFalse(state.isLocating)
        assertFalse(state.isSearching)
        assertEquals(0, fakeLocationService.freshLocationCallCount)
        assertEquals(0, fakeRepository.searchCallCount)
    }

    // =============================================================
    // 3. SCANNING PIPELINE, STATE TRANSITIONS & TOP 10 ROUTING
    // =============================================================

    @Test
    fun testScanNearbyStationsFullPipelineAndTop10Routing() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        // Create 25 candidate stations at increasing distances (approx 0.01 deg latitude ~ 1.11km per step)
        val stations = (1..25).map { idx ->
            createTestStation(
                id = "station_$idx",
                name = "Trạm $idx",
                lat = userLat + (idx * 0.01),
                lon = userLon + (idx * 0.01),
                powerWatts = 250_000L,
                powerLabel = "250kW",
                availablePlugs = 2,
                totalPlugs = 4
            )
        }
        fakeRepository.nearbyResult = Result.success(stations)

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.hasSearched)
        assertFalse(state.isLocating)
        assertFalse(state.isSearching)
        assertFalse(state.isRoutingLoading)
        assertEquals(userLat, state.userLatitude!!, 0.0001)
        assertEquals(userLon, state.userLongitude!!, 0.0001)
        assertEquals(25, state.rawStations.size)

        // Verify strictly Top 10 extracted
        assertEquals(10, state.top10DisplayStations.size)

        // Verify ordering: nearest stations 1..10 first
        for (i in 0 until 10) {
            assertEquals("station_${i + 1}", state.top10DisplayStations[i].id)
            assertNotNull(state.top10DisplayStations[i].distanceKm)
            assertNotNull(state.top10DisplayStations[i].drivingMetrics)
        }

        // Verify coordinator was called once with strictly 10 destinations
        assertEquals(1, fakeCoordinator.callCount)
        assertEquals(10, fakeCoordinator.lastDestinations.size)
        assertEquals(userLat, fakeCoordinator.lastOriginLat!!, 0.0001)
        assertEquals(userLon, fakeCoordinator.lastOriginLng!!, 0.0001)

        // Verify routingMetrics map
        assertEquals(10, state.routingMetrics.size)
        assertTrue(state.routingMetrics.containsKey("station_1"))
        assertTrue(state.routingMetrics.containsKey("station_10"))
    }

    // =============================================================
    // 4. STRICT <= 10 DESTINATIONS POLICY ENFORCEMENT
    // =============================================================

    @Test
    fun testStrictMax10DestinationsEnforcedEvenWith50Stations() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        val stations = (1..50).map { idx ->
            createTestStation(
                id = "station_$idx",
                name = "Trạm $idx",
                lat = userLat + (idx * 0.005),
                lon = userLon + (idx * 0.005)
            )
        }
        fakeRepository.nearbyResult = Result.success(stations)

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        // Guarantee destination batch is strictly <= 10
        assertTrue(fakeCoordinator.lastDestinations.size <= 10)
        assertEquals(10, fakeCoordinator.lastDestinations.size)
        assertEquals(10, viewModel.uiState.value.top10DisplayStations.size)
    }

    // =============================================================
    // 5. WATTAGE FILTER TOGGLE & FRESH ROUTING
    // =============================================================

    @Test
    fun testToggleWattageFilterRecomputesTop10AndFreshRouting() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        // 5 stations of 360kW, 10 stations of 250kW, 10 stations of 60kW
        val list360 = (1..5).map { idx ->
            createTestStation("st_360_$idx", "Trạm 360 $idx", userLat + 0.01 * idx, userLon, 360_000L, "360kW")
        }
        val list250 = (1..10).map { idx ->
            createTestStation("st_250_$idx", "Trạm 250 $idx", userLat + 0.02 * idx, userLon, 250_000L, "250kW")
        }
        val list60 = (1..10).map { idx ->
            createTestStation("st_60_$idx", "Trạm 60 $idx", userLat + 0.03 * idx, userLon, 60_000L, "60kW")
        }

        fakeRepository.nearbyResult = Result.success(list360 + list250 + list60)

        // 1. Initial scan with no wattage filter
        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()
        assertEquals(1, fakeCoordinator.callCount)
        assertEquals(10, viewModel.uiState.value.top10DisplayStations.size)

        // 2. Filter by 360kW only -> exactly 5 stations match
        viewModel.toggleWattageFilter(WattageOption.KW_360)
        testScheduler.advanceUntilIdle()

        assertEquals(setOf(WattageOption.KW_360), viewModel.uiState.value.selectedWattages)
        assertEquals(2, fakeCoordinator.callCount)
        assertEquals(5, fakeCoordinator.lastDestinations.size)
        assertEquals(5, viewModel.uiState.value.top10DisplayStations.size)
        assertTrue(viewModel.uiState.value.top10DisplayStations.all { it.powers.any { p -> p.typeWatts == 360_000L } })

        // 3. Add 250kW filter (OR condition: 360kW OR 250kW) -> 5 + 10 = 15 candidate stations -> Top 10 extracted
        viewModel.toggleWattageFilter(WattageOption.KW_250)
        testScheduler.advanceUntilIdle()

        assertEquals(setOf(WattageOption.KW_360, WattageOption.KW_250), viewModel.uiState.value.selectedWattages)
        assertEquals(3, fakeCoordinator.callCount)
        assertEquals(10, fakeCoordinator.lastDestinations.size)
        assertEquals(10, viewModel.uiState.value.top10DisplayStations.size)

        // 4. Untoggle 360kW -> only 250kW remains -> exactly 10 stations
        viewModel.toggleWattageFilter(WattageOption.KW_360)
        testScheduler.advanceUntilIdle()

        assertEquals(setOf(WattageOption.KW_250), viewModel.uiState.value.selectedWattages)
        assertEquals(4, fakeCoordinator.callCount)
        assertEquals(10, fakeCoordinator.lastDestinations.size)
        assertTrue(viewModel.uiState.value.top10DisplayStations.all { it.powers.any { p -> p.typeWatts == 250_000L } })
    }

    // =============================================================
    // 6. UNAUTHENTICATED FAVORITE GUARD
    // =============================================================

    @Test
    fun testUnauthenticatedToggleFavoriteEmitsShowLoginRequired() = runTest {
        sessionManager.authCookie = null // Unauthenticated user

        val station = createTestStation("st_test", "Trạm Vincom", 21.0285, 105.8542)

        viewModel.toggleFavorite(station)
        testScheduler.advanceUntilIdle()

        val event = viewModel.events.first()
        assertTrue(event is NearbyUiEvent.ShowLoginRequired)
        assertEquals("Trạm Vincom", (event as NearbyUiEvent.ShowLoginRequired).stationName)

        // Verify repository state is completely untouched
        assertTrue(viewModel.uiState.value.favoriteStationIds.isEmpty())
        assertTrue(fakeRepository.favoritesState.value.isEmpty())
    }

    // =============================================================
    // 7. AUTHENTICATED FAVORITE TOGGLE & TWO-WAY STATE SYNC
    // =============================================================

    @Test
    fun testAuthenticatedToggleFavoriteAddsAndRemovesFavorite() = runTest {
        sessionManager.authCookie = "valid_cookie_123"

        val station = createTestStation("st_landmark", "Trạm Landmark 81", 10.795, 106.721)

        // 1. First toggle: Add to favorites
        viewModel.toggleFavorite(station)
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.favoriteStationIds.contains("st_landmark"))
        assertTrue(fakeRepository.favoritesState.value.any { it.id == "st_landmark" })

        val addEvent = viewModel.events.first()
        assertTrue(addEvent is NearbyUiEvent.ShowToast)
        assertTrue((addEvent as NearbyUiEvent.ShowToast).message.contains("yêu thích"))

        // 2. Second toggle: Remove from favorites
        viewModel.toggleFavorite(station)
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.favoriteStationIds.contains("st_landmark"))
        assertFalse(fakeRepository.favoritesState.value.any { it.id == "st_landmark" })

        val removeEvent = viewModel.events.first()
        assertTrue(removeEvent is NearbyUiEvent.ShowToast)
        assertTrue((removeEvent as NearbyUiEvent.ShowToast).message.contains("xóa"))
    }

    // =============================================================
    // 8. REFRESH BEHAVIOR
    // =============================================================

    @Test
    fun testRefreshUsesExistingGpsCoordinatesWhenValid() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        val stations = (1..5).map { idx ->
            createTestStation("st_$idx", "Trạm $idx", userLat + 0.01 * idx, userLon)
        }
        fakeRepository.nearbyResult = Result.success(stations)

        // Initial scan
        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()
        assertEquals(1, fakeLocationService.freshLocationCallCount)
        assertEquals(1, fakeRepository.searchCallCount)

        // Now clear location service to verify refresh does NOT call getFreshLocation
        fakeLocationService.locationToReturn = null

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        // freshLocationCallCount remains 1 because refresh re-used existing coordinates
        assertEquals(1, fakeLocationService.freshLocationCallCount)
        assertEquals(2, fakeRepository.searchCallCount)
        assertEquals(userLat, fakeRepository.lastSearchLat!!, 0.0001)
        assertEquals(userLon, fakeRepository.lastSearchLon!!, 0.0001)
    }

    @Test
    fun testRefreshFallsBackToGpsWhenCoordinatesAreNull() = runTest {
        fakeLocationService.locationToReturn = createMockLocation(21.0285, 105.8542)

        // refresh called without prior search (coordinates are null)
        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(1, fakeLocationService.freshLocationCallCount)
        assertTrue(viewModel.uiState.value.hasSearched)
    }

    // =============================================================
    // 9. ERROR HANDLING & CLEAR ERROR
    // =============================================================

    @Test
    fun testLocationErrorSetsErrorMessage() = runTest {
        fakeLocationService.locationToReturn = null // Location acquisition failed

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLocating)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("vị trí"))

        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testSearchApiErrorSetsErrorMessage() = runTest {
        fakeLocationService.locationToReturn = createMockLocation(21.0285, 105.8542)
        fakeRepository.nearbyResult = Result.failure(IOException("Mạng không ổn định"))

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSearching)
        assertEquals("Mạng không ổn định", state.errorMessage)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    // =============================================================
    // 10. EMPTY SEARCH RESULT
    // =============================================================

    @Test
    fun testEmptyNearbyStationsHandledGracefully() = runTest {
        fakeLocationService.locationToReturn = createMockLocation(21.0285, 105.8542)
        fakeRepository.nearbyResult = Result.success(emptyList())

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.hasSearched)
        assertFalse(state.isSearching)
        assertFalse(state.isRoutingLoading)
        assertTrue(state.top10DisplayStations.isEmpty())
        assertTrue(state.routingMetrics.isEmpty())
        assertEquals(0, fakeCoordinator.callCount)
    }
}
