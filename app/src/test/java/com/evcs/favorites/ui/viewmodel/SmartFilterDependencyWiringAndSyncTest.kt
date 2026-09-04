package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
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
 * Comprehensive verification test for Phase 01:
 * Storage Wiring & ViewModel Filter Synchronization.
 *
 * Verifies:
 * 1. provideFactory correctly assigns and wires the supplied SmartFilterPreferences.
 * 2. Mode changes (AC, DC, CUSTOM) persist directly to the underlying SessionStorage.
 * 3. Defensive sanitization: persisted DC without tier or CUSTOM without config resets cleanly to SmartFilterMode.NONE.
 * 4. clearSmartFilter() clears active smart mode, selected DC tier, and legacy wattage preferences simultaneously.
 * 5. Legacy purge: activating smart filters purges legacy selectedWattages and calls filterPrefs.clear().
 * 6. Precedence rule: active smart filter takes priority on cold-start and suppresses legacy wattage chip overrides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartFilterDependencyWiringAndSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var legacyStorage: InMemorySessionStorage
    private lateinit var smartFilterPreferences: SmartFilterPreferences
    private lateinit var legacyFilterPreferences: NearbyFilterPreferences
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

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powers: List<Pair<Long, String>>
    ): Station {
        val powerPorts = powers.map { (w, lbl) ->
            PowerPort(
                typeWatts = w,
                label = lbl,
                availablePlugs = 2,
                totalPlugs = 2,
                displayString = "$lbl: trống 2/2"
            )
        }
        return Station(
            id = id,
            name = name,
            address = "Address $id",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = "CCS2",
            depotStatus = "Normal",
            powers = powerPorts,
            totalAvailablePlugs = 2,
            totalPlugs = 2
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = InMemorySessionStorage()
        legacyStorage = InMemorySessionStorage()
        smartFilterPreferences = SmartFilterPreferences(storage)
        legacyFilterPreferences = NearbyFilterPreferences(legacyStorage)
        sessionManager = SessionManager(storage)
        fakeRepository = FakeEvcsRepository(storage)
        fakeLocationService = FakeLocationService()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        smartPrefs: SmartFilterPreferences = smartFilterPreferences,
        legacyPrefs: NearbyFilterPreferences = legacyFilterPreferences
    ): NearbyViewModel {
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = legacyPrefs,
            smartFilterPreferences = smartPrefs,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun testProvideFactoryWiresSuppliedSmartFilterPreferencesAndPersistsModes() = runTest(testDispatcher) {
        val customStorage = InMemorySessionStorage()
        val customSmartPrefs = SmartFilterPreferences(customStorage)
        val customLegacyStorage = InMemorySessionStorage()
        val customLegacyPrefs = NearbyFilterPreferences(customLegacyStorage)

        val factory = NearbyViewModel.provideFactory(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = customLegacyPrefs,
            smartFilterPreferences = customSmartPrefs,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val vm = factory.create(NearbyViewModel::class.java)

        // Verify initial state is NONE
        assertEquals(SmartFilterMode.NONE, vm.uiState.value.activeFilterMode)

        // 1. Toggle AC
        vm.toggleAcFilter()
        advanceUntilIdle()
        assertEquals(SmartFilterMode.AC, vm.uiState.value.activeFilterMode)
        assertEquals("AC", customStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))

        // 2. Select DC Tier
        vm.selectDcTier(DcWattageTier.GE_120KW)
        advanceUntilIdle()
        assertEquals(SmartFilterMode.DC, vm.uiState.value.activeFilterMode)
        assertEquals(DcWattageTier.GE_120KW, vm.uiState.value.selectedDcTier)
        assertEquals("DC", customStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))
        assertEquals("GE_120KW", customStorage.getString(SmartFilterPreferences.KEY_SELECTED_DC_TIER))

        // 3. Save and apply CUSTOM config
        val customConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 150,
            maxKw = 250
        )
        vm.saveAndApplyCustomFilter(customConfig)
        advanceUntilIdle()
        assertEquals(SmartFilterMode.CUSTOM, vm.uiState.value.activeFilterMode)
        assertEquals("CUSTOM", customStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))
        assertNull(customStorage.getString(SmartFilterPreferences.KEY_SELECTED_DC_TIER))
        assertTrue(customSmartPrefs.hasCustomConfig())
    }

    @Test
    fun testDefensiveColdStartSanitizationForIncompleteDcOrCustom() = runTest(testDispatcher) {
        // Case A: Persisted DC mode without a selected tier (corrupted / incomplete)
        val dcStorage = InMemorySessionStorage()
        dcStorage.putString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE, "DC")
        // No DC tier set!
        val dcPrefs = SmartFilterPreferences(dcStorage)

        val vmDc = createViewModel(smartPrefs = dcPrefs)
        assertEquals(SmartFilterMode.NONE, vmDc.uiState.value.activeFilterMode)
        assertFalse(vmDc.uiState.value.isDcSubFilterVisible)
        assertNull(vmDc.uiState.value.selectedDcTier)
        // Storage should also be sanitized
        assertEquals(SmartFilterMode.NONE, dcPrefs.getActiveFilterMode())

        // Case B: Persisted CUSTOM mode without config (corrupted / incomplete)
        val customStorage = InMemorySessionStorage()
        customStorage.putString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE, "CUSTOM")
        // No custom config set!
        val customPrefs = SmartFilterPreferences(customStorage)

        val vmCustom = createViewModel(smartPrefs = customPrefs)
        assertEquals(SmartFilterMode.NONE, vmCustom.uiState.value.activeFilterMode)
        assertNull(vmCustom.uiState.value.savedCustomConfig)
        assertEquals(SmartFilterMode.NONE, customPrefs.getActiveFilterMode())
    }

    @Test
    fun testLegacyWattagePurgeOnSmartFilterActivation() = runTest(testDispatcher) {
        // Setup initial state with legacy wattage chips persisted
        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_250, WattageOption.KW_60))
        val vm = createViewModel()

        assertEquals(setOf(WattageOption.KW_250, WattageOption.KW_60), vm.uiState.value.selectedWattages)
        assertEquals(2, legacyFilterPreferences.getSelectedWattages().size)

        // 1. Activating AC mode must purge legacy wattages atomically
        vm.toggleAcFilter()
        advanceUntilIdle()
        assertEquals(SmartFilterMode.AC, vm.uiState.value.activeFilterMode)
        assertTrue("UI state selectedWattages must be empty", vm.uiState.value.selectedWattages.isEmpty())
        assertTrue("Legacy preferences storage must be cleared", legacyFilterPreferences.getSelectedWattages().isEmpty())

        // Repopulate legacy storage to test selectDcTier
        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_30))
        vm.selectDcTier(DcWattageTier.GE_60KW)
        advanceUntilIdle()
        assertEquals(SmartFilterMode.DC, vm.uiState.value.activeFilterMode)
        assertTrue(vm.uiState.value.selectedWattages.isEmpty())
        assertTrue(legacyFilterPreferences.getSelectedWattages().isEmpty())

        // Repopulate legacy storage to test saveAndApplyCustomFilter
        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_11))
        val config = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.DC_GE_60KW
        )
        vm.saveAndApplyCustomFilter(config)
        advanceUntilIdle()
        assertEquals(SmartFilterMode.CUSTOM, vm.uiState.value.activeFilterMode)
        assertTrue(vm.uiState.value.selectedWattages.isEmpty())
        assertTrue(legacyFilterPreferences.getSelectedWattages().isEmpty())
    }

    @Test
    fun testClearSmartFilterClearsBothSmartAndLegacyFilters() = runTest(testDispatcher) {
        val vm = createViewModel()

        // Set DC tier and populate legacy storage
        vm.selectDcTier(DcWattageTier.BETWEEN_30_60KW)
        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_180))
        advanceUntilIdle()

        assertEquals(SmartFilterMode.DC, vm.uiState.value.activeFilterMode)
        assertEquals(DcWattageTier.BETWEEN_30_60KW, vm.uiState.value.selectedDcTier)
        assertTrue(vm.uiState.value.isDcSubFilterVisible)

        // Clear smart filter
        vm.clearSmartFilter()
        advanceUntilIdle()

        // Verify UI state
        assertEquals(SmartFilterMode.NONE, vm.uiState.value.activeFilterMode)
        assertNull(vm.uiState.value.selectedDcTier)
        assertFalse(vm.uiState.value.isDcSubFilterVisible)
        assertTrue(vm.uiState.value.selectedWattages.isEmpty())

        // Verify storage
        assertEquals(SmartFilterMode.NONE, smartFilterPreferences.getActiveFilterMode())
        assertNull(smartFilterPreferences.getSelectedDcTier())
        assertTrue(legacyFilterPreferences.getSelectedWattages().isEmpty())
    }

    @Test
    fun testInitializationPrecedenceSuppressesLegacyWattagesWhenSmartFilterActive() = runTest(testDispatcher) {
        // Pre-populate both legacy wattages and a valid smart filter mode (AC)
        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_250))
        smartFilterPreferences.saveActiveFilterMode(SmartFilterMode.AC)

        val vm = createViewModel()

        // Active smart filter takes precedence: legacy wattage chips must be suppressed and cleared
        assertEquals(SmartFilterMode.AC, vm.uiState.value.activeFilterMode)
        assertTrue("selectedWattages must be empty on cold start when smart filter is active", vm.uiState.value.selectedWattages.isEmpty())
        assertTrue("filterPrefs must be purged on cold start when smart filter is active", legacyFilterPreferences.getSelectedWattages().isEmpty())
    }

    @Test
    fun testExitDcModeAndToggleAcOffPurgeLegacyWattages() = runTest(testDispatcher) {
        val vm = createViewModel()

        // 1. Enter DC mode, then exit DC mode -> verify selectedWattages remains empty and legacy cleared
        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_250))
        vm.enterDcMode()
        assertTrue(vm.uiState.value.selectedWattages.isEmpty())
        assertTrue(legacyFilterPreferences.getSelectedWattages().isEmpty())

        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_60))
        vm.exitDcMode()
        advanceUntilIdle()
        assertEquals(SmartFilterMode.NONE, vm.uiState.value.activeFilterMode)
        assertTrue(vm.uiState.value.selectedWattages.isEmpty())
        assertTrue(legacyFilterPreferences.getSelectedWattages().isEmpty())

        // 2. Toggle AC on, then toggle AC off -> returning to NONE does not reactivate legacy wattage chips
        vm.toggleAcFilter() // ON -> AC
        advanceUntilIdle()
        assertEquals(SmartFilterMode.AC, vm.uiState.value.activeFilterMode)

        legacyFilterPreferences.saveSelectedWattages(setOf(WattageOption.KW_11))
        vm.toggleAcFilter() // OFF -> NONE
        advanceUntilIdle()
        assertEquals(SmartFilterMode.NONE, vm.uiState.value.activeFilterMode)
        assertTrue(vm.uiState.value.selectedWattages.isEmpty())
        assertTrue(legacyFilterPreferences.getSelectedWattages().isEmpty())
    }
}
