package com.evcs.favorites.data.routing

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.screens.RouteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01: Minimum Power Criteria & Starting SoC Settings Integration.
 *
 * Verifies:
 * 1. Default EvRoutingSettings initializes with minChargerPowerKw = 60.0 and presets match infrastructure.
 * 2. Out-of-bound power values (< 20.0 kW or > 250.0 kW) clamp safely during sanitized() and createSanitized().
 * 3. RoutingPreferencesManager correctly reads, writes, persists, and emits updated minChargerPowerKw.
 * 4. RouteViewModel initializes and updates RouteUiState.minChargerPowerKw reactively.
 * 5. Invalidation of stale routePlan occurs when minChargerPowerKw, startBatteryPercent, or safeRangeKm is modified.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvRoutingMinPowerSettingsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var storage: InMemorySessionStorage
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var locationsRepository: VietnamLocationsRepository
    private lateinit var evSmartRoutePlanner: EvSmartRoutePlanner
    private lateinit var evcsRepository: EvcsRepository
    private lateinit var viewModel: RouteViewModel

    private class FakeRoutingCoordinator(
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
        powerKw: Double
    ): Station {
        val watts = (powerKw * 1000).toLong()
        val port = PowerPort(
            typeWatts = watts,
            label = "${powerKw.toInt()}kW",
            availablePlugs = 2,
            totalPlugs = 4,
            displayString = "${powerKw.toInt()}kW: trống 2/4"
        )
        return Station(
            id = id,
            name = name,
            address = "QL1A, $name",
            latitude = lat,
            longitude = lng,
            summary = "Trạm sạc VinFast $powerKw kW",
            connectors = "${powerKw.toInt()}kW",
            depotStatus = "Normal",
            powers = listOf(port),
            totalPlugs = 4,
            totalAvailablePlugs = 2
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        storage = InMemorySessionStorage()
        prefsManager = RoutingPreferencesManager(
            storage = storage,
            ioDispatcher = testDispatcher
        )

        locationsRepository = VietnamLocationsRepository()
        evSmartRoutePlanner = EvSmartRoutePlanner(
            coordinator = FakeRoutingCoordinator(testDispatcher)
        )

        val sessionManager = SessionManager(storage)
        evcsRepository = EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = storage
        )

        viewModel = RouteViewModel(
            locationsRepository = locationsRepository,
            evSmartRoutePlanner = evSmartRoutePlanner,
            evcsRepository = evcsRepository,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testDefaultSettingsAndPresets_matchRequiredSpecifications() {
        val defaultSettings = EvRoutingSettings()

        // Default minimum charger power must be 60.0 kW
        assertEquals(60.0, defaultSettings.minChargerPowerKw, 0.001)
        assertEquals(60.0, EvRoutingSettings.DEFAULT_MIN_CHARGER_POWER_KW, 0.001)

        // Validation bounds
        assertEquals(20.0, EvRoutingSettings.MIN_CHARGER_POWER_KW, 0.001)
        assertEquals(250.0, EvRoutingSettings.MAX_CHARGER_POWER_KW, 0.001)

        // Predefined power presets matching Vietnamese infrastructure
        assertEquals(30.0, EvRoutingSettings.PRESET_POWER_STANDARD, 0.001)
        assertEquals(60.0, EvRoutingSettings.PRESET_POWER_FAST, 0.001)
        assertEquals(150.0, EvRoutingSettings.PRESET_POWER_ULTRA, 0.001)
        assertEquals(250.0, EvRoutingSettings.PRESET_POWER_SUPER, 0.001)

        // Preferences manager default matches
        assertEquals(60.0, prefsManager.evSettings.value.minChargerPowerKw, 0.001)
    }

    @Test
    fun testBoundaryClampingAndSanitization_enforcesPowerLimits() {
        // Underflow: < 20.0 kW clamped to 20.0 kW
        val underflow = EvRoutingSettings(minChargerPowerKw = 5.0).sanitized()
        assertEquals(20.0, underflow.minChargerPowerKw, 0.001)

        // Overflow: > 250.0 kW clamped to 250.0 kW
        val overflow = EvRoutingSettings(minChargerPowerKw = 500.0).sanitize()
        assertEquals(250.0, overflow.minChargerPowerKw, 0.001)

        // In-bounds: 150.0 kW preserved
        val valid = EvRoutingSettings(minChargerPowerKw = 150.0).sanitized()
        assertEquals(150.0, valid.minChargerPowerKw, 0.001)

        // Factory createSanitized clamps as well
        val factoryClamped = EvRoutingSettings.createSanitized(minChargerPowerKw = 10.0)
        assertEquals(20.0, factoryClamped.minChargerPowerKw, 0.001)
    }

    @Test
    fun testRoutingPreferencesManager_persistenceAndReactiveEmission() = runTest {
        // 1. Initial state
        assertEquals(60.0, prefsManager.evSettings.value.minChargerPowerKw, 0.001)

        // 2. Update via convenience updater
        prefsManager.updateMinChargerPowerKw(150.0)
        assertEquals(150.0, prefsManager.evSettings.value.minChargerPowerKw, 0.001)
        assertEquals(150.0, prefsManager.settings.value.evSettings.minChargerPowerKw, 0.001)

        // 3. Storage key verification
        assertEquals("150.0", storage.getString(RoutingPreferencesManager.KEY_EV_MIN_CHARGER_POWER_KW))

        // 4. Clamping on convenience updater
        prefsManager.updateMinChargerPowerKw(10.0)
        assertEquals(20.0, prefsManager.evSettings.value.minChargerPowerKw, 0.001)

        prefsManager.updateMinChargerPowerKw(300.0)
        assertEquals(250.0, prefsManager.evSettings.value.minChargerPowerKw, 0.001)

        // 5. Cold load recovery across instance
        val coldManager = RoutingPreferencesManager(
            storage = storage,
            ioDispatcher = testDispatcher
        )
        val loadedSettings = coldManager.loadSettings()
        assertEquals(250.0, loadedSettings.evSettings.minChargerPowerKw, 0.001)
        assertEquals(250.0, coldManager.evSettings.value.minChargerPowerKw, 0.001)
    }

    @Test
    fun testRouteViewModel_updatesUiStateAndSyncsWithPreferences() = runTest {
        // RouteUiState initializes with 60.0 kW
        testScheduler.advanceUntilIdle()
        assertEquals(60.0, viewModel.uiState.value.minChargerPowerKw, 0.001)

        // Calling onMinPowerChanged updates UI State and persists to manager
        viewModel.onMinPowerChanged(150.0)
        testScheduler.advanceUntilIdle()

        assertEquals(150.0, viewModel.uiState.value.minChargerPowerKw, 0.001)
        assertEquals(150.0, prefsManager.evSettings.value.minChargerPowerKw, 0.001)

        // Clamping in ViewModel
        viewModel.onMinPowerChanged(10.0)
        testScheduler.advanceUntilIdle()
        assertEquals(20.0, viewModel.uiState.value.minChargerPowerKw, 0.001)

        viewModel.onMinPowerChanged(350.0)
        testScheduler.advanceUntilIdle()
        assertEquals(250.0, viewModel.uiState.value.minChargerPowerKw, 0.001)

        // External preference change propagates reactively to ViewModel
        prefsManager.updateMinChargerPowerKw(30.0)
        testScheduler.advanceUntilIdle()
        assertEquals(30.0, viewModel.uiState.value.minChargerPowerKw, 0.001)
    }

    @Test
    fun testRoutePlanInvalidation_whenSettingsChange() = runTest {
        testScheduler.advanceUntilIdle()
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        val st1 = createTestStation(
            "st_1", "Trạm 1",
            origCoord.lat + (destCoord.lat - origCoord.lat) * 0.25,
            origCoord.lng + (destCoord.lng - origCoord.lng) * 0.25,
            120.0
        )
        viewModel.setCandidateStations(listOf(st1))

        // Plan initial route
        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        assertNotNull("Route plan should be populated", viewModel.uiState.value.routePlan)

        // 1. Changing minChargerPowerKw invalidates stale routePlan
        viewModel.onMinPowerChanged(150.0)
        assertNull("routePlan must be cleared when minChargerPowerKw changes", viewModel.uiState.value.routePlan)

        // Re-plan
        viewModel.planRoute()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.routePlan)

        // 2. Changing startBatteryPercent invalidates stale routePlan
        viewModel.onStartBatteryPercentChanged(80)
        assertNull("routePlan must be cleared when startBatteryPercent changes", viewModel.uiState.value.routePlan)

        // Re-plan
        viewModel.planRoute()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.routePlan)

        // 3. Changing safeRangeKm invalidates stale routePlan
        viewModel.onSafeRangeChanged(350)
        assertNull("routePlan must be cleared when safeRangeKm changes", viewModel.uiState.value.routePlan)
    }
}
