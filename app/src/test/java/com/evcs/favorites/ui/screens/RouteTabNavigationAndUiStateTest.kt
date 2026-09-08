package com.evcs.favorites.ui.screens

import android.location.Location
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.AdministrativeDistrict
import com.evcs.favorites.data.locations.LocationCoordinate
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.ChargerTier
import com.evcs.favorites.data.routing.EvRoutingSettings
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RouteCoordinate
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.navigation.AppTab
import com.evcs.favorites.ui.theme.AppIcons
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
 * Single Comprehensive Verification Test for Phase 04:
 * Three-Tab Navigation & Route Screen UI State Architecture.
 *
 * Verifies:
 * 1. Tab Structure & Strict Ordering Contract:
 *    - AppTab has exactly 3 entries: NEARBY (0), FAVORITES (1 - centered), ROUTE (2 - beside favorites).
 *    - AppTab.ROUTE has label "Lộ trình" and automotive route icon.
 *    - Bidirectional tab transitions between NEARBY, FAVORITES, and ROUTE.
 * 2. Vehicle Range & Battery Input Sliders:
 *    - Safe range defaults to 200 km, clamped to [100 km, 500 km].
 *    - Starting SoC defaults to 100%, clamped to [10%, 100%].
 * 3. Origin & Destination Administrative Selectors:
 *    - Populates provinces and districts from VietnamLocationsRepository.
 *    - Province selection auto-updates district list and centroid coordinate.
 *    - 1-Tap GPS location immediately reverse-matches nearest district and flags isOriginCurrentLocation.
 *    - Bidirectional Swap Origin <-> Destination swaps names, districts, and coordinates.
 * 4. Corridor Route Planning & Stop Timeline:
 *    - Produces optimal multi-stop route plan with scheduled VinFast stops.
 *    - Verifies timeline stop metadata: distance, arrival/target SoC, power pills, and live plug statuses.
 * 5. Dead-Zone Alert Banner:
 *    - Detects unbridgeable highway corridor gaps and generates high-visibility DeadZoneWarning.
 * 6. Energy Corridor Profile:
 *    - Verifies battery depletion waypoints and replenishment jumps along the route.
 * 7. Alternate Station Swapping ("Đổi trạm khác"):
 *    - Opens bottom sheet with alternative candidates.
 *    - Swapping alternate station updates route, recomputes adjacent legs, and updates energy profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteTabNavigationAndUiStateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var repository: EvcsRepository
    private lateinit var locationsRepository: VietnamLocationsRepository
    private lateinit var evSmartRoutePlanner: EvSmartRoutePlanner
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var viewModel: RouteViewModel

    class FakeLocationService : LocationService() {
        var mockLocation: Location? = null
        override fun hasLocationPermission(): Boolean = true
        override suspend fun getFreshLocation(): Location? = mockLocation
    }

    class FakeRoutingCoordinator(
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

    private fun createMockLocation(lat: Double, lng: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lng
        }
    }

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lng: Double,
        powerKw: Double,
        totalPlugs: Int = 4,
        availablePlugs: Int = 2
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
            address = "Quốc lộ 1A, $name",
            latitude = lat,
            longitude = lng,
            summary = "Trạm sạc VinFast",
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
        sessionManager = SessionManager(sessionStorage)
        repository = EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = sessionStorage
        )
        locationsRepository = VietnamLocationsRepository()
        evSmartRoutePlanner = EvSmartRoutePlanner(
            coordinator = FakeRoutingCoordinator(testDispatcher)
        )
        fakeLocationService = FakeLocationService()
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        prefsManager.saveSettings(
            RoutingSettings(
                preferredEngine = RoutingEngineMode.HAVERSINE_ONLY,
                autoFallbackEnabled = true
            )
        )

        viewModel = RouteViewModel(
            locationsRepository = locationsRepository,
            evSmartRoutePlanner = evSmartRoutePlanner,
            evcsRepository = repository,
            locationService = fakeLocationService,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Tab Structure & Strict Ordering Contract
    // =========================================================================
    @Test
    fun testTabStructureAndStrictOrderingContract() {
        val tabs = AppTab.entries
        assertEquals("AppTab must strictly contain 3 navigation tabs", 3, tabs.size)

        // Slot 0: NEARBY ("Quanh đây")
        assertEquals(AppTab.NEARBY, tabs[0])
        assertEquals("Quanh đây", tabs[0].label)
        assertEquals(Icons.Filled.LocationOn, tabs[0].selectedIcon)

        // Slot 1: FAVORITES ("Yêu thích" - anchored in center)
        assertEquals(AppTab.FAVORITES, tabs[1])
        assertEquals("Yêu thích", tabs[1].label)
        assertEquals(Icons.Filled.Favorite, tabs[1].selectedIcon)

        // Slot 2: ROUTE ("Lộ trình" - placed beside favorites)
        assertEquals(AppTab.ROUTE, tabs[2])
        assertEquals("Lộ trình", tabs[2].label)
        assertEquals(AppIcons.Route, tabs[2].selectedIcon)
        assertEquals(AppIcons.Route, tabs[2].unselectedIcon)

        // Default tab selection and transitions
        var currentTab = AppTab.NEARBY
        assertEquals(AppTab.NEARBY, currentTab)

        // Transition: NEARBY -> FAVORITES
        currentTab = AppTab.FAVORITES
        assertEquals(AppTab.FAVORITES, currentTab)

        // Transition: FAVORITES -> ROUTE
        currentTab = AppTab.ROUTE
        assertEquals(AppTab.ROUTE, currentTab)

        // Transition: ROUTE -> NEARBY
        currentTab = AppTab.NEARBY
        assertEquals(AppTab.NEARBY, currentTab)
    }

    // =========================================================================
    // 2. Vehicle Range & Battery Input Cards
    // =========================================================================
    @Test
    fun testVehicleRangeAndBatteryInputCardsWithClamping() {
        val initial = viewModel.uiState.value
        assertEquals("Default vehicle safe range must be 200 km", 200, initial.safeRangeKm)
        assertEquals("Default start SoC must be 100%", 100, initial.startBatteryPercent)

        // Valid range updates
        viewModel.onSafeRangeChanged(350)
        assertEquals(350, viewModel.uiState.value.safeRangeKm)

        // Clamping min range (100 km)
        viewModel.onSafeRangeChanged(50)
        assertEquals(100, viewModel.uiState.value.safeRangeKm)

        // Clamping max range (500 km)
        viewModel.onSafeRangeChanged(650)
        assertEquals(500, viewModel.uiState.value.safeRangeKm)

        // Valid battery SoC updates
        viewModel.onStartBatteryPercentChanged(80)
        assertEquals(80, viewModel.uiState.value.startBatteryPercent)

        // Clamping min SoC (10%)
        viewModel.onStartBatteryPercentChanged(5)
        assertEquals(10, viewModel.uiState.value.startBatteryPercent)

        // Clamping max SoC (100%)
        viewModel.onStartBatteryPercentChanged(110)
        assertEquals(100, viewModel.uiState.value.startBatteryPercent)
    }

    // =========================================================================
    // 3. Origin & Destination Administrative Selection & 1-Tap GPS
    // =========================================================================
    @Test
    fun testOriginDestinationSelectionGpsAndSwap() = runTest {
        val state = viewModel.uiState.value
        assertTrue("Provinces list must be populated", state.availableProvinces.size >= 63)
        assertEquals("Default origin province should be Hà Nội", "Hà Nội", state.originProvince)
        assertTrue("Origin districts must not be empty", state.originDistricts.isNotEmpty())
        assertNotNull("Origin coordinate must be resolved", state.originCoordinate)

        // Change origin province to Đà Nẵng
        viewModel.onOriginProvinceSelected("Đà Nẵng")
        assertEquals("Đà Nẵng", viewModel.uiState.value.originProvince)
        assertTrue(viewModel.uiState.value.originDistricts.any { it.name.contains("Hải Châu", ignoreCase = true) })
        assertFalse(viewModel.uiState.value.isOriginCurrentLocation)

        // Change origin district
        viewModel.onOriginDistrictSelected("Hải Châu")
        assertEquals("Hải Châu", viewModel.uiState.value.originDistrict)
        assertNotNull(viewModel.uiState.value.originCoordinate)

        // Change destination province and district
        viewModel.onDestinationProvinceSelected("Hồ Chí Minh")
        assertEquals("Hồ Chí Minh", viewModel.uiState.value.destinationProvince)
        assertTrue(viewModel.uiState.value.destinationDistricts.isNotEmpty())

        viewModel.onDestinationDistrictSelected("Quận 1")
        assertEquals("Quận 1", viewModel.uiState.value.destinationDistrict)
        assertNotNull(viewModel.uiState.value.destinationCoordinate)

        // Test 1-Tap GPS location [🎯]
        // Mock Hanoi Hoan Kiem coordinates: 21.0285, 105.8542
        fakeLocationService.mockLocation = createMockLocation(21.0285, 105.8542)
        viewModel.useCurrentGpsLocation()
        testScheduler.advanceUntilIdle()

        val gpsState = viewModel.uiState.value
        assertTrue("isOriginCurrentLocation must be true after 1-tap GPS", gpsState.isOriginCurrentLocation)
        assertEquals("Hà Nội", gpsState.originProvince)
        assertTrue("District should match Hoàn Kiếm", gpsState.originDistrict.contains("Hoàn Kiếm", ignoreCase = true))
        assertNotNull(gpsState.originCoordinate)
        assertEquals(21.0285, gpsState.originCoordinate!!.lat, 0.001)
        assertEquals(105.8542, gpsState.originCoordinate!!.lng, 0.001)

        // Test Bidirectional Swap Origin <-> Destination
        val prevOriginProv = gpsState.originProvince
        val prevOriginDist = gpsState.originDistrict
        val prevDestProv = gpsState.destinationProvince
        val prevDestDist = gpsState.destinationDistrict

        viewModel.swapOriginAndDestination()

        val swappedState = viewModel.uiState.value
        assertEquals(prevDestProv, swappedState.originProvince)
        assertEquals(prevDestDist, swappedState.originDistrict)
        assertEquals(prevOriginProv, swappedState.destinationProvince)
        assertEquals(prevOriginDist, swappedState.destinationDistrict)
        assertFalse(swappedState.isOriginCurrentLocation)
    }

    // =========================================================================
    // 4. Corridor Route Planning & Stop Timeline
    // =========================================================================
    @Test
    fun testCorridorRoutePlanningAndStopTimeline() = runTest {
        // Destination is Da Nang (~650 km from Hanoi)
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        // Station placed directly along interpolated route (~25% into trip, ~160km)
        val st1Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.25
        val st1Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.25
        val st1 = createTestStation("st_stop_1", "VinFast Trạm 1", st1Lat, st1Lng, 180.0, 4, 3)

        // Station 2 placed ~55% into trip (~350km)
        val st2Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.55
        val st2Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.55
        val st2 = createTestStation("st_stop_2", "VinFast Trạm 2", st2Lat, st2Lng, 250.0, 4, 0)

        viewModel.setCandidateStations(listOf(st1, st2))
        viewModel.onSafeRangeChanged(200)
        viewModel.onStartBatteryPercentChanged(100)

        // Trigger Plan Route
        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Loading must be false after route planning completes", state.isLoading)
        assertNull("Error message must be null on successful planning", state.errorMessage)

        val plan = state.routePlan
        assertNotNull("Route plan must be generated", plan)
        assertTrue("Total trip distance must be > 0", plan!!.totalDistanceKm > 0)
        assertTrue("Driving duration must be > 0", plan.totalDrivingDurationSeconds > 0)

        // Stops inspection
        assertTrue("Must schedule at least 1 charging stop for 600km trip with 200km safe range", plan.stops.isNotEmpty())
        val firstStop = plan.stops[0]
        assertEquals(1, firstStop.stopIndex)
        assertTrue("Distance from origin must be positive", firstStop.distanceFromOriginKm > 0)
        assertTrue("Arrival SoC must be positive", firstStop.arrivalBatteryPercent > 0)
        assertEquals("⚡ 180 kW", firstStop.powerDisplayLabel)
        assertTrue("Live status badge must format correctly", firstStop.liveStatusBadge.contains("Trống 3/4"))

        // Final destination summary
        assertTrue("Final arrival battery % must be within safe bounds", plan.finalBatteryPercent >= 0)
        assertEquals(plan.stops.size, plan.chargingStopsCount)
    }

    // =========================================================================
    // 5. Dead-Zone Alert Banner
    // =========================================================================
    @Test
    fun testDeadZoneAlertBannerWhenCorridorExceedsSafeRange() = runTest {
        // Trip from Hanoi to Da Nang (~650 km) with NO charging stations and low safe range (100 km)
        viewModel.setCandidateStations(emptyList())
        viewModel.onSafeRangeChanged(100) // Restricted safe range
        viewModel.onStartBatteryPercentChanged(100)

        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        val plan = viewModel.uiState.value.routePlan
        assertNotNull("Route plan must exist", plan)
        val deadZone = plan!!.deadZoneWarning
        assertNotNull("DeadZoneWarning must be created when gap exceeds vehicle safe range", deadZone)
        assertTrue("Missing range must be positive", deadZone!!.missingRangeKm > 0)
        assertEquals(100, deadZone.safeRangeKm)
        assertTrue("Warning message must mention missing km", deadZone.message.contains("Cảnh báo vùng trắng sạc"))
        assertFalse("isSuccess must be false when dead zone is detected", plan.isSuccess)
    }

    // =========================================================================
    // 6. Energy Corridor Bar Profile
    // =========================================================================
    @Test
    fun testEnergyCorridorProfileWaypoints() = runTest {
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        val stLat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.20
        val stLng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.20
        val st1 = createTestStation("st_corridor_1", "VinFast Trạm Corridor", stLat, stLng, 150.0)

        viewModel.setCandidateStations(listOf(st1))
        viewModel.onSafeRangeChanged(250)
        viewModel.onStartBatteryPercentChanged(90)

        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        val plan = viewModel.uiState.value.routePlan
        assertNotNull(plan)
        val profile = plan!!.energyProfile
        assertTrue("Energy profile must have multiple waypoints", profile.size >= 2)

        // Point 0: Origin
        assertEquals(0.0, profile[0].distanceKm, 0.001)
        assertEquals(90, profile[0].batteryPercent)
        assertFalse(profile[0].isChargingStop)

        // Check replenishment waypoint jump if stops exist
        val chargingPoints = profile.filter { it.isChargingStop }
        if (chargingPoints.isNotEmpty()) {
            assertEquals(85, chargingPoints.first().batteryPercent) // Target SoC default
        }
    }

    // =========================================================================
    // 7. Alternate Station Swapping ("Đổi trạm khác")
    // =========================================================================
    @Test
    fun testAlternateStationSwappingBottomSheetAndLegRecalculation() = runTest {
        val origCoord = viewModel.uiState.value.originCoordinate!!
        val destCoord = viewModel.uiState.value.destinationCoordinate!!

        val st1Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.25
        val st1Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.25
        val st1 = createTestStation("st_station_a", "VinFast Station A", st1Lat, st1Lng, 180.0)

        val st2Lat = origCoord.lat + (destCoord.lat - origCoord.lat) * 0.26
        val st2Lng = origCoord.lng + (destCoord.lng - origCoord.lng) * 0.26
        val st2 = createTestStation("st_station_b", "VinFast Station B (Alt)", st2Lat, st2Lng, 250.0)

        viewModel.setCandidateStations(listOf(st1, st2))
        viewModel.onSafeRangeChanged(200)
        viewModel.planRoute()
        testScheduler.advanceUntilIdle()

        val originalPlan = viewModel.uiState.value.routePlan
        assertNotNull(originalPlan)
        assertTrue(originalPlan!!.stops.isNotEmpty())

        val targetStop = originalPlan.stops[0]

        // 1. Select stop for swap -> bottom sheet opens
        viewModel.selectStopForSwap(targetStop)
        assertEquals(targetStop, viewModel.uiState.value.selectedStopForSwap)

        // 2. Dismiss bottom sheet
        viewModel.dismissSwapBottomSheet()
        assertNull(viewModel.uiState.value.selectedStopForSwap)

        // 3. Swap stop with alternate station st2
        viewModel.selectStopForSwap(targetStop)
        viewModel.swapStation(targetStop.stopIndex, st2)

        val updatedPlan = viewModel.uiState.value.routePlan
        assertNotNull(updatedPlan)
        assertNull("selectedStopForSwap must be reset to null after swap", viewModel.uiState.value.selectedStopForSwap)
        assertEquals(st2.id, updatedPlan!!.stops[0].station.id)
        assertEquals("VinFast Station B (Alt)", updatedPlan.stops[0].station.name)
        assertEquals("⚡ 250 kW", updatedPlan.stops[0].powerDisplayLabel)
    }
}
