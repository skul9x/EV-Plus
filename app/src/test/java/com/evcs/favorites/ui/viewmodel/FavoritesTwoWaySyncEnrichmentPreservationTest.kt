package com.evcs.favorites.ui.viewmodel

import android.location.Location
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
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.state.FavoritesUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive test file for Phase 02:
 * Favorites Two-Way Sync Enrichment Preservation.
 *
 * Requirements verified:
 * 1. Existing stations retain drivingMetrics (duration, distance, traffic) when repository.favoritesState emits.
 * 2. Removing a favorite via repository emission removes only the targeted station while preserving metrics on remaining stations.
 * 3. Adding a new favorite preserves metrics on existing stations and adds the new station to the UI state with enrichment.
 * 4. Atomic _uiState.update prevents state reversion when concurrent emissions occur.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesTwoWaySyncEnrichmentPreservationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var authEngine: AuthEngine
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var fakeRepository: TestEvcsRepository

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
        var routesToReturn: Map<String, DrivingMetrics>? = null

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            routesToReturn?.let { return it }
            return destinations.associate { d ->
                val index = d.id.filter { it.isDigit() }.toIntOrNull() ?: 1
                d.id to DrivingMetrics(
                    distanceMeters = (index * 1200).toLong(),
                    durationSeconds = (index * 180).toLong(),
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    class TestEvcsRepository(
        sessionStorage: InMemorySessionStorage,
        val testDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage,
        ioDispatcher = testDispatcher
    ) {
        var favoritesResult: Result<List<Station>> = Result.success(emptyList())

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
        availablePlugs: Int = 2
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = 60_000L,
                label = "60kW",
                availablePlugs = availablePlugs,
                totalPlugs = 4,
                displayString = "60kW: trống $availablePlugs/4"
            )
        )
        return Station(
            id = id,
            name = name,
            address = "$name Address",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = 4
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
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeCoordinator = FakeRoutingCoordinator()
        fakeRepository = TestEvcsRepository(sessionStorage, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): FavoritesViewModel {
        return FavoritesViewModel(
            repository = fakeRepository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @Test
    fun testFavoritesTwoWaySync_existingStationsRetainDrivingMetricsWhenRepoEmitsUpdatedList() = runTest(testDispatcher) {
        val station1 = createStation("st_1", "Station 1", 21.0300, 105.8500)
        val station2 = createStation("st_2", "Station 2", 21.0400, 105.8600)

        fakeRepository.favoritesResult = Result.success(listOf(station1, station2))
        fakeRepository.emitFavorites(listOf(station1, station2))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val stateBefore = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, stateBefore.stations.size)
        val st1Before = stateBefore.stations.first { it.id == "st_1" }
        val st2Before = stateBefore.stations.first { it.id == "st_2" }

        assertNotNull("Station 1 should have drivingMetrics", st1Before.drivingMetrics)
        assertNotNull("Station 2 should have drivingMetrics", st2Before.drivingMetrics)
        val expectedMetrics1 = st1Before.drivingMetrics!!
        val expectedMetrics2 = st2Before.drivingMetrics!!

        // Simulate repository emitting an updated station list (e.g. background data refresh with updated names)
        val updatedStation1 = station1.copy(name = "Station 1 Renamed")
        val updatedStation2 = station2.copy(name = "Station 2 Renamed")
        fakeRepository.emitFavorites(listOf(updatedStation1, updatedStation2))
        advanceUntilIdle()

        val stateAfter = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, stateAfter.stations.size)
        val st1After = stateAfter.stations.first { it.id == "st_1" }
        val st2After = stateAfter.stations.first { it.id == "st_2" }

        // Assert names updated
        assertEquals("Station 1 Renamed", st1After.name)
        assertEquals("Station 2 Renamed", st2After.name)

        // Assert drivingMetrics were preserved exactly
        assertEquals(expectedMetrics1.durationSeconds, st1After.drivingMetrics?.durationSeconds)
        assertEquals(expectedMetrics1.distanceMeters, st1After.drivingMetrics?.distanceMeters)
        assertEquals(expectedMetrics1.trafficCondition, st1After.drivingMetrics?.trafficCondition)

        assertEquals(expectedMetrics2.durationSeconds, st2After.drivingMetrics?.durationSeconds)
        assertEquals(expectedMetrics2.distanceMeters, st2After.drivingMetrics?.distanceMeters)
        assertEquals(expectedMetrics2.trafficCondition, st2After.drivingMetrics?.trafficCondition)
    }

    @Test
    fun testFavoritesTwoWaySync_removingFavoriteViaRepoEmissionRemovesTargetAndPreservesRemainingMetrics() = runTest(testDispatcher) {
        val station1 = createStation("st_1", "Station 1", 21.0300, 105.8500)
        val station2 = createStation("st_2", "Station 2", 21.0400, 105.8600)

        fakeRepository.favoritesResult = Result.success(listOf(station1, station2))
        fakeRepository.emitFavorites(listOf(station1, station2))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val stateBefore = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(2, stateBefore.stations.size)
        val st2Before = stateBefore.stations.first { it.id == "st_2" }
        assertNotNull(st2Before.drivingMetrics)

        // Simulate station1 removed via external action (e.g. toggled off from Nearby tab)
        fakeRepository.emitFavorites(listOf(station2))
        advanceUntilIdle()

        val stateAfter = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals("Only station 2 should remain", 1, stateAfter.stations.size)
        assertEquals("st_2", stateAfter.stations[0].id)

        val st2After = stateAfter.stations[0]
        assertEquals("Station 2 metrics should be preserved", st2Before.drivingMetrics?.durationSeconds, st2After.drivingMetrics?.durationSeconds)
    }

    @Test
    fun testFavoritesTwoWaySync_addingNewFavoritePreservesExistingMetricsAndAddsNewStationWithEnrichment() = runTest(testDispatcher) {
        val station1 = createStation("st_1", "Station 1", 21.0300, 105.8500)
        val station2 = createStation("st_2", "Station 2", 21.0400, 105.8600)
        val station3 = createStation("st_3", "Station 3", 21.0500, 105.8700)

        fakeRepository.favoritesResult = Result.success(listOf(station1, station2))
        fakeRepository.emitFavorites(listOf(station1, station2))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val stateBefore = viewModel.uiState.value as FavoritesUiState.Success
        val st1Before = stateBefore.stations.first { it.id == "st_1" }
        val origMetrics1 = st1Before.drivingMetrics!!

        val coordinatorCallsBefore = fakeCoordinator.callCount

        // Add station3 via favoritesState emission (e.g. favorited in Nearby screen)
        fakeRepository.emitFavorites(listOf(station1, station2, station3))
        advanceUntilIdle()

        val stateAfter = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(3, stateAfter.stations.size)
        val stationIds = stateAfter.stations.map { it.id }
        assertTrue("New station3 should be in UI state", stationIds.contains("st_3"))

        val st1After = stateAfter.stations.first { it.id == "st_1" }
        assertEquals("Station 1 metrics preserved", origMetrics1.durationSeconds, st1After.drivingMetrics?.durationSeconds)

        val st3After = stateAfter.stations.first { it.id == "st_3" }
        assertNotNull("Station 3 should have driving metrics calculated", st3After.drivingMetrics)
        assertTrue("Coordinator was invoked for new station", fakeCoordinator.callCount > coordinatorCallsBefore)
    }

    @Test
    fun testFavoritesTwoWaySync_atomicUpdatePreventsStateReversionUnderConcurrentEmissions() = runTest(testDispatcher) {
        val station1 = createStation("st_1", "Station 1", 21.0300, 105.8500)
        val station2 = createStation("st_2", "Station 2", 21.0400, 105.8600)
        val station3 = createStation("st_3", "Station 3", 21.0500, 105.8700)

        fakeRepository.favoritesResult = Result.success(listOf(station1, station2))
        fakeRepository.emitFavorites(listOf(station1, station2))

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Launch concurrent emissions on testDispatcher
        launch {
            fakeRepository.emitFavorites(listOf(station1, station2, station3))
        }
        launch {
            viewModel.selectStationForDetail(station2)
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(3, state.stations.size)
        assertNotNull("Selected detail station should not be overwritten/lost", state.selectedStationForDetail)
        assertEquals("st_2", state.selectedStationForDetail?.id)
        assertNotNull("Selected station should retain its metrics", state.selectedStationForDetail?.drivingMetrics)
    }
}
