package com.evcs.favorites.ui.screens

import android.location.Location
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
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Single comprehensive test suite for Phase 03: Pre-Filter Selection on Initial Screen,
 * Persistence & Immediate Execution.
 *
 * Verifies:
 * 1. Initial filter state restoration from SmartFilterPreferences prior to search (hasSearched == false).
 * 2. Pre-filter selection on initial screen (AC, DC tiers, Custom) updates uiState and persists immediately.
 * 3. Immediate execution of the pre-selected filter upon raw stations scan.
 * 4. DC sub-filter entry/exit transitions and clearSmartFilter() on the initial screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyPreFilterAndInitialScanTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var smartFilterPreferences: SmartFilterPreferences
    private lateinit var filterPreferences: NearbyFilterPreferences
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeRoutingCoordinator: FakeRoutingCoordinator
    private lateinit var routingPreferencesManager: RoutingPreferencesManager

    private lateinit var stationAc: Station
    private lateinit var stationDc30: Station
    private lateinit var stationDc60: Station
    private lateinit var stationDc120: Station
    private lateinit var stationDc250: Station

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted
        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = com.evcs.favorites.data.api.EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var nearbyResult: Result<List<Station>> = Result.success(emptyList())

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return nearbyResult
        }
    }

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            return destinations.associate { d ->
                d.id to DrivingMetrics(
                    distanceMeters = 1500L,
                    durationSeconds = 180L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
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
        powers: List<Pair<Long, String>>
    ): Station {
        val powerPorts = powers.map { (watts, label) ->
            PowerPort(
                typeWatts = watts,
                label = label,
                availablePlugs = 2,
                totalPlugs = 2,
                displayString = "$label: trống 2/2"
            )
        }
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $name",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powers.joinToString { it.second },
            depotStatus = "Normal",
            powers = powerPorts,
            totalAvailablePlugs = 2 * powers.size,
            totalPlugs = 2 * powers.size
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "mock_auth_token"
        }
        smartFilterPreferences = SmartFilterPreferences(sessionStorage)
        filterPreferences = NearbyFilterPreferences(sessionStorage)
        routingPreferencesManager = RoutingPreferencesManager(sessionStorage)
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeRoutingCoordinator = FakeRoutingCoordinator()

        stationAc = createTestStation("st_ac", "Trạm AC", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        stationDc30 = createTestStation("st_dc30", "Trạm DC 30", 21.0287, 105.8544, listOf(30_000L to "DC 30kW"))
        stationDc60 = createTestStation("st_dc60", "Trạm DC 60", 21.0288, 105.8545, listOf(60_000L to "DC 60kW"))
        stationDc120 = createTestStation("st_dc120", "Trạm DC 120", 21.0289, 105.8546, listOf(120_000L to "DC 120kW"))
        stationDc250 = createTestStation("st_dc250", "Trạm DC 250", 21.0290, 105.8547, listOf(250_000L to "DC 250kW"))

        fakeRepository.nearbyResult = Result.success(
            listOf(stationAc, stationDc30, stationDc60, stationDc120, stationDc250)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        smartPrefs: SmartFilterPreferences = smartFilterPreferences
    ): NearbyViewModel {
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingPreferencesManager = routingPreferencesManager,
            routingCoordinator = fakeRoutingCoordinator,
            filterPreferences = filterPreferences,
            smartFilterPreferences = smartPrefs,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            routingDebounceMs = 0L
        )
    }

    @Test
    fun testColdStartRestorationPriorToSearchAndImmediateExecutionUponScan() = runTest(testDispatcher) {
        // 1. Arrange: Stored preferences have DC mode and GE_120KW tier before ViewModel creation
        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.DC)
        smartFilterPreferences.saveSelectedDcTier(DcWattageTier.GE_120KW)

        // 2. Act: Initialize ViewModel
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 3. Assert: Initial UI state restores persisted pre-filter correctly before search
        assertFalse("hasSearched must be false initially", viewModel.uiState.value.hasSearched)
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertEquals(DcWattageTier.GE_120KW, viewModel.uiState.value.selectedDcTier)
        assertTrue(viewModel.uiState.value.isDcSubFilterVisible)
        assertTrue("No stations displayed before scanning", viewModel.uiState.value.top10DisplayStations.isEmpty())

        // 4. Act: Trigger scan from the initial hero screen
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 5. Assert: Pre-selected DC >= 120kW filter is immediately applied to fetched stations
        assertTrue(viewModel.uiState.value.hasSearched)
        val displayedStations = viewModel.uiState.value.top10DisplayStations
        assertEquals(2, displayedStations.size)
        val stationIds = displayedStations.map { it.id }.toSet()
        assertEquals(setOf("st_dc120", "st_dc250"), stationIds)
    }

    @Test
    fun testPreFilterAcSelectionOnInitialScreenPersistsAndAppliesImmediatelyOnScan() = runTest(testDispatcher) {
        // 1. Arrange: Fresh initial state with SmartFilterMode.NONE
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasSearched)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)

        // 2. Act: User selects AC filter on initial screen
        viewModel.toggleAcFilter()
        advanceUntilIdle()

        // 3. Assert: UI state and persistence update immediately without search
        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)
        assertEquals(SmartFilterMode.AC, smartFilterPreferences.getActiveFilterMode())
        assertFalse("hasSearched remains false", viewModel.uiState.value.hasSearched)
        assertTrue("Stations list remains empty", viewModel.uiState.value.top10DisplayStations.isEmpty())

        // 4. Act: Execute scan
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 5. Assert: AC filter immediately applied to the raw stations
        assertTrue(viewModel.uiState.value.hasSearched)
        val displayedStations = viewModel.uiState.value.top10DisplayStations
        assertEquals(1, displayedStations.size)
        assertEquals("st_ac", displayedStations.first().id)
    }

    @Test
    fun testPreFilterDcSubFilterWorkflowAndBackTransitionOnInitialScreen() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 1. User taps DC button on initial screen to enter DC mode
        viewModel.enterDcMode()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDcSubFilterVisible)
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertNull(viewModel.uiState.value.selectedDcTier)
        assertEquals(SmartFilterMode.DC, smartFilterPreferences.getActiveFilterMode())

        // 2. User selects DC tier LE_30KW on initial screen
        viewModel.selectDcTier(DcWattageTier.LE_30KW)
        advanceUntilIdle()

        assertEquals(DcWattageTier.LE_30KW, viewModel.uiState.value.selectedDcTier)
        assertEquals(DcWattageTier.LE_30KW, smartFilterPreferences.getSelectedDcTier())
        assertEquals(SmartFilterMode.DC, smartFilterPreferences.getActiveFilterMode())

        // 3. User taps back arrow to exit DC mode
        viewModel.exitDcMode()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDcSubFilterVisible)
        assertNull(viewModel.uiState.value.selectedDcTier)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
        assertNull(smartFilterPreferences.getSelectedDcTier())
    }

    @Test
    fun testClearSmartFilterOnInitialScreenResetsToNoneAndPersists() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // User sets AC filter first
        viewModel.toggleAcFilter()
        advanceUntilIdle()
        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)
        assertEquals(SmartFilterMode.AC, smartFilterPreferences.getActiveFilterMode())

        // User taps clear filter
        viewModel.clearSmartFilter()
        advanceUntilIdle()

        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
        assertNull(viewModel.uiState.value.selectedDcTier)
        assertFalse(viewModel.uiState.value.isDcSubFilterVisible)
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
    }

    @Test
    fun testPreFilterCustomModeAndExecutionUponScan() = runTest(testDispatcher) {
        // Arrange: Custom filter configured for 50kW to 150kW
        val customConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 50,
            maxKw = 150
        )
        smartFilterPreferences.saveCustomConfig(customConfig)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Act: Apply custom filter on initial screen
        viewModel.applyCustomFilter()
        advanceUntilIdle()

        assertEquals(SmartFilterMode.CUSTOM, viewModel.uiState.value.activeFilterMode)
        assertEquals(customConfig, viewModel.uiState.value.savedCustomConfig)
        assertEquals(SmartFilterMode.CUSTOM, smartFilterPreferences.getActiveFilterMode())

        // Scan nearby stations
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // Assert: Only DC 60kW and DC 120kW stations are returned (within 50-150kW)
        assertTrue(viewModel.uiState.value.hasSearched)
        val displayedStations = viewModel.uiState.value.top10DisplayStations
        assertEquals(2, displayedStations.size)
        val stationIds = displayedStations.map { it.id }.toSet()
        assertEquals(setOf("st_dc60", "st_dc120"), stationIds)
    }
}
