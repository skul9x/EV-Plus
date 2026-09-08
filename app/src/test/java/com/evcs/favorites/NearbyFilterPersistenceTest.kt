package com.evcs.favorites

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.WattageOption
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
 * Comprehensive verification test for Phase 02:
 * Wattage Filter Persistence & Restoration Across App Restarts.
 *
 * Verifies:
 * 1. NearbyFilterPreferences encoding & decoding:
 *    - Empty set persistence (removes key from storage).
 *    - Single WattageOption persistence and extraction.
 *    - Multiple WattageOptions persistence and extraction.
 *    - Corrupted/unknown token resilience (skips invalid tokens, trims whitespace, parses valid tokens).
 *    - Clearing persisted preferences removes the key.
 * 2. NearbyViewModel integration:
 *    - Cold-start initializes uiState.selectedWattages from NearbyFilterPreferences.
 *    - Toggling wattage filters persists updated sets directly to storage.
 *    - Clearing wattage filters removes preferences from storage.
 * 3. Cross-session restoration & automatic filter application on scan:
 *    - A second NearbyViewModel sharing the persistent storage restores selected filters.
 *    - Upon scanNearbyStations() or refresh(), newly scanned stations are automatically filtered
 *      according to restored wattage preferences without requiring user re-tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyFilterPersistenceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var filterPreferences: NearbyFilterPreferences
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted
        override suspend fun getFreshLocation(): Location? = locationToReturn
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

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powers: List<Long>,
        availablePlugs: Int = 2,
        totalPlugs: Int = 2
    ): Station {
        val powerPorts = powers.map { w ->
            com.evcs.favorites.data.model.PowerPort(
                typeWatts = w,
                label = "${w / 1000}kW",
                availablePlugs = availablePlugs,
                totalPlugs = totalPlugs,
                displayString = "${w / 1000}kW: trống $availablePlugs/$totalPlugs"
            )
        }
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $id",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powers.joinToString { "${it / 1000}kW" },
            depotStatus = "OPEN",
            powers = powerPorts,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        filterPreferences = NearbyFilterPreferences(storage)
        sessionManager = SessionManager(storage)
        fakeRepository = FakeEvcsRepository(storage)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // Part 1: NearbyFilterPreferences Unit Tests
    // =========================================================================

    @Test
    fun testPreferencesEmptySetRemovesKeyAndReturnsEmpty() {
        assertFalse(filterPreferences.hasPersistedFilters())
        assertEquals(emptySet<WattageOption>(), filterPreferences.getSelectedWattages())

        // Saving empty set when no key exists
        filterPreferences.saveSelectedWattages(emptySet())
        assertFalse(filterPreferences.hasPersistedFilters())
        assertNull(storage.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES))

        // Saving items then saving empty set removes key
        filterPreferences.saveSelectedWattages(setOf(WattageOption.KW_250))
        assertTrue(filterPreferences.hasPersistedFilters())
        filterPreferences.saveSelectedWattages(emptySet())
        assertFalse(filterPreferences.hasPersistedFilters())
        assertNull(storage.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES))
    }

    @Test
    fun testPreferencesSingleOptionEncodeDecode() {
        filterPreferences.saveSelectedWattages(setOf(WattageOption.KW_180))

        assertTrue(filterPreferences.hasPersistedFilters())
        assertEquals("KW_180", storage.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES))

        val decoded = filterPreferences.getSelectedWattages()
        assertEquals(setOf(WattageOption.KW_180), decoded)
    }

    @Test
    fun testPreferencesMultipleOptionsEncodeDecode() {
        val selected = setOf(WattageOption.KW_360, WattageOption.KW_150, WattageOption.KW_60)
        filterPreferences.saveSelectedWattages(selected)

        assertTrue(filterPreferences.hasPersistedFilters())
        val rawValue = storage.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES)
        assertTrue(rawValue != null)

        val decoded = filterPreferences.getSelectedWattages()
        assertEquals(selected, decoded)
    }

    @Test
    fun testPreferencesCorruptedAndUnknownValuesGracefullyHandled() {
        // Corrupted tokens, unknown enum names, empty tokens, leading/trailing whitespace
        storage.putString(
            NearbyFilterPreferences.KEY_SELECTED_WATTAGES,
            " KW_250 , UNKNOWN_WATTAGE, , KW_60, INVALID#@!, KW_11 "
        )

        val decoded = filterPreferences.getSelectedWattages()
        assertEquals(
            setOf(WattageOption.KW_250, WattageOption.KW_60, WattageOption.KW_11),
            decoded
        )
    }

    @Test
    fun testPreferencesClearRemovesKey() {
        filterPreferences.saveSelectedWattages(setOf(WattageOption.KW_250, WattageOption.KW_120))
        assertTrue(filterPreferences.hasPersistedFilters())

        filterPreferences.clear()

        assertFalse(filterPreferences.hasPersistedFilters())
        assertNull(storage.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES))
        assertTrue(filterPreferences.getSelectedWattages().isEmpty())
    }

    // =========================================================================
    // Part 2: NearbyViewModel Filter State & Persistence Integration
    // =========================================================================

    @Test
    fun testViewModelInitializesSelectedWattagesFromPreferences() = runTest(testDispatcher) {
        // Pre-persist filter preferences
        filterPreferences.saveSelectedWattages(setOf(WattageOption.KW_250, WattageOption.KW_180))

        val viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = filterPreferences,
            routingCoordinator = MultiTierRoutingCoordinator(),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        assertEquals(
            setOf(WattageOption.KW_250, WattageOption.KW_180),
            viewModel.uiState.value.selectedWattages
        )
    }

    @Test
    fun testViewModelDefaultFilterPreferencesWhenNull() = runTest(testDispatcher) {
        val viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = null,
            routingCoordinator = MultiTierRoutingCoordinator(),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        assertTrue(viewModel.uiState.value.selectedWattages.isEmpty())
    }

    @Test
    fun testToggleWattageFilterPersistsToStorage() = runTest(testDispatcher) {
        val viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = filterPreferences,
            routingCoordinator = MultiTierRoutingCoordinator(),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        assertTrue(viewModel.uiState.value.selectedWattages.isEmpty())
        assertFalse(filterPreferences.hasPersistedFilters())

        // Toggle KW_250 ON
        viewModel.toggleWattageFilter(WattageOption.KW_250)
        advanceUntilIdle()

        assertEquals(setOf(WattageOption.KW_250), viewModel.uiState.value.selectedWattages)
        assertEquals(setOf(WattageOption.KW_250), filterPreferences.getSelectedWattages())
        assertTrue(filterPreferences.hasPersistedFilters())

        // Toggle KW_60 ON
        viewModel.toggleWattageFilter(WattageOption.KW_60)
        advanceUntilIdle()

        assertEquals(
            setOf(WattageOption.KW_250, WattageOption.KW_60),
            viewModel.uiState.value.selectedWattages
        )
        assertEquals(
            setOf(WattageOption.KW_250, WattageOption.KW_60),
            filterPreferences.getSelectedWattages()
        )

        // Toggle KW_250 OFF
        viewModel.toggleWattageFilter(WattageOption.KW_250)
        advanceUntilIdle()

        assertEquals(setOf(WattageOption.KW_60), viewModel.uiState.value.selectedWattages)
        assertEquals(setOf(WattageOption.KW_60), filterPreferences.getSelectedWattages())

        // Toggle KW_60 OFF -> becomes empty set -> key removed
        viewModel.toggleWattageFilter(WattageOption.KW_60)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.selectedWattages.isEmpty())
        assertFalse(filterPreferences.hasPersistedFilters())
    }

    @Test
    fun testClearWattageFiltersRemovesFromStorage() = runTest(testDispatcher) {
        val viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = filterPreferences,
            routingCoordinator = MultiTierRoutingCoordinator(),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        viewModel.toggleWattageFilter(WattageOption.KW_180)
        advanceUntilIdle()
        assertTrue(filterPreferences.hasPersistedFilters())

        viewModel.clearWattageFilters()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.selectedWattages.isEmpty())
        assertFalse(filterPreferences.hasPersistedFilters())
        assertNull(storage.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES))
    }

    // =========================================================================
    // Part 3: Cross-Session Restoration & Automatic Application on Scan
    // =========================================================================

    @Test
    fun testCrossSessionRestorationAndAutoFilteringOnScan() = runTest(testDispatcher) {
        // Session 1: User launches app, selects KW_250 filter chip, and closes app
        val session1ViewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = filterPreferences,
            routingCoordinator = MultiTierRoutingCoordinator(),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
        session1ViewModel.toggleWattageFilter(WattageOption.KW_250)
        advanceUntilIdle()

        assertEquals(setOf(WattageOption.KW_250), filterPreferences.getSelectedWattages())

        // Prepare raw mock stations returned from EVCS repository:
        // Station 1: Ultra-fast 250kW (matches filter)
        // Station 2: Fast 60kW only (does NOT match filter)
        val stationUltraFast = createTestStation(
            id = "station_250kw",
            name = "VinFast Royal City",
            lat = 21.0286,
            lon = 105.8543,
            powers = listOf(250_000L)
        )
        val stationFast = createTestStation(
            id = "station_60kw",
            name = "VinFast Times City",
            lat = 21.0287,
            lon = 105.8544,
            powers = listOf(60_000L)
        )
        fakeRepository.nearbyResult = Result.success(listOf(stationUltraFast, stationFast))

        // Session 2: App cold restarts. Fresh NearbyViewModel created with the same storage
        val session2Preferences = NearbyFilterPreferences(storage)
        val session2ViewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = session2Preferences,
            routingCoordinator = MultiTierRoutingCoordinator(),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        // Verify restored filter state in Session 2 immediately upon instantiation
        assertEquals(
            setOf(WattageOption.KW_250),
            session2ViewModel.uiState.value.selectedWattages
        )

        // Session 2 performs scanNearbyStations()
        session2ViewModel.scanNearbyStations()
        advanceUntilIdle()

        // Raw stations contains both stations
        assertEquals(2, session2ViewModel.uiState.value.rawStations.size)

        // But top10DisplayStations MUST only contain station_250kw,
        // proving the restored wattage filter was applied automatically to the scan results!
        val displayedStations = session2ViewModel.uiState.value.top10DisplayStations
        assertEquals(1, displayedStations.size)
        assertEquals("station_250kw", displayedStations[0].id)

        // Also test refresh() applies the restored filter
        session2ViewModel.refresh()
        advanceUntilIdle()

        val refreshedStations = session2ViewModel.uiState.value.top10DisplayStations
        assertEquals(1, refreshedStations.size)
        assertEquals("station_250kw", refreshedStations[0].id)
    }

    @Test
    fun testProvideFactoryWiredWithFilterPreferences() {
        val factory = NearbyViewModel.provideFactory(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = filterPreferences,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val createdVm = factory.create(NearbyViewModel::class.java)
        org.junit.Assert.assertNotNull(createdVm)
    }
}
