package com.evcs.favorites.data.routing

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Haversine Fallback Duration & ETA Sort Order (ANDROID-LOGIC-001).
 *
 * Verifies:
 * 1. `computeHaversine` calculates realistic duration (> 0s) for positive distances based on 30 km/h urban heuristic.
 * 2. `formattedDuration` outputs realistic formatted times (e.g. "~1 giờ 40 phút" for 50 km) instead of "0 phút".
 * 3. `FavoritesViewModel` sorts a 50 km Haversine station AFTER a 500m OSRM station (120s duration).
 * 4. Defensive sorting in `FavoritesViewModel` handles legacy zero-duration fallback data without inversion.
 * 5. Sorting stability when all stations use Haversine fallback (ordered strictly ascending by distance/duration).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HaversineRoutingEtaSortTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var fakeCoordinator: FakeRoutingCoordinator

    private var currentTime: Long = 1_000_000L

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var customMetricsProvider: (destinations: List<RoutingDestination>) -> Map<String, DrivingMetrics> = { emptyMap() }

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            return customMetricsProvider(destinations)
        }
    }

    private val favoritesResponseJson = """
        {
          "sync": true,
          "csrf": "csrf_token_fav_123",
          "server": [
            {"locationId": "station_near_osrm", "name": "Trạm Gần OSRM", "address": "Quận 1", "summary": "24/7", "connectors": "60kW"},
            {"locationId": "station_far_haversine", "name": "Trạm Xa Haversine", "address": "Bình Dương", "summary": "24/7", "connectors": "120kW"},
            {"locationId": "station_mid_haversine", "name": "Trạm Vừa Haversine", "address": "Thủ Đức", "summary": "24/7", "connectors": "60kW"}
          ]
        }
    """.trimIndent()

    // Near: ~500m away (lat + 0.004)
    // Mid: ~5km away (lat + 0.045)
    // Far: ~50km away (lat + 0.45)
    private val searchResponseJson = """
        {
          "code": 200000,
          "data": [
            {"locationId": "station_near_osrm", "stationName": "Trạm Gần OSRM", "latitude": 10.7809, "longitude": 106.7009, "depotStatus": "Normal", "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 2, "totalEvse": 2}]},
            {"locationId": "station_far_haversine", "stationName": "Trạm Xa Haversine", "latitude": 11.2269, "longitude": 106.7009, "depotStatus": "Normal", "evsePowers": [{"type": 120000, "numberOfAvailableEvse": 1, "totalEvse": 2}]},
            {"locationId": "station_mid_haversine", "stationName": "Trạm Vừa Haversine", "latitude": 10.8219, "longitude": 106.7009, "depotStatus": "Normal", "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 2, "totalEvse": 2}]}
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("favorite.html") -> MockResponse().setResponseCode(200).setBody(favoritesResponseJson)
                    path.contains("search") -> MockResponse().setResponseCode(200).setBody(searchResponseJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "valid_auth_cookie"
            phpSessionId = "phpsess_123"
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        apiClient = EvcsApiClient(sessionManager, okHttpClient, baseUrl)
        repository = EvcsRepository(apiClient = apiClient, cacheStorage = sessionStorage, autoResolveCoordinates = false)
        authEngine = AuthEngine(sessionManager, okHttpClient, baseUrl)
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        fakeCoordinator = FakeRoutingCoordinator()
        currentTime = 1_000_000L
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): FavoritesViewModel {
        return FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            locationService = null,
            dispatcher = testDispatcher,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            ioDispatcher = testDispatcher
        ).apply {
            timeProvider = { currentTime }
        }
    }

    // =========================================================================
    // 1. computeHaversine Heuristic Duration Calculation
    // =========================================================================

    @Test
    fun testComputeHaversine_calculatesNonZeroDurationForPositiveDistances() {
        val coordinator = MultiTierRoutingCoordinator()
        val originLat = 10.7769
        val originLng = 106.7009

        // Destination ~50 km north: lat = 10.7769 + (50,000 / 111,000) ≈ 11.2273
        val dest50km = RoutingDestination("dest_50km", 11.2273, 106.7009)
        val dest0km = RoutingDestination("dest_0km", originLat, originLng)
        val dest100m = RoutingDestination("dest_100m", originLat + 0.0009, originLng)

        val results = coordinator.computeHaversine(originLat, originLng, listOf(dest50km, dest0km, dest100m))

        // 50 km check: distance ~50000m, duration ~6000s (100 minutes)
        val metric50km = results["dest_50km"]
        assertNotNull(metric50km)
        assertTrue("Distance should be around 50km (49k-51k m)", metric50km!!.distanceMeters in 49000L..51000L)
        assertTrue("Duration for 50km must be around 6000s, not 0s", metric50km.durationSeconds in 5800L..6200L)
        assertNotEquals("Haversine duration must not be 0s for positive distance", 0L, metric50km.durationSeconds)
        assertEquals(RoutingEngineType.HAVERSINE, metric50km.engineUsed)

        // 0 km check: identical coordinates should have 0L duration
        val metric0km = results["dest_0km"]
        assertNotNull(metric0km)
        assertEquals(0L, metric0km!!.distanceMeters)
        assertEquals(0L, metric0km.durationSeconds)

        // 100 m check: should be clamped by coerceAtLeast(60L)
        val metric100m = results["dest_100m"]
        assertNotNull(metric100m)
        assertTrue("Duration for short distance must be at least 60 seconds", metric100m!!.durationSeconds >= 60L)
    }

    // =========================================================================
    // 2. formattedDuration Realistic Time Verification
    // =========================================================================

    @Test
    fun testFormattedDuration_displaysRealisticTimeForHaversineStations() {
        val coordinator = MultiTierRoutingCoordinator()
        val originLat = 10.7769
        val originLng = 106.7009
        val dest50km = RoutingDestination("dest_50km", 11.2273, 106.7009)

        val metrics = coordinator.computeHaversine(originLat, originLng, listOf(dest50km))["dest_50km"]!!

        // For ~50 km at 30 km/h: ~6000s -> ~100 minutes -> "1 giờ 40 phút"
        val formatted = metrics.formattedDuration
        assertNotEquals("Formatted duration must not be '0 phút' for 50 km station", "0 phút", formatted)
        assertTrue("Formatted duration should indicate hours (e.g., '1 giờ 40 phút')", formatted.contains("giờ"))

        // Also test exact 6000s DrivingMetrics
        val exact50kmMetrics = DrivingMetrics(
            distanceMeters = 50_000L,
            durationSeconds = 6000L,
            engineUsed = RoutingEngineType.HAVERSINE
        )
        assertEquals("1 giờ 40 phút", exact50kmMetrics.formattedDuration)

        // Test zero distance
        val zeroMetrics = DrivingMetrics(
            distanceMeters = 0L,
            durationSeconds = 0L,
            engineUsed = RoutingEngineType.HAVERSINE
        )
        assertEquals("0 phút", zeroMetrics.formattedDuration)
    }

    // =========================================================================
    // 3. FavoritesViewModel Sorting: 50 km Haversine sorts AFTER 500m OSRM
    // =========================================================================

    @Test
    fun testFavoritesViewModel_haversineDistantStationSortsAfterNearOsrmStation() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        // Station near: 500m road route via OSRM (120s / 2 mins)
        // Station far: 50km straight line fallback via Haversine (6000s / 100 mins)
        fakeCoordinator.customMetricsProvider = { _ ->
            mapOf(
                "station_near_osrm" to DrivingMetrics(
                    distanceMeters = 500L,
                    durationSeconds = 120L,
                    trafficCondition = TrafficCondition.UNKNOWN,
                    engineUsed = RoutingEngineType.OSRM
                ),
                "station_far_haversine" to DrivingMetrics(
                    distanceMeters = 50000L,
                    durationSeconds = 6000L,
                    trafficCondition = TrafficCondition.UNKNOWN,
                    engineUsed = RoutingEngineType.HAVERSINE
                )
            )
        }

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val state = viewModel.uiState.value as FavoritesUiState.Success
        val stations = state.stations

        // 1st place must be nearby OSRM station (120s)
        assertEquals("station_near_osrm", stations[0].id)
        assertEquals(120L, stations[0].drivingMetrics?.durationSeconds)

        // 2nd place must be distant Haversine station (6000s)
        assertEquals("station_far_haversine", stations[1].id)
        assertEquals(6000L, stations[1].drivingMetrics?.durationSeconds)

        // Verifies Haversine station did not falsely jump ahead to index 0
        assertTrue(stations[0].drivingMetrics!!.durationSeconds < stations[1].drivingMetrics!!.durationSeconds)
    }

    // =========================================================================
    // 4. Defensive Comparator Sorting against Legacy Zero-Duration Data
    // =========================================================================

    @Test
    fun testFavoritesViewModel_defensiveSortPreventsZeroDurationHaversineInversion() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        // Simulate legacy unpatched bug state: Station Far has durationSeconds = 0L but distanceMeters = 50000L
        fakeCoordinator.customMetricsProvider = { _ ->
            mapOf(
                "station_near_osrm" to DrivingMetrics(
                    distanceMeters = 500L,
                    durationSeconds = 120L,
                    trafficCondition = TrafficCondition.UNKNOWN,
                    engineUsed = RoutingEngineType.OSRM
                ),
                "station_far_haversine" to DrivingMetrics(
                    distanceMeters = 50000L,
                    durationSeconds = 0L, // Legacy bugged value!
                    trafficCondition = TrafficCondition.UNKNOWN,
                    engineUsed = RoutingEngineType.HAVERSINE
                )
            )
        }

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val state = viewModel.uiState.value as FavoritesUiState.Success
        val stations = state.stations

        // Station near (120s) MUST still rank before Station far (50km with 0s bugged duration)
        assertEquals("station_near_osrm", stations[0].id)
        assertEquals("station_far_haversine", stations[1].id)
    }

    // =========================================================================
    // 5. Sorting Stability when ALL Stations use Haversine Fallback
    // =========================================================================

    @Test
    fun testFavoritesViewModel_sortingStabilityWhenAllStationsUseHaversineFallback() = runTest(testDispatcher) {
        val userLat = 10.7769
        val userLon = 106.7009

        // All stations use Haversine with durations strictly calculated by computeHaversine
        val coordinator = MultiTierRoutingCoordinator()
        fakeCoordinator.customMetricsProvider = { dests ->
            coordinator.computeHaversine(userLat, userLon, dests)
        }

        val viewModel = createViewModel()
        viewModel.initialLoadJob?.join()
        viewModel.routingJob?.join()

        viewModel.updateUserLocation(userLat, userLon)
        viewModel.routingJob?.join()

        val state = viewModel.uiState.value as FavoritesUiState.Success
        val stations = state.stations

        // Order should be strictly ascending by distance / duration:
        // near (~500m) < mid (~5km) < far (~50km)
        assertEquals("station_near_osrm", stations[0].id)
        assertEquals("station_mid_haversine", stations[1].id)
        assertEquals("station_far_haversine", stations[2].id)

        val duration0 = stations[0].drivingMetrics!!.durationSeconds
        val duration1 = stations[1].drivingMetrics!!.durationSeconds
        val duration2 = stations[2].drivingMetrics!!.durationSeconds

        assertTrue("Near duration ($duration0) <= Mid duration ($duration1)", duration0 <= duration1)
        assertTrue("Mid duration ($duration1) <= Far duration ($duration2)", duration1 <= duration2)
        assertEquals(RoutingEngineType.HAVERSINE, stations[0].drivingMetrics!!.engineUsed)
        assertEquals(RoutingEngineType.HAVERSINE, stations[1].drivingMetrics!!.engineUsed)
        assertEquals(RoutingEngineType.HAVERSINE, stations[2].drivingMetrics!!.engineUsed)
    }
}
