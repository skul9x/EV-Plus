package com.evcs.favorites.ui.screens

import androidx.lifecycle.ViewModelProvider
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import kotlinx.coroutines.CoroutineDispatcher
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
 * Single Comprehensive Verification Test for Phase 02:
 * Background Coroutine Dispatch for Alternate Station Swapping.
 *
 * Verifies:
 * 1. swapStation immediately clears selectedStopForSwap and executes recalculation in a background coroutine without blocking the caller.
 * 2. Upon background recalculation completion, _uiState.value.routePlan reflects the updated stops, arrival SoCs, and battery trajectory.
 * 3. swapStopWithBackup seamlessly swaps primary and backup stations asynchronously.
 * 4. Rapid successive swap requests cancel or supersede earlier calculations without race conditions or corrupting the final route plan.
 * 5. Factory wiring correctly supports defaultDispatcher injection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelStationSwapAsyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var locationsRepository: VietnamLocationsRepository
    private lateinit var evSmartRoutePlanner: EvSmartRoutePlanner
    private lateinit var evcsRepository: EvcsRepository
    private lateinit var viewModel: RouteViewModel

    private class TestRoutingCoordinator(
        private val dispatcher: CoroutineDispatcher
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

    private fun createStation(
        id: String,
        name: String,
        lat: Double,
        lng: Double,
        powerKw: Double,
        availablePlugs: Int = 2,
        totalPlugs: Int = 4
    ): Station {
        val watts = (powerKw * 1000).toLong()
        val port = PowerPort(
            typeWatts = watts,
            label = "${powerKw.toInt()}kW",
            availablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            displayString = "${powerKw.toInt()}kW: trống $availablePlugs/$totalPlugs"
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
            totalPlugs = totalPlugs,
            totalAvailablePlugs = availablePlugs
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        sessionStorage = InMemorySessionStorage()
        prefsManager = RoutingPreferencesManager(
            storage = sessionStorage,
            ioDispatcher = testDispatcher
        )

        locationsRepository = VietnamLocationsRepository()
        evSmartRoutePlanner = EvSmartRoutePlanner(
            coordinator = TestRoutingCoordinator(testDispatcher)
        )

        val sessionManager = SessionManager(sessionStorage)
        evcsRepository = EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = sessionStorage
        )

        viewModel = RouteViewModel(
            locationsRepository = locationsRepository,
            evSmartRoutePlanner = evSmartRoutePlanner,
            evcsRepository = evcsRepository,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testBackgroundCoroutineSwapDispatchAndCancellationSafety() = runTest(testDispatcher) {
        // Step 1: Configure vehicle parameters
        viewModel.onSafeRangeChanged(350)
        viewModel.onStartBatteryPercentChanged(100)
        viewModel.onMinPowerChanged(60.0)

        // Read default coordinates from ViewModel (Hà Nội -> Đà Nẵng, ~620 km)
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        // Stop 1 is at fraction 0.35 along route (~219 km from origin)
        val frac1 = 0.35
        val st1Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * frac1
        val st1Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * frac1

        val primaryStop1 = createStation(
            id = "st-primary-1",
            name = "Trạm Chính Hà Tĩnh",
            lat = st1Lat,
            lng = st1Lng,
            powerKw = 150.0,
            availablePlugs = 2,
            totalPlugs = 4
        )

        val backupSafe = createStation(
            id = "st-backup-safe",
            name = "Trạm Dự Phòng Hà Tĩnh",
            lat = st1Lat + 0.015,
            lng = st1Lng + 0.015,
            powerKw = 60.0,
            availablePlugs = 3,
            totalPlugs = 4
        )

        val altStationC = createStation(
            id = "st-alt-c",
            name = "Trạm Thay Thế C Hà Tĩnh",
            lat = st1Lat + 0.020,
            lng = st1Lng + 0.020,
            powerKw = 120.0,
            availablePlugs = 4,
            totalPlugs = 4
        )

        // Stop 2 is at fraction 0.70 along route (~438 km from origin)
        val frac2 = 0.70
        val st2Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * frac2
        val st2Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * frac2

        val loneStop2 = createStation(
            id = "st-lone-2",
            name = "Trạm Đơn Quảng Bình",
            lat = st2Lat,
            lng = st2Lng,
            powerKw = 120.0,
            availablePlugs = 2,
            totalPlugs = 4
        )

        // Seed ViewModel with all stations and plan initial route
        viewModel.setCandidateStations(listOf(primaryStop1, backupSafe, altStationC, loneStop2))
        advanceUntilIdle()

        viewModel.planRoute()
        advanceUntilIdle()

        val initialPlan = viewModel.uiState.value.routePlan
        assertNotNull("Initial route plan should not be null", initialPlan)
        assertTrue("Route calculation must succeed", initialPlan!!.isSuccess)
        assertEquals("Should have 2 charging stops", 2, initialPlan.stops.size)
        val initialStop1 = initialPlan.stops[0]
        assertEquals("Initial Stop 1 primary station must be st-primary-1", "st-primary-1", initialStop1.station.id)

        // =========================================================================
        // Assertion 1: swapStation immediately clears selectedStopForSwap and
        // offloads recalculation to background coroutine without blocking caller.
        // =========================================================================
        viewModel.selectStopForSwap(initialStop1)
        assertEquals("Stop should be selected for swap bottom sheet", initialStop1, viewModel.uiState.value.selectedStopForSwap)

        // Call swapStation to swap with altStationC
        viewModel.swapStation(stopIndex = 1, alternateStation = altStationC)

        // BEFORE advancing virtual time on testDispatcher:
        // The bottom sheet selection MUST be dismissed immediately (0ms UI drop)
        assertNull("selectedStopForSwap must be immediately cleared upon swapStation invocation", viewModel.uiState.value.selectedStopForSwap)
        // Route plan must NOT be updated yet because recalculation is queued on background coroutine
        assertEquals("Active stop must still be st-primary-1 before background dispatcher advances", "st-primary-1", viewModel.uiState.value.routePlan!!.stops[0].station.id)

        // =========================================================================
        // Assertion 2: Upon background recalculation completion, _uiState.value.routePlan
        // reflects the updated stops, arrival SoCs, and battery trajectory.
        // =========================================================================
        advanceUntilIdle()

        val planAfterAltSwap = viewModel.uiState.value.routePlan
        assertNotNull("Route plan after swap must not be null", planAfterAltSwap)
        val updatedStop1 = planAfterAltSwap!!.stops[0]
        assertEquals("Stop 1 primary must now be st-alt-c", "st-alt-c", updatedStop1.station.id)
        assertEquals("Stop 1 power must now be 120 kW", 120.0, updatedStop1.maxPowerKw, 0.001)
        assertTrue("Arrival battery SoC must be computed and positive", updatedStop1.arrivalBatteryPercent in 1..100)
        assertTrue("Energy profile trajectory must be updated and non-empty", planAfterAltSwap.energyProfile.isNotEmpty())
        assertEquals("Origin waypoint starts at 100% SoC", 100, planAfterAltSwap.energyProfile.first().batteryPercent)
        assertTrue("Destination waypoint retains positive battery SoC", planAfterAltSwap.energyProfile.last().batteryPercent > 0)

        // =========================================================================
        // Assertion 3: swapStopWithBackup seamlessly swaps primary and backup stations asynchronously.
        // =========================================================================
        val backupTarget = updatedStop1.backupStation
        assertNotNull("Updated stop 1 must have a backup station", backupTarget)
        val expectedBackupId = backupTarget!!.id

        viewModel.swapStopWithBackup(stopIndex = 1)
        // Before advancing dispatcher:
        assertEquals("Stop 1 station must not change before background coroutine advances", "st-alt-c", viewModel.uiState.value.routePlan!!.stops[0].station.id)

        advanceUntilIdle()

        val planAfterBackupSwap = viewModel.uiState.value.routePlan
        assertNotNull(planAfterBackupSwap)
        val stopAfterBackupSwap = planAfterBackupSwap!!.stops[0]
        assertEquals("Primary station must now be the former backup station", expectedBackupId, stopAfterBackupSwap.station.id)
        assertNotNull("Backup station should now preserve the former primary station", stopAfterBackupSwap.backupStation)
        assertEquals("st-alt-c", stopAfterBackupSwap.backupStation!!.id)
        assertTrue("Arrival battery SoC must be computed", stopAfterBackupSwap.arrivalBatteryPercent in 1..100)

        // =========================================================================
        // Assertion 4: Rapid successive swap requests cancel or supersede earlier
        // calculations without race conditions or corrupting the final route plan.
        // =========================================================================
        val planBeforeRapid = viewModel.uiState.value.routePlan!!
        val baselineStop1Id = planBeforeRapid.stops[0].station.id

        // Rapid dispatch: Request swap to altStationC, then immediately request swap to primaryStop1
        viewModel.swapStation(stopIndex = 1, alternateStation = altStationC)
        // Without advancing time, immediately dispatch second swap request
        viewModel.swapStation(stopIndex = 1, alternateStation = primaryStop1)

        // Before coroutines execute:
        assertEquals("Route plan station remains baseline before coroutines advance", baselineStop1Id, viewModel.uiState.value.routePlan!!.stops[0].station.id)

        // Execute all scheduled coroutines
        advanceUntilIdle()

        val finalPlan = viewModel.uiState.value.routePlan
        assertNotNull(finalPlan)
        assertEquals("The final route plan must reflect the superseded latest request (st-primary-1)", "st-primary-1", finalPlan!!.stops[0].station.id)
        assertNull("No error message should be present after superseded cancellation", viewModel.uiState.value.errorMessage)
        assertFalse("isLoading must be false", viewModel.uiState.value.isLoading)

        // Test rapid swap followed immediately by planRoute cancellation
        viewModel.swapStation(stopIndex = 1, alternateStation = altStationC)
        viewModel.planRoute()
        advanceUntilIdle()

        val rePlannedPlan = viewModel.uiState.value.routePlan
        assertNotNull(rePlannedPlan)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)

        // =========================================================================
        // Factory Wiring Verification
        // =========================================================================
        val factory = RouteViewModel.provideFactory(
            locationsRepository = locationsRepository,
            evSmartRoutePlanner = evSmartRoutePlanner,
            evcsRepository = evcsRepository,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
        val createdVm = factory.create(RouteViewModel::class.java)
        assertNotNull("Factory should successfully create RouteViewModel instance", createdVm)
    }
}
