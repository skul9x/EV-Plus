package com.evcs.favorites

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
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
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Verification test for Phase 02:
 * ViewModel Pipeline Re-sorting Integration & State Verification.
 *
 * Verifies:
 * 1. testScanNearbyStations_initiallyShowsHaversine_thenResortsByRoadDistance
 * 2. testInversionResolution_stationWithShorterRoadDistanceBecomesFirst
 * 3. testWattageFilterToggle_recomputesAndSortsByRoadDistance
 * 4. testRefresh_preservesOrUpdatesRoadDistanceSorting
 * 5. testRoutingCoordinatorFailure_retainsHaversineFallbackGracefully
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyRoadDistanceRoutingIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: ControllableFakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var filterPrefs: NearbyFilterPreferences
    private lateinit var viewModel: NearbyViewModel

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted
        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    class ControllableFakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount: Int = 0
        var gate: CompletableDeferred<Unit>? = null
        var metricsProvider: (destinations: List<RoutingDestination>) -> Map<String, DrivingMetrics> = { dests ->
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
            callCount++
            gate?.await()
            return metricsProvider(destinations)
        }
    }

    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var nearbyResult: Result<List<Station>> = Result.success(emptyList())

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return nearbyResult
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
        powerWatts: Long = 250_000L,
        powerLabel: String = "250kW",
        availablePlugs: Int = 2
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerWatts,
                label = powerLabel,
                availablePlugs = availablePlugs,
                totalPlugs = 2,
                displayString = "$powerLabel: trống $availablePlugs/2"
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
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = 2
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeCoordinator = ControllableFakeRoutingCoordinator()
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        filterPrefs = NearbyFilterPreferences(storage = sessionStorage)

        viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
            routingPreferencesManager = prefsManager,
            filterPreferences = filterPrefs,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 1. testScanNearbyStations_initiallyShowsHaversine_thenResortsByRoadDistance
     * Initial UI state before routing completion emits Haversine order; upon route completion,
     * top10DisplayStations is dynamically reordered strictly by ascending road distance.
     */
    @Test
    fun testScanNearbyStations_initiallyShowsHaversine_thenResortsByRoadDistance() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        // Station A: closest Haversine (~1.1km), but longest road distance (4500m)
        // Station B: mid Haversine (~2.2km), mid road distance (3000m)
        // Station C: farthest Haversine (~3.3km), but shortest road distance (1500m)
        val stationA = createStation("st_a", "Station A", userLat + 0.01, userLon)
        val stationB = createStation("st_b", "Station B", userLat + 0.02, userLon)
        val stationC = createStation("st_c", "Station C", userLat + 0.03, userLon)
        fakeRepository.nearbyResult = Result.success(listOf(stationA, stationB, stationC))

        // Gate routing coordinator to pause calculation mid-flight
        val routingGate = CompletableDeferred<Unit>()
        fakeCoordinator.gate = routingGate
        fakeCoordinator.metricsProvider = {
            mapOf(
                "st_a" to DrivingMetrics(distanceMeters = 4500L, durationSeconds = 500L, trafficCondition = TrafficCondition.FREE_FLOW, engineUsed = RoutingEngineType.OSRM),
                "st_b" to DrivingMetrics(distanceMeters = 3000L, durationSeconds = 350L, trafficCondition = TrafficCondition.FREE_FLOW, engineUsed = RoutingEngineType.OSRM),
                "st_c" to DrivingMetrics(distanceMeters = 1500L, durationSeconds = 200L, trafficCondition = TrafficCondition.FREE_FLOW, engineUsed = RoutingEngineType.OSRM)
            )
        }

        viewModel.scanNearbyStations()
        testScheduler.runCurrent()

        // 1. Verify intermediate state while routing is loading:
        val intermediateState = viewModel.uiState.value
        assertTrue(intermediateState.isRoutingLoading)
        assertEquals(3, intermediateState.top10DisplayStations.size)
        // Strictly Haversine order: A, B, C
        assertEquals("st_a", intermediateState.top10DisplayStations[0].id)
        assertEquals("st_b", intermediateState.top10DisplayStations[1].id)
        assertEquals("st_c", intermediateState.top10DisplayStations[2].id)
        assertNull(intermediateState.top10DisplayStations[0].drivingMetrics)

        // 2. Complete routing calculation
        routingGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        // 3. Verify final state after routing completion:
        val finalState = viewModel.uiState.value
        assertFalse(finalState.isRoutingLoading)
        assertEquals(3, finalState.top10DisplayStations.size)
        // Re-sorted strictly by road distance: C (1500m), B (3000m), A (4500m)
        assertEquals("st_c", finalState.top10DisplayStations[0].id)
        assertEquals("st_b", finalState.top10DisplayStations[1].id)
        assertEquals("st_a", finalState.top10DisplayStations[2].id)

        assertEquals(1500L, finalState.top10DisplayStations[0].drivingMetrics?.distanceMeters)
        assertEquals(3000L, finalState.top10DisplayStations[1].drivingMetrics?.distanceMeters)
        assertEquals(4500L, finalState.top10DisplayStations[2].drivingMetrics?.distanceMeters)
    }

    /**
     * 2. testInversionResolution_stationWithShorterRoadDistanceBecomesFirst
     * Station with 2.5 km Haversine / 2.8 km road distance jumps ahead of station
     * with 2.0 km Haversine / 5.5 km road distance upon route arrival.
     */
    @Test
    fun testInversionResolution_stationWithShorterRoadDistanceBecomesFirst() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        // Station 1: ~2.0 km Haversine (0.018 deg lat) -> Road distance 5.5 km (5500m)
        // Station 2: ~2.5 km Haversine (0.023 deg lat) -> Road distance 2.8 km (2800m)
        val station1 = createStation("station_1", "Trạm 1", userLat + 0.018, userLon)
        val station2 = createStation("station_2", "Trạm 2", userLat + 0.023, userLon)
        fakeRepository.nearbyResult = Result.success(listOf(station1, station2))

        fakeCoordinator.metricsProvider = {
            mapOf(
                "station_1" to DrivingMetrics(distanceMeters = 5500L, durationSeconds = 600L, trafficCondition = TrafficCondition.MODERATE_CONGESTION, engineUsed = RoutingEngineType.OSRM),
                "station_2" to DrivingMetrics(distanceMeters = 2800L, durationSeconds = 300L, trafficCondition = TrafficCondition.FREE_FLOW, engineUsed = RoutingEngineType.OSRM)
            )
        }

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.top10DisplayStations.size)

        // Inversion verified: station_2 with 2.8 km road distance jumps ahead of station_1 (5.5 km road distance)
        assertEquals("station_2", state.top10DisplayStations[0].id)
        assertEquals(2800L, state.top10DisplayStations[0].drivingMetrics?.distanceMeters)

        assertEquals("station_1", state.top10DisplayStations[1].id)
        assertEquals(5500L, state.top10DisplayStations[1].drivingMetrics?.distanceMeters)
    }

    /**
     * 3. testWattageFilterToggle_recomputesAndSortsByRoadDistance
     * Toggling wattage filters triggers fresh pipeline and applies road-distance
     * re-sorting on the filtered Top 10 subset.
     */
    @Test
    fun testWattageFilterToggle_recomputesAndSortsByRoadDistance() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        // Mix of 60kW and 250kW stations
        val st60kA = createStation("st_60_a", "Trạm 60kW A", userLat + 0.01, userLon, powerWatts = 60_000L, powerLabel = "60kW")
        val st60kB = createStation("st_60_b", "Trạm 60kW B", userLat + 0.02, userLon, powerWatts = 60_000L, powerLabel = "60kW")
        val st250kA = createStation("st_250_a", "Trạm 250kW A", userLat + 0.015, userLon, powerWatts = 250_000L, powerLabel = "250kW")
        val st250kB = createStation("st_250_b", "Trạm 250kW B", userLat + 0.025, userLon, powerWatts = 250_000L, powerLabel = "250kW")

        fakeRepository.nearbyResult = Result.success(listOf(st60kA, st60kB, st250kA, st250kB))

        fakeCoordinator.metricsProvider = { dests ->
            dests.associate { d ->
                val (distance, duration) = when (d.id) {
                    "st_60_a" -> 5000L to 550L
                    "st_60_b" -> 1000L to 120L
                    "st_250_a" -> 4000L to 450L   // 250kW A: Haversine ~1.66km, Road 4000m
                    "st_250_b" -> 2000L to 250L   // 250kW B: Haversine ~2.78km, Road 2000m
                    else -> 3000L to 300L
                }
                d.id to DrivingMetrics(
                    distanceMeters = distance,
                    durationSeconds = duration,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }

        // Initial scan: all stations present, sorted by road distance: st_60_b(1000), st_250_b(2000), st_250_a(4000), st_60_a(5000)
        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("st_60_b", viewModel.uiState.value.top10DisplayStations[0].id)
        assertEquals("st_250_b", viewModel.uiState.value.top10DisplayStations[1].id)
        assertEquals("st_250_a", viewModel.uiState.value.top10DisplayStations[2].id)
        assertEquals("st_60_a", viewModel.uiState.value.top10DisplayStations[3].id)

        // Toggle 250kW filter
        viewModel.toggleWattageFilter(WattageOption.KW_250)
        testScheduler.advanceUntilIdle()

        val filteredState = viewModel.uiState.value
        assertTrue(filteredState.selectedWattages.contains(WattageOption.KW_250))
        assertEquals(2, filteredState.top10DisplayStations.size)
        // Road distance order on filtered subset: st_250_b (2000m) ahead of st_250_a (4000m)
        assertEquals("st_250_b", filteredState.top10DisplayStations[0].id)
        assertEquals(2000L, filteredState.top10DisplayStations[0].drivingMetrics?.distanceMeters)
        assertEquals("st_250_a", filteredState.top10DisplayStations[1].id)
        assertEquals(4000L, filteredState.top10DisplayStations[1].drivingMetrics?.distanceMeters)

        // Clear wattage filters
        viewModel.clearWattageFilters()
        testScheduler.advanceUntilIdle()

        val resetState = viewModel.uiState.value
        assertTrue(resetState.selectedWattages.isEmpty())
        assertEquals(4, resetState.top10DisplayStations.size)
        assertEquals("st_60_b", resetState.top10DisplayStations[0].id)
        assertEquals("st_250_b", resetState.top10DisplayStations[1].id)
        assertEquals("st_250_a", resetState.top10DisplayStations[2].id)
        assertEquals("st_60_a", resetState.top10DisplayStations[3].id)
    }

    /**
     * 4. testRefresh_preservesOrUpdatesRoadDistanceSorting
     * Calling refresh() maintains proper road-distance sorting with updated coordinates.
     */
    @Test
    fun testRefresh_preservesOrUpdatesRoadDistanceSorting() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        val stationX = createStation("st_x", "Station X", userLat + 0.01, userLon)
        val stationY = createStation("st_y", "Station Y", userLat + 0.02, userLon)
        fakeRepository.nearbyResult = Result.success(listOf(stationX, stationY))

        // Initial metrics: X = 3000m, Y = 2000m -> Y is first
        fakeCoordinator.metricsProvider = {
            mapOf(
                "st_x" to DrivingMetrics(distanceMeters = 3000L, durationSeconds = 300L, trafficCondition = TrafficCondition.FREE_FLOW, engineUsed = RoutingEngineType.OSRM),
                "st_y" to DrivingMetrics(distanceMeters = 2000L, durationSeconds = 200L, trafficCondition = TrafficCondition.FREE_FLOW, engineUsed = RoutingEngineType.OSRM)
            )
        }

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val initialOrder = viewModel.uiState.value.top10DisplayStations.map { it.id }
        assertEquals(listOf("st_y", "st_x"), initialOrder)

        // Now simulate route updates upon refresh (e.g. traffic changes metrics):
        // X = 1200m, Y = 2500m -> X becomes first
        fakeCoordinator.metricsProvider = {
            mapOf(
                "st_x" to DrivingMetrics(distanceMeters = 1200L, durationSeconds = 120L, trafficCondition = TrafficCondition.FREE_FLOW, engineUsed = RoutingEngineType.OSRM),
                "st_y" to DrivingMetrics(distanceMeters = 2500L, durationSeconds = 250L, trafficCondition = TrafficCondition.HEAVY_CONGESTION, engineUsed = RoutingEngineType.OSRM)
            )
        }

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        val refreshedState = viewModel.uiState.value
        assertEquals(listOf("st_x", "st_y"), refreshedState.top10DisplayStations.map { it.id })
        assertEquals(1200L, refreshedState.top10DisplayStations[0].drivingMetrics?.distanceMeters)
        assertEquals(2500L, refreshedState.top10DisplayStations[1].drivingMetrics?.distanceMeters)
    }

    /**
     * 5. testRoutingCoordinatorFailure_retainsHaversineFallbackGracefully
     * When routing coordinator encounters an error and falls back to Haversine metrics,
     * the station list remains valid and sorted without crashes.
     */
    @Test
    fun testRoutingCoordinatorFailure_retainsHaversineFallbackGracefully() = runTest {
        val userLat = 21.0285
        val userLon = 105.8542
        fakeLocationService.locationToReturn = createMockLocation(userLat, userLon)

        val stationA = createStation("st_a", "Station A", userLat + 0.01, userLon)
        val stationB = createStation("st_b", "Station B", userLat + 0.02, userLon)
        fakeRepository.nearbyResult = Result.success(listOf(stationB, stationA))

        // Routing coordinator returns Tier 3 Haversine fallback metrics
        fakeCoordinator.metricsProvider = { dests ->
            dests.associate { d ->
                val haversineMeters = if (d.id == "st_a") 1110L else 2220L
                d.id to DrivingMetrics(
                    distanceMeters = haversineMeters,
                    durationSeconds = 120L,
                    trafficCondition = TrafficCondition.UNKNOWN,
                    engineUsed = RoutingEngineType.HAVERSINE
                )
            }
        }

        viewModel.scanNearbyStations()
        testScheduler.advanceUntilIdle()

        val stateWithHaversineFallback = viewModel.uiState.value
        assertFalse(stateWithHaversineFallback.isRoutingLoading)
        assertNull(stateWithHaversineFallback.errorMessage)
        assertEquals(2, stateWithHaversineFallback.top10DisplayStations.size)
        // Station A (1110m) then Station B (2220m)
        assertEquals("st_a", stateWithHaversineFallback.top10DisplayStations[0].id)
        assertEquals("st_b", stateWithHaversineFallback.top10DisplayStations[1].id)
        assertEquals(RoutingEngineType.HAVERSINE, stateWithHaversineFallback.top10DisplayStations[0].drivingMetrics?.engineUsed)

        // Also test emptyMap fallback (e.g. routing totally failed or timed out with fallback disabled)
        fakeCoordinator.metricsProvider = { emptyMap() }
        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        val stateWithEmptyMap = viewModel.uiState.value
        assertFalse(stateWithEmptyMap.isRoutingLoading)
        assertNull(stateWithEmptyMap.errorMessage)
        assertEquals(2, stateWithEmptyMap.top10DisplayStations.size)
        // Graceful fallback: sorted by Haversine distanceKm
        assertEquals("st_a", stateWithEmptyMap.top10DisplayStations[0].id)
        assertEquals("st_b", stateWithEmptyMap.top10DisplayStations[1].id)
        assertNotNull(stateWithEmptyMap.top10DisplayStations[0].distanceKm)
    }
}
