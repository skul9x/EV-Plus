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
 * Phase 02: Cold Start Restoration & Cross-Session Lifecycle Verification.
 *
 * Verifies the complete end-to-end cold-start lifecycle across application restarts:
 * 1. Session 1 -> 2: AC filter persistence, cold restoration, and instant pipeline filtering.
 * 2. Session 2 -> 3: DC filter with tier persistence, sub-filter bar visibility restoration, and pipeline filtering.
 * 3. Session 3 -> 4: Custom filter configuration persistence, restoration, and pipeline filtering.
 * 4. Session 4 -> 5: Unfiltered (NONE) state restoration after clearing filters.
 * 5. Session 6: Defensive sanitization of incomplete DC state (mode=DC, tier=null) to NONE.
 * 6. Session 7: Defensive sanitization of corrupted custom configuration JSON to NONE.
 * 7. Session 8: Legacy wattage filter suppression and purge upon smart filter initialization.
 * 8. Session 9: Defensive resilience against malformed preference string values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartFilterColdStartRestorationPipelineTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sharedStorage: InMemorySessionStorage
    private lateinit var legacyStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService

    private lateinit var stationAcOnly: Station
    private lateinit var stationDc30: Station
    private lateinit var stationDc60: Station
    private lateinit var stationDc150: Station
    private lateinit var stationDc250: Station
    private lateinit var stationMixedAcDc: Station
    private lateinit var stationMaintaining: Station
    private lateinit var stationOutOfService: Station

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

    private fun createStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powers: List<Pair<Long, String>>,
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
        sharedStorage = InMemorySessionStorage()
        legacyStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sharedStorage)
        fakeRepository = FakeEvcsRepository(sharedStorage)

        // User coordinates: 21.0285, 105.8542 (Hanoi center)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }

        stationAcOnly = createStation(
            id = "st-ac",
            name = "Trạm AC Vincom",
            lat = 21.0290,
            lon = 105.8545,
            powers = listOf(11_000L to "AC 11kW", 22_000L to "AC 22kW")
        )

        stationDc30 = createStation(
            id = "st-dc-30",
            name = "Trạm DC 30kW Times City",
            lat = 21.0295,
            lon = 105.8550,
            powers = listOf(30_000L to "DC 30kW")
        )

        stationDc60 = createStation(
            id = "st-dc-60",
            name = "Trạm DC 60kW Royal City",
            lat = 21.0300,
            lon = 105.8555,
            powers = listOf(60_000L to "DC 60kW")
        )

        stationDc150 = createStation(
            id = "st-dc-150",
            name = "Trạm DC 150kW Landmark",
            lat = 21.0305,
            lon = 105.8560,
            powers = listOf(150_000L to "DC 150kW")
        )

        stationDc250 = createStation(
            id = "st-dc-250",
            name = "Trạm Siêu Nhanh 250kW Thăng Long",
            lat = 21.0310,
            lon = 105.8565,
            powers = listOf(250_000L to "DC 250kW")
        )

        stationMixedAcDc = createStation(
            id = "st-mixed",
            name = "Trạm Hỗn Hợp Ocean Park",
            lat = 21.0315,
            lon = 105.8570,
            powers = listOf(11_000L to "AC 11kW", 250_000L to "DC 250kW")
        )

        stationMaintaining = createStation(
            id = "st-maint",
            name = "Trạm Bảo Trì",
            lat = 21.0286,
            lon = 105.8543,
            powers = listOf(250_000L to "DC 250kW"),
            depotStatus = "Maintaining"
        )

        stationOutOfService = createStation(
            id = "st-oos",
            name = "Trạm Ngừng Phục Vụ",
            lat = 21.0287,
            lon = 105.8544,
            powers = listOf(250_000L to "DC 250kW"),
            availablePlugs = 0,
            totalPlugs = 2,
            depotStatus = "OutOfService"
        )

        val fullStationList = listOf(
            stationAcOnly,
            stationDc30,
            stationDc60,
            stationDc150,
            stationDc250,
            stationMixedAcDc,
            stationMaintaining,
            stationOutOfService
        )
        fakeRepository.nearbyResult = Result.success(fullStationList)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModelInstance(
        smartPrefsStorage: InMemorySessionStorage = sharedStorage,
        legacyPrefsStorage: InMemorySessionStorage = legacyStorage
    ): NearbyViewModel {
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            filterPreferences = NearbyFilterPreferences(legacyPrefsStorage),
            smartFilterPreferences = SmartFilterPreferences(smartPrefsStorage),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @Test
    fun testEndToEndCrossSessionColdStartRestorationAndDefensiveLifecycle() = runTest(testDispatcher) {
        // =========================================================================
        // SESSION 1: User launches app (clean state), scans, and activates AC filter
        // =========================================================================
        var vm = createViewModelInstance()
        assertEquals(SmartFilterMode.NONE, vm.uiState.value.activeFilterMode)
        assertFalse(vm.uiState.value.isDcSubFilterVisible)
        assertNull(vm.uiState.value.selectedDcTier)

        vm.scanNearbyStations()
        advanceUntilIdle()

        // 6 valid stations (excluding maintaining and 0-available)
        assertEquals(6, vm.uiState.value.top10DisplayStations.size)
        assertEquals("Top 10 trạm sạc VinFast gần nhất còn cổng trống", vm.uiState.value.filterSummaryPillText)

        // User activates AC filter mode
        vm.toggleAcFilter()
        advanceUntilIdle()

        assertEquals(SmartFilterMode.AC, vm.uiState.value.activeFilterMode)
        assertEquals(2, vm.uiState.value.top10DisplayStations.size)
        val acIds = vm.uiState.value.top10DisplayStations.map { it.id }.toSet()
        assertEquals(setOf("st-ac", "st-mixed"), acIds)
        assertEquals("Tìm thấy 2 trạm có cổng AC khả dụng", vm.uiState.value.filterSummaryPillText)
        assertEquals("AC", sharedStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))

        // Simulate App Process Termination
        vm = null.run { createViewModelInstance() } // Force nullification & instantiate Session 2

        // =========================================================================
        // SESSION 2: Cold Start AC Mode Restoration -> Switch to DC >= 60kW Tier
        // =========================================================================
        // 1. Verify cold start state BEFORE scan
        assertEquals(SmartFilterMode.AC, vm.uiState.value.activeFilterMode)
        assertFalse(vm.uiState.value.isDcSubFilterVisible)
        assertNull(vm.uiState.value.selectedDcTier)

        // 2. User initiates scan on cold start: pipeline immediately applies restored AC filter
        vm.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.top10DisplayStations.size)
        assertEquals(setOf("st-ac", "st-mixed"), vm.uiState.value.top10DisplayStations.map { it.id }.toSet())
        assertEquals("Tìm thấy 2 trạm có cổng AC khả dụng", vm.uiState.value.filterSummaryPillText)

        // 3. User switches to DC mode with GE_60KW tier
        vm.enterDcMode()
        vm.selectDcTier(DcWattageTier.GE_60KW)
        advanceUntilIdle()

        assertEquals(SmartFilterMode.DC, vm.uiState.value.activeFilterMode)
        assertEquals(DcWattageTier.GE_60KW, vm.uiState.value.selectedDcTier)
        assertTrue(vm.uiState.value.isDcSubFilterVisible)

        // DC >= 60kW stations: st-dc-60 (60kW), st-dc-150 (150kW), st-dc-250 (250kW), st-mixed (250kW DC)
        assertEquals(4, vm.uiState.value.top10DisplayStations.size)
        val dc60Ids = vm.uiState.value.top10DisplayStations.map { it.id }.toSet()
        assertEquals(setOf("st-dc-60", "st-dc-150", "st-dc-250", "st-mixed"), dc60Ids)
        assertEquals("Tìm thấy 4 trạm có cổng DC ≥ 60kW khả dụng", vm.uiState.value.filterSummaryPillText)

        // Verify storage persisted
        assertEquals("DC", sharedStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))
        assertEquals("GE_60KW", sharedStorage.getString(SmartFilterPreferences.KEY_SELECTED_DC_TIER))

        // Simulate App Process Termination
        vm = null.run { createViewModelInstance() } // Session 3

        // =========================================================================
        // SESSION 3: Cold Start DC Tier Restoration -> Configure Custom Filter
        // =========================================================================
        // 1. Verify cold start state BEFORE scan
        assertEquals(SmartFilterMode.DC, vm.uiState.value.activeFilterMode)
        assertEquals(DcWattageTier.GE_60KW, vm.uiState.value.selectedDcTier)
        assertTrue(vm.uiState.value.isDcSubFilterVisible)

        // 2. User initiates scan on cold start: pipeline immediately filters DC >= 60kW
        vm.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(4, vm.uiState.value.top10DisplayStations.size)
        assertEquals(setOf("st-dc-60", "st-dc-150", "st-dc-250", "st-mixed"), vm.uiState.value.top10DisplayStations.map { it.id }.toSet())
        assertEquals("Tìm thấy 4 trạm có cổng DC ≥ 60kW khả dụng", vm.uiState.value.filterSummaryPillText)

        // 3. User configures Custom filter range: minKw = 150
        val customConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 150,
            maxKw = null
        )
        vm.saveAndApplyCustomFilter(customConfig)
        advanceUntilIdle()

        assertEquals(SmartFilterMode.CUSTOM, vm.uiState.value.activeFilterMode)
        assertEquals(customConfig, vm.uiState.value.savedCustomConfig)
        assertFalse(vm.uiState.value.isDcSubFilterVisible)

        // Matching stations: st-dc-150 (150kW), st-dc-250 (250kW), st-mixed (250kW)
        assertEquals(3, vm.uiState.value.top10DisplayStations.size)
        val customIds = vm.uiState.value.top10DisplayStations.map { it.id }.toSet()
        assertEquals(setOf("st-dc-150", "st-dc-250", "st-mixed"), customIds)
        assertEquals("Tìm thấy 3 trạm theo bộ lọc tùy chỉnh", vm.uiState.value.filterSummaryPillText)

        // Verify storage persisted
        assertEquals("CUSTOM", sharedStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))
        assertTrue(sharedStorage.getString(SmartFilterPreferences.KEY_CUSTOM_CONFIG)?.contains("150") == true)

        // Simulate App Process Termination
        vm = null.run { createViewModelInstance() } // Session 4

        // =========================================================================
        // SESSION 4: Cold Start Custom Restoration -> User Clears Filter
        // =========================================================================
        // 1. Verify cold start state BEFORE scan
        assertEquals(SmartFilterMode.CUSTOM, vm.uiState.value.activeFilterMode)
        assertEquals(customConfig, vm.uiState.value.savedCustomConfig)

        // 2. User triggers scan: pipeline applies custom filter
        vm.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.top10DisplayStations.size)
        assertEquals(setOf("st-dc-150", "st-dc-250", "st-mixed"), vm.uiState.value.top10DisplayStations.map { it.id }.toSet())
        assertEquals("Tìm thấy 3 trạm theo bộ lọc tùy chỉnh", vm.uiState.value.filterSummaryPillText)

        // 3. User clears smart filter
        vm.clearSmartFilter()
        advanceUntilIdle()

        assertEquals(SmartFilterMode.NONE, vm.uiState.value.activeFilterMode)
        assertNull(vm.uiState.value.selectedDcTier)
        assertFalse(vm.uiState.value.isDcSubFilterVisible)
        assertEquals(6, vm.uiState.value.top10DisplayStations.size)
        assertEquals("Top 10 trạm sạc VinFast gần nhất còn cổng trống", vm.uiState.value.filterSummaryPillText)

        // Verify storage persisted
        assertEquals("NONE", sharedStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))
        assertNull(sharedStorage.getString(SmartFilterPreferences.KEY_SELECTED_DC_TIER))

        // Simulate App Process Termination
        vm = null.run { createViewModelInstance() } // Session 5

        // =========================================================================
        // SESSION 5: Cold Start Unfiltered (NONE) Restoration
        // =========================================================================
        assertEquals(SmartFilterMode.NONE, vm.uiState.value.activeFilterMode)
        assertNull(vm.uiState.value.selectedDcTier)
        assertFalse(vm.uiState.value.isDcSubFilterVisible)

        vm.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(6, vm.uiState.value.top10DisplayStations.size)
        assertEquals("Top 10 trạm sạc VinFast gần nhất còn cổng trống", vm.uiState.value.filterSummaryPillText)

        // =========================================================================
        // SESSION 6: Defensive Sanitization of Incomplete DC State (DC with tier=null)
        // =========================================================================
        val incompleteDcStorage = InMemorySessionStorage().apply {
            putString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE, "DC")
            // Intentionally omit KEY_SELECTED_DC_TIER
        }
        val vmDefensiveDc = createViewModelInstance(smartPrefsStorage = incompleteDcStorage)

        // Must sanitize to NONE and isDcSubFilterVisible = false
        assertEquals(SmartFilterMode.NONE, vmDefensiveDc.uiState.value.activeFilterMode)
        assertNull(vmDefensiveDc.uiState.value.selectedDcTier)
        assertFalse(vmDefensiveDc.uiState.value.isDcSubFilterVisible)
        assertEquals("NONE", incompleteDcStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))

        vmDefensiveDc.scanNearbyStations()
        advanceUntilIdle()
        assertEquals(6, vmDefensiveDc.uiState.value.top10DisplayStations.size)
        assertEquals("Top 10 trạm sạc VinFast gần nhất còn cổng trống", vmDefensiveDc.uiState.value.filterSummaryPillText)

        // =========================================================================
        // SESSION 7: Defensive Sanitization of Corrupted Custom Config JSON
        // =========================================================================
        val corruptedCustomStorage = InMemorySessionStorage().apply {
            putString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE, "CUSTOM")
            putString(SmartFilterPreferences.KEY_CUSTOM_CONFIG, "{broken_json_syntax: [invalid}")
        }
        val vmDefensiveCustom = createViewModelInstance(smartPrefsStorage = corruptedCustomStorage)

        // Must sanitize to NONE
        assertEquals(SmartFilterMode.NONE, vmDefensiveCustom.uiState.value.activeFilterMode)
        assertNull(vmDefensiveCustom.uiState.value.savedCustomConfig)
        assertEquals("NONE", corruptedCustomStorage.getString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE))

        vmDefensiveCustom.scanNearbyStations()
        advanceUntilIdle()
        assertEquals(6, vmDefensiveCustom.uiState.value.top10DisplayStations.size)
        assertEquals("Top 10 trạm sạc VinFast gần nhất còn cổng trống", vmDefensiveCustom.uiState.value.filterSummaryPillText)

        // =========================================================================
        // SESSION 8: Legacy Wattage Filter Suppression and Purge on Cold Start
        // =========================================================================
        val legacyStorageInstance = InMemorySessionStorage().apply {
            putString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES, "KW_250,KW_180")
        }
        val smartWithAcStorage = InMemorySessionStorage().apply {
            putString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE, "AC")
        }

        val vmLegacyPurge = createViewModelInstance(
            smartPrefsStorage = smartWithAcStorage,
            legacyPrefsStorage = legacyStorageInstance
        )

        // Legacy wattage preferences must be suppressed and purged
        assertEquals(SmartFilterMode.AC, vmLegacyPurge.uiState.value.activeFilterMode)
        assertTrue(vmLegacyPurge.uiState.value.selectedWattages.isEmpty())
        assertNull(legacyStorageInstance.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES))

        vmLegacyPurge.scanNearbyStations()
        advanceUntilIdle()

        // Only AC stations returned, not 250kW legacy stations
        assertEquals(2, vmLegacyPurge.uiState.value.top10DisplayStations.size)
        assertEquals(setOf("st-ac", "st-mixed"), vmLegacyPurge.uiState.value.top10DisplayStations.map { it.id }.toSet())
        assertEquals("Tìm thấy 2 trạm có cổng AC khả dụng", vmLegacyPurge.uiState.value.filterSummaryPillText)

        // =========================================================================
        // SESSION 9: Defensive Resilience Against Malformed Preference Enum Strings
        // =========================================================================
        val malformedStringsStorage = InMemorySessionStorage().apply {
            putString(SmartFilterPreferences.KEY_ACTIVE_FILTER_MODE, "UNKNOWN_MODE_XYZ")
            putString(SmartFilterPreferences.KEY_SELECTED_DC_TIER, "UNKNOWN_TIER_ABC")
        }
        val vmMalformed = createViewModelInstance(smartPrefsStorage = malformedStringsStorage)

        // Does not crash, defaults to NONE and null
        assertEquals(SmartFilterMode.NONE, vmMalformed.uiState.value.activeFilterMode)
        assertNull(vmMalformed.uiState.value.selectedDcTier)
        assertFalse(vmMalformed.uiState.value.isDcSubFilterVisible)

        vmMalformed.scanNearbyStations()
        advanceUntilIdle()
        assertEquals(6, vmMalformed.uiState.value.top10DisplayStations.size)
        assertEquals("Top 10 trạm sạc VinFast gần nhất còn cổng trống", vmMalformed.uiState.value.filterSummaryPillText)
    }
}
