package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.BoundedLruMap
import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.here.HereEvApiClient
import com.evcs.favorites.data.network.here.model.HereAddress
import com.evcs.favorites.data.network.here.model.HereConnector
import com.evcs.favorites.data.network.here.model.HereConnectorStatus
import com.evcs.favorites.data.network.here.model.HereConnectorStatusesContainer
import com.evcs.favorites.data.network.here.model.HereConnectorsContainer
import com.evcs.favorites.data.network.here.model.HereEvStation
import com.evcs.favorites.data.network.here.model.HereModelMapper
import com.evcs.favorites.data.network.here.model.HerePosition
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.filter.NearbyStationFilter
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Exactly One Comprehensive File-Based Test Suite for Phase 03:
 * ViewModel Orchestration, Realtime GPS Refresh & Regression Verification.
 *
 * Verifies:
 * 1. Seamless Discovery in Fallback Mode:
 *    - When EVCS API is blocked (HTTP 403 / Cloudflare WAF / 429), calling viewModel.scanNearbyStations()
 *      populates top10DisplayStations via secondary HERE API fallback with zero error UI (errorMessage == null).
 * 2. Realtime Automotive GPS Updates:
 *    - When viewModel.onLocationUpdated(newLat, newLon) is triggered while EVCS is in cooldown,
 *      ViewModel updates userLatitude/userLongitude, triggers fresh discovery via fallback repository,
 *      and updates the station list and driving routes.
 * 3. DC Wattage Tier & Smart Filter Compatibility:
 *    - Selecting DC tiers (e.g. GE_60KW, GE_120KW, LE_30KW) correctly filters fallback stations by aggregated
 *      port ratings and prunes motorbike-only stations.
 * 4. Quick Chip & Custom Filter Modes:
 *    - Quick chips (e.g. DC_GE_120KW) filter fallback stations accurately.
 * 5. Road Distance Matrix Routing:
 *    - Top 10 fallback stations receive driving metrics (km, duration) via MultiTierRoutingCoordinator
 *      and are sorted in ascending order of driving distance. Destination list strictly <= 10.
 * 6. Two-Way Favorites Integration:
 *    - Fallback stations can be toggled as favorites, updating repository StateFlow, UI state,
 *      and emitting debounced toast notifications. Coordinates resolve symmetrically via dual-key caching.
 * 7. Complete Failure Graceful Degradation:
 *    - When both EVCS and HERE API fail, ViewModel cleanly populates uiState.errorMessage,
 *      resets loading states, and avoids crashes.
 * 8. Cancellation Safety:
 *    - Rapid GPS movements or repeated scans cleanly cancel preceding in-flight jobs without state corruption.
 * 9. Circuit Breaker Cooldown & Recovery:
 *    - Half-Open probe success restores primary EVCS data seamlessly when cooldown expires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelHereFallbackIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeEvcsApiClient: FakeEvcsApiClient
    private lateinit var fakeHereEvApiClient: FakeHereEvApiClient
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeRoutingCoordinator: FakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var coordinateCache: MutableMap<String, Pair<Double, Double>>
    private lateinit var repository: EvcsRepository
    private lateinit var viewModel: NearbyViewModel

    private val defaultLat = 21.0285
    private val defaultLon = 105.8542

    // =========================================================================
    // Fakes and Helpers
    // =========================================================================

    class FakeEvcsApiClient(sessionManager: SessionManager) : EvcsApiClient(sessionManager = sessionManager) {
        var searchHandler: (suspend (Double, Double) -> Result<List<SearchStationRaw>>)? = null
        var searchCallCount: Int = 0

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            token: String,
            wattageTypes: List<String>?
        ): Result<List<SearchStationRaw>> {
            searchCallCount++
            return searchHandler?.invoke(latitude, longitude) ?: Result.success(emptyList())
        }

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            token: String
        ): Result<List<SearchStationRaw>> {
            searchCallCount++
            return searchHandler?.invoke(latitude, longitude) ?: Result.success(emptyList())
        }

        override suspend fun searchStations(
            latitude: Double,
            longitude: Double,
            wattageTypes: List<String>?
        ): Result<List<SearchStationRaw>> {
            searchCallCount++
            return searchHandler?.invoke(latitude, longitude) ?: Result.success(emptyList())
        }

        override suspend fun saveFavorites(
            stations: List<com.evcs.favorites.data.model.FavoriteStationRaw>,
            csrf: String
        ): Result<Boolean> {
            return Result.success(true)
        }
    }

    class FakeHereEvApiClient : HereEvApiClient() {
        var fetchHandler: (suspend (Double, Double, Int, Boolean, Int) -> Result<List<Station>>)? = null
        var fetchCallCount: Int = 0
        var lastRequestedLat: Double? = null
        var lastRequestedLon: Double? = null

        override suspend fun fetchNearbyStations(
            latitude: Double,
            longitude: Double,
            radiusMeters: Int,
            dcOnly: Boolean,
            maxResults: Int
        ): Result<List<Station>> {
            fetchCallCount++
            lastRequestedLat = latitude
            lastRequestedLon = longitude
            return fetchHandler?.invoke(latitude, longitude, radiusMeters, dcOnly, maxResults)
                ?: Result.success(emptyList())
        }
    }

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
        var lastDestinations: List<RoutingDestination> = emptyList()

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings,
            forceRefresh: Boolean
        ): Map<String, DrivingMetrics> {
            callCount++
            lastDestinations = destinations
            return destinations.mapIndexed { index, dest ->
                dest.id to DrivingMetrics(
                    distanceMeters = ((index + 1) * 1200L),
                    durationSeconds = ((index + 1) * 180L),
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }.toMap()
        }
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    private fun createHereStation(
        id: String,
        cpoId: String? = id,
        name: String,
        street: String,
        city: String,
        lat: Double,
        lon: Double,
        connectors: List<HereConnector>
    ): HereEvStation {
        return HereEvStation(
            id = id,
            cpoId = cpoId,
            name = name,
            address = HereAddress(street = street, city = city),
            position = HerePosition(latitude = lat, longitude = lon),
            connectors = HereConnectorsContainer(connector = connectors)
        )
    }

    private fun createConnector(
        powerKw: Double,
        powerType: String = "DC",
        available: Int = 2,
        total: Int = 2
    ): HereConnector {
        val statuses = mutableListOf<HereConnectorStatus>()
        val occupied = (total - available).coerceAtLeast(0)
        repeat(available) { statuses.add(HereConnectorStatus(state = "AVAILABLE")) }
        repeat(occupied) { statuses.add(HereConnectorStatus(state = "OCCUPIED")) }
        return HereConnector(
            maxPowerLevel = powerKw,
            powerType = powerType,
            numberOfAvailable = available,
            numberOfConnectors = total,
            connectorStatuses = HereConnectorStatusesContainer(
                connectorStatus = statuses,
                numberOfAvailable = available
            )
        )
    }

    private fun generate12HereStations(originLat: Double = defaultLat, originLon: Double = defaultLon): List<HereEvStation> {
        val list = mutableListOf<HereEvStation>()
        for (i in 1..12) {
            val tierPowerKw = when (i % 4) {
                1 -> 60.0
                2 -> 120.0
                3 -> 250.0
                else -> 30.0
            }
            val available = if (i == 12) 0 else 2
            val stationId = "c.vcs%03d".format(i)
            list.add(
                createHereStation(
                    id = stationId,
                    cpoId = stationId,
                    name = "Trạm sạc VinFast",
                    street = "Đường %d".format(i),
                    city = "Hà Nội",
                    lat = originLat + (i * 0.005),
                    lon = originLon + (i * 0.005),
                    connectors = listOf(
                        createConnector(powerKw = tierPowerKw, powerType = "DC", available = available, total = 2)
                    )
                )
            )
        }
        return list
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_auth_cookie"
            phpSessionId = "valid_php_session"
        }
        fakeEvcsApiClient = FakeEvcsApiClient(sessionManager)
        fakeHereEvApiClient = FakeHereEvApiClient()
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(defaultLat, defaultLon)
        }
        fakeRoutingCoordinator = FakeRoutingCoordinator()
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        coordinateCache = BoundedLruMap(maxCapacity = 500)

        repository = EvcsRepository(
            apiClient = fakeEvcsApiClient,
            cacheStorage = sessionStorage,
            coordinateCache = coordinateCache,
            ioDispatcher = testDispatcher,
            hereEvApiClient = fakeHereEvApiClient
        )

        viewModel = NearbyViewModel(
            repository = repository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeRoutingCoordinator,
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

    // =========================================================================
    // 1. Seamless Discovery in Fallback Mode (Cold Start under 403 WAF)
    // =========================================================================

    @Test
    fun testInitialScan_whenEvcs403Waf_fallsBackToHereApi_emitsTop10WithoutError() = runTest {
        // Setup: Primary EVCS fails with HTTP 403 Forbidden (Cloudflare WAF)
        fakeEvcsApiClient.searchHandler = { _, _ ->
            Result.failure(IOException("HTTP 403 Forbidden: Cloudflare WAF Challenge"))
        }

        // Secondary HERE API returns 12 VinFast stations
        val rawHereStations = generate12HereStations()
        fakeHereEvApiClient.fetchHandler = { _, _, _, dcOnly, _ ->
            Result.success(rawHereStations.map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) })
        }

        // Trigger scan
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        val state = viewModel.uiState.value

        // 1. Verify UI State Machine transitions
        assertFalse("isSearching must transition to false", state.isSearching)
        assertFalse("isLocating must be false", state.isLocating)
        assertFalse("isLoading must be false", state.isLoading)
        assertTrue("hasSearched must be true", state.hasSearched)
        assertNull("errorMessage must be null (no error flickers for user)", state.errorMessage)

        // 2. Verify Stations Emitted
        assertEquals("rawStations must contain 12 stations", 12, state.rawStations.size)
        assertEquals("top10DisplayStations strictly limited to 10 nearest", 10, state.top10DisplayStations.size)

        // 3. Verify VinFast Normalization and Name Enrichment
        state.top10DisplayStations.forEach { station ->
            assertEquals("VinFast", station.evse)
            assertTrue(
                "Station name must be enriched with address: ${station.name}",
                station.name.startsWith("VinFast - Đường")
            )
            assertTrue(
                "Station must pass NearbyStationFilter.isVinFastStation",
                NearbyStationFilter.isVinFastStation(station)
            )
        }

        // 4. Verify Routing Coordinator Invoked
        assertTrue("Routing coordinator should have been called", fakeRoutingCoordinator.callCount >= 1)
        assertEquals(10, fakeRoutingCoordinator.lastDestinations.size)

        // 5. Verify Stations have driving metrics and are sorted
        state.top10DisplayStations.forEach { station ->
            assertNotNull("Station ${station.id} must have driving metrics", station.drivingMetrics)
        }
    }

    // =========================================================================
    // 2. Realtime Automotive GPS Updates During Cooldown
    // =========================================================================

    @Test
    fun testRealtimeGpsUpdate_duringCooldown_updatesCoordinatesAndRefreshesFallbackStations() = runTest {
        // Initial scan trips EVCS 403 circuit breaker
        fakeEvcsApiClient.searchHandler = { _, _ ->
            Result.failure(IOException("HTTP 403 Forbidden: WAF blocked"))
        }
        fakeHereEvApiClient.fetchHandler = { lat, lon, _, dcOnly, _ ->
            val stations = generate12HereStations(lat, lon)
            Result.success(stations.map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) })
        }

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(1, fakeEvcsApiClient.searchCallCount)
        assertEquals(1, fakeHereEvApiClient.fetchCallCount)
        assertEquals(defaultLat, viewModel.uiState.value.userLatitude!!, 0.0001)

        // Car moves to new location (21.0500, 105.8500) during 10-minute cooldown
        val newLat = 21.0500
        val newLon = 105.8500

        viewModel.onLocationUpdated(newLat, newLon)
        advanceUntilIdle()

        // Verify EVCS was NOT called again (circuit breaker Open state), HERE API was called directly
        assertEquals("EVCS call count should remain 1 during cooldown", 1, fakeEvcsApiClient.searchCallCount)
        assertEquals("HERE API call count should be 2", 2, fakeHereEvApiClient.fetchCallCount)
        assertEquals(newLat, fakeHereEvApiClient.lastRequestedLat!!, 0.0001)
        assertEquals(newLon, fakeHereEvApiClient.lastRequestedLon!!, 0.0001)

        // Verify UI state coordinates updated
        val state = viewModel.uiState.value
        assertEquals(newLat, state.userLatitude!!, 0.0001)
        assertEquals(newLon, state.userLongitude!!, 0.0001)
        assertFalse(state.isSearching)
        assertNull(state.errorMessage)
        assertEquals(10, state.top10DisplayStations.size)

        // Also verify pull-to-refresh / locationService refresh
        val refreshLat = 21.0600
        val refreshLon = 105.8600
        fakeLocationService.locationToReturn = createMockLocation(refreshLat, refreshLon)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("EVCS still in cooldown", 1, fakeEvcsApiClient.searchCallCount)
        assertEquals(3, fakeHereEvApiClient.fetchCallCount)
        assertEquals(refreshLat, viewModel.uiState.value.userLatitude!!, 0.0001)
        assertEquals(refreshLon, viewModel.uiState.value.userLongitude!!, 0.0001)
    }

    // =========================================================================
    // 3. DC Wattage Tier & Smart Filter Compatibility (and Motorbike Exclusion)
    // =========================================================================

    @Test
    fun testDcWattageTierFiltering_onFallbackStations_filtersByPowerRatingAndPrunesMotorbike() = runTest {
        fakeEvcsApiClient.searchHandler = { _, _ ->
            Result.failure(IOException("HTTP 403 Forbidden"))
        }

        // Create fallback stations with specific DC tiers:
        // - st_30: 30kW DC
        // - st_60: 60kW DC
        // - st_120: 120kW DC
        // - st_moto: 3.5kW AC only (motorbike-only station)
        val testHereStations = listOf(
            createHereStation("c.st30", "c.st30", "Trạm 30", "Phố 30", "Hà Nội", 21.03, 105.85,
                listOf(createConnector(powerKw = 30.0, powerType = "DC"))),
            createHereStation("c.st60", "c.st60", "Trạm 60", "Phố 60", "Hà Nội", 21.04, 105.85,
                listOf(createConnector(powerKw = 60.0, powerType = "DC"))),
            createHereStation("c.st120", "c.st120", "Trạm 120", "Phố 120", "Hà Nội", 21.05, 105.85,
                listOf(createConnector(powerKw = 120.0, powerType = "DC"))),
            createHereStation("c.stmoto", "c.stmoto", "Trạm Moto", "Phố Moto", "Hà Nội", 21.06, 105.85,
                listOf(createConnector(powerKw = 3.5, powerType = "AC")))
        )

        fakeHereEvApiClient.fetchHandler = { _, _, _, _, _ ->
            Result.success(testHereStations.map { HereModelMapper.mapStationToDomain(it, dcOnly = false) })
        }

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 1. Initial unfiltered state: Motorbike station (3.5kW) must be pruned by hasCarCompatiblePorts()
        val initialDisplay = viewModel.uiState.value.top10DisplayStations
        assertEquals("Motorbike station must be excluded", 3, initialDisplay.size)
        assertTrue(initialDisplay.none { it.id == "c.stmoto" })

        // 2. Select ≤ 30kW DC Tier
        viewModel.selectDcTier(DcWattageTier.LE_30KW)
        advanceUntilIdle()

        val dc30State = viewModel.uiState.value
        assertEquals(SmartFilterMode.DC, dc30State.activeFilterMode)
        assertEquals(DcWattageTier.LE_30KW, dc30State.selectedDcTier)
        assertEquals(1, dc30State.top10DisplayStations.size)
        assertEquals("c.st30", dc30State.top10DisplayStations[0].id)

        // 3. Select ≥ 60kW DC Tier (matches 60kW and 120kW)
        viewModel.selectDcTier(DcWattageTier.GE_60KW)
        advanceUntilIdle()

        val dc60State = viewModel.uiState.value
        assertEquals(2, dc60State.top10DisplayStations.size)
        assertTrue(dc60State.top10DisplayStations.any { it.id == "c.st60" })
        assertTrue(dc60State.top10DisplayStations.any { it.id == "c.st120" })

        // 4. Select ≥ 120kW DC Tier
        viewModel.selectDcTier(DcWattageTier.GE_120KW)
        advanceUntilIdle()

        val dc120State = viewModel.uiState.value
        assertEquals(1, dc120State.top10DisplayStations.size)
        assertEquals("c.st120", dc120State.top10DisplayStations[0].id)

        // 5. Clear Smart Filter -> Returns all 3 car-compatible stations
        viewModel.clearSmartFilter()
        advanceUntilIdle()

        val clearedState = viewModel.uiState.value
        assertEquals(SmartFilterMode.NONE, clearedState.activeFilterMode)
        assertNull(clearedState.selectedDcTier)
        assertEquals(3, clearedState.top10DisplayStations.size)
    }

    // =========================================================================
    // 4. Quick Chip & Custom Filter Modes on Fallback Stations
    // =========================================================================

    @Test
    fun testQuickChipFiltering_onFallbackStations_appliesCorrectly() = runTest {
        fakeEvcsApiClient.searchHandler = { _, _ -> Result.failure(IOException("403 Forbidden")) }

        // Station A: 30kW
        // Station B: 60kW
        // Station C: 120kW
        val stations = listOf(
            createHereStation("c.st_30", "c.st_30", "Trạm 30", "Phố A", "Hà Nội", 21.03, 105.85,
                listOf(createConnector(powerKw = 30.0, available = 2, total = 2))),
            createHereStation("c.st_60", "c.st_60", "Trạm 60", "Phố B", "Hà Nội", 21.04, 105.85,
                listOf(createConnector(powerKw = 60.0, available = 2, total = 2))),
            createHereStation("c.st_120", "c.st_120", "Trạm 120", "Phố C", "Hà Nội", 21.05, 105.85,
                listOf(createConnector(powerKw = 120.0, available = 2, total = 2)))
        )
        fakeHereEvApiClient.fetchHandler = { _, _, _, dcOnly, _ ->
            Result.success(stations.map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) })
        }

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)

        // Apply Quick Chip: DC_GE_120KW
        viewModel.saveAndApplyCustomFilter(
            CustomFilterConfig(
                mode = CustomFilterMode.QUICK_CHIP,
                quickChip = QuickChipOption.DC_GE_120KW
            )
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SmartFilterMode.CUSTOM, state.activeFilterMode)
        assertEquals(1, state.top10DisplayStations.size)
        assertEquals("c.st_120", state.top10DisplayStations[0].id)
    }

    // =========================================================================
    // 5. Road Distance Matrix Routing on Fallback Stations
    // =========================================================================

    @Test
    fun testRoadDistanceRouting_calculatesRoutesAndSortsTop10FallbackStations() = runTest {
        fakeEvcsApiClient.searchHandler = { _, _ -> Result.failure(IOException("403 Forbidden")) }
        val rawHereStations = generate12HereStations()
        fakeHereEvApiClient.fetchHandler = { _, _, _, dcOnly, _ ->
            Result.success(rawHereStations.map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) })
        }

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(10, state.top10DisplayStations.size)

        // Verify routing coordinator destinations strictly <= 10
        assertTrue("Destinations sent to coordinator must be <= 10", fakeRoutingCoordinator.lastDestinations.size <= 10)

        // Verify driving metrics are populated
        state.top10DisplayStations.forEach { st ->
            assertNotNull("Station ${st.id} must contain driving metrics", st.drivingMetrics)
            assertTrue("Distance in meters must be positive", st.drivingMetrics!!.distanceMeters > 0)
        }

        // Verify stations are sorted by driving distance ascending
        for (i in 0 until state.top10DisplayStations.size - 1) {
            val d1 = state.top10DisplayStations[i].drivingMetrics!!.distanceMeters
            val d2 = state.top10DisplayStations[i + 1].drivingMetrics!!.distanceMeters
            assertTrue("Stations must be sorted by driving distance ($d1 <= $d2)", d1 <= d2)
        }
    }

    // =========================================================================
    // 6. Two-Way Favorites Integration on Fallback Stations
    // =========================================================================

    @Test
    fun testFavoritesIntegration_onFallbackStations() = runTest {
        fakeEvcsApiClient.searchHandler = { _, _ -> Result.failure(IOException("403 Forbidden")) }
        val rawHereStations = generate12HereStations()
        fakeHereEvApiClient.fetchHandler = { _, _, _, dcOnly, _ ->
            Result.success(rawHereStations.map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) })
        }

        val emittedEvents = mutableListOf<NearbyUiEvent>()
        val collectJob = launch { viewModel.events.toList(emittedEvents) }

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        val targetStation = viewModel.uiState.value.top10DisplayStations.first()
        val targetId = targetStation.id // e.g. "c.vcs001"

        // 1. Initially not favorite
        assertFalse(viewModel.uiState.value.favoriteStationIds.contains(targetId))
        assertFalse(repository.favoriteIdsState.value.contains(targetId))

        // 2. Toggle Favorite -> Adds to favorites
        viewModel.resetToastDebounceForTesting()
        viewModel.toggleFavorite(targetStation)
        advanceUntilIdle()

        assertTrue("favoriteStationIds must include $targetId", viewModel.uiState.value.favoriteStationIds.contains(targetId))
        assertTrue("Repository must consider $targetId favorite", repository.favoriteIdsState.value.contains(targetId))
        assertTrue(emittedEvents.any { it is NearbyUiEvent.ShowToast && it.message.contains("Đã thêm vào danh sách yêu thích") })

        // 3. Verify dual-key coordinate resolution
        val coordsCanonical = repository.getStationCoordinates(targetId)
        val coordsUnprefixed = repository.getStationCoordinates(targetId.removePrefix("c."))
        assertNotNull("Coordinates for canonical ID must exist", coordsCanonical)
        assertEquals("Dual-key resolution must produce identical coordinates", coordsCanonical, coordsUnprefixed)

        // 4. Toggle Favorite again -> Removes from favorites
        viewModel.resetToastDebounceForTesting()
        viewModel.toggleFavorite(targetStation)
        advanceUntilIdle()

        assertFalse("favoriteStationIds must not include $targetId", viewModel.uiState.value.favoriteStationIds.contains(targetId))
        assertFalse("Repository must no longer consider $targetId favorite", repository.favoriteIdsState.value.contains(targetId))
        assertTrue(emittedEvents.any { it is NearbyUiEvent.ShowToast && it.message.contains("Đã xóa khỏi danh sách yêu thích") })

        collectJob.cancel()
    }

    // =========================================================================
    // 7. Complete Failure Graceful Degradation (Both EVCS and HERE Fail)
    // =========================================================================

    @Test
    fun testDualFailure_whenBothEvcsAndHereFail_surfacesCleanErrorMessageWithoutCrashing() = runTest {
        // Both EVCS and HERE API fail
        fakeEvcsApiClient.searchHandler = { _, _ -> Result.failure(IOException("EVCS 403 Forbidden")) }
        fakeHereEvApiClient.fetchHandler = { _, _, _, _, _ ->
            Result.failure(IOException("HERE Maps EV API Service Unavailable (503)"))
        }

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSearching)
        assertFalse(state.isLocating)
        assertFalse(state.isLoading)
        assertTrue(state.top10DisplayStations.isEmpty())
        assertNotNull("errorMessage must be populated when both sources fail", state.errorMessage)
        assertTrue(
            "errorMessage should reflect secondary failure",
            state.errorMessage!!.contains("HERE Maps EV API Service Unavailable")
        )
    }

    // =========================================================================
    // 8. Cancellation Safety (Rapid Successive Invocations)
    // =========================================================================

    @Test
    fun testCancellationSafety_rapidSuccessiveUpdates_cancelsPreviousJobsCleanly() = runTest {
        fakeEvcsApiClient.searchHandler = { _, _ -> Result.failure(IOException("403 Forbidden")) }
        fakeHereEvApiClient.fetchHandler = { lat, lon, _, dcOnly, _ ->
            val stations = generate12HereStations(lat, lon)
            Result.success(stations.map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) })
        }

        // Trigger initial scan
        val initialJob = viewModel.scanNearbyStations()
        assertTrue(initialJob.isActive)

        // Immediately trigger GPS update before initial completes
        val updateJob = viewModel.onLocationUpdated(21.0555, 105.8555)

        // Initial scan job must be cancelled cleanly
        assertTrue("Preceding scan job must be cancelled", initialJob.isCancelled)
        assertTrue("New update job must be active", updateJob.isActive)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(21.0555, state.userLatitude!!, 0.0001)
        assertEquals(105.8555, state.userLongitude!!, 0.0001)
        assertFalse(state.isSearching)
        assertNull(state.errorMessage)
        assertEquals(10, state.top10DisplayStations.size)
    }

    // =========================================================================
    // 9. Half-Open Probe Recovery to EVCS
    // =========================================================================

    @Test
    fun testCircuitBreakerCooldownAndHalfOpenRecovery() = runTest {
        // Trip circuit breaker with 403
        fakeEvcsApiClient.searchHandler = { _, _ -> Result.failure(IOException("403 Forbidden")) }
        fakeHereEvApiClient.fetchHandler = { lat, lon, _, dcOnly, _ ->
            Result.success(generate12HereStations(lat, lon).map { HereModelMapper.mapStationToDomain(it, dcOnly = dcOnly) })
        }

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertTrue(repository.isCircuitBreakerOpen())
        assertEquals(1, fakeEvcsApiClient.searchCallCount)
        assertEquals(1, fakeHereEvApiClient.fetchCallCount)

        // Fast-forward cooldown by resetting blockedUntil
        repository.evcsWafBlockedUntil.set(0L)
        assertFalse(repository.isCircuitBreakerOpen())

        // Primary EVCS recovers
        fakeEvcsApiClient.searchHandler = { lat, lon ->
            Result.success(
                listOf(
                    SearchStationRaw(
                        locationId = "evcs_rec_01",
                        id = "evcs_rec_01",
                        stationName = "Trạm EVCS Primary Recovered",
                        stationAddress = "Hà Nội",
                        latitude = lat,
                        longitude = lon,
                        evse = "VinFast",
                        evsePowers = listOf(
                            EvsePowerRaw(
                                type = 250_000L,
                                numberOfAvailableEvse = 2,
                                totalEvse = 2
                            )
                        )
                    )
                )
            )
        }

        // Next scan will probe EVCS in Half-Open state
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals("EVCS must be probed", 2, fakeEvcsApiClient.searchCallCount)
        assertEquals("HERE API should NOT have been called during successful probe", 1, fakeHereEvApiClient.fetchCallCount)

        val state = viewModel.uiState.value
        assertFalse(repository.isCircuitBreakerOpen())
        assertNull(state.errorMessage)
        assertEquals(1, state.top10DisplayStations.size)
        assertEquals("evcs_rec_01", state.top10DisplayStations[0].id)
    }
}
