package com.evcs.favorites.ui.screens

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.ui.components.SmartFilterUiHelper
import com.evcs.favorites.ui.state.NearbyUiState
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
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
 * Single comprehensive test file for Phase 04: Nearby Screen Smart Filter UI & Animation.
 *
 * Verifies:
 * 1. Initial render displays 3 buttons: [ 🎯 Custom ], [ ⚡ DC ], [ 🔌 AC ].
 * 2. Selecting AC marks AC button active with integrated [ ✕ ] cancel capability.
 * 3. Selecting DC triggers animated DC sub-filter with 4 power tier chips and 48dp thumb-zone back button.
 * 4. Selecting a DC tier locks selection on that tier.
 * 5. Tapping Back button exits DC mode back to 3 buttons.
 * 6. Tapping Custom with no saved configuration triggers CustomConfigPromptDialog state.
 * 7. Dynamic info pill text correctly reflects station count and active filter description.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySmartFilterUiStateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var smartFilterPrefs: SmartFilterPreferences
    private lateinit var filterPrefs: NearbyFilterPreferences
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var viewModel: NearbyViewModel

    // Fake Location Service
    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted
        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    // Fake Routing Coordinator
    class FakeRoutingCoordinator : MultiTierRoutingCoordinator()

    // Fake EVCS Repository
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

    private fun createTestStation(id: String, name: String, watts: Long): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = watts,
                label = "${watts / 1000}kW",
                availablePlugs = 2,
                totalPlugs = 2,
                displayString = "${watts / 1000}kW: 2/2"
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $id",
            latitude = 21.0,
            longitude = 105.8,
            summary = "24/7",
            connectors = "${watts / 1000}kW",
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
        sessionManager = SessionManager(sessionStorage)
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService()
        fakeCoordinator = FakeRoutingCoordinator()
        smartFilterPrefs = SmartFilterPreferences(storage = sessionStorage)
        filterPrefs = NearbyFilterPreferences(storage = sessionStorage)
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)

        viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            filterPreferences = filterPrefs,
            smartFilterPreferences = smartFilterPrefs,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Initial Render Displays 3 Buttons: [ 🎯 Custom ], [ ⚡ DC ], [ 🔌 AC ]
    // =========================================================================

    @Test
    fun testInitialRender_displays3ButtonsWithDistinctIconsAndLabels() = runTest {
        val uiState = viewModel.uiState.value

        // Mode and flags are initial defaults
        assertEquals(SmartFilterMode.NONE, uiState.activeFilterMode)
        assertFalse(uiState.isDcSubFilterVisible)
        assertNull(uiState.selectedDcTier)

        // UI Helper icon emojis & labels verification
        assertEquals("🎯", SmartFilterUiHelper.EMOJI_CUSTOM)
        assertEquals("Custom", SmartFilterUiHelper.CUSTOM_LABEL)

        assertEquals("⚡", SmartFilterUiHelper.EMOJI_DC)
        assertEquals("DC", SmartFilterUiHelper.DC_LABEL)

        assertEquals("🔌", SmartFilterUiHelper.EMOJI_AC)
        assertEquals("AC", SmartFilterUiHelper.AC_LABEL)

        // Custom label formatting when unconfigured or inactive
        assertEquals("Custom", SmartFilterUiHelper.formatCustomButtonLabel(null, false))
        assertEquals("Custom", SmartFilterUiHelper.formatCustomButtonLabel(null, true))

        // Integrated cancel button should NOT be visible when filter mode is NONE
        assertFalse(SmartFilterUiHelper.isCancelButtonVisible(SmartFilterMode.NONE))
    }

    // =========================================================================
    // 2. Selecting AC marks AC button active with integrated [ ✕ ] cancel capability
    // =========================================================================

    @Test
    fun testSelectingAc_marksAcActiveWithIntegratedCancelCapability() = runTest {
        // Toggle AC mode on
        viewModel.toggleAcFilter()

        val activeState = viewModel.uiState.value
        assertEquals(SmartFilterMode.AC, activeState.activeFilterMode)
        assertFalse(activeState.isDcSubFilterVisible)
        assertNull(activeState.selectedDcTier)

        // Integrated cancel [✕] is visible for AC
        assertTrue(SmartFilterUiHelper.isCancelButtonVisible(SmartFilterMode.AC))

        // Tapping active AC button again resets to NONE
        viewModel.toggleAcFilter()
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)

        // Tapping integrated [✕] cancel (calls clearSmartFilter) also resets to NONE
        viewModel.toggleAcFilter()
        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)
        viewModel.clearSmartFilter()
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
    }

    // =========================================================================
    // 3. Selecting DC triggers animated DC sub-filter with 4 power tier chips
    //    and 48dp thumb-zone back button
    // =========================================================================

    @Test
    fun testSelectingDc_triggersAnimatedDcSubFilterWith4TierChipsAndThumbBackButton() = runTest {
        viewModel.enterDcMode()

        val dcState = viewModel.uiState.value
        assertTrue("isDcSubFilterVisible must be true when entering DC mode", dcState.isDcSubFilterVisible)
        assertEquals(SmartFilterMode.DC, dcState.activeFilterMode)
        assertNull("selectedDcTier must be null initially upon entering DC mode", dcState.selectedDcTier)

        // Thumb-zone back button constant
        assertEquals("Quay lại", SmartFilterUiHelper.BACK_LABEL)

        // Verify the 4 DC power tier chips
        val tiers = DcWattageTier.entries
        assertEquals(4, tiers.size)
        assertEquals(DcWattageTier.LE_30KW, tiers[0])
        assertEquals("≤ 30kW", tiers[0].label)
        assertEquals(DcWattageTier.BETWEEN_30_60KW, tiers[1])
        assertEquals("30 - 60kW", tiers[1].label)
        assertEquals(DcWattageTier.GE_60KW, tiers[2])
        assertEquals("≥ 60kW", tiers[2].label)
        assertEquals(DcWattageTier.GE_120KW, tiers[3])
        assertEquals("≥ 120kW", tiers[3].label)
    }

    // =========================================================================
    // 4. Selecting a DC tier locks selection on that tier
    // =========================================================================

    @Test
    fun testSelectingDcTier_locksSelectionOnThatTier() = runTest {
        viewModel.enterDcMode()

        // Select GE_60KW
        viewModel.selectDcTier(DcWattageTier.GE_60KW)
        assertEquals(DcWattageTier.GE_60KW, viewModel.uiState.value.selectedDcTier)
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)
        assertTrue(viewModel.uiState.value.isDcSubFilterVisible)

        // Tapping the same chip preserves selection (does not toggle off)
        viewModel.selectDcTier(DcWattageTier.GE_60KW)
        assertEquals(DcWattageTier.GE_60KW, viewModel.uiState.value.selectedDcTier)

        // Selecting a different tier switches selection
        viewModel.selectDcTier(DcWattageTier.GE_120KW)
        assertEquals(DcWattageTier.GE_120KW, viewModel.uiState.value.selectedDcTier)
    }

    // =========================================================================
    // 5. Tapping Back button exits DC mode back to 3 buttons
    // =========================================================================

    @Test
    fun testTappingBackButton_exitsDcModeBackTo3Buttons() = runTest {
        viewModel.enterDcMode()
        viewModel.selectDcTier(DcWattageTier.BETWEEN_30_60KW)
        assertTrue(viewModel.uiState.value.isDcSubFilterVisible)
        assertNotNull(viewModel.uiState.value.selectedDcTier)

        // User taps [ ← Quay lại ]
        viewModel.exitDcMode()

        val exitedState = viewModel.uiState.value
        assertFalse("isDcSubFilterVisible must be false after exitDcMode", exitedState.isDcSubFilterVisible)
        assertNull("selectedDcTier must be reset to null after exitDcMode", exitedState.selectedDcTier)
        assertEquals("activeFilterMode must be reset to NONE", SmartFilterMode.NONE, exitedState.activeFilterMode)
    }

    // =========================================================================
    // 6. Tapping Custom with no saved configuration triggers CustomConfigPromptDialog state
    // =========================================================================

    @Test
    fun testTappingCustom_withNoSavedConfig_triggersPromptDialog_andSavesConfigProperly() = runTest {
        // Initially no custom configuration
        assertFalse(smartFilterPrefs.hasCustomConfig())

        // Tapping Custom triggers prompt dialog
        viewModel.applyCustomFilter()
        assertTrue("showCustomConfigPrompt must be true", viewModel.uiState.value.showCustomConfigPrompt)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)

        // Dismissing prompt dialog resets showCustomConfigPrompt
        viewModel.dismissCustomPrompt()
        assertFalse("showCustomConfigPrompt must be false after dismiss", viewModel.uiState.value.showCustomConfigPrompt)

        // When user configures and saves custom filter
        val customConfig = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.DC_GE_60KW
        )
        viewModel.saveAndApplyCustomFilter(customConfig)

        val customState = viewModel.uiState.value
        assertEquals(SmartFilterMode.CUSTOM, customState.activeFilterMode)
        assertFalse(customState.showCustomConfigPrompt)
        assertEquals(customConfig, customState.savedCustomConfig)

        // Custom button badge formatting
        val badge = SmartFilterUiHelper.formatCustomButtonLabel(customConfig, isActive = true)
        assertEquals("Custom • ≥60kW", badge)

        // Cancel button is visible for Custom
        assertTrue(SmartFilterUiHelper.isCancelButtonVisible(SmartFilterMode.CUSTOM))

        // Custom range badge formatting test
        val rangeConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 60,
            maxKw = 150
        )
        val rangeBadge = SmartFilterUiHelper.formatCustomButtonLabel(rangeConfig, isActive = true)
        assertEquals("Custom • 60-150kW", rangeBadge)

        val minOnlyConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 80
        )
        assertEquals("Custom • ≥80kW", SmartFilterUiHelper.formatCustomButtonLabel(minOnlyConfig, isActive = true))

        val maxOnlyConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            maxKw = 30
        )
        assertEquals("Custom • ≤30kW", SmartFilterUiHelper.formatCustomButtonLabel(maxOnlyConfig, isActive = true))

        // Clear filter resets back to NONE
        viewModel.clearSmartFilter()
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)
    }

    // =========================================================================
    // 7. Dynamic Info Pill text correctly reflects station count and active filter description
    // =========================================================================

    @Test
    fun testDynamicInfoPillText_reflectsStationCountAndActiveFilterDescription() {
        val station1 = createTestStation("st1", "Trạm 1", 60_000L)
        val station2 = createTestStation("st2", "Trạm 2", 120_000L)
        val station3 = createTestStation("st3", "Trạm 3", 22_000L)

        // Mode 1: NONE
        val noneState = NearbyUiState(
            activeFilterMode = SmartFilterMode.NONE,
            top10DisplayStations = listOf(station1, station2)
        )
        assertEquals(
            "Top 10 trạm sạc VinFast gần nhất còn cổng trống",
            noneState.filterSummaryPillText
        )

        // Mode 2: AC
        val acState = NearbyUiState(
            activeFilterMode = SmartFilterMode.AC,
            top10DisplayStations = listOf(station3)
        )
        assertEquals(
            "Tìm thấy 1 trạm có cổng AC khả dụng",
            acState.filterSummaryPillText
        )

        // Mode 3: DC with selected tier
        val dcStateWithTier = NearbyUiState(
            activeFilterMode = SmartFilterMode.DC,
            selectedDcTier = DcWattageTier.GE_60KW,
            top10DisplayStations = listOf(station1, station2)
        )
        assertEquals(
            "Tìm thấy 2 trạm có cổng DC ≥ 60kW khả dụng",
            dcStateWithTier.filterSummaryPillText
        )

        // Mode 4: DC without tier
        val dcStateNoTier = NearbyUiState(
            activeFilterMode = SmartFilterMode.DC,
            selectedDcTier = null,
            top10DisplayStations = listOf(station1)
        )
        assertEquals(
            "Top 10 trạm sạc VinFast gần nhất còn cổng trống",
            dcStateNoTier.filterSummaryPillText
        )

        // Mode 5: CUSTOM
        val customState = NearbyUiState(
            activeFilterMode = SmartFilterMode.CUSTOM,
            top10DisplayStations = listOf(station1, station2, station3)
        )
        assertEquals(
            "Tìm thấy 3 trạm theo bộ lọc tùy chỉnh",
            customState.filterSummaryPillText
        )
    }
}
