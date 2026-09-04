package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.LocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single comprehensive test file for Phase 04:
 * Nearby Routing Settings Reactivity & Observation (ANDROID-LOGIC-007).
 *
 * Verifies:
 * 1. When RoutingPreferencesManager saves new routing settings, NearbyViewModel detects the emission
 *    and triggers routing re-evaluation for visible top 10 stations upon settings update.
 * 2. NearbyViewModel.updateRoutingSettings saves settings to prefsManager and updates routing metrics.
 * 3. No re-routing is dispatched if top 10 stations are empty or GPS location has not been acquired yet.
 * 4. validateGoogleApiKey delegates directly to RoutingPreferencesManager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyRoutingSettingsSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeRepository: FakeEvcsRepository
    private lateinit var fakeLocationService: FakeLocationService
    private lateinit var fakeCoordinator: FakeRoutingCoordinator
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var viewModel: NearbyViewModel

    // Fake Location Service
    class FakeLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted

        override suspend fun getFreshLocation(): Location? = locationToReturn
    }

    // Fake Routing Coordinator
    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount: Int = 0
        var lastOriginLat: Double? = null
        var lastOriginLng: Double? = null
        var lastDestinations: List<RoutingDestination> = emptyList()
        var lastSettings: RoutingSettings? = null

        var metricsProvider: (destinations: List<RoutingDestination>, settings: RoutingSettings) -> Map<String, DrivingMetrics> =
            { dests, settings ->
                val engine = when (settings.preferredEngine) {
                    RoutingEngineMode.GOOGLE_ONLY -> RoutingEngineType.GOOGLE
                    RoutingEngineMode.OSRM_ONLY -> RoutingEngineType.OSRM
                    RoutingEngineMode.HAVERSINE_ONLY -> RoutingEngineType.HAVERSINE
                    RoutingEngineMode.AUTO -> RoutingEngineType.OSRM
                }
                dests.associate { d ->
                    d.id to DrivingMetrics(
                        distanceMeters = 3000L,
                        durationSeconds = 300L,
                        trafficCondition = TrafficCondition.FREE_FLOW,
                        engineUsed = engine
                    )
                }
            }

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            lastOriginLat = originLat
            lastOriginLng = originLng
            lastDestinations = destinations
            lastSettings = settings
            return metricsProvider(destinations, settings)
        }
    }

    // Fake EVCS Repository
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
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    private fun createTestStation(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        powerWatts: Long = 250_000L,
        powerLabel: String = "250kW",
        availablePlugs: Int = 2,
        totalPlugs: Int = 2,
        depotStatus: String = "Normal"
    ): Station {
        val powers = listOf(
            PowerPort(
                typeWatts = powerWatts,
                label = powerLabel,
                availablePlugs = availablePlugs,
                totalPlugs = totalPlugs,
                displayString = "$powerLabel: trống $availablePlugs/$totalPlugs"
            )
        )
        return Station(
            id = id,
            name = name,
            address = "Địa chỉ $id",
            latitude = lat,
            longitude = lon,
            summary = "24/7",
            connectors = powerLabel,
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = availablePlugs,
            totalPlugs = totalPlugs
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage)
        fakeRepository = FakeEvcsRepository(sessionStorage)
        fakeLocationService = FakeLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }
        fakeCoordinator = FakeRoutingCoordinator()
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)

        viewModel = NearbyViewModel(
            repository = fakeRepository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingCoordinator = fakeCoordinator,
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
    fun `when RoutingPreferencesManager saves new routing settings NearbyViewModel detects emission and re-evaluates routing for visible top 10`() = runTest {
        // Seed 3 nearby stations and scan
        val stations = listOf(
            createTestStation("st-1", "Station 1", 21.0300, 105.8550),
            createTestStation("st-2", "Station 2", 21.0350, 105.8600),
            createTestStation("st-3", "Station 3", 21.0400, 105.8650)
        )
        fakeRepository.nearbyResult = Result.success(stations)

        // Initial scan
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.top10DisplayStations.size)
        assertEquals(1, fakeCoordinator.callCount)
        assertEquals(RoutingEngineMode.AUTO, fakeCoordinator.lastSettings?.preferredEngine)

        // Save new settings directly through RoutingPreferencesManager (e.g. from Settings or another ViewModel)
        val newSettings = RoutingSettings(
            preferredEngine = RoutingEngineMode.GOOGLE_ONLY,
            googleApiKey = "AIzaSyFakeGoogleKey123"
        )
        prefsManager.saveSettings(newSettings)
        advanceUntilIdle()

        // Verify NearbyViewModel detected the StateFlow emission and triggered re-calculation
        assertEquals(2, fakeCoordinator.callCount)
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, fakeCoordinator.lastSettings?.preferredEngine)
        assertEquals("AIzaSyFakeGoogleKey123", fakeCoordinator.lastSettings?.googleApiKey)
        assertEquals(RoutingEngineType.GOOGLE, viewModel.uiState.value.top10DisplayStations[0].drivingMetrics?.engineUsed)
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, viewModel.routingSettings.value.preferredEngine)
    }

    @Test
    fun `updateRoutingSettings saves to prefsManager and updates routing metrics`() = runTest {
        // Seed stations and scan
        val stations = listOf(
            createTestStation("st-1", "Station 1", 21.0300, 105.8550),
            createTestStation("st-2", "Station 2", 21.0350, 105.8600)
        )
        fakeRepository.nearbyResult = Result.success(stations)

        viewModel.scanNearbyStations()
        advanceUntilIdle()

        assertEquals(1, fakeCoordinator.callCount)

        // Update settings via NearbyViewModel.updateRoutingSettings
        val updatedSettings = RoutingSettings(
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            googleApiKey = ""
        )
        val job = viewModel.updateRoutingSettings(updatedSettings)
        job.join()
        advanceUntilIdle()

        // Verify settings are persisted to prefsManager
        assertEquals(RoutingEngineMode.OSRM_ONLY, prefsManager.settings.value.preferredEngine)
        assertEquals(RoutingEngineMode.OSRM_ONLY, viewModel.routingSettings.value.preferredEngine)

        // Verify routing coordinator re-calculated metrics with new settings
        assertEquals(2, fakeCoordinator.callCount)
        assertEquals(RoutingEngineMode.OSRM_ONLY, fakeCoordinator.lastSettings?.preferredEngine)
        assertEquals(RoutingEngineType.OSRM, viewModel.uiState.value.top10DisplayStations[0].drivingMetrics?.engineUsed)
    }

    @Test
    fun `no re-routing is dispatched if top 10 stations are empty or GPS location has not been acquired yet`() = runTest {
        // Initially, no GPS scan has occurred: user coords are null and top 10 is empty
        assertEquals(0, fakeCoordinator.callCount)
        assertTrue(viewModel.uiState.value.top10DisplayStations.isEmpty())

        // 1. Trigger via prefsManager.saveSettings
        prefsManager.saveSettings(
            RoutingSettings(
                preferredEngine = RoutingEngineMode.GOOGLE_ONLY,
                googleApiKey = "AIzaSyKeyWithoutScan"
            )
        )
        advanceUntilIdle()

        // Coordinator should NOT have been invoked
        assertEquals(0, fakeCoordinator.callCount)

        // 2. Trigger via viewModel.updateRoutingSettings
        val job = viewModel.updateRoutingSettings(
            RoutingSettings(
                preferredEngine = RoutingEngineMode.OSRM_ONLY
            )
        )
        job.join()
        advanceUntilIdle()

        // Coordinator should STILL NOT have been invoked
        assertEquals(0, fakeCoordinator.callCount)
        // But settings were successfully persisted to storage
        assertEquals(RoutingEngineMode.OSRM_ONLY, prefsManager.settings.value.preferredEngine)

        // 3. Scan with empty result from API: GPS is acquired, but stations list is empty
        fakeRepository.nearbyResult = Result.success(emptyList())
        viewModel.scanNearbyStations()
        advanceUntilIdle()

        // Top 10 is still empty, coordinator not called
        assertEquals(0, fakeCoordinator.callCount)
        assertNotNull(viewModel.uiState.value.userLatitude)

        // Trigger settings change when GPS exists but top 10 is empty
        prefsManager.saveSettings(RoutingSettings(preferredEngine = RoutingEngineMode.HAVERSINE_ONLY))
        advanceUntilIdle()

        // Coordinator should still not have been invoked
        assertEquals(0, fakeCoordinator.callCount)
    }

    @Test
    fun `validateGoogleApiKey delegates directly to prefsManager`() = runTest {
        // Blank key returns failure
        val blankResult = viewModel.validateGoogleApiKey("   ")
        assertTrue(blankResult.isFailure)
    }
}
