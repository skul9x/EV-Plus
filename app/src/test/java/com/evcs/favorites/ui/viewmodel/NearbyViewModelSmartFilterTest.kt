package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 02:
 * Persistence & ViewModel Filter Pipeline.
 *
 * Verifies:
 * 1. SmartFilterPreferences direct persistence and resilient parsing:
 *    - Mode, DC tier, and CustomFilterConfig serialization/deserialization.
 *    - hasCustomConfig() check and corrupted value tolerance.
 * 2. NearbyViewModel Smart Filter Pipeline:
 *    - Cold-start restoration of persisted filter from previous session.
 *    - DC sub-filter row visibility restoration when DC mode was persisted with a tier.
 *    - Toggling AC filter (applies AC filter, toggling again resets to NONE / unfiltered).
 *    - Entering DC mode exposes DC sub-filter without filtering stations until tier selected.
 *    - Selecting DC tier filters strictly by that tier.
 *    - Exiting DC mode resets filter state and returns to 3-button mode.
 *    - Clicking Custom without saved configuration triggers showCustomConfigPrompt = true.
 *    - Dismissing custom prompt clears showCustomConfigPrompt = false.
 *    - Clicking Custom with saved configuration executes custom filter pipeline.
 *    - saveAndApplyCustomFilter persists config and immediately activates pipeline.
 *    - clearSmartFilter resets filter to NONE and refreshes pipeline.
 *    - filterSummaryPillText dynamically reflects station counts and active filter descriptions.
 *    - provideFactory correctly wires smartFilterPreferences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelSmartFilterTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var smartFilterPreferences: SmartFilterPreferences
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
        powers: List<Pair<Long, String>>, // typeWatts to label (e.g. 11_000L to "AC 11kW", 60_000L to "DC 60kW")
        availablePlugs: Int = 2,
        totalPlugs: Int = 2,
        depotStatus: String = "OPEN"
    ): Station {
        val powerPorts = powers.map { (w, lbl) ->
            PowerPort(
                typeWatts = w,
                label = lbl,
                availablePlugs = availablePlugs,
                totalPlugs = totalPlugs,
                displayString = "$lbl: trống $availablePlugs/$totalPlugs"
            )
        }
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $id",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powers.joinToString { it.second },
            depotStatus = depotStatus,
            powers = powerPorts,
            totalAvailablePlugs = if (depotStatus.equals("Maintaining", true)) 0 else availablePlugs * powers.size,
            totalPlugs = totalPlugs * powers.size
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        smartFilterPreferences = SmartFilterPreferences(storage)
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

    private fun createViewModel(
        prefs: SmartFilterPreferences = smartFilterPreferences
    ): NearbyViewModel {
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            smartFilterPreferences = prefs,
            routingCoordinator = MultiTierRoutingCoordinator(),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    // =========================================================================
    // Part 1: SmartFilterPreferences Storage Unit Tests
    // =========================================================================

    @Test
    fun testPreferencesSaveAndGetFilterMode() {
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())

        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.AC)
        assertEquals(SmartFilterMode.AC, smartFilterPreferences.getActiveFilterMode())

        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.DC)
        assertEquals(SmartFilterMode.DC, smartFilterPreferences.getActiveFilterMode())

        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.CUSTOM)
        assertEquals(SmartFilterMode.CUSTOM, smartFilterPreferences.getActiveFilterMode())

        // Corrupted value tolerance
        storage.putString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE, "CORRUPTED_MODE")
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
    }

    @Test
    fun testPreferencesSaveAndGetDcTier() {
        assertNull(smartFilterPreferences.getSelectedDcTier())

        smartFilterPreferences.saveSelectedDcTier(DcWattageTier.BETWEEN_30_60KW)
        assertEquals(DcWattageTier.BETWEEN_30_60KW, smartFilterPreferences.getSelectedDcTier())

        smartFilterPreferences.saveSelectedDcTier(DcWattageTier.GE_120KW)
        assertEquals(DcWattageTier.GE_120KW, smartFilterPreferences.getSelectedDcTier())

        // Save null removes key
        smartFilterPreferences.saveSelectedDcTier(null)
        assertNull(smartFilterPreferences.getSelectedDcTier())
        assertNull(storage.getString(SmartFilterPreferences.KEY_SELECTED_DC_TIER))

        // Corrupted value tolerance
        storage.putString(SmartFilterPreferences.KEY_SELECTED_DC_TIER, "INVALID_TIER")
        assertNull(smartFilterPreferences.getSelectedDcTier())
    }

    @Test
    fun testPreferencesCustomConfigPersistenceAndHasCustomConfig() {
        assertFalse(smartFilterPreferences.hasCustomConfig())
        assertNull(smartFilterPreferences.getCustomConfig())

        val quickChipConfig = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.DC_GE_60KW
        )
        smartFilterPreferences.saveCustomConfig(quickChipConfig)
        assertTrue(smartFilterPreferences.hasCustomConfig())
        assertEquals(quickChipConfig, smartFilterPreferences.getCustomConfig())

        val rangeConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 60,
            maxKw = 150
        )
        smartFilterPreferences.saveCustomConfig(rangeConfig)
        assertTrue(smartFilterPreferences.hasCustomConfig())
        assertEquals(rangeConfig, smartFilterPreferences.getCustomConfig())

        // Removing custom config
        smartFilterPreferences.saveCustomConfig(null)
        assertFalse(smartFilterPreferences.hasCustomConfig())
        assertNull(smartFilterPreferences.getCustomConfig())

        // Corrupted JSON tolerance
        storage.putString(SmartFilterPreferences.KEY_CUSTOM_CONFIG, "{invalid json: true")
        assertFalse(smartFilterPreferences.hasCustomConfig())
        assertNull(smartFilterPreferences.getCustomConfig())
    }

    @Test
    fun testPreferencesClearMethods() {
        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.DC)
        smartFilterPreferences.saveSelectedDcTier(DcWattageTier.GE_60KW)
        smartFilterPreferences.saveCustomConfig(CustomFilterConfig(minKw = 50))

        // clearActiveFilter only clears active mode and tier
        smartFilterPreferences.clearActiveFilter()
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
        assertNull(smartFilterPreferences.getSelectedDcTier())
        assertTrue(smartFilterPreferences.hasCustomConfig())

        // clear removes everything
        smartFilterPreferences.clear()
        assertFalse(smartFilterPreferences.hasCustomConfig())
    }

    // =========================================================================
    // Part 2: NearbyViewModel Smart Filter Cold-Start & State Restoration
    // =========================================================================

    @Test
    fun testInitialStateLoadsPersistedDcFilterAndRestoresSubFilter() = runTest(testDispatcher) {
        // Persist DC filter mode and selected tier from a prior session
        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.DC)
        smartFilterPreferences.saveSelectedDcTier(DcWattageTier.GE_60KW)

        val viewModel = createViewModel()

        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertEquals(DcWattageTier.GE_60KW, viewModel.uiState.value.selectedDcTier)
        assertTrue("isDcSubFilterVisible must be true when persisted mode was DC with a tier",
            viewModel.uiState.value.isDcSubFilterVisible)
    }

    @Test
    fun testInitialStateLoadsPersistedAcAndCustomFilters() = runTest(testDispatcher) {
        // Test AC restoration
        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.AC)
        val vmAc = createViewModel()
        assertEquals(SmartFilterMode.AC, vmAc.uiState.value.activeFilterMode)
        assertFalse(vmAc.uiState.value.isDcSubFilterVisible)
        assertNull(vmAc.uiState.value.selectedDcTier)

        // Test Custom restoration
        val customConfig = CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 80, maxKw = 200)
        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.CUSTOM)
        smartFilterPreferences.saveCustomConfig(customConfig)
        val vmCustom = createViewModel()
        assertEquals(SmartFilterMode.CUSTOM, vmCustom.uiState.value.activeFilterMode)
        assertEquals(customConfig, vmCustom.uiState.value.savedCustomConfig)
        assertFalse(vmCustom.uiState.value.isDcSubFilterVisible)
    }

    // =========================================================================
    // Part 3: AC Filter Toggle Pipeline Integration
    // =========================================================================

    @Test
    fun testToggleAcFilterAppliesFilterAndTogglingAgainResetsToUnfiltered() = runTest(testDispatcher) {
        val stationAc = createTestStation("st_ac", "Trạm AC", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        val stationDc60 = createTestStation("st_dc60", "Trạm DC 60", 21.0287, 105.8544, listOf(60_000L to "DC 60kW"))
        val stationDc250 = createTestStation("st_dc250", "Trạm DC 250", 21.0288, 105.8545, listOf(250_000L to "DC 250kW"))

        fakeRepository.nearbyResult = Result.success(listOf(stationAc, stationDc60, stationDc250))
        val viewModel = createViewModel()

        // Initial scan: all 3 stations present
        viewModel.scanNearbyStations()
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)

        // 1. Toggle AC filter ON
        viewModel.toggleAcFilter()
        advanceUntilIdle()

        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)
        assertEquals(SmartFilterMode.AC, smartFilterPreferences.getActiveFilterMode())
        assertFalse(viewModel.uiState.value.isDcSubFilterVisible)
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("st_ac", viewModel.uiState.value.top10DisplayStations.first().id)

        // 2. Toggle AC filter OFF (toggling again resets to NONE / unfiltered)
        viewModel.toggleAcFilter()
        advanceUntilIdle()

        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)
    }

    // =========================================================================
    // Part 4: DC Mode Transition, Sub-Filter Row & Tier Selection
    // =========================================================================

    @Test
    fun testEnterDcModeExposesDcSubFilterWithoutFilteringAndSelectDcTierFiltersStrictly() = runTest(testDispatcher) {
        val stationAc = createTestStation("st_ac", "Trạm AC", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        val stationDc30 = createTestStation("st_dc30", "Trạm DC 30", 21.0287, 105.8544, listOf(30_000L to "DC 30kW"))
        val stationDc60 = createTestStation("st_dc60", "Trạm DC 60", 21.0288, 105.8545, listOf(60_000L to "DC 60kW"))
        val stationDc250 = createTestStation("st_dc250", "Trạm DC 250", 21.0289, 105.8546, listOf(250_000L to "DC 250kW"))

        fakeRepository.nearbyResult = Result.success(listOf(stationAc, stationDc30, stationDc60, stationDc250))
        val viewModel = createViewModel()
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.top10DisplayStations.size)

        // 1. Enter DC Mode: exposes DC sub-filter without filtering until a tier is selected
        viewModel.enterDcMode()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDcSubFilterVisible)
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertNull(viewModel.uiState.value.selectedDcTier)
        // All 4 stations remain visible until a tier is chosen
        assertEquals(4, viewModel.uiState.value.top10DisplayStations.size)

        // 2. Select DC Tier LE_30KW (<= 30kW)
        viewModel.selectDcTier(DcWattageTier.LE_30KW)
        advanceUntilIdle()

        assertEquals(DcWattageTier.LE_30KW, viewModel.uiState.value.selectedDcTier)
        assertEquals(DcWattageTier.LE_30KW, smartFilterPreferences.getSelectedDcTier())
        assertEquals(SmartFilterMode.DC, smartFilterPreferences.getActiveFilterMode())
        assertTrue(viewModel.uiState.value.isDcSubFilterVisible)
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("st_dc30", viewModel.uiState.value.top10DisplayStations.first().id)

        // 3. Switch DC Tier to GE_120KW (>= 120kW)
        viewModel.selectDcTier(DcWattageTier.GE_120KW)
        advanceUntilIdle()

        assertEquals(DcWattageTier.GE_120KW, viewModel.uiState.value.selectedDcTier)
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("st_dc250", viewModel.uiState.value.top10DisplayStations.first().id)
    }

    @Test
    fun testExitDcModeResetsFilterStateAndReturnsTo3ButtonMode() = runTest(testDispatcher) {
        val stationAc = createTestStation("st_ac", "Trạm AC", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        val stationDc60 = createTestStation("st_dc60", "Trạm DC 60", 21.0287, 105.8544, listOf(60_000L to "DC 60kW"))

        fakeRepository.nearbyResult = Result.success(listOf(stationAc, stationDc60))
        val viewModel = createViewModel()
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // Enter DC mode and select tier
        viewModel.enterDcMode()
        viewModel.selectDcTier(DcWattageTier.BETWEEN_30_60KW)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)

        // Exit DC Mode
        viewModel.exitDcMode()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDcSubFilterVisible)
        assertNull(viewModel.uiState.value.selectedDcTier)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
        assertNull(smartFilterPreferences.getSelectedDcTier())
        // Restores unfiltered stations
        assertEquals(2, viewModel.uiState.value.top10DisplayStations.size)
    }

    // =========================================================================
    // Part 5: Custom Filter Flow & Config Prompt Modal Triggers
    // =========================================================================

    @Test
    fun testApplyCustomFilterWithoutSavedConfigShowsPromptDialog() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        assertFalse(smartFilterPreferences.hasCustomConfig())
        assertFalse(viewModel.uiState.value.showCustomConfigPrompt)

        // Tapping Custom with no configuration saved
        viewModel.applyCustomFilter()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCustomConfigPrompt)
        // Does not enter CUSTOM mode
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)

        // User dismisses dialog prompt
        viewModel.dismissCustomPrompt()
        assertFalse(viewModel.uiState.value.showCustomConfigPrompt)
    }

    @Test
    fun testApplyCustomFilterWithSavedConfigExecutesPipeline() = runTest(testDispatcher) {
        val customConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 100,
            maxKw = 300
        )
        smartFilterPreferences.saveCustomConfig(customConfig)

        val stationAc = createTestStation("st_ac", "Trạm AC", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        val stationDc60 = createTestStation("st_dc60", "Trạm DC 60", 21.0287, 105.8544, listOf(60_000L to "DC 60kW"))
        val stationDc250 = createTestStation("st_dc250", "Trạm DC 250", 21.0288, 105.8545, listOf(250_000L to "DC 250kW"))

        fakeRepository.nearbyResult = Result.success(listOf(stationAc, stationDc60, stationDc250))
        val viewModel = createViewModel()
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)

        // Tap Custom filter
        viewModel.applyCustomFilter()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showCustomConfigPrompt)
        assertEquals(SmartFilterMode.CUSTOM, viewModel.uiState.value.activeFilterMode)
        assertEquals(customConfig, viewModel.uiState.value.savedCustomConfig)
        assertEquals(SmartFilterMode.CUSTOM, smartFilterPreferences.getActiveFilterMode())

        // Only st_dc250 matches 100..300 kW
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("st_dc250", viewModel.uiState.value.top10DisplayStations.first().id)
    }

    @Test
    fun testSaveAndApplyCustomFilterSavesAndImmediatelyActivatesPipeline() = runTest(testDispatcher) {
        val stationAc = createTestStation("st_ac", "Trạm AC", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        val stationDc30 = createTestStation("st_dc30", "Trạm DC 30", 21.0287, 105.8544, listOf(30_000L to "DC 30kW"))
        val stationDc180 = createTestStation("st_dc180", "Trạm DC 180", 21.0288, 105.8545, listOf(180_000L to "DC 180kW"))

        fakeRepository.nearbyResult = Result.success(listOf(stationAc, stationDc30, stationDc180))
        val viewModel = createViewModel()
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)

        val newConfig = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.DC_LE_30KW
        )

        // Save & apply returned from Settings modal
        viewModel.saveAndApplyCustomFilter(newConfig)
        advanceUntilIdle()

        assertTrue(smartFilterPreferences.hasCustomConfig())
        assertEquals(newConfig, smartFilterPreferences.getCustomConfig())
        assertEquals(SmartFilterMode.CUSTOM, viewModel.uiState.value.activeFilterMode)
        assertEquals(newConfig, viewModel.uiState.value.savedCustomConfig)
        assertFalse(viewModel.uiState.value.isDcSubFilterVisible)

        // Only st_dc30 matches DC_LE_30KW
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals("st_dc30", viewModel.uiState.value.top10DisplayStations.first().id)
    }

    // =========================================================================
    // Part 6: Clear Filter & Dynamic Summary Pill Text Verification
    // =========================================================================

    @Test
    fun testClearSmartFilterResetsToNoneAndRefreshesPipeline() = runTest(testDispatcher) {
        val stationAc = createTestStation("st_ac", "Trạm AC", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        val stationDc = createTestStation("st_dc", "Trạm DC", 21.0287, 105.8544, listOf(60_000L to "DC 60kW"))

        fakeRepository.nearbyResult = Result.success(listOf(stationAc, stationDc))
        val viewModel = createViewModel()
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // Set AC filter
        viewModel.toggleAcFilter()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.top10DisplayStations.size)

        // Clear smart filter
        viewModel.clearSmartFilter()
        advanceUntilIdle()

        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
        assertNull(viewModel.uiState.value.selectedDcTier)
        assertFalse(viewModel.uiState.value.isDcSubFilterVisible)
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
        assertEquals(2, viewModel.uiState.value.top10DisplayStations.size)
    }

    @Test
    fun testFilterSummaryPillTextDynamicallyReflectsCountsAndFilterDescriptions() = runTest(testDispatcher) {
        val stationAc1 = createTestStation("st_ac1", "Trạm AC 1", 21.0286, 105.8543, listOf(11_000L to "AC 11kW"))
        val stationAc2 = createTestStation("st_ac2", "Trạm AC 2", 21.0287, 105.8544, listOf(22_000L to "AC 22kW"))
        val stationDc60 = createTestStation("st_dc60", "Trạm DC 60", 21.0288, 105.8545, listOf(60_000L to "DC 60kW"))

        fakeRepository.nearbyResult = Result.success(listOf(stationAc1, stationAc2, stationDc60))
        val viewModel = createViewModel()
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // 1. NONE mode
        assertEquals(
            "Top 10 trạm sạc VinFast gần nhất còn cổng trống",
            viewModel.uiState.value.filterSummaryPillText
        )

        // 2. AC mode (2 matching stations)
        viewModel.toggleAcFilter()
        advanceUntilIdle()
        assertEquals(
            "Tìm thấy 2 trạm có cổng AC khả dụng",
            viewModel.uiState.value.filterSummaryPillText
        )

        // 3. DC mode without tier selected yet
        viewModel.enterDcMode()
        advanceUntilIdle()
        assertEquals(
            "Top 10 trạm sạc VinFast gần nhất còn cổng trống",
            viewModel.uiState.value.filterSummaryPillText
        )

        // 4. DC mode with GE_60KW tier (1 matching station)
        viewModel.selectDcTier(DcWattageTier.GE_60KW)
        advanceUntilIdle()
        assertEquals(
            "Tìm thấy 1 trạm có cổng DC ≥ 60kW khả dụng",
            viewModel.uiState.value.filterSummaryPillText
        )

        // 5. Custom mode (with quick chip matching AC -> 2 stations)
        val acCustomConfig = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.AC
        )
        viewModel.saveAndApplyCustomFilter(acCustomConfig)
        advanceUntilIdle()
        assertEquals(
            "Tìm thấy 2 trạm theo bộ lọc tùy chỉnh",
            viewModel.uiState.value.filterSummaryPillText
        )
    }

    @Test
    fun testProvideFactoryWiredWithSmartFilterPreferences() {
        val factory = NearbyViewModel.provideFactory(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            smartFilterPreferences = smartFilterPreferences,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        val createdVm = factory.create(NearbyViewModel::class.java)
        assertNotNull(createdVm)
    }
}
