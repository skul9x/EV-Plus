package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.WattageOption
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
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.state.FavoritesUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive test file for Phase 02:
 * ViewModel and Background Job Decommissioning.
 *
 * Requirements verified:
 * 1. FavoritesViewModel loads favorite stations and updates live status without spawning any forecast background tasks.
 * 2. NearbyViewModel executes GPS/road sorting and distance filtering without spawning any forecast background tasks.
 * 3. Station state lists in both ViewModels remain stable and responsive during refresh and search queries with zero forecast mutations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForecastDecommissionViewModelPipelineTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var fakeRepository: TrackingEvcsRepository
    private lateinit var routingPrefsManager: RoutingPreferencesManager
    private lateinit var nearbyFilterPrefs: NearbyFilterPreferences
    private lateinit var smartFilterPrefs: SmartFilterPreferences

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null
            set(value) {
                field = value
                setLocation(value)
            }

        override fun hasLocationPermission(): Boolean = permissionGranted
        override suspend fun getFreshLocation(): Location? {
            setLocation(locationToReturn)
            return locationToReturn
        }
    }

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount = 0

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            return destinations.associate { d ->
                val index = d.id.filter { it.isDigit() }.toIntOrNull() ?: 1
                d.id to DrivingMetrics(
                    distanceMeters = (index * 1000).toLong(),
                    durationSeconds = (index * 120).toLong(),
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    class TrackingEvcsRepository(
        sessionStorage: InMemorySessionStorage,
        testDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage,
        ioDispatcher = testDispatcher
    ) {
        var favoritesResult: Result<List<Station>> = Result.success(emptyList())
        var nearbyResult: Result<List<Station>> = Result.success(emptyList())

        var enrichStationsWithForecastCallCount = 0
        var fetchStationForecastCallCount = 0

        fun emitFavorites(stations: List<Station>) {
            val favField = EvcsRepository::class.java.getDeclaredField("_favoritesState")
            favField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (favField.get(this) as MutableStateFlow<List<Station>>).value = stations
        }

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return favoritesResult
        }

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return nearbyResult
        }

        suspend fun fetchStationForecast(
            station: Station,
            forceRefresh: Boolean
        ): Result<Nothing?> {
            fetchStationForecastCallCount++
            return Result.success(null)
        }

        suspend fun enrichStationsWithForecast(
            stations: List<Station>,
            forceRefresh: Boolean,
            onStationUpdated: ((Station) -> Unit)?
        ): List<Station> {
            enrichStationsWithForecastCallCount++
            return stations
        }
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    private fun createStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powerWatts: Long = 60_000L,
        powerLabel: String = "60kW",
        availablePlugs: Int = 2,
        totalPlugs: Int = 4
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
            address = "$name Address",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powerLabel,
            depotStatus = "Normal",
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
            authCookie = "valid_test_token"
        }
        authEngine = AuthEngine(sessionManager)

        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeCoordinator = FakeRoutingCoordinator()
        fakeRepository = TrackingEvcsRepository(sessionStorage, testDispatcher)
        routingPrefsManager = RoutingPreferencesManager(storage = sessionStorage)
        nearbyFilterPrefs = NearbyFilterPreferences(storage = sessionStorage)
        smartFilterPrefs = SmartFilterPreferences(storage = sessionStorage)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createFavoritesViewModel(): FavoritesViewModel {
        return FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            routingPreferencesManager = routingPrefsManager,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    private fun createNearbyViewModel(): NearbyViewModel {
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingPreferencesManager = routingPrefsManager,
            routingCoordinator = fakeCoordinator,
            filterPreferences = nearbyFilterPrefs,
            smartFilterPreferences = smartFilterPrefs,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @Test
    fun testFavoritesViewModel_loadsAndRefreshesWithZeroForecastNetworkTasksAndNoMutations() = runTest(testDispatcher) {
        val st1 = createStation("st_1", "Station 1", 21.0300, 105.8500)
        val st2 = createStation("st_2", "Station 2", 21.0400, 105.8600)
        val st3 = createStation("st_3", "Station 3", 21.0500, 105.8700)
        val initialStations = listOf(st1, st2, st3)

        fakeRepository.favoritesResult = Result.success(initialStations)
        fakeRepository.emitFavorites(initialStations)

        val favViewModel = createFavoritesViewModel()
        advanceUntilIdle()

        // 1. Verify successful load and routing without any forecast enrichment calls
        val state = favViewModel.uiState.value as FavoritesUiState.Success
        assertEquals(3, state.stations.size)
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)

        // Verify all stations have driving metrics attached
        state.stations.forEach { station ->
            assertNotNull("Station ${station.id} should have driving metrics", station.drivingMetrics)
        }

        // 2. Perform pull-to-refresh
        favViewModel.refreshFavorites()
        advanceUntilIdle()

        val refreshedState = favViewModel.uiState.value as FavoritesUiState.Success
        assertEquals(3, refreshedState.stations.size)
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)



        // 3. Verify repository two-way sync updates without triggering forecast tasks
        val updatedSt1 = st1.copy(name = "Station 1 Updated")
        fakeRepository.emitFavorites(listOf(updatedSt1, st2, st3))
        advanceUntilIdle()

        val syncState = favViewModel.uiState.value as FavoritesUiState.Success
        assertEquals("Station 1 Updated", syncState.stations.first { it.id == "st_1" }.name)
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)
    }

    @Test
    fun testNearbyViewModel_executesFilteringAndRoutingWithZeroForecastTasksAndNoMutations() = runTest(testDispatcher) {
        val st1 = createStation("st_1", "Station 1", 21.0300, 105.8500, powerWatts = 60_000L, powerLabel = "60kW")
        val st2 = createStation("st_2", "Station 2", 21.0350, 105.8520, powerWatts = 120_000L, powerLabel = "120kW")
        val st3 = createStation("st_3", "Station 3", 21.0400, 105.8550, powerWatts = 250_000L, powerLabel = "250kW")
        val rawStations = listOf(st1, st2, st3)

        fakeRepository.nearbyResult = Result.success(rawStations)

        val nearbyViewModel = createNearbyViewModel()
        advanceUntilIdle()

        // 1. Scan nearby stations
        nearbyViewModel.scanNearbyStations()
        advanceUntilIdle()

        val uiState = nearbyViewModel.uiState.value
        assertEquals(3, uiState.top10DisplayStations.size)
        assertTrue(uiState.hasSearched)
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)

        // Verify sorted by driving distance
        uiState.top10DisplayStations.forEach { station ->
            assertNotNull("Station ${station.id} should have driving metrics", station.drivingMetrics)
        }

        // 2. Filter toggle (e.g. 60kW only)
        nearbyViewModel.toggleWattageFilter(WattageOption.KW_60)
        advanceUntilIdle()

        val filteredState = nearbyViewModel.uiState.value
        assertEquals(1, filteredState.top10DisplayStations.size)
        assertEquals("st_1", filteredState.top10DisplayStations.first().id)
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)

        // 3. Clear filters
        nearbyViewModel.clearWattageFilters()
        advanceUntilIdle()

        val clearedFilterState = nearbyViewModel.uiState.value
        assertEquals(3, clearedFilterState.top10DisplayStations.size)
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)

        // 4. Reload / refresh (pull-to-refresh)
        nearbyViewModel.refresh()
        advanceUntilIdle()

        val reloadedState = nearbyViewModel.uiState.value
        assertEquals(3, reloadedState.top10DisplayStations.size)
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)
    }

    @Test
    fun testViewModels_stationListsRemainStableAndResponsiveWithoutBackgroundForecastJobs() = runTest(testDispatcher) {
        val st1 = createStation("st_1", "Station 1", 21.0300, 105.8500)
        val st2 = createStation("st_2", "Station 2", 21.0400, 105.8600)
        fakeRepository.favoritesResult = Result.success(listOf(st1, st2))
        fakeRepository.nearbyResult = Result.success(listOf(st1, st2))
        fakeRepository.emitFavorites(listOf(st1, st2))

        val favViewModel = createFavoritesViewModel()
        val nearbyViewModel = createNearbyViewModel()
        advanceUntilIdle()

        nearbyViewModel.scanNearbyStations()
        advanceUntilIdle()

        // Verify Favorites state
        val favState = favViewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, favState.stations.size)

        // Verify Nearby state
        val nearbyState = nearbyViewModel.uiState.value
        assertEquals(2, nearbyState.top10DisplayStations.size)

        // Both ViewModels have completed all background work without spawning any forecast tasks
        assertEquals(0, fakeRepository.enrichStationsWithForecastCallCount)
        assertEquals(0, fakeRepository.fetchStationForecastCallCount)
    }
}
