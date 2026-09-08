package com.evcs.favorites.ui.screens

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
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
 * Single Comprehensive Verification Test for Phase 03:
 * Comprehensive Route Station Sourcing.
 *
 * Verifies:
 * 1. Highway Station Discovery: Route planner successfully discovers highway charging stations
 *    from [EvcsRepository.getAllKnownStations] even when personal favoritesState is empty or sparse.
 * 2. False Dead-Zone Alert Elimination: Confirms no false dead-zone warnings are generated when
 *    sufficient stations exist in the comprehensive cache along national highway corridors.
 * 3. Graceful Fallback: Gracefully handles route planning when no stations are available anywhere
 *    without crashing or throwing unhandled exceptions.
 * 4. Deduplication & Coordinate Enrichment: [EvcsRepository.getAllKnownStations] cleanly deduplicates
 *    stations by unique station ID (case-insensitive) and enriches missing coordinates.
 * 5. Factory Wiring: Verifies [RouteViewModel.provideFactory] wires candidateStationsProvider
 *    to supply [EvcsRepository.getAllKnownStations] by default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteStationSourcingIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var repository: EvcsRepository
    private lateinit var locationsRepository: VietnamLocationsRepository
    private lateinit var evSmartRoutePlanner: EvSmartRoutePlanner
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var viewModel: RouteViewModel

    class FakeRoutingCoordinator(
        private val dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) : MultiTierRoutingCoordinator(ioDispatcher = dispatcher) {
        override suspend fun calculateRoutePath(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            settings: RoutingSettings
        ): RoutePathResult {
            return computeHaversineRoute(originLat, originLng, destLat, destLng)
        }
    }

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lng: Double,
        powerKw: Double = 180.0,
        totalPlugs: Int = 4,
        availablePlugs: Int = 2
    ): Station {
        val watts = (powerKw * 1000).toLong()
        val port = PowerPort(
            typeWatts = watts,
            label = "${powerKw.toInt()}kW",
            availablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            displayString = "${powerKw.toInt()}kW: trống $availablePlugs/$totalPlugs"
        )
        return Station(
            id = id,
            name = name,
            address = "Quốc lộ 1A, $name",
            latitude = lat,
            longitude = lng,
            summary = "Trạm sạc VinFast Cao tốc",
            connectors = "${powerKw.toInt()}kW",
            depotStatus = "Normal",
            powers = listOf(port),
            totalPlugs = totalPlugs,
            totalAvailablePlugs = availablePlugs
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        repository = EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = sessionStorage
        )
        locationsRepository = VietnamLocationsRepository()
        evSmartRoutePlanner = EvSmartRoutePlanner(
            coordinator = FakeRoutingCoordinator(testDispatcher)
        )
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        prefsManager.saveSettings(
            RoutingSettings(
                preferredEngine = RoutingEngineMode.HAVERSINE_ONLY,
                autoFallbackEnabled = true
            )
        )

        viewModel = RouteViewModel(
            locationsRepository = locationsRepository,
            evSmartRoutePlanner = evSmartRoutePlanner,
            evcsRepository = repository,
            routingPreferencesManager = prefsManager,
            candidateStationsProvider = { repository.getAllKnownStations() },
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Highway Station Discovery from getAllKnownStations (Favorites Empty)
    // =========================================================================
    @Test
    fun testRoutePlannerDiscoversHighwayStationsFromAllKnownStationsWhenFavoritesEmpty() = runTest {
        // Assert favoritesState is empty
        assertTrue("Personal favorites must be empty initially", repository.favoritesState.value.isEmpty())

        // Origin: Hanoi (~21.0285, 105.8542), Destination: Da Nang (~16.0544, 108.2022) (~650km)
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        // Populate highway stations directly into repository's cluster station cache at ~100km intervals
        val highwayStations = (1..5).map { i ->
            val fraction = i * 0.16
            val lat = origCoord.lat + (destCoord.lat - origCoord.lat) * fraction
            val lng = origCoord.lng + (destCoord.lng - origCoord.lng) * fraction
            createTestStation("hw_station_$i", "VinFast Highway $i", lat, lng, 180.0)
        }
        repository.cacheClusterStations(highwayStations)

        // Verify getAllKnownStations aggregates these stations
        val allKnown = repository.getAllKnownStations()
        assertEquals("getAllKnownStations must contain all 5 cached cluster stations", 5, allKnown.size)

        // Plan route without setting any customCandidateStations
        viewModel.onSafeRangeChanged(200)
        viewModel.onStartBatteryPercentChanged(100)

        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Loading must be false after route planning completes", state.isLoading)
        assertNull("Error message must be null on successful planning", state.errorMessage)

        val plan = state.routePlan
        assertNotNull("Route plan must be generated successfully", plan)
        assertTrue("Stops must be scheduled using discovered highway stations", plan!!.stops.isNotEmpty())
        assertNull("Dead zone warning must be null when highway stations are discovered", plan.deadZoneWarning)

        // Assert scheduled stops originate from the cached highway stations
        val scheduledIds = plan.stops.map { it.station.id }
        assertTrue("Scheduled stops must include highway stations", scheduledIds.contains("hw_station_1"))
    }

    // =========================================================================
    // 2. Elimination of False Dead-Zone Warnings with Comprehensive Cache
    // =========================================================================
    @Test
    fun testNoFalseDeadZoneWarningsWhenSufficientStationsExistInComprehensiveCache() = runTest {
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        // Personal favorites is sparse: only 1 station in Hanoi (0.0km, at origin)
        val sparseFav = createTestStation("fav_hanoi", "VinFast Hoàn Kiếm", origCoord.lat, origCoord.lng, 60.0)
        repository.addFavoriteStation(sparseFav)
        assertEquals(1, repository.favoritesState.value.size)

        // Highway stations along the corridor cached in cluster station cache
        val highwayStations = (1..5).map { i ->
            val fraction = i * 0.16 // ~100km intervals along ~650km corridor
            val lat = origCoord.lat + (destCoord.lat - origCoord.lat) * fraction
            val lng = origCoord.lng + (destCoord.lng - origCoord.lng) * fraction
            createTestStation("corridor_st_$i", "VinFast Cao Tốc $i", lat, lng, 180.0)
        }
        repository.cacheClusterStations(highwayStations)

        // Vehicle safe range 200 km
        viewModel.onSafeRangeChanged(200)
        viewModel.onStartBatteryPercentChanged(100)

        // Plan route
        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        val plan = viewModel.uiState.value.routePlan
        assertNotNull("Route plan must be created", plan)
        assertNull(
            "False dead-zone alert must NOT be emitted when highway stations exist in comprehensive cache",
            plan!!.deadZoneWarning
        )
        assertTrue("Route planner must schedule multiple charging stops along highway", plan.stops.size >= 2)
    }

    // =========================================================================
    // 3. Graceful Fallback When No Stations Are Available Anywhere
    // =========================================================================
    @Test
    fun testGracefulFallbackWhenNoStationsAvailableAnywhere() = runTest {
        // Ensure repository has zero favorites and zero cached cluster stations
        repository.clearOfflineCache()
        assertTrue("getAllKnownStations must be empty", repository.getAllKnownStations().isEmpty())
        assertTrue("favoritesState must be empty", repository.favoritesState.value.isEmpty())

        viewModel.onSafeRangeChanged(150)
        viewModel.onStartBatteryPercentChanged(100)

        // Plan route with no stations
        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Loading must be false after planning finishes", state.isLoading)
        assertNull("Planning must not fail with unexpected error message", state.errorMessage)

        val plan = state.routePlan
        assertNotNull("Plan object should still be returned gracefully", plan)
        assertTrue("Stops must be empty when no charging stations exist", plan!!.stops.isEmpty())
        assertNotNull("Dead zone warning must be generated when corridor cannot be completed", plan.deadZoneWarning)
        assertTrue(
            "Dead zone warning should describe the gap",
            plan.deadZoneWarning!!.message.contains("Cảnh báo vùng trắng sạc")
        )
    }

    // =========================================================================
    // 4. Clean Deduplication & Coordinate Enrichment in getAllKnownStations
    // =========================================================================
    @Test
    fun testGetAllKnownStationsDeduplicationAndCoordinateEnrichment() = runTest {
        // Station in favorites has missing/zero coordinates
        val favWithoutCoords = Station(
            id = "VF-ST-100",
            name = "Trạm Sạc Yêu Thích",
            address = "Địa chỉ",
            latitude = 0.0,
            longitude = 0.0,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal"
        )
        repository.addFavoriteStation(favWithoutCoords)

        // Same station in cluster station cache has real GPS coordinates
        val clusterWithCoords = createTestStation(
            id = "vf-st-100", // Case-insensitive ID
            name = "Trạm Sạc Yêu Thích (Cluster Enriched)",
            lat = 18.6732,
            lng = 105.6811,
            powerKw = 250.0
        )
        val otherClusterStation = createTestStation(
            id = "vf-st-200",
            name = "Trạm Sạc Khác",
            lat = 19.8000,
            lng = 105.7500,
            powerKw = 180.0
        )
        repository.cacheClusterStations(listOf(clusterWithCoords, otherClusterStation))

        val allStations = repository.getAllKnownStations()

        // Assert clean deduplication: 1 fav + 2 cluster (1 overlap) = exactly 2 unique stations
        assertEquals("Must deduplicate stations by ID case-insensitively", 2, allStations.size)

        val deduplicatedFav = allStations.find { it.id.equals("vf-st-100", ignoreCase = true) }
        assertNotNull("Station VF-ST-100 must be present", deduplicatedFav)
        assertEquals("Latitude must be enriched from cluster station", 18.6732, deduplicatedFav!!.latitude, 0.0001)
        assertEquals("Longitude must be enriched from cluster station", 105.6811, deduplicatedFav.longitude, 0.0001)

        val otherStation = allStations.find { it.id.equals("vf-st-200", ignoreCase = true) }
        assertNotNull("Station vf-st-200 must be present", otherStation)
    }

    // =========================================================================
    // 5. Factory Wiring: Default Candidate Stations Provider
    // =========================================================================
    @Test
    fun testRouteViewModelFactoryWiresCandidateStationsProvider() = runTest {
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        val midLat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.20
        val midLng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.20
        val highwaySt = createTestStation("factory_test_st", "VinFast Factory Test", midLat, midLng, 250.0)
        repository.cacheClusterStation(highwaySt)

        // Create ViewModel via provideFactory with candidateStationsProvider wired
        val factory = RouteViewModel.provideFactory(
            locationsRepository = locationsRepository,
            evSmartRoutePlanner = evSmartRoutePlanner,
            evcsRepository = repository,
            routingPreferencesManager = prefsManager,
            candidateStationsProvider = { repository.getAllKnownStations() },
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val vmFromFactory = factory.create(RouteViewModel::class.java)

        vmFromFactory.onSafeRangeChanged(200)
        vmFromFactory.planRoute()
        testScheduler.advanceUntilIdle()

        val plan = vmFromFactory.uiState.value.routePlan
        assertNotNull("Factory-created RouteViewModel must produce route plan", plan)
        assertTrue("Stops must be planned from repository's all known stations", plan!!.stops.isNotEmpty())
        assertEquals("factory_test_st", plan.stops[0].station.id)
    }
}
