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
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive test file verifying Phase 06:
 * Filter Chip Routing Debounce & Quota Protection (PERF-ROUT-01).
 *
 * Requirements verified:
 * 1. Immediate UI update: When filter chips/smart filters are toggled, UI selection states
 *    and local filtered stations update immediately without waiting for network.
 * 2. Debouncing: Rapid consecutive filter interactions within 300ms debounce window collapse
 *    into exactly 1 remote routing coordinator request for the final filter state.
 * 3. Empty filter results immediately clear loading state and cancel pending debounce jobs.
 * 4. Manual refresh / fresh scan bypasses debounce delay (debounce = false) for immediate routing.
 * 5. Configurable routingDebounceMs (e.g. 0L) dispatches immediately when configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilterRoutingDebounceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount = 0
        var lastDestinations: List<RoutingDestination> = emptyList()

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            lastDestinations = destinations
            return destinations.associate { dest ->
                dest.id to DrivingMetrics(
                    distanceMeters = 1500L,
                    durationSeconds = 120L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    class FakeLocationService : LocationService() {
        var locationToReturn: Location? = null
        override fun hasLocationPermission(): Boolean = true
        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    class FakeEvcsRepository(
        sessionStorage: InMemorySessionStorage
    ) : EvcsRepository(
        apiClient = EvcsApiClient(SessionManager(sessionStorage)),
        cacheStorage = sessionStorage
    ) {
        var stationsToReturn: List<Station> = emptyList()

        override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
            return Result.success(stationsToReturn)
        }
    }

    @Before
    fun setUp() {
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_cookie"
            phpSessionId = "sess_123"
        }
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        fakeCoordinator = FakeRoutingCoordinator()
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService().apply {
            locationToReturn = Location("gps").apply {
                latitude = 10.7769
                longitude = 106.7009
            }
        }
    }

    private fun createTestStations(): List<Station> {
        return listOf(
            Station(
                id = "station_60kw_1",
                name = "Trạm 60kW A",
                address = "123 Đường A",
                latitude = 10.7770,
                longitude = 106.7010,
                summary = "24/7",
                connectors = "60kW",
                depotStatus = "Normal",
                powers = listOf(PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 2, totalPlugs = 2)),
                totalAvailablePlugs = 2,
                totalPlugs = 2
            ),
            Station(
                id = "station_250kw_1",
                name = "Trạm 250kW B",
                address = "456 Đường B",
                latitude = 10.7780,
                longitude = 106.7020,
                summary = "24/7",
                connectors = "250kW",
                depotStatus = "Normal",
                powers = listOf(PowerPort(typeWatts = 250_000L, label = "250kW", availablePlugs = 1, totalPlugs = 2)),
                totalAvailablePlugs = 1,
                totalPlugs = 2
            ),
            Station(
                id = "station_ac_1",
                name = "Trạm AC C",
                address = "789 Đường C",
                latitude = 10.7790,
                longitude = 106.7030,
                summary = "24/7",
                connectors = "11kW",
                depotStatus = "Normal",
                powers = listOf(PowerPort(typeWatts = 11_000L, label = "11kW", availablePlugs = 4, totalPlugs = 4)),
                totalAvailablePlugs = 4,
                totalPlugs = 4
            ),
            Station(
                id = "station_combo_1",
                name = "Trạm Combo D",
                address = "101 Đường D",
                latitude = 10.7800,
                longitude = 106.7040,
                summary = "24/7",
                connectors = "60kW, 250kW",
                depotStatus = "Normal",
                powers = listOf(
                    PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 1, totalPlugs = 2),
                    PowerPort(typeWatts = 250_000L, label = "250kW", availablePlugs = 2, totalPlugs = 2)
                ),
                totalAvailablePlugs = 3,
                totalPlugs = 4
            )
        )
    }

    private fun createNearbyViewModel(
        debounceMs: Long = 300L,
        stations: List<Station> = createTestStations()
    ): NearbyViewModel {
        fakeRepository.stationsToReturn = stations
        return NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            filterPreferences = NearbyFilterPreferences(sessionStorage),
            smartFilterPreferences = SmartFilterPreferences(sessionStorage),
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            routingDebounceMs = debounceMs
        )
    }

    @Test
    fun testRapidFilterChipToggles_DebouncesRoutingToSingleCoordinatorCall() = runTest(testDispatcher) {
        val viewModel = createNearbyViewModel(debounceMs = 300L)

        // 1. Initial scan with debounce = false
        val scanJob = viewModel.scanNearbyStations()
        scanJob.join()
        advanceUntilIdle()

        assertEquals("Initial scan must calculate routes immediately", 1, fakeCoordinator.callCount)
        val initialCalls = fakeCoordinator.callCount

        // 2. Tap 1: Toggle 60kW at t = 0ms
        viewModel.toggleWattageFilter(WattageOption.KW_60)
        runCurrent()

        // Verify UI updates immediately
        assertTrue("UI state must immediately reflect 60kW selected", viewModel.uiState.value.selectedWattages.contains(WattageOption.KW_60))
        assertTrue("Routing loading indicator must be true while debouncing", viewModel.uiState.value.isRoutingLoading)
        assertEquals("Coordinator must NOT be called before debounce expires", initialCalls, fakeCoordinator.callCount)

        // 3. Advance virtual time by 100ms (< 300ms)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("Coordinator must NOT be called at 100ms", initialCalls, fakeCoordinator.callCount)

        // 4. Tap 2: Toggle 250kW at t = 100ms
        viewModel.toggleWattageFilter(WattageOption.KW_250)
        runCurrent()

        assertTrue("UI state must immediately reflect both 60kW and 250kW selected", viewModel.uiState.value.selectedWattages.contains(WattageOption.KW_250))
        assertEquals("Coordinator must NOT be called after second tap", initialCalls, fakeCoordinator.callCount)

        // 5. Advance virtual time by 100ms (t = 200ms total, 100ms since Tap 2)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("Coordinator must NOT be called at 200ms", initialCalls, fakeCoordinator.callCount)

        // 6. Tap 3: Toggle off 60kW at t = 200ms
        viewModel.toggleWattageFilter(WattageOption.KW_60)
        runCurrent()

        assertFalse("UI state must immediately reflect 60kW deselected", viewModel.uiState.value.selectedWattages.contains(WattageOption.KW_60))
        assertTrue("UI state must keep 250kW selected", viewModel.uiState.value.selectedWattages.contains(WattageOption.KW_250))
        assertEquals("Coordinator must NOT be called after third tap", initialCalls, fakeCoordinator.callCount)

        // 7. Advance virtual time by 200ms (t = 400ms total, 200ms since Tap 3)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals("Coordinator must still NOT be called because only 200ms passed since Tap 3", initialCalls, fakeCoordinator.callCount)

        // 8. Advance virtual time by remaining 100ms (t = 500ms total, 300ms since Tap 3) -> Debounce triggers!
        advanceTimeBy(100L)
        advanceUntilIdle()

        assertEquals(
            "Exactly 1 route calculation must be dispatched after 300ms debounce expires",
            initialCalls + 1,
            fakeCoordinator.callCount
        )

        // 9. Verify UI state has completed routing
        val finalState = viewModel.uiState.value
        assertFalse("isRoutingLoading must be false once routing completes", finalState.isRoutingLoading)
        assertTrue("Stations in top 10 must have drivingMetrics attached", finalState.top10DisplayStations.all { it.drivingMetrics != null })

        // 10. Verify destination IDs match the stations filtered by 250kW
        val destinationIds = fakeCoordinator.lastDestinations.map { it.id }.toSet()
        assertTrue("Destination stations should contain 250kW stations", destinationIds.contains("station_250kw_1"))
        assertTrue("Destination stations should contain Combo station", destinationIds.contains("station_combo_1"))
        assertFalse("Destination stations should not contain AC-only station", destinationIds.contains("station_ac_1"))
    }

    @Test
    fun testRapidSmartFilterToggles_DebouncesRoutingCoordinator() = runTest(testDispatcher) {
        val viewModel = createNearbyViewModel(debounceMs = 300L)

        viewModel.scanNearbyStations().join()
        advanceUntilIdle()
        val initialCalls = fakeCoordinator.callCount

        // 1. Rapidly switch through smart filters: AC -> DC mode -> DC Tier 60kW
        viewModel.toggleAcFilter()
        runCurrent()
        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)

        advanceTimeBy(100L)
        runCurrent()

        viewModel.enterDcMode()
        runCurrent()
        assertEquals(SmartFilterMode.DC, viewModel.uiState.value.activeFilterMode)

        advanceTimeBy(100L)
        runCurrent()

        viewModel.selectDcTier(DcWattageTier.GE_60KW)
        runCurrent()
        assertEquals(DcWattageTier.GE_60KW, viewModel.uiState.value.selectedDcTier)

        assertEquals("No network call should be made during rapid toggling", initialCalls, fakeCoordinator.callCount)

        // Advance 250ms from last action (insufficient for 300ms debounce)
        advanceTimeBy(250L)
        runCurrent()
        assertEquals("Coordinator call must not trigger before 300ms window expires", initialCalls, fakeCoordinator.callCount)

        // Advance past 300ms debounce window
        advanceTimeBy(50L)
        advanceUntilIdle()

        assertEquals("Coordinator must be called exactly once for final smart filter state", initialCalls + 1, fakeCoordinator.callCount)
        assertFalse(viewModel.uiState.value.isRoutingLoading)
    }

    @Test
    fun testManualRefresh_BypassesDebounceAndCalculatesImmediately() = runTest(testDispatcher) {
        val viewModel = createNearbyViewModel(debounceMs = 300L)

        viewModel.scanNearbyStations().join()
        advanceUntilIdle()
        val initialCalls = fakeCoordinator.callCount

        // Trigger manual refresh
        val refreshJob = viewModel.refresh()
        refreshJob.join()
        advanceUntilIdle()

        assertEquals(
            "Manual refresh must execute routing immediately without 300ms debounce wait",
            initialCalls + 1,
            fakeCoordinator.callCount
        )
    }

    @Test
    fun testEmptyFilterResult_CancelsPendingDebounceJobAndClearsLoading() = runTest(testDispatcher) {
        // ViewModel with stations that don't match 150kW
        val viewModel = createNearbyViewModel(debounceMs = 300L)

        viewModel.scanNearbyStations().join()
        advanceUntilIdle()
        val initialCalls = fakeCoordinator.callCount

        // Filter for wattage that no test station supports
        viewModel.toggleWattageFilter(WattageOption.KW_150)
        runCurrent()

        // Verify empty state is handled immediately
        val state = viewModel.uiState.value
        assertTrue("Top 10 list must be empty", state.top10DisplayStations.isEmpty())
        assertFalse("isRoutingLoading must be false immediately when no stations match", state.isRoutingLoading)
        assertTrue("routingMetrics must be empty", state.routingMetrics.isEmpty())

        // Advance time past debounce window to verify coordinator is NOT called
        advanceTimeBy(500L)
        advanceUntilIdle()

        assertEquals("Coordinator must NOT be called when filtered station list is empty", initialCalls, fakeCoordinator.callCount)
    }

    @Test
    fun testZeroDebounceConfiguration_ExecutesRoutingImmediately() = runTest(testDispatcher) {
        // When routingDebounceMs = 0L, debounce is disabled
        val viewModel = createNearbyViewModel(debounceMs = 0L)

        viewModel.scanNearbyStations().join()
        advanceUntilIdle()
        val initialCalls = fakeCoordinator.callCount

        // Toggle filter chip
        viewModel.toggleWattageFilter(WattageOption.KW_60)
        advanceUntilIdle()

        assertEquals(
            "When debounceMs <= 0, route calculation must execute immediately without delay",
            initialCalls + 1,
            fakeCoordinator.callCount
        )
    }
}
