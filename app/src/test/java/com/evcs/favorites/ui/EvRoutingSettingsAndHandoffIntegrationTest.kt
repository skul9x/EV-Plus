package com.evcs.favorites.ui

import android.content.Context
import androidx.car.app.CarContext
import com.evcs.favorites.car.CarFocusModeBridge
import com.evcs.favorites.car.CarNavigationDispatcher
import com.evcs.favorites.car.CarNavigationIntentSpec
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.ChargerTier
import com.evcs.favorites.data.routing.EnergyWaypoint
import com.evcs.favorites.data.routing.EvRouteStop
import com.evcs.favorites.data.routing.EvSmartRoutePlan
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RouteCoordinate
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RouteSessionData
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.StopAvailabilityStatus
import com.evcs.favorites.focus.FocusBadgeColor
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.focus.FocusModeTelemetryEngine
import com.evcs.favorites.focus.FocusModeViewLayoutHelper
import com.evcs.favorites.focus.FocusServiceIntentSpec
import com.evcs.favorites.ui.screens.RouteViewModel
import kotlinx.coroutines.CoroutineDispatcher
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
import java.io.File
import java.lang.reflect.Proxy

/**
 * Single Comprehensive Verification Test for Phase 05:
 * Settings Modal Integration & Multi-Stop Navigation Handoff.
 *
 * Verifies:
 * 1. Dedicated EV Smart Routing Settings UI:
 *    - Section header "Cấu hình Lộ trình & Pin Xe EV".
 *    - Sliders for safe range (100 - 500 km, default 200 km), arrival reserve buffer (5 - 25%, default 10%),
 *      and target charging SoC (70 - 95%, default 85%).
 *    - Switch for +25% safety duration delay buffer (default On).
 *    - Two-way reactive synchronization between RoutingPreferencesManager and RouteViewModel.
 *    - Value boundary clamping.
 * 2. Multi-Stop Navigation Handoff & CTA:
 *    - High-visibility "BẮT ĐẦU DẪN ĐƯỜNG" primary CTA button embedded in RouteResultsSection.
 *    - Constructs RouteSessionData with complete multi-stop itinerary.
 *    - Dispatch pipeline to CarNavigationDispatcher and FocusModeForegroundService for Leg 1.
 * 3. Arrival Detection (<= 300 meters) in FocusModeTelemetryEngine:
 *    - Detects driver arrival within 300m of the charging stop waypoint.
 *    - Generates "Đã đến trạm sạc" state and FloatingViewState with action "Tiếp tục chặng tiếp theo".
 * 4. Waypoint Progression ("Tiếp tục chặng tiếp theo"):
 *    - Seamlessly advances from Leg 1 (Stop 1) to Leg 2 (Stop 2), and then to Leg 3 (Final Destination).
 *    - Re-targets navigation intent specs and resets arrival state on progression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvRoutingSettingsAndHandoffIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var locationsRepo: VietnamLocationsRepository
    private lateinit var routePlanner: EvSmartRoutePlanner
    private lateinit var evcsRepo: EvcsRepository
    private lateinit var viewModel: RouteViewModel

    private class FakeRoutingCoordinator(
        dispatcher: CoroutineDispatcher
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        prefsManager = RoutingPreferencesManager(sessionStorage)

        val sessionManager = SessionManager(sessionStorage)
        locationsRepo = VietnamLocationsRepository()
        evcsRepo = EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = sessionStorage
        )
        routePlanner = EvSmartRoutePlanner(
            coordinator = FakeRoutingCoordinator(testDispatcher)
        )

        viewModel = RouteViewModel(
            locationsRepository = locationsRepo,
            evSmartRoutePlanner = routePlanner,
            evcsRepository = evcsRepo,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        CarNavigationDispatcher.resetTestLaunchers()
        CarFocusModeBridge.resetTestStarter()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. EV Smart Routing Settings UI & Two-Way Reactive Sync
    // =========================================================================

    @Test
    fun testSettingsModal_containsRequiredEvConfigurationComponents() {
        val modalSourceFile = File("src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt")
        val altPathFile = File("app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt")
        val targetFile = if (modalSourceFile.exists()) modalSourceFile else altPathFile

        assertTrue("RoutingSettingsModal source file must exist", targetFile.exists())
        val content = targetFile.readText()

        // Section header
        assertTrue(
            "Settings modal must include header 'Cấu hình Lộ trình & Pin Xe EV'",
            content.contains("Cấu hình Lộ trình & Pin Xe EV")
        )

        // Safe range slider (100 - 500 km, default 200)
        assertTrue(
            "Settings modal must include safe range slider with explanatory text",
            content.contains("Quãng đường an toàn ở 100% pin") &&
                    content.contains("Khai báo số km thực tế xe đi được an toàn ở 100% pin")
        )

        // Arrival reserve buffer slider (5 - 25%, default 10%)
        assertTrue(
            "Settings modal must include arrival buffer slider",
            content.contains("Mức pin dự phòng tối thiểu khi đến trạm / đích")
        )

        // Target charging SoC slider (70 - 95%, default 85%)
        assertTrue(
            "Settings modal must include target charging SoC slider",
            content.contains("Mức pin mục tiêu khi sạc")
        )

        // Safety duration buffer switch (+25%)
        assertTrue(
            "Settings modal must include +25% safety duration delay buffer switch",
            content.contains("Cộng thêm 25% thời gian trễ an toàn khi sạc")
        )

        // EvSmartRoutingSettingsCard embedding
        assertTrue(
            "Settings modal must embed EvSmartRoutingSettingsCard",
            content.contains("EvSmartRoutingSettingsCard(")
        )
    }

    @Test
    fun testTwoWayReactiveSynchronization_betweenPreferencesAndViewModel() = runTest {
        // Initial defaults
        val initialEvSettings = prefsManager.evRoutingSettings.value
        assertEquals(200, initialEvSettings.vehicleSafeRangeKm)
        assertEquals(100, initialEvSettings.startBatteryPercent)
        assertEquals(10, initialEvSettings.arrivalBufferSocPercent)
        assertEquals(85, initialEvSettings.targetChargingSocPercent)
        assertTrue(initialEvSettings.safetyDurationBufferEnabled)

        // Initial ViewModel UI State matches preferences
        assertEquals(200, viewModel.uiState.value.safeRangeKm)
        assertEquals(100, viewModel.uiState.value.startBatteryPercent)

        // Direction A: Updating via RoutingPreferencesManager updates ViewModel
        prefsManager.updateVehicleSafeRangeKm(320)
        prefsManager.updateStartBatteryPercent(85)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(320, viewModel.uiState.value.safeRangeKm)
        assertEquals(85, viewModel.uiState.value.startBatteryPercent)

        // Direction B: Updating via ViewModel UI actions updates RoutingPreferencesManager
        viewModel.onSafeRangeChanged(280)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(280, viewModel.uiState.value.safeRangeKm)
        assertEquals(280, prefsManager.evRoutingSettings.value.vehicleSafeRangeKm)

        viewModel.onStartBatteryPercentChanged(75)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(75, viewModel.uiState.value.startBatteryPercent)
        assertEquals(75, prefsManager.evRoutingSettings.value.startBatteryPercent)

        // Boundary clamping: safe range [100, 500]
        viewModel.onSafeRangeChanged(50) // below min
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(100, viewModel.uiState.value.safeRangeKm)
        assertEquals(100, prefsManager.evRoutingSettings.value.vehicleSafeRangeKm)

        viewModel.onSafeRangeChanged(750) // above max
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(500, viewModel.uiState.value.safeRangeKm)
        assertEquals(500, prefsManager.evRoutingSettings.value.vehicleSafeRangeKm)

        // Boundary clamping: start battery [10, 100]
        viewModel.onStartBatteryPercentChanged(5) // below min
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(10, viewModel.uiState.value.startBatteryPercent)
        assertEquals(10, prefsManager.evRoutingSettings.value.startBatteryPercent)

        viewModel.onStartBatteryPercentChanged(120) // above max
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(100, viewModel.uiState.value.startBatteryPercent)
        assertEquals(100, prefsManager.evRoutingSettings.value.startBatteryPercent)
    }

    // =========================================================================
    // 2. Start Navigation CTA & Multi-Stop Route Session Data Modeling
    // =========================================================================

    @Test
    fun testRouteScreen_containsStartNavigationCtaButton() {
        val screenFile = File("src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt")
        val altScreenFile = File("app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt")
        val targetFile = if (screenFile.exists()) screenFile else altScreenFile
        assertTrue("RouteScreen source file must exist", targetFile.exists())
        val content = targetFile.readText()

        assertTrue(
            "RouteScreen must define StartNavigationCtaButton with text 'BẮT ĐẦU DẪN ĐƯỜNG'",
            content.contains("BẮT ĐẦU DẪN ĐƯỜNG") && content.contains("fun StartNavigationCtaButton(")
        )

        assertTrue(
            "RouteResultsSection must embed StartNavigationCtaButton",
            content.contains("StartNavigationCtaButton(")
        )
    }

    @Test
    fun testMultiStopRouteSession_modelingAndJsonIntentSerialization() {
        val samplePlan = createSampleMultiStopRoutePlan()
        val session = RouteSessionData(
            plan = samplePlan,
            currentLegIndex = 0,
            originLabel = "Hà Nội, Hoàn Kiếm",
            destinationLabel = "Đà Nẵng, Hải Châu"
        )

        // 2 intermediate stops => total 3 legs
        assertEquals(3, session.totalLegs)
        assertEquals(0, session.currentLegIndex)
        assertFalse(session.isFinalLeg)
        assertFalse(session.isCompleted)

        // Leg 1 target is Stop 1: Ninh Bình
        assertEquals("stop_ninh_binh", session.currentTargetStation.id)
        assertEquals("Trạm Sạc VinFast Ninh Bình", session.currentTargetStation.name)
        assertEquals("Chặng 1/3: Đến Trạm Sạc VinFast Ninh Bình", session.progressionLabel)

        // Intent specification creation & JSON serialization
        val startSpec = FocusModeForegroundService.getStartRouteSessionIntentSpec(session)
        assertEquals(FocusModeForegroundService.ACTION_START_ROUTE_SESSION, startSpec.action)
        assertNotNull(startSpec.payloadJson)

        // Deserialization round-trip
        val deserialized = FocusModeForegroundService.parseRouteSessionJson(startSpec.payloadJson!!)
        assertNotNull(deserialized)
        assertEquals(session.plan.totalDistanceKm, deserialized!!.plan.totalDistanceKm, 0.001)
        assertEquals(session.plan.stops.size, deserialized.plan.stops.size)
        assertEquals(session.originLabel, deserialized.originLabel)
        assertEquals(session.destinationLabel, deserialized.destinationLabel)
        assertEquals("stop_ninh_binh", deserialized.currentTargetStation.id)
    }

    // =========================================================================
    // 3. Multi-Stop Navigation Dispatch Pipeline & Intent Specs
    // =========================================================================

    @Test
    fun testNavigationDispatch_generatesLeg1SpecsForInCarAndMobile() {
        val samplePlan = createSampleMultiStopRoutePlan()
        val session = RouteSessionData(
            plan = samplePlan,
            currentLegIndex = 0,
            originLabel = "Hà Nội, Hoàn Kiếm",
            destinationLabel = "Đà Nẵng, Hải Châu"
        )

        // In-Car Head Unit spec (CarNavigationIntentSpec)
        val carSpec = CarNavigationDispatcher.getRouteNavigationIntentSpec(session)
        assertEquals(CarContext.ACTION_NAVIGATE, carSpec.action)
        assertTrue(carSpec.uriString.contains("20.2506,105.9745"))

        // Mobile Fallback Navigation spec
        val fallbackSpec = CarNavigationDispatcher.getFallbackRouteNavigationIntentSpec(session)
        assertEquals("android.intent.action.VIEW", fallbackSpec.action)
        assertTrue(fallbackSpec.uriString.contains("google.navigation:q=20.2506,105.9745"))

        // Test dispatch hook captures Leg 1 spec and triggers Focus Mode startup
        var dispatchedSpec: CarNavigationIntentSpec? = null
        var focusServiceSpec: FocusServiceIntentSpec? = null

        CarNavigationDispatcher.testActivitySpecLauncher = { spec -> dispatchedSpec = spec }
        CarFocusModeBridge.testServiceSpecStarter = { spec -> focusServiceSpec = spec }

        val success = CarNavigationDispatcher.dispatchRouteNavigation(null, session)

        assertTrue(success)
        assertNotNull(dispatchedSpec)
        assertTrue(dispatchedSpec?.uriString?.contains("20.2506,105.9745") == true)

        assertNotNull(focusServiceSpec)
        assertEquals(FocusModeForegroundService.ACTION_START_ROUTE_SESSION, focusServiceSpec?.action)
        assertNotNull(focusServiceSpec?.payloadJson)
    }

    // =========================================================================
    // 4. Telemetry Engine Arrival Detection (<= 300 meters)
    // =========================================================================

    @Test
    fun testArrivalDetection_triggersAt300Meters() = runTest {
        val samplePlan = createSampleMultiStopRoutePlan()
        val session = RouteSessionData(
            plan = samplePlan,
            currentLegIndex = 0,
            originLabel = "Hà Nội, Hoàn Kiếm",
            destinationLabel = "Đà Nẵng, Hải Châu"
        )
        val stop1 = session.currentTargetStation // (20.2506, 105.9745)

        val engine = FocusModeTelemetryEngine(
            initialStation = stop1,
            fetchStationTelemetry = { _, _, _ -> Result.success(stop1) },
            defaultDispatcher = testDispatcher,
            initialRouteSession = session
        )

        // 1. Driver far away (5 km)
        engine.updateDriverLocation(20.20, 105.95)
        assertFalse("Must NOT detect arrival when distance is > 300m", engine.state.value.hasArrivedAtStop)
        assertNull(engine.state.value.arrivalMessage)

        // 2. Driver approaches within 250m (<= 0.3 km)
        // Ninh Bình coords: 20.2506, 105.9745. Driver at: 20.2515, 105.9750 (~110m away)
        engine.updateDriverLocation(20.2515, 105.9750)
        assertTrue(
            "Must detect arrival when within 300 meters of stop waypoint",
            engine.state.value.hasArrivedAtStop
        )
        assertEquals(
            "Đã đến trạm sạc: VinFast Ninh Bình",
            engine.state.value.arrivalMessage
        )

        // 3. Floating HUD presentation reflects arrival state
        val floatingState = FocusModeViewLayoutHelper.formatViewState(engine.state.value)
        assertEquals("Đã đến trạm sạc: VinFast Ninh Bình", floatingState.badgeText)
        assertEquals(FocusBadgeColor.GREEN, floatingState.badgeColorToken)
        assertTrue(floatingState.isRerouteAvailable)
        assertEquals("Tiếp tục chặng tiếp theo", floatingState.rerouteButtonText)
    }

    // =========================================================================
    // 5. Waypoint Progression Pipeline ("Tiếp tục chặng tiếp theo")
    // =========================================================================

    @Test
    fun testWaypointProgression_advancesFromStop1ToStop2AndDestination() = runTest {
        val samplePlan = createSampleMultiStopRoutePlan()
        val session = RouteSessionData(
            plan = samplePlan,
            currentLegIndex = 0,
            originLabel = "Hà Nội, Hoàn Kiếm",
            destinationLabel = "Đà Nẵng, Hải Châu"
        )

        val engine = FocusModeTelemetryEngine(
            initialStation = session.currentTargetStation,
            fetchStationTelemetry = { _, _, _ -> Result.success(session.currentTargetStation) },
            defaultDispatcher = testDispatcher,
            initialRouteSession = session
        )

        // Arrive at Stop 1 (Ninh Bình)
        engine.updateDriverLocation(20.2506, 105.9745)
        assertTrue(engine.state.value.hasArrivedAtStop)
        assertEquals("Chặng 1/3: Đến Trạm Sạc VinFast Ninh Bình", engine.state.value.routeSession?.progressionLabel)

        // --- Action: "Tiếp tục chặng tiếp theo" (Advance to Leg 2) ---
        val leg2Session = engine.advanceRouteLeg()
        assertNotNull(leg2Session)
        assertEquals(1, leg2Session!!.currentLegIndex)
        assertEquals("stop_vinh", leg2Session.currentTargetStation.id)
        assertEquals("Trạm Sạc VinFast Vinh", leg2Session.currentTargetStation.name)
        assertEquals("Chặng 2/3: Đến Trạm Sạc VinFast Vinh", leg2Session.progressionLabel)
        assertFalse(leg2Session.isFinalLeg)

        // Arrival state must reset for the new leg
        assertFalse(engine.state.value.hasArrivedAtStop)
        assertNull(engine.state.value.arrivalMessage)
        assertEquals("VinFast Vinh", engine.state.value.targetStation.name)

        // Verify updated navigation intent spec targets Stop 2 (Vinh)
        val leg2Spec = CarNavigationDispatcher.getRouteNavigationIntentSpec(leg2Session)
        assertTrue(leg2Spec.uriString.contains("18.6796,105.6813"))

        // --- Driver arrives at Stop 2 (Vinh: 18.6796, 105.6813) ---
        engine.updateDriverLocation(18.6796, 105.6813)
        assertTrue(engine.state.value.hasArrivedAtStop)
        assertEquals("Đã đến trạm sạc: VinFast Vinh", engine.state.value.arrivalMessage)

        // --- Action: "Tiếp tục chặng tiếp theo" (Advance to Leg 3: Final Destination) ---
        val leg3Session = engine.advanceRouteLeg()
        assertNotNull(leg3Session)
        assertEquals(2, leg3Session!!.currentLegIndex)
        assertTrue(leg3Session.isFinalLeg)
        assertEquals("Đà Nẵng, Hải Châu", leg3Session.currentTargetStation.name)
        assertEquals("Chặng cuối 3/3: Đến Đà Nẵng, Hải Châu", leg3Session.progressionLabel)

        // Reset arrival state for final leg
        assertFalse(engine.state.value.hasArrivedAtStop)
        assertNull(engine.state.value.arrivalMessage)

        // Verify navigation intent targets final destination Đà Nẵng (16.0544, 108.2022)
        val finalSpec = CarNavigationDispatcher.getFallbackRouteNavigationIntentSpec(leg3Session)
        assertTrue(finalSpec.uriString.contains("16.0544,108.2022"))

        // Arrive at Final Destination
        engine.updateDriverLocation(16.0544, 108.2022)
        assertTrue(engine.state.value.hasArrivedAtStop)

        // Advancing beyond final destination returns null (trip finished)
        val finishedSession = engine.advanceRouteLeg()
        assertNull("Advancing past final destination must return null", finishedSession)
    }

    // =========================================================================
    // Test Helpers
    // =========================================================================

    private fun createSampleMultiStopRoutePlan(): EvSmartRoutePlan {
        val stop1Station = Station(
            id = "stop_ninh_binh",
            name = "Trạm Sạc VinFast Ninh Bình",
            address = "Trần Hưng Đạo, Ninh Bình",
            latitude = 20.2506,
            longitude = 105.9745,
            summary = "Trạm sạc 250kW",
            connectors = "CCS2 x 4",
            depotStatus = "Normal",
            powers = listOf(PowerPort(typeWatts = 250_000L, availablePlugs = 3, totalPlugs = 4)),
            totalAvailablePlugs = 3,
            totalPlugs = 4
        )

        val stop2Station = Station(
            id = "stop_vinh",
            name = "Trạm Sạc VinFast Vinh",
            address = "Quang Trung, TP Vinh, Nghệ An",
            latitude = 18.6796,
            longitude = 105.6813,
            summary = "Trạm sạc 150kW",
            connectors = "CCS2 x 2",
            depotStatus = "Normal",
            powers = listOf(PowerPort(typeWatts = 150_000L, availablePlugs = 2, totalPlugs = 2)),
            totalAvailablePlugs = 2,
            totalPlugs = 2
        )

        val stop1 = EvRouteStop(
            stopIndex = 1,
            station = stop1Station,
            distanceFromOriginKm = 95.0,
            distanceFromPreviousStopKm = 95.0,
            arrivalBatteryPercent = 52,
            targetBatteryPercent = 85,
            estimatedChargingMinutes = 22,
            maxPowerKw = 250.0,
            chargerTier = ChargerTier.ULTRA_FAST_DC,
            availabilityStatus = StopAvailabilityStatus.AVAILABLE
        )

        val stop2 = EvRouteStop(
            stopIndex = 2,
            station = stop2Station,
            distanceFromOriginKm = 295.0,
            distanceFromPreviousStopKm = 200.0,
            arrivalBatteryPercent = 18,
            targetBatteryPercent = 85,
            estimatedChargingMinutes = 35,
            maxPowerKw = 150.0,
            chargerTier = ChargerTier.ULTRA_FAST_DC,
            availabilityStatus = StopAvailabilityStatus.AVAILABLE
        )

        return EvSmartRoutePlan(
            originLat = 21.0285,
            originLng = 105.8542,
            destinationLat = 16.0544,
            destinationLng = 108.2022,
            totalDistanceKm = 760.0,
            totalDrivingDurationSeconds = 34200L,
            totalChargingDurationMinutes = 57,
            stops = listOf(stop1, stop2),
            energyProfile = listOf(
                EnergyWaypoint(distanceKm = 0.0, batteryPercent = 100),
                EnergyWaypoint(distanceKm = 95.0, batteryPercent = 52, isChargingStop = true),
                EnergyWaypoint(distanceKm = 295.0, batteryPercent = 18, isChargingStop = true),
                EnergyWaypoint(distanceKm = 760.0, batteryPercent = 32)
            ),
            polylineCoordinates = listOf(
                RouteCoordinate(21.0285, 105.8542),
                RouteCoordinate(20.2506, 105.9745),
                RouteCoordinate(18.6796, 105.6813),
                RouteCoordinate(16.0544, 108.2022)
            ),
            deadZoneWarning = null,
            finalBatteryPercent = 32
        )
    }
}
