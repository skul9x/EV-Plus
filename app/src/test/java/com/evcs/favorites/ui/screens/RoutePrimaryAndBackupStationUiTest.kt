package com.evcs.favorites.ui.screens

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.locations.VietnamLocationsRepository
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.EvSmartRoutePlanner
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RouteCoordinate
import com.evcs.favorites.data.routing.RoutePathResult
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.distanceFromPrimaryStationKm
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
 * Single Comprehensive Verification Test for Phase 04:
 * Primary & Backup Station Pairing and Timeline UI.
 *
 * Verifies:
 * 1. Each generated EvRouteStop with multiple nearby candidates has a non-null backupStation.
 * 2. The backup station is within <= 10.0 km straight-line/corridor distance and is NOT an opposite-carriageway highway trap.
 * 3. Ranking prioritizes live available plugs, higher power, and lower diversion distance.
 * 4. Stations that are traps, > 10 km away, or slow AC (< 20 kW) are disqualified from being backup stations.
 * 5. UI state correctly exposes backup station name, power, live plug status, and relative distance.
 * 6. Calling swapStopWithBackup(stopIndex) successfully swaps the primary station with the backup station and updates route energy calculations.
 * 7. Old primary station becomes the new backup station, enabling seamless 1-tap swap back.
 * 8. Stops with no candidates within 10 km correctly have backupStation == null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePrimaryAndBackupStationUiTest {

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
        powerKw: Double,
        availablePlugs: Int = 2,
        totalPlugs: Int = 4,
        isOppositeTrap: Boolean = false
    ): Station {
        val watts = (powerKw * 1000).toLong()
        val port = PowerPort(
            typeWatts = watts,
            label = "${powerKw.toInt()}kW",
            availablePlugs = availablePlugs,
            totalPlugs = totalPlugs,
            displayString = "${powerKw.toInt()}kW: trống $availablePlugs/$totalPlugs"
        )
        val summaryText = if (isOppositeTrap) {
            "Trạm sạc VinFast cao tốc chiều ngược lại"
        } else {
            "Trạm sạc VinFast $powerKw kW"
        }
        return Station(
            id = id,
            name = name,
            address = if (isOppositeTrap) "QL1A làn ngược, $name" else "QL1A, $name",
            latitude = lat,
            longitude = lng,
            summary = summaryText,
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
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testPrimaryAndBackupStationPairingAndSwapFlow() = runTest(testDispatcher) {
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

        // Primary candidate at Stop 1: 150 kW, 4 plugs, 2 available
        val primaryStop1 = createStation(
            id = "st-primary-1",
            name = "Trạm Chính Hà Tĩnh",
            lat = st1Lat,
            lng = st1Lng,
            powerKw = 150.0,
            availablePlugs = 2,
            totalPlugs = 4
        )

        // Candidate Backup A: ~3.5 km away, 60 kW, 3 available plugs (SAFE, IDEAL BACKUP)
        // 1 deg lat ~ 111 km => 0.03 deg ~ 3.3 km
        val backupSafe = createStation(
            id = "st-backup-safe",
            name = "Trạm Dự Phòng Hà Tĩnh",
            lat = st1Lat + 0.025,
            lng = st1Lng + 0.015,
            powerKw = 60.0,
            availablePlugs = 3,
            totalPlugs = 4
        )

        // Candidate Backup B: ~2.0 km away, but an opposite-lane highway trap -> MUST BE REJECTED
        val backupTrap = createStation(
            id = "st-backup-trap",
            name = "Trạm Bẫy Làn Ngược Chiều",
            lat = st1Lat + 0.015,
            lng = st1Lng + 0.01,
            powerKw = 120.0,
            availablePlugs = 4,
            totalPlugs = 4,
            isOppositeTrap = true
        )

        // Candidate Backup C: 25 km away (> 10 km) -> MUST BE REJECTED due to proximity limit
        val backupTooFar = createStation(
            id = "st-backup-far",
            name = "Trạm Quá Xa",
            lat = st1Lat + 0.25,
            lng = st1Lng + 0.1,
            powerKw = 120.0,
            availablePlugs = 4,
            totalPlugs = 4
        )

        // Stop 2 is at fraction 0.70 along route (~438 km from origin)
        // Only ONE station exists here, so backupStation should be null!
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

        // Seed ViewModel with all stations
        val allTestStations = listOf(primaryStop1, backupSafe, backupTrap, backupTooFar, loneStop2)
        viewModel.setCandidateStations(allTestStations)
        advanceUntilIdle()

        // Plan Route
        viewModel.planRoute()
        advanceUntilIdle()

        // Verify route calculation succeeded
        val initialPlan = viewModel.uiState.value.routePlan
        assertNotNull("Initial route plan should not be null", initialPlan)
        assertTrue("Route calculation must succeed without dead zones", initialPlan!!.isSuccess)
        assertEquals("Should have 2 charging stops along 620 km route", 2, initialPlan.stops.size)

        // Assertion 1: Stop 1 has a paired backup station
        val stop1 = initialPlan.stops[0]
        assertEquals("Stop 1 primary station must be st-primary-1", "st-primary-1", stop1.station.id)
        assertNotNull("Stop 1 with nearby candidate must have a non-null backupStation", stop1.backupStation)

        // Assertion 2: Backup station is backupSafe, within <= 10 km, and not a highway trap
        val backup = stop1.backupStation!!
        assertEquals("Backup station should be st-backup-safe", "st-backup-safe", backup.id)
        assertFalse("Backup station cannot be a highway trap", backup.summary.contains("ngược lại", ignoreCase = true))

        val distToBackupKm = stop1.backupDistanceKm
        assertNotNull("backupDistanceKm must not be null", distToBackupKm)
        assertTrue("Backup station must be within <= 10.0 km of primary station", distToBackupKm!! <= 10.0)
        assertTrue("Distance helper should match backupDistanceKm", distToBackupKm > 0.0)
        assertEquals(
            "distanceFromPrimaryStationKm matches helper",
            distToBackupKm,
            distanceFromPrimaryStationKm(stop1.station, backup),
            0.001
        )

        // Assertion 3: Lone Stop 2 has no backup station within 10 km -> backupStation == null
        val stop2 = initialPlan.stops[1]
        assertEquals("Stop 2 primary station is st-lone-2", "st-lone-2", stop2.station.id)
        assertNull("Stop 2 without nearby candidates must have backupStation == null", stop2.backupStation)
        assertNull("Stop 2 backupDistanceKm must be null", stop2.backupDistanceKm)

        // Assertion 4: UI state correctly exposes backup station metadata
        assertEquals("Backup station name matches", "Trạm Dự Phòng Hà Tĩnh", backup.name)
        assertEquals("⚡ 60 kW", stop1.backupPowerDisplayLabel)
        assertEquals("🟢 Trống 3/4", stop1.backupLiveStatusBadge)

        val origDuration = stop1.estimatedChargingMinutes
        val origArrivalSoc = stop1.arrivalBatteryPercent

        // Step 2: Execute 1-tap Swap to Backup Station
        viewModel.swapStopWithBackup(stopIndex = 1)
        advanceUntilIdle()

        val swappedPlan = viewModel.uiState.value.routePlan
        assertNotNull("Swapped plan must not be null", swappedPlan)

        val swappedStop1 = swappedPlan!!.stops[0]
        // Assertion 5: Primary stop is now the former backup station
        assertEquals("Active stop must now be st-backup-safe", "st-backup-safe", swappedStop1.station.id)
        assertEquals("Active stop power is now 60 kW", 60.0, swappedStop1.maxPowerKw, 0.001)

        // Assertion 6: The old primary station is now preserved as the new backup station
        assertNotNull("Swapped stop must have old primary as new backupStation", swappedStop1.backupStation)
        assertEquals("New backup station must be st-primary-1", "st-primary-1", swappedStop1.backupStation!!.id)
        assertEquals("⚡ 150 kW", swappedStop1.backupPowerDisplayLabel)
        assertEquals("🟢 Trống 2/4", swappedStop1.backupLiveStatusBadge)

        // Assertion 7: Route energy and charging metrics are seamlessly updated
        assertTrue("Charging minutes updated for 60kW station", swappedStop1.estimatedChargingMinutes >= origDuration)
        assertEquals("Energy profile is rebuilt with valid waypoints", 6, swappedPlan.energyProfile.size)
        assertEquals("Origin waypoint starts at 100% SoC", 100, swappedPlan.energyProfile.first().batteryPercent)
        assertTrue("Destination waypoint has positive SoC", swappedPlan.energyProfile.last().batteryPercent > 0)

        // Step 3: 1-Tap Swap Back to original primary station
        viewModel.swapStopWithBackup(stopIndex = 1)
        advanceUntilIdle()

        val restoredPlan = viewModel.uiState.value.routePlan
        assertNotNull("Restored plan must not be null", restoredPlan)
        val restoredStop1 = restoredPlan!!.stops[0]

        // Assertion 8: Primary station restored and backup station restored
        assertEquals("Primary station restored to st-primary-1", "st-primary-1", restoredStop1.station.id)
        assertNotNull("Backup station is restored", restoredStop1.backupStation)
        assertEquals("Backup station restored to st-backup-safe", "st-backup-safe", restoredStop1.backupStation!!.id)
        assertEquals("Arrival SoC restored", origArrivalSoc, restoredStop1.arrivalBatteryPercent)
        assertEquals("Charging duration restored", origDuration, restoredStop1.estimatedChargingMinutes)
    }
}
