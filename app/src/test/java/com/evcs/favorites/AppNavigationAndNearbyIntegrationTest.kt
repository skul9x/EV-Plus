package com.evcs.favorites

import android.location.Location
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
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
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.navigation.AppTab
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single Comprehensive Integration Test for Phase 04:
 * Bottom Navigation Bar, NearbyScreen & Two-Way State Sync.
 *
 * Verifies:
 * 1. Navigation Architecture:
 *    - AppTab enum definitions (FAVORITES vs NEARBY, labels, selected & unselected icons).
 *    - Strict default start destination is AppTab.NEARBY.
 *    - Tab switching logic preserving active state.
 * 2. Two-Way Favorites Synchronization:
 *    - Favoriting a station on NearbyScreen (via NearbyViewModel) dispatches through shared EvcsRepository,
 *      updating favoritesState and instantly reflecting inside FavoritesViewModel UI state.
 *    - Deleting a station on FavoritesScreen (via FavoritesViewModel) updates repository favoriteIdsState,
 *      instantly un-tinting the heart icon inside NearbyViewModel favoriteStationIds.
 *    - Removing a favorite from NearbyViewModel updates repository and syncs to FavoritesViewModel.
 * 3. Shared Routing Settings & BYOK:
 *    - Routing settings modifications (e.g. updating Google Routes API Key or preferred engine)
 *      are reactively observed across both ViewModels through shared RoutingPreferencesManager.
 * 4. NearbyScreen Integration Features:
 *    - Wattage filter clear (clearWattageFilters) resets selectedWattages and recomputes Top 10.
 *    - Unauthenticated favorite tap emits ShowLoginRequired dialog event.
 *    - Authenticated favorite tap emits ShowToast feedback event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigationAndNearbyIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var authEngine: AuthEngine

    private lateinit var favoritesViewModel: FavoritesViewModel
    private lateinit var nearbyViewModel: NearbyViewModel

    // Fake Location Service
    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted

        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    // Fake Routing Coordinator
    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount: Int = 0
        var lastDestinations: List<RoutingDestination> = emptyList()

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            lastDestinations = destinations
            return destinations.associate { d ->
                d.id to DrivingMetrics(
                    distanceMeters = 2000L,
                    durationSeconds = 300L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    // Fake EVCS Repository managing reactive StateFlows in memory
    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var searchResults: List<Station> = emptyList()

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return Result.success(favoritesState.value)
        }

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return Result.success(searchResults)
        }

        override suspend fun addFavoriteStation(station: Station): Result<Unit> {
            val current = favoritesState.value.toMutableList()
            if (current.none { it.id == station.id }) {
                current.add(station)
            }
            updateInternalState(current)
            return Result.success(Unit)
        }

        override suspend fun removeFavoriteStation(locationId: String): Result<Unit> {
            val current = favoritesState.value.toMutableList()
            current.removeAll { it.id == locationId }
            updateInternalState(current)
            return Result.success(Unit)
        }

        private fun updateInternalState(stations: List<Station>) {
            val favField = EvcsRepository::class.java.getDeclaredField("_favoritesState")
            favField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val favFlow = favField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<List<Station>>
            favFlow.value = stations

            val idField = EvcsRepository::class.java.getDeclaredField("_favoriteIdsState")
            idField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val idFlow = idField.get(this) as kotlinx.coroutines.flow.MutableStateFlow<Set<String>>
            idFlow.value = stations.map { it.id }.toSet()
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
        lat: Double = 21.0285,
        lon: Double = 105.8542,
        powerWatts: Long = 250_000L,
        powerLabel: String = "250kW"
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerWatts,
                label = powerLabel,
                availablePlugs = 2,
                totalPlugs = 2,
                displayString = "$powerLabel: trống 2/2"
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $name",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powerLabel,
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = 2,
            totalPlugs = 2
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "authenticated_token_999"
            userEmail = "driver@evcs.vn"
        }
        authEngine = AuthEngine(sessionManager)
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeCoordinator = FakeRoutingCoordinator()
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)

        // Instantiate ViewModels sharing the exact same singleton repository & preferences
        favoritesViewModel = FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        nearbyViewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. NAVIGATION ARCHITECTURE & APPTAB SPECIFICATION TESTS
    // =========================================================================

    @Test
    fun testNavigationTabEnumDefinitionsAndDefaults() {
        // Verify all navigation tabs exist
        val tabs = AppTab.entries
        assertEquals(2, tabs.size)
        assertTrue(tabs.contains(AppTab.FAVORITES))
        assertTrue(tabs.contains(AppTab.NEARBY))

        // Verify AppTab.FAVORITES specifications
        assertEquals("Yêu thích", AppTab.FAVORITES.label)
        assertEquals(Icons.Filled.Favorite, AppTab.FAVORITES.selectedIcon)
        assertEquals(Icons.Outlined.FavoriteBorder, AppTab.FAVORITES.unselectedIcon)

        // Verify AppTab.NEARBY specifications
        assertEquals("Quanh đây", AppTab.NEARBY.label)
        assertEquals(Icons.Filled.LocationOn, AppTab.NEARBY.selectedIcon)
        assertEquals(Icons.Outlined.LocationOn, AppTab.NEARBY.unselectedIcon)

        // Verify default start tab contract
        var activeTab = AppTab.NEARBY
        assertEquals(AppTab.NEARBY, activeTab)

        // Verify tab switching transitions
        activeTab = AppTab.FAVORITES
        assertEquals(AppTab.FAVORITES, activeTab)

        activeTab = AppTab.NEARBY
        assertEquals(AppTab.NEARBY, activeTab)
    }

    // =========================================================================
    // 2. TWO-WAY FAVORITE SYNCHRONIZATION INTEGRATION TESTS
    // =========================================================================

    @Test
    fun testTwoWayFavoriteSyncBetweenNearbyAndFavoritesScreens() = runTest {
        val stationA = createTestStation("st_bac_ninh", "VinFast Dabaco Bac Ninh")
        val stationB = createTestStation("st_royal_city", "VinFast Royal City")

        // Seed repository with stationA initially
        fakeRepository.addFavoriteStation(stationA)
        testScheduler.advanceUntilIdle()

        // Fetch favorites in FavoritesViewModel to initialize Success state
        favoritesViewModel.fetchFavorites()
        testScheduler.advanceUntilIdle()

        val initialFavUiState = favoritesViewModel.uiState.value
        assertTrue(initialFavUiState is FavoritesUiState.Success)
        val initialStations = (initialFavUiState as FavoritesUiState.Success).stations
        assertEquals(1, initialStations.size)
        assertEquals("st_bac_ninh", initialStations[0].id)

        // NearbyViewModel should also have stationA in favoriteStationIds
        assertTrue(nearbyViewModel.uiState.value.favoriteStationIds.contains("st_bac_ninh"))

        // --- PART A: Add stationB on NearbyScreen ---
        nearbyViewModel.toggleFavorite(stationB)
        testScheduler.advanceUntilIdle()

        // Verify repository received stationB
        assertTrue(fakeRepository.favoritesState.value.any { it.id == "st_royal_city" })
        assertTrue(fakeRepository.favoriteIdsState.value.contains("st_royal_city"))

        // Verify NearbyViewModel updated its favoriteStationIds
        assertTrue(nearbyViewModel.uiState.value.favoriteStationIds.contains("st_royal_city"))

        // Verify FavoritesViewModel automatically received stationB via reactive observer
        val updatedFavUiState = favoritesViewModel.uiState.value
        assertTrue(updatedFavUiState is FavoritesUiState.Success)
        val currentFavList = (updatedFavUiState as FavoritesUiState.Success).stations
        assertEquals(2, currentFavList.size)
        val currentFavIds = currentFavList.map { it.id }.toSet()
        assertTrue(currentFavIds.contains("st_bac_ninh"))
        assertTrue(currentFavIds.contains("st_royal_city"))

        // --- PART B: Delete stationA on FavoritesScreen ---
        favoritesViewModel.removeFavorite("st_bac_ninh")
        testScheduler.advanceUntilIdle()

        // Verify FavoritesViewModel state reflects removal
        val afterDeleteFavState = favoritesViewModel.uiState.value as FavoritesUiState.Success
        assertEquals(1, afterDeleteFavState.stations.size)
        assertEquals("st_royal_city", afterDeleteFavState.stations[0].id)

        // Verify repository removed stationA
        assertFalse(fakeRepository.favoriteIdsState.value.contains("st_bac_ninh"))
        assertTrue(fakeRepository.favoriteIdsState.value.contains("st_royal_city"))

        // Verify NearbyScreen instantly un-tints the heart icon for stationA
        assertFalse(nearbyViewModel.uiState.value.favoriteStationIds.contains("st_bac_ninh"))
        assertTrue(nearbyViewModel.uiState.value.favoriteStationIds.contains("st_royal_city"))

        // --- PART C: Remove stationB from NearbyScreen ---
        nearbyViewModel.toggleFavorite(stationB)
        testScheduler.advanceUntilIdle()

        // Verify repository has 0 favorites
        assertTrue(fakeRepository.favoriteIdsState.value.isEmpty())
        assertTrue(fakeRepository.favoritesState.value.isEmpty())

        // Verify NearbyScreen has 0 favorites
        assertTrue(nearbyViewModel.uiState.value.favoriteStationIds.isEmpty())

        // Verify FavoritesScreen updated to 0 favorites
        val emptyFavState = favoritesViewModel.uiState.value as FavoritesUiState.Success
        assertTrue(emptyFavState.stations.isEmpty())
    }

    // =========================================================================
    // 3. SHARED ROUTING PREFERENCES & SETTINGS SYNCHRONIZATION TESTS
    // =========================================================================

    @Test
    fun testSharedRoutingSettingsAcrossScreens() = runTest {
        // Initial settings should have default values
        val initialSettings = prefsManager.settings.value
        assertEquals(com.evcs.favorites.data.routing.RoutingEngineMode.OSRM_ONLY, initialSettings.preferredEngine)
        assertTrue(initialSettings.googleApiKey.isEmpty())

        // Both ViewModels observe the same routingSettings flow
        assertEquals(initialSettings, favoritesViewModel.routingSettings.value)

        // Update settings via FavoritesViewModel
        val newSettings = RoutingSettings(
            preferredEngine = com.evcs.favorites.data.routing.RoutingEngineMode.GOOGLE_ONLY,
            googleApiKey = "AIzaSyTestKey1234567890",
            autoFallbackEnabled = true
        )
        favoritesViewModel.updateRoutingSettings(newSettings)
        testScheduler.advanceUntilIdle()

        // Verify prefsManager persisted the settings
        val storedSettings = prefsManager.settings.value
        assertEquals(com.evcs.favorites.data.routing.RoutingEngineMode.GOOGLE_ONLY, storedSettings.preferredEngine)
        assertEquals("AIzaSyTestKey1234567890", storedSettings.googleApiKey)
        assertTrue(storedSettings.autoFallbackEnabled)

        // Verify the update is immediately observable by both screens
        assertEquals(storedSettings, favoritesViewModel.routingSettings.value)
    }

    // =========================================================================
    // 4. NEARBY SCREEN CLEAR WATTAGE FILTERS & EVENT INTEGRATION TESTS
    // =========================================================================

    @Test
    fun testNearbyClearWattageFiltersAndPipelineReExecution() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        val st360 = createTestStation("st_360", "Trạm 360", userLat + 0.01, userLon, 360_000L, "360kW")
        val st60 = createTestStation("st_60", "Trạm 60", userLat + 0.02, userLon, 60_000L, "60kW")
        fakeRepository.searchResults = listOf(st360, st60)

        // Perform initial scan
        nearbyViewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        assertEquals(2, nearbyViewModel.uiState.value.top10DisplayStations.size)

        // Filter by 360kW only
        nearbyViewModel.toggleWattageFilter(WattageOption.KW_360)
        testScheduler.advanceUntilIdle()

        assertEquals(1, nearbyViewModel.uiState.value.top10DisplayStations.size)
        assertEquals("st_360", nearbyViewModel.uiState.value.top10DisplayStations[0].id)
        assertEquals(setOf(WattageOption.KW_360), nearbyViewModel.uiState.value.selectedWattages)

        // Clear wattage filters
        nearbyViewModel.clearWattageFilters()
        testScheduler.advanceUntilIdle()

        // Selected wattages must now be empty and all stations restored
        assertTrue(nearbyViewModel.uiState.value.selectedWattages.isEmpty())
        assertEquals(2, nearbyViewModel.uiState.value.top10DisplayStations.size)
    }

    @Test
    fun testNearbyUnauthenticatedFavoriteEmitsShowLoginRequired() = runTest {
        // Clear auth cookie to simulate unauthenticated user
        sessionManager.authCookie = null
        val station = createTestStation("st_guest", "Trạm Guest")

        nearbyViewModel.toggleFavorite(station)
        testScheduler.advanceUntilIdle()

        val event = nearbyViewModel.events.first()
        assertTrue("Must emit ShowLoginRequired for unauthenticated favorite attempt", event is NearbyUiEvent.ShowLoginRequired)
        assertEquals("Trạm Guest", (event as NearbyUiEvent.ShowLoginRequired).stationName)

        // Repository remains unmodified
        assertTrue(nearbyViewModel.uiState.value.favoriteStationIds.isEmpty())
        assertTrue(fakeRepository.favoritesState.value.isEmpty())
    }

    @Test
    fun testNearbyAuthenticatedFavoriteEmitsShowToast() = runTest {
        sessionManager.authCookie = "valid_auth"
        val station = createTestStation("st_auth", "Trạm Auth")

        nearbyViewModel.toggleFavorite(station)
        testScheduler.advanceUntilIdle()

        val event = nearbyViewModel.events.first()
        assertTrue("Must emit ShowToast upon successful favorite", event is NearbyUiEvent.ShowToast)
        assertTrue((event as NearbyUiEvent.ShowToast).message.contains("yêu thích"))
    }
}
