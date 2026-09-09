package com.evcs.favorites.ui.screens

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.EvRoutingSettings
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RouteCoordinate
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
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
 * Single Comprehensive Verification Test for Phase 03:
 * Insufficient Power Fallback Modal Flow & Criteria Relaxation.
 *
 * Verifies:
 * 1. When routing produces an insufficientPowerWarning, RouteUiState.insufficientPowerDialog.isVisible becomes true.
 * 2. Dialog state captures the exact required power, suggested fallback station metadata, power, and stop index.
 * 3. Calling onAcceptRelaxedPower() dismisses the dialog while retaining the complete route plan.
 * 4. Calling onOpenSwapFromDialog() dismisses the dialog and sets selectedStopForSwap to the affected stop.
 * 5. Calling onDismissInsufficientPowerDialog() hides the dialog without losing existing state.
 * 6. Calling onRelaxPowerThresholdAndRecalculate(newPowerKw) updates preference, hides dialog, and recomputes route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteInsufficientPowerDialogTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var locationsRepository: VietnamLocationsRepository
    private lateinit var evSmartRoutePlanner: EvSmartRoutePlanner
    private lateinit var evcsRepository: EvcsRepository
    private lateinit var viewModel: RouteViewModel

    private class TestRoutingCoordinator(
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

    private fun createStation(
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
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInsufficientPowerFallbackDialogAndFlow() = runTest(testDispatcher) {
        // Step 1: Set up vehicle parameters (safe range = 350 km, SoC = 100%, required power = 60 kW)
        viewModel.onSafeRangeChanged(350)
        viewModel.onStartBatteryPercentChanged(100)
        viewModel.onMinPowerChanged(60.0)

        // Read default coordinates from ViewModel (Hà Nội -> Đà Nẵng, ~620 km)
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        // Stop 1 placed at 35% along route (~219 km, reachable within 350km safe range).
        // It only has 30 kW charger (Tier 2, below required 60 kW threshold).
        val st1Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.35
        val st1Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.35
        val fallbackStation = createStation(
            id = "station_ha_tinh_30kw",
            name = "VinFast Hà Tĩnh 30kW",
            lat = st1Lat,
            lng = st1Lng,
            powerKw = 30.0
        )

        // Stop 2 placed at 70% along route (~438 km from origin, ~219 km from Stop 1).
        // It has 180 kW fast charger (Tier 1).
        val st2Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.70
        val st2Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.70
        val fastStation = createStation(
            id = "station_hue_180kw",
            name = "VinFast Huế 180kW",
            lat = st2Lat,
            lng = st2Lng,
            powerKw = 180.0
        )

        viewModel.setCandidateStations(listOf(fallbackStation, fastStation))
        advanceUntilIdle()

        // Step 2: Trigger Route Planning
        viewModel.planRoute()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val plan = state.routePlan
        assertNotNull("Route plan must be generated successfully", plan)
        assertNull("Route plan must not have deadZoneWarning", plan?.deadZoneWarning)
        assertNotNull("Plan must contain insufficientPowerWarning when Stop 1 is fallback 30kW", plan?.insufficientPowerWarning)

        val warning = plan!!.insufficientPowerWarning!!
        assertEquals("Warning required power must match 60.0 kW", 60.0, warning.requiredPowerKw, 0.001)
        assertEquals("Fallback station must be Stop 1 (30kW)", fallbackStation.id, warning.fallbackStation.id)
        assertEquals(30.0, warning.fallbackPowerKw, 0.001)
        assertEquals(1, warning.stopIndex)

        // Assertion 1: RouteUiState.insufficientPowerDialog.isVisible becomes true
        assertTrue("Dialog must be visible when plan has insufficientPowerWarning", state.insufficientPowerDialog.isVisible)

        // Assertion 2: Dialog state captures exact metadata
        val dialogState = state.insufficientPowerDialog
        assertEquals(60.0, dialogState.requiredPowerKw, 0.001)
        assertEquals(30.0, dialogState.fallbackPowerKw, 0.001)
        assertEquals(1, dialogState.stopIndex)
        assertEquals(fallbackStation.id, dialogState.fallbackStation?.id)
        assertEquals(warning.message, dialogState.message)

        // Assertion 3: onAcceptRelaxedPower() dismisses dialog while retaining complete route plan
        viewModel.onAcceptRelaxedPower()
        advanceUntilIdle()

        val acceptedState = viewModel.uiState.value
        assertFalse("Dialog must be dismissed after accept", acceptedState.insufficientPowerDialog.isVisible)
        assertNotNull("Route plan must be preserved after accept", acceptedState.routePlan)
        assertEquals(plan.totalDistanceKm, acceptedState.routePlan?.totalDistanceKm ?: 0.0, 0.001)
        assertEquals(2, acceptedState.routePlan?.stops?.size)

        // Re-open dialog state for testing next action
        viewModel.planRoute()
        advanceUntilIdle()
        assertTrue("Dialog should be visible again after re-planning", viewModel.uiState.value.insufficientPowerDialog.isVisible)

        // Assertion 4: onOpenSwapFromDialog() dismisses dialog and sets selectedStopForSwap
        viewModel.onOpenSwapFromDialog()
        advanceUntilIdle()

        val swapState = viewModel.uiState.value
        assertFalse("Dialog must be dismissed after opening swap", swapState.insufficientPowerDialog.isVisible)
        assertNotNull("selectedStopForSwap must be set to the affected stop", swapState.selectedStopForSwap)
        assertEquals(fallbackStation.id, swapState.selectedStopForSwap?.station?.id)
        assertEquals(1, swapState.selectedStopForSwap?.stopIndex)

        // Dismiss swap bottom sheet
        viewModel.dismissSwapBottomSheet()
        assertNull(viewModel.uiState.value.selectedStopForSwap)

        // Re-open dialog state for testing dismiss action
        viewModel.planRoute()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.insufficientPowerDialog.isVisible)

        // Assertion 5: onDismissInsufficientPowerDialog() hides dialog without losing existing state
        viewModel.onDismissInsufficientPowerDialog()
        advanceUntilIdle()

        val dismissedState = viewModel.uiState.value
        assertFalse("Dialog must be hidden after dismiss", dismissedState.insufficientPowerDialog.isVisible)
        assertNotNull("Route plan must still be intact after dialog dismissal", dismissedState.routePlan)
        assertNull("selectedStopForSwap must remain null", dismissedState.selectedStopForSwap)

        // Assertion 6: onRelaxPowerThresholdAndRecalculate() updates threshold, hides dialog, and recalculates route
        // When threshold is relaxed to 30.0 kW, the 30kW station satisfies the condition, so no warning is emitted
        viewModel.onRelaxPowerThresholdAndRecalculate(30.0)
        advanceUntilIdle()

        val relaxedState = viewModel.uiState.value
        assertEquals("Min charger power must be updated to 30.0 kW", 30.0, relaxedState.minChargerPowerKw, 0.001)
        assertFalse("Dialog must be hidden after relaxation", relaxedState.insufficientPowerDialog.isVisible)
        assertNotNull("Recalculated route plan must exist", relaxedState.routePlan)
        assertNull("No insufficient power warning should be emitted when threshold is 30kW", relaxedState.routePlan?.insufficientPowerWarning)
        assertEquals("Station 1 is still selected as stop 1", fallbackStation.id, relaxedState.routePlan?.stops?.firstOrNull()?.station?.id)
    }
}
