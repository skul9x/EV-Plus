package com.evcs.favorites.ui.screens

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.EnergyWaypoint
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.components.calculateCorridorPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 04:
 * Compose Dropdown & Zero-Allocation UI Optimizations.
 *
 * Verifies:
 * 1. VietnamLocationsRepository:
 *    - `getAllProvinces()` returns cached instance across multiple calls (`assertSame`).
 *    - Pinned metropolises (Hà Nội, Hồ Chí Minh, Đà Nẵng) are preserved at top.
 * 2. RouteUiState & RouteViewModel:
 *    - Pre-computed `originDistrictNames` and `destinationDistrictNames` are populated in state.
 *    - Synchronized with `originDistricts` and `destinationDistricts` during initialization,
 *      province change, GPS 1-tap resolution, and origin-destination swapping.
 * 3. EnergyCorridorBar Geometry:
 *    - Pure coordinate projection function `calculateCorridorPoints` calculates exact x, y values
 *      across normal, multi-stop charging jump, and edge case profiles without object churn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteUiPerformanceOptimizationTest {

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
    // 1. VietnamLocationsRepository Province Cache Memoization
    // =========================================================================
    @Test
    fun testLocationsRepositoryReturnsCachedProvincesInstance() {
        val firstCall = locationsRepository.getAllProvinces()
        val secondCall = locationsRepository.getAllProvinces()
        val thirdCall = locationsRepository.cachedProvinces

        assertTrue("Provinces list must contain 63 provinces/cities", firstCall.size >= 63)
        assertSame("getAllProvinces() must return the exact same cached instance across calls", firstCall, secondCall)
        assertSame("cachedProvinces must match getAllProvinces() instance", firstCall, thirdCall)

        // Verify pinned cities at the top
        assertEquals("Hà Nội", firstCall[0])
        assertEquals("Hồ Chí Minh", firstCall[1])
        assertEquals("Đà Nẵng", firstCall[2])
    }

    // =========================================================================
    // 2. RouteUiState Precomputed District Names Synchronization
    // =========================================================================
    @Test
    fun testRouteUiStateDistrictNamesSynchronization() = runTest {
        // Initial state
        val initialState = viewModel.uiState.value
        assertTrue("originDistrictNames must not be empty", initialState.originDistrictNames.isNotEmpty())
        assertTrue("destinationDistrictNames must not be empty", initialState.destinationDistrictNames.isNotEmpty())
        assertEquals(
            initialState.originDistricts.map { it.name },
            initialState.originDistrictNames
        )
        assertEquals(
            initialState.destinationDistricts.map { it.name },
            initialState.destinationDistrictNames
        )

        // 1. Change Origin Province to Đà Nẵng
        viewModel.onOriginProvinceSelected("Đà Nẵng")
        val stateAfterOriginChange = viewModel.uiState.value
        assertEquals("Đà Nẵng", stateAfterOriginChange.originProvince)
        assertEquals(
            stateAfterOriginChange.originDistricts.map { it.name },
            stateAfterOriginChange.originDistrictNames
        )
        assertTrue(stateAfterOriginChange.originDistrictNames.any { it.contains("Hải Châu", ignoreCase = true) })

        // 2. Change Destination Province to Hà Nội
        viewModel.onDestinationProvinceSelected("Hà Nội")
        val stateAfterDestChange = viewModel.uiState.value
        assertEquals("Hà Nội", stateAfterDestChange.destinationProvince)
        assertEquals(
            stateAfterDestChange.destinationDistricts.map { it.name },
            stateAfterDestChange.destinationDistrictNames
        )
        assertTrue(stateAfterDestChange.destinationDistrictNames.any { it.contains("Hoàn Kiếm", ignoreCase = true) })

        // 3. 1-Tap GPS Location
        fakeLocationService.mockLocation = createMockLocation(21.0285, 105.8542) // Hanoi
        viewModel.useCurrentGpsLocation()
        testScheduler.advanceUntilIdle()

        val gpsState = viewModel.uiState.value
        assertTrue(gpsState.isOriginCurrentLocation)
        assertEquals(
            gpsState.originDistricts.map { it.name },
            gpsState.originDistrictNames
        )
        assertTrue(gpsState.originDistrictNames.any { it.contains("Hoàn Kiếm", ignoreCase = true) })

        // 4. Swap Origin <-> Destination
        val prevOriginNames = gpsState.originDistrictNames
        val prevDestNames = gpsState.destinationDistrictNames

        viewModel.swapOriginAndDestination()

        val swappedState = viewModel.uiState.value
        assertEquals(prevDestNames, swappedState.originDistrictNames)
        assertEquals(prevOriginNames, swappedState.destinationDistrictNames)
        assertEquals(
            swappedState.originDistricts.map { it.name },
            swappedState.originDistrictNames
        )
        assertEquals(
            swappedState.destinationDistricts.map { it.name },
            swappedState.destinationDistrictNames
        )
    }

    // =========================================================================
    // 3. EnergyCorridorBar Waypoint Geometry Functional Consistency
    // =========================================================================
    @Test
    fun testEnergyCorridorBarWaypointGeometryAcrossRouteProfiles() {
        val canvasWidth = 800f
        val canvasHeight = 90f

        // Case A: Linear depletion profile (0 km @ 100% -> 200 km @ 20%)
        val linearProfile = listOf(
            EnergyWaypoint(distanceKm = 0.0, batteryPercent = 100, isChargingStop = false),
            EnergyWaypoint(distanceKm = 100.0, batteryPercent = 60, isChargingStop = false),
            EnergyWaypoint(distanceKm = 200.0, batteryPercent = 20, isChargingStop = false)
        )
        val linearPoints = calculateCorridorPoints(
            energyProfile = linearProfile,
            totalDistanceKm = 200.0,
            width = canvasWidth,
            height = canvasHeight
        )
        assertEquals(3, linearPoints.size)

        // Origin (0 km, 100%) -> x=0, y=0 (top of canvas for 100% SoC)
        assertEquals(0f, linearPoints[0].x, 0.001f)
        assertEquals(0f, linearPoints[0].y, 0.001f)
        assertFalse(linearPoints[0].isChargingStop)

        // Midpoint (100 km, 60%) -> x=400, y=90*(1 - 0.60) = 36
        assertEquals(400f, linearPoints[1].x, 0.001f)
        assertEquals(36f, linearPoints[1].y, 0.001f)

        // Destination (200 km, 20%) -> x=800, y=90*(1 - 0.20) = 72
        assertEquals(800f, linearPoints[2].x, 0.001f)
        assertEquals(72f, linearPoints[2].y, 0.001f)

        // Case B: Multi-stop profile with charging replenishment jump
        val multiStopProfile = listOf(
            EnergyWaypoint(distanceKm = 0.0, batteryPercent = 90, isChargingStop = false),
            EnergyWaypoint(distanceKm = 150.0, batteryPercent = 25, isChargingStop = false), // Arrival at stop
            EnergyWaypoint(distanceKm = 150.0, batteryPercent = 80, isChargingStop = true),  // Replenishment jump
            EnergyWaypoint(distanceKm = 300.0, batteryPercent = 30, isChargingStop = false)  // Arrival at destination
        )
        val multiStopPoints = calculateCorridorPoints(
            energyProfile = multiStopProfile,
            totalDistanceKm = 300.0,
            width = canvasWidth,
            height = canvasHeight
        )
        assertEquals(4, multiStopPoints.size)

        // Waypoints 1 & 2 share the same distance (150 km -> x=400f)
        assertEquals(400f, multiStopPoints[1].x, 0.001f)
        assertEquals(400f, multiStopPoints[2].x, 0.001f)
        assertFalse(multiStopPoints[1].isChargingStop)
        assertTrue(multiStopPoints[2].isChargingStop)

        // Point 2 (after charging) has higher battery percent -> lower y coordinate (closer to top)
        assertTrue(
            "Replenished battery point must have lower y (closer to 100% top)",
            multiStopPoints[2].y < multiStopPoints[1].y
        )
        assertEquals(90f * (1f - 0.25f), multiStopPoints[1].y, 0.001f)
        assertEquals(90f * (1f - 0.80f), multiStopPoints[2].y, 0.001f)

        // Case C: Edge & boundary conditions
        val emptyPoints = calculateCorridorPoints(
            energyProfile = emptyList(),
            totalDistanceKm = 100.0,
            width = canvasWidth,
            height = canvasHeight
        )
        assertTrue(emptyPoints.isEmpty())

        val zeroDistancePoints = calculateCorridorPoints(
            energyProfile = linearProfile,
            totalDistanceKm = 0.0,
            width = canvasWidth,
            height = canvasHeight
        )
        assertTrue(zeroDistancePoints.isEmpty())

        val zeroDimensionPoints = calculateCorridorPoints(
            energyProfile = linearProfile,
            totalDistanceKm = 100.0,
            width = 0f,
            height = canvasHeight
        )
        assertTrue(zeroDimensionPoints.isEmpty())
    }
}
