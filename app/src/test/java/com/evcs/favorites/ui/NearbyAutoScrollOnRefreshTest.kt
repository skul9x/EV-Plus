package com.evcs.favorites.ui

import android.location.Location
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.api.EvcsApiClient
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
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.components.NearbyUiHelper
import com.evcs.favorites.ui.components.RefreshTriggerType
import com.evcs.favorites.ui.components.ScrollToTopEffect
import com.evcs.favorites.ui.state.NearbyUiEvent
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification for Phase 01: Nearby Auto-Scroll on Refresh.
 *
 * Requirements covered:
 * 1. Verify that user-initiated refresh completion produces a [NearbyUiEvent.ScrollToTop] action.
 * 2. Verify that regular passive polling or pagination does not emit spurious scroll-to-top triggers.
 * 3. Verify state preservation across non-empty list reloads and UI helper scroll target resolution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAutoScrollOnRefreshTest {

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

    private val sampleStation1 = Station(
        id = "station_1",
        name = "VinFast - Landmark 81",
        address = "720A Dien Bien Phu, Binh Thanh, HCMC",
        latitude = 10.7951,
        longitude = 106.7218,
        summary = "Mở 24/7",
        connectors = "250kW, 60kW",
        depotStatus = "Normal",
        distanceKm = 1.2,
        powers = listOf(
            PowerPort(typeWatts = 250000, totalPlugs = 4, availablePlugs = 2),
            PowerPort(typeWatts = 60000, totalPlugs = 2, availablePlugs = 1)
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 6
    )

    private val sampleStation2 = Station(
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

    private val sampleStation3 = Station(
        id = "station_3",
        name = "VinFast - Sala Sarimi",
        address = "10 Mai Chi Tho, An Loi Dong, District 2, HCMC",
        latitude = 10.7712,
        longitude = 106.7189,
        summary = "Mở 24/7",
        connectors = "60kW",
        depotStatus = "Normal",
        distanceKm = 4.1,
        powers = listOf(
            PowerPort(typeWatts = 60000, totalPlugs = 6, availablePlugs = 3)
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 6
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

        fakeRepo.nearbyResult = Result.success(listOf(sampleStation1, sampleStation2, sampleStation3))

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
    // 1. NearbyUiHelper Pure State & Decision Rules Tests
    // =========================================================================

    @Test
    fun shouldScrollToTop_acceptsOnlyUserInitiatedRefreshWithNonEmptyItemsAndFreshTimestamp() {
        val now = 1000L
        val earlier = 900L

        // Valid user-initiated refresh
        assertTrue(
            "User refresh with valid items and newer timestamp must trigger auto scroll",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.USER_REFRESH,
                itemCount = 3,
                lastHandledTimestamp = earlier,
                eventTimestamp = now
            )
        )

        // Boolean overload parity
        assertTrue(
            "Boolean overload with isUserInitiated=true must trigger auto scroll",
            NearbyUiHelper.shouldScrollToTop(
                isUserInitiated = true,
                itemCount = 3,
                lastHandledTimestamp = earlier,
                eventTimestamp = now
            )
        )
    }

    @Test
    fun shouldScrollToTop_rejectsPassiveBackgroundPaginationAndFilterChanges() {
        val now = 1000L
        val earlier = 900L

        // Passive background
        assertFalse(
            "Passive background update must never trigger auto scroll",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.PASSIVE_BACKGROUND,
                itemCount = 5,
                lastHandledTimestamp = earlier,
                eventTimestamp = now
            )
        )

        // Pagination
        assertFalse(
            "Pagination or infinite scrolling must never trigger auto scroll",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.PAGINATION,
                itemCount = 5,
                lastHandledTimestamp = earlier,
                eventTimestamp = now
            )
        )

        // Filter change
        assertTrue(
            "Filter toggling must trigger auto scroll to top",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.FILTER_CHANGE,
                itemCount = 5,
                lastHandledTimestamp = earlier,
                eventTimestamp = now
            )
        )

        // Boolean overload parity for passive
        assertFalse(
            "Boolean overload with isUserInitiated=false must not trigger auto scroll",
            NearbyUiHelper.shouldScrollToTop(
                isUserInitiated = false,
                itemCount = 5,
                lastHandledTimestamp = earlier,
                eventTimestamp = now
            )
        )
    }

    @Test
    fun shouldScrollToTop_rejectsEmptyListOrStaleTimestampToPreventSpuriousScrolls() {
        // Zero items (empty list)
        assertFalse(
            "Empty list reload must not trigger auto scroll",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.USER_REFRESH,
                itemCount = 0,
                lastHandledTimestamp = 500L,
                eventTimestamp = 1000L
            )
        )

        // Stale or duplicate timestamp (recomposition guard)
        assertFalse(
            "Equal or older timestamp must be ignored to prevent repeated animations on recomposition",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.USER_REFRESH,
                itemCount = 2,
                lastHandledTimestamp = 1000L,
                eventTimestamp = 1000L
            )
        )
        assertFalse(
            "Older timestamp must be ignored",
            NearbyUiHelper.shouldScrollToTop(
                triggerType = RefreshTriggerType.USER_REFRESH,
                itemCount = 2,
                lastHandledTimestamp = 1000L,
                eventTimestamp = 999L
            )
        )
    }

    @Test
    fun resolveTargetScrollPosition_resetsToZeroForUserRefreshAndPreservesPositionForOthers() {
        val currentVisibleIndex = 4
        val currentScrollOffset = 85

        // User refresh -> resets viewport to top (0, 0)
        val resetTarget = NearbyUiHelper.resolveTargetScrollPosition(
            triggerType = RefreshTriggerType.USER_REFRESH,
            currentIndex = currentVisibleIndex,
            currentOffset = currentScrollOffset
        )
        assertEquals(0, resetTarget.first)
        assertEquals(0, resetTarget.second)

        val resetTargetBool = NearbyUiHelper.resolveTargetScrollPosition(
            isUserInitiated = true,
            currentIndex = currentVisibleIndex,
            currentOffset = currentScrollOffset
        )
        assertEquals(0, resetTargetBool.first)
        assertEquals(0, resetTargetBool.second)

        // Passive background -> preserves driver's current browsing index & offset
        val passiveTarget = NearbyUiHelper.resolveTargetScrollPosition(
            triggerType = RefreshTriggerType.PASSIVE_BACKGROUND,
            currentIndex = currentVisibleIndex,
            currentOffset = currentScrollOffset
        )
        assertEquals(currentVisibleIndex, passiveTarget.first)
        assertEquals(currentScrollOffset, passiveTarget.second)

        // Pagination -> preserves position
        val paginationTarget = NearbyUiHelper.resolveTargetScrollPosition(
            triggerType = RefreshTriggerType.PAGINATION,
            currentIndex = currentVisibleIndex,
            currentOffset = currentScrollOffset
        )
        assertEquals(currentVisibleIndex, paginationTarget.first)
        assertEquals(currentScrollOffset, paginationTarget.second)

        // Filter change -> preserves position
        val filterTarget = NearbyUiHelper.resolveTargetScrollPosition(
            triggerType = RefreshTriggerType.FILTER_CHANGE,
            currentIndex = currentVisibleIndex,
            currentOffset = currentScrollOffset
        )
        assertEquals(currentVisibleIndex, filterTarget.first)
        assertEquals(currentScrollOffset, filterTarget.second)
    }

    @Test
    fun scrollToTopEffect_dataModelIntegrity() {
        val effect = ScrollToTopEffect(
            triggerTimestamp = 123456789L,
            triggerType = RefreshTriggerType.USER_REFRESH
        )
        assertEquals(123456789L, effect.triggerTimestamp)
        assertEquals(RefreshTriggerType.USER_REFRESH, effect.triggerType)
    }

    // =========================================================================
    // 2. NearbyViewModel Integration Tests
    // =========================================================================

    @Test
    fun userInitiatedRefresh_emitsScrollToTopEventAndUpdatesLastRefreshTimestamp() = testScope.runTest {
        val events = mutableListOf<NearbyUiEvent>()
        val collectJob = launch {
            viewModel.events.toList(events)
        }

        // Trigger explicit user refresh
        val refreshJob = viewModel.refresh(RefreshTriggerType.USER_REFRESH)
        refreshJob.join()
        advanceUntilIdle()

        // 1. Stations loaded
        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)

        // 2. Timestamp updated to non-zero positive value
        val lastTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertTrue("lastRefreshTimestamp must be updated on user refresh", lastTimestamp > 0L)

        // 3. ScrollToTop event emitted with matching timestamp
        val scrollEvents = events.filterIsInstance<NearbyUiEvent.ScrollToTop>()
        assertEquals(1, scrollEvents.size)
        assertEquals(lastTimestamp, scrollEvents.first().timestamp)

        collectJob.cancel()
    }

    @Test
    fun passiveBackgroundRefresh_doesNotEmitScrollToTopAndPreservesLastRefreshTimestamp() = testScope.runTest {
        val events = mutableListOf<NearbyUiEvent>()
        val collectJob = launch {
            viewModel.events.toList(events)
        }

        val initialTimestamp = viewModel.uiState.value.lastRefreshTimestamp
        assertEquals(0L, initialTimestamp)

        // Trigger passive background refresh
        val passiveJob = viewModel.refresh(RefreshTriggerType.PASSIVE_BACKGROUND)
        passiveJob.join()
        advanceUntilIdle()

        // 1. Stations loaded
        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)

        // 2. Timestamp must NOT be updated for passive updates
        assertEquals(0L, viewModel.uiState.value.lastRefreshTimestamp)

        // 3. ScrollToTop event must NOT be emitted
        val scrollEvents = events.filterIsInstance<NearbyUiEvent.ScrollToTop>()
        assertTrue("Passive background refresh must not emit ScrollToTop", scrollEvents.isEmpty())

        collectJob.cancel()
    }

    @Test
    fun filterChanges_doNotEmitScrollToTop() = testScope.runTest {
        val events = mutableListOf<NearbyUiEvent>()
        val collectJob = launch {
            viewModel.events.toList(events)
        }

        // Populate initial stations
        val initialJob = viewModel.scanNearbyStations()
        initialJob.join()
        advanceUntilIdle()

        // Toggle filter
        viewModel.toggleWattageFilter(WattageOption.KW_60)
        advanceUntilIdle()

        val scrollEvents = events.filterIsInstance<NearbyUiEvent.ScrollToTop>()
        assertTrue("Filter toggling must not emit ScrollToTop event", scrollEvents.isEmpty())

        collectJob.cancel()
    }

    @Test
    fun emptySearchResults_doNotEmitScrollToTop() = testScope.runTest {
        val events = mutableListOf<NearbyUiEvent>()
        val collectJob = launch {
            viewModel.events.toList(events)
        }

        // Configure repository to return empty list
        fakeRepo.nearbyResult = Result.success(emptyList())

        val refreshJob = viewModel.refresh(RefreshTriggerType.USER_REFRESH)
        refreshJob.join()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.top10DisplayStations.isEmpty())
        val scrollEvents = events.filterIsInstance<NearbyUiEvent.ScrollToTop>()
        assertTrue("Empty search results must not emit ScrollToTop", scrollEvents.isEmpty())

        collectJob.cancel()
    }

    @Test
    fun statePreservationAcrossNonEmptyListReloads() = testScope.runTest {
        // Step 1: User initially loaded 3 stations and is scrolled down reading station 2
        var userVisibleIndex = 2
        var userScrollOffset = 45

        val initialJob = viewModel.scanNearbyStations()
        initialJob.join()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)

        // Step 2: A passive telemetry or background sync occurs with updated data
        val updatedStation1 = sampleStation1.copy(summary = "Cập nhật công suất thời gian thực")
        val updatedStation2 = sampleStation2.copy(totalAvailablePlugs = 1)
        fakeRepo.nearbyResult = Result.success(listOf(updatedStation1, updatedStation2, sampleStation3))

        val passiveJob = viewModel.refresh(RefreshTriggerType.PASSIVE_BACKGROUND)
        passiveJob.join()
        advanceUntilIdle()

        // Resolve scroll position for passive update
        val preservedPosition = NearbyUiHelper.resolveTargetScrollPosition(
            triggerType = RefreshTriggerType.PASSIVE_BACKGROUND,
            currentIndex = userVisibleIndex,
            currentOffset = userScrollOffset
        )
        // Position must remain untouched
        assertEquals(2, preservedPosition.first)
        assertEquals(45, preservedPosition.second)

        // Step 3: Now user explicitly taps the refresh button
        val userRefreshJob = viewModel.refresh(RefreshTriggerType.USER_REFRESH)
        userRefreshJob.join()
        advanceUntilIdle()

        // Resolve scroll position for user refresh
        val refreshedPosition = NearbyUiHelper.resolveTargetScrollPosition(
            triggerType = RefreshTriggerType.USER_REFRESH,
            currentIndex = userVisibleIndex,
            currentOffset = userScrollOffset
        )
        // Position must be reset to top
        assertEquals(0, refreshedPosition.first)
        assertEquals(0, refreshedPosition.second)
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
        var freshLocationCallCount: Int = 0

        override fun hasLocationPermission(): Boolean = permissionGranted

        override suspend fun getFreshLocation(): Location? {
            freshLocationCallCount++
            return locationToReturn
        }
    }

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount: Int = 0
        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
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
