package com.evcs.favorites.ui

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.EvcsTelemetryRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.data.telemetry.EvcsTelemetryDataSource
import com.evcs.favorites.domain.Station24hStatsCalculator
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.components.NearbyUiHelper
import com.evcs.favorites.ui.components.RefreshTriggerType
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification for Phase 02: Auto-Scroll To Top on Refresh & Filter Changes.
 *
 * Requirements covered:
 * 1. [NearbyUiHelper.shouldScrollToTop] returns true for USER_REFRESH and FILTER_CHANGE when itemCount > 0 and eventTimestamp > lastHandledTimestamp.
 * 2. [NearbyUiHelper.shouldScrollToTop] returns false for PASSIVE_BACKGROUND and PAGINATION.
 * 3. [NearbyUiHelper.shouldScrollToTop] returns false when list is empty (itemCount == 0) or timestamp is stale.
 * 4. Filter modifications in [NearbyViewModel] emit updated lastRefreshTimestamp for AC, DC tier, Custom, clear, and wattage toggles.
 * 5. Passive background refresh preserves lastRefreshTimestamp without triggering scroll updates.
 * 6. User refresh updates lastRefreshTimestamp uniformly across active filter modes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAutoScrollFilterFixTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeRepo: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeRoutingCoordinator: FakeRoutingCoordinator
    private lateinit var routingPrefsManager: RoutingPreferencesManager
    private lateinit var filterPreferences: NearbyFilterPreferences
    private lateinit var smartFilterPreferences: SmartFilterPreferences
    private lateinit var telemetryRepo: EvcsTelemetryRepository
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var viewModel: NearbyViewModel

    private val stationAcAndDc = Station(
        id = "station_1",
        name = "VinFast - Landmark 81",
        address = "720A Dien Bien Phu, Binh Thanh, HCMC",
        latitude = 10.7951,
        longitude = 106.7218,
        summary = "Mở 24/7",
        connectors = "250kW, 60kW, 11kW",
        depotStatus = "Normal",
        distanceKm = 1.2,
        powers = listOf(
            PowerPort(typeWatts = 250000, totalPlugs = 4, availablePlugs = 2),
            PowerPort(typeWatts = 60000, totalPlugs = 2, availablePlugs = 1),
            PowerPort(typeWatts = 11000, totalPlugs = 2, availablePlugs = 2)
        ),
        totalAvailablePlugs = 5,
        totalPlugs = 8
    )

    private val stationDcOnly = Station(
        id = "station_2",
        name = "VinFast - Thao Dien Pearl",
        address = "12 Quoc Huong, Thao Dien, District 2, HCMC",
        latitude = 10.8034,
        longitude = 106.7325,
        summary = "Mở 24/7",
        connectors = "120kW, 60kW",
        depotStatus = "Normal",
        distanceKm = 2.4,
        powers = listOf(
            PowerPort(typeWatts = 120000, totalPlugs = 2, availablePlugs = 1),
            PowerPort(typeWatts = 60000, totalPlugs = 4, availablePlugs = 2)
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 6
    )

    private val stationAcOnly = Station(
        id = "station_3",
        name = "VinFast - Sala Sarimi",
        address = "10 Mai Chi Tho, An Loi Dong, District 2, HCMC",
        latitude = 10.7712,
        longitude = 106.7189,
        summary = "Mở 24/7",
        connectors = "11kW",
        depotStatus = "Normal",
        distanceKm = 4.1,
        powers = listOf(
            PowerPort(typeWatts = 11000, totalPlugs = 4, availablePlugs = 2)
        ),
        totalAvailablePlugs = 2,
        totalPlugs = 4
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        fakeRepo = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService().apply {
            locationToReturn = createMockLocation(10.7950, 106.7215)
        }
        fakeRoutingCoordinator = FakeRoutingCoordinator()
        routingPrefsManager = RoutingPreferencesManager(storage = sessionStorage)
        filterPreferences = NearbyFilterPreferences(storage = sessionStorage)
        smartFilterPreferences = SmartFilterPreferences(storage = sessionStorage)
        telemetryRepo = EvcsTelemetryRepository(
            dataSource = EvcsTelemetryDataSource(sessionManager = sessionManager, ioDispatcher = testDispatcher),
            statsCalculator = Station24hStatsCalculator,
            ioDispatcher = testDispatcher
        )

        fakeRepo.nearbyResult = Result.success(listOf(stationAcAndDc, stationDcOnly, stationAcOnly))

        viewModel = NearbyViewModel(
            repository = fakeRepo,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingPreferencesManager = routingPrefsManager,
            routingCoordinator = fakeRoutingCoordinator,
            filterPreferences = filterPreferences,
            smartFilterPreferences = smartFilterPreferences,
            telemetryRepository = telemetryRepo,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. UI Helper Tests: shouldScrollToTop with USER_REFRESH & FILTER_CHANGE
    // =========================================================================

    @Test
    fun shouldScrollToTop_acceptsUserRefreshAndFilterChangeWhenListNonEmptyAndTimestampFresh() {
        val now = 2000L
        val previous = 1000L

        // USER_REFRESH with non-empty list and newer timestamp -> true
        assertTrue(
            "USER_REFRESH must trigger auto-scroll when items > 0 and timestamp is newer",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.USER_REFRESH,
                itemCount = 3,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )

        // FILTER_CHANGE with non-empty list and newer timestamp -> true
        assertTrue(
            "FILTER_CHANGE must trigger auto-scroll when items > 0 and timestamp is newer",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.FILTER_CHANGE,
                itemCount = 3,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )

        // Boolean overload parity for user initiated
        assertTrue(
            "Boolean overload with isUserInitiated=true must trigger auto-scroll",
            NearbyUiHelper.shouldScrollToTop(
                isUserInitiated = true,
                itemCount = 3,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )
    }

    @Test
    fun shouldScrollToTop_rejectsPassiveBackgroundAndPagination() {
        val now = 2000L
        val previous = 1000L

        // PASSIVE_BACKGROUND -> false
        assertFalse(
            "PASSIVE_BACKGROUND must never trigger auto-scroll to preserve reading position",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.PASSIVE_BACKGROUND,
                itemCount = 3,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )

        // PAGINATION -> false
        assertFalse(
            "PAGINATION must never trigger auto-scroll",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.PAGINATION,
                itemCount = 3,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )

        // Boolean overload parity for passive
        assertFalse(
            "Boolean overload with isUserInitiated=false must not trigger auto-scroll",
            NearbyUiHelper.shouldScrollToTop(
                isUserInitiated = false,
                itemCount = 3,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )
    }

    @Test
    fun shouldScrollToTop_rejectsEmptyListOrStaleTimestamp() {
        val now = 2000L
        val previous = 1000L

        // Empty list with USER_REFRESH -> false
        assertFalse(
            "Empty list must not scroll to top on USER_REFRESH",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.USER_REFRESH,
                itemCount = 0,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )

        // Empty list with FILTER_CHANGE -> false
        assertFalse(
            "Empty list must not scroll to top on FILTER_CHANGE",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.FILTER_CHANGE,
                itemCount = 0,
                lastHandledTimestamp = previous,
                eventTimestamp = now
            )
        )

        // Stale timestamp with USER_REFRESH -> false
        assertFalse(
            "Stale or duplicate timestamp must not scroll on USER_REFRESH",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.USER_REFRESH,
                itemCount = 5,
                lastHandledTimestamp = now,
                eventTimestamp = now
            )
        )

        // Stale timestamp with FILTER_CHANGE -> false
        assertFalse(
            "Stale or duplicate timestamp must not scroll on FILTER_CHANGE",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.FILTER_CHANGE,
                itemCount = 5,
                lastHandledTimestamp = now,
                eventTimestamp = previous
            )
        )
    }

    // =========================================================================
    // 2. ViewModel Tests: Filter Changes Emit Updated lastRefreshTimestamp
    // =========================================================================

    @Test
    fun filterChangesInViewModel_emitUpdatedLastRefreshTimestamp() = testScope.runTest {
        // Initial scan
        val scanJob = viewModel.scanNearbyStations()
        scanJob.join()
        advanceUntilIdle()

        val initialTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)

        // 1. Toggle AC filter -> updates timestamp
        Thread.sleep(10)
        viewModel.toggleAcFilter()?.join()
        advanceUntilIdle()

        val acTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("AC filter toggle must update lastRefreshTimestamp", acTimestamp > initialTimestamp)
        assertEquals(SmartFilterMode.AC, viewModel.uiState.value.activeFilterMode)

        // 2. Enter DC mode & select DC tier -> updates timestamp
        Thread.sleep(10)
        viewModel.enterDcMode()
        advanceUntilIdle()
        val enterDcTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("enterDcMode must update lastRefreshTimestamp", enterDcTimestamp > acTimestamp)

        Thread.sleep(10)
        viewModel.selectDcTier(DcWattageTier.GE_60KW)?.join()
        advanceUntilIdle()
        val dcTierTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("selectDcTier must update lastRefreshTimestamp", dcTierTimestamp > enterDcTimestamp)

        // 3. Exit DC mode -> updates timestamp
        Thread.sleep(10)
        viewModel.exitDcMode()?.join()
        advanceUntilIdle()
        val exitDcTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("exitDcMode must update lastRefreshTimestamp", exitDcTimestamp > dcTierTimestamp)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)

        // 4. Save and apply custom filter -> updates timestamp
        Thread.sleep(10)
        val customConfig = CustomFilterConfig(quickChip = QuickChipOption.DC_GE_60KW)
        viewModel.saveAndApplyCustomFilter(customConfig)?.join()
        advanceUntilIdle()
        val customTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("saveAndApplyCustomFilter must update lastRefreshTimestamp", customTimestamp > exitDcTimestamp)
        assertEquals(SmartFilterMode.CUSTOM, viewModel.uiState.value.activeFilterMode)

        // 5. Clear smart filter via clearFilters() -> updates timestamp
        Thread.sleep(10)
        viewModel.clearFilters()?.join()
        advanceUntilIdle()
        val clearTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("clearFilters must update lastRefreshTimestamp", clearTimestamp > customTimestamp)
        assertEquals(SmartFilterMode.NONE, viewModel.uiState.value.activeFilterMode)

        // 6. Toggle Wattage chip filter -> updates timestamp
        Thread.sleep(10)
        viewModel.toggleWattageFilter(WattageOption.KW_60)?.join()
        advanceUntilIdle()
        val wattageTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("toggleWattageFilter must update lastRefreshTimestamp", wattageTimestamp > clearTimestamp)

        // 7. Clear Wattage filters -> updates timestamp
        Thread.sleep(10)
        viewModel.clearWattageFilters()?.join()
        advanceUntilIdle()
        val clearWattageTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("clearWattageFilters must update lastRefreshTimestamp", clearWattageTimestamp > wattageTimestamp)
    }

    @Test
    fun passiveBackgroundRefresh_preservesLastRefreshTimestamp() = testScope.runTest {
        // Initial scan
        viewModel.scanNearbyStations().join()
        advanceUntilIdle()

        // Trigger a filter change to establish a positive lastRefreshTimestamp
        viewModel.toggleAcFilter()?.join()
        advanceUntilIdle()
        val beforePassiveTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue(beforePassiveTimestamp > 0L)

        // Passive background refresh
        viewModel.refresh(triggerType = RefreshTriggerType.PASSIVE_BACKGROUND).join()
        advanceUntilIdle()

        val afterPassiveTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertEquals(
            "Passive background refresh must preserve lastRefreshTimestamp to prevent unwanted scroll",
            beforePassiveTimestamp,
            afterPassiveTimestamp
        )
    }

    @Test
    fun userInitiatedRefresh_updatesLastRefreshTimestampAcrossActiveFilterModes() = testScope.runTest {
        // Initial scan
        viewModel.scanNearbyStations().join()
        advanceUntilIdle()

        // Set AC filter mode
        viewModel.toggleAcFilter()?.join()
        advanceUntilIdle()
        val beforeAcRefreshTimestamp = viewModel.uiState.value.lastRefreshTimestamp

        Thread.sleep(10)
        viewModel.refresh(triggerType = RefreshTriggerType.USER_REFRESH).join()
        advanceUntilIdle()
        val afterAcRefreshTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue(
            "User refresh in AC mode must update lastRefreshTimestamp",
            afterAcRefreshTimestamp > beforeAcRefreshTimestamp
        )

        // Switch to DC mode
        viewModel.selectDcTier(DcWattageTier.GE_60KW)?.join()
        advanceUntilIdle()
        val beforeDcRefreshTimestamp = viewModel.uiState.value.lastRefreshTimestamp

        Thread.sleep(10)
        viewModel.refreshNearbyStations(isUserRefresh = true).join()
        advanceUntilIdle()
        val afterDcRefreshTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue(
            "User refresh in DC mode must update lastRefreshTimestamp",
            afterDcRefreshTimestamp > beforeDcRefreshTimestamp
        )
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted

        override suspend fun getFreshLocation(): Location? {
            return locationToReturn
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
                    distanceMeters = 2000L,
                    durationSeconds = 300L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
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

        override suspend fun getFavorites(
            userLat: Double?,
            userLon: Double?,
            autoResolveUnknownCoordinates: Boolean
        ): Result<List<Station>> {
            return Result.success(emptyList())
        }
    }
}
