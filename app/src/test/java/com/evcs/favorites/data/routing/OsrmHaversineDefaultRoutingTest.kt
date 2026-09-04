package com.evcs.favorites.data.routing

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Default OSRM Routing & BYOK Decoupling.
 *
 * Verifies:
 * 1. Default `RoutingSettings` initializes with `preferredEngine == OSRM_ONLY` and `autoFallbackEnabled == true`.
 * 2. `RoutingPreferencesManager.loadSettings()` defaults unconfigured or missing engine settings to `OSRM_ONLY`.
 * 3. Successful OSRM table calculation returns driving metrics without any Google API key.
 * 4. OSRM HTTP 500 error triggers Haversine fallback with valid distance and duration.
 * 5. OSRM network/timeout failure triggers Haversine fallback.
 * 6. Disabling auto-fallback returns empty map upon OSRM error without crashing.
 * 7. Input sanitization: empty destinations or invalid coordinates safely return empty maps.
 * 8. `NearbyViewModel` and `FavoritesViewModel` function immediately without an API key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OsrmHaversineDefaultRoutingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient

    private val originLat = 10.7769
    private val originLng = 106.7009

    private val osrmSuccessResponseJson = """
        {
          "code": "Ok",
          "durations": [
            [0.0, 360.5, 720.0]
          ],
          "distances": [
            [0.0, 2500.0, 5000.0]
          ]
        }
    """.trimIndent()

    private val favoritesResponseJson = """
        {
          "sync": true,
          "csrf": "csrf_token_phase1",
          "server": [
            {"locationId": "station_fav_1", "name": "Vincom Đồng Khởi", "address": "Quận 1", "summary": "24/7", "connectors": "60kW"}
          ]
        }
    """.trimIndent()

    private val searchResponseJson = """
        {
          "code": 200000,
          "data": [
            {"locationId": "station_fav_1", "stationName": "Vincom Đồng Khởi", "latitude": 10.7780, "longitude": 106.7020, "depotStatus": "Normal", "evsePowers": [{"type": 60000, "numberOfAvailableEvse": 2, "totalEvse": 2}]}
          ]
        }
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "auth_cookie_phase1"
            phpSessionId = "phpsess_phase1"
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Default RoutingSettings Initialization
    // =========================================================================

    @Test
    fun testDefaultRoutingSettings_initializesWithOsrmOnlyAndFallbackTrue() {
        val settings = RoutingSettings()

        assertEquals(RoutingEngineMode.OSRM_ONLY, settings.preferredEngine)
        assertTrue(settings.autoFallbackEnabled)
        assertEquals("", settings.googleApiKey)
        assertNull(settings.customOsrmServerUrl)
    }

    // =========================================================================
    // 2. RoutingPreferencesManager Default Loading & Migration
    // =========================================================================

    @Test
    fun testRoutingPreferencesManager_defaultsUnconfiguredOrMissingToOsrmOnly() {
        // Case A: Empty storage
        val emptyStorageManager = RoutingPreferencesManager(storage = sessionStorage)
        val loadedDefault = emptyStorageManager.loadSettings()

        assertEquals(RoutingEngineMode.OSRM_ONLY, loadedDefault.preferredEngine)
        assertTrue(loadedDefault.autoFallbackEnabled)
        assertEquals("", loadedDefault.googleApiKey)
        assertEquals(RoutingEngineMode.OSRM_ONLY, emptyStorageManager.settings.value.preferredEngine)

        // Case B: Legacy "AUTO" migration
        sessionStorage.putString(RoutingPreferencesManager.KEY_PREFERRED_ENGINE, "AUTO")
        val migratedAuto = emptyStorageManager.loadSettings()
        assertEquals(RoutingEngineMode.OSRM_ONLY, migratedAuto.preferredEngine)

        // Case C: Corrupted or unrecognized engine string
        sessionStorage.putString(RoutingPreferencesManager.KEY_PREFERRED_ENGINE, "UNKNOWN_ENGINE_MODE")
        val fallbackUnrecognized = emptyStorageManager.loadSettings()
        assertEquals(RoutingEngineMode.OSRM_ONLY, fallbackUnrecognized.preferredEngine)

        // Case D: Explicit user choices preserved
        sessionStorage.putString(RoutingPreferencesManager.KEY_PREFERRED_ENGINE, "GOOGLE_ONLY")
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, emptyStorageManager.loadSettings().preferredEngine)

        sessionStorage.putString(RoutingPreferencesManager.KEY_PREFERRED_ENGINE, "HAVERSINE_ONLY")
        assertEquals(RoutingEngineMode.HAVERSINE_ONLY, emptyStorageManager.loadSettings().preferredEngine)
    }

    // =========================================================================
    // 3. Successful OSRM Table Calculation without Google API Key
    // =========================================================================

    @Test
    fun testOsrmMatrixCalculation_success_returnsMetricsWithoutApiKey() = runTest(testDispatcher) {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(osrmSuccessResponseJson)
        )

        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )
        val coordinator = MultiTierRoutingCoordinator(
            osrmClient = osrmClient,
            ioDispatcher = testDispatcher
        )

        val destinations = listOf(
            RoutingDestination(id = "station_1", latitude = 10.7800, longitude = 106.7050),
            RoutingDestination(id = "station_2", latitude = 10.7900, longitude = 106.7150)
        )

        // Default settings: preferredEngine = OSRM_ONLY, googleApiKey = ""
        val settings = RoutingSettings(
            googleApiKey = "",
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = true
        )

        val routes = coordinator.calculateRoutes(originLat, originLng, destinations, settings)

        assertEquals(2, routes.size)
        val metric1 = routes["station_1"]
        assertNotNull(metric1)
        assertEquals(2500L, metric1!!.distanceMeters)
        assertEquals(361L, metric1.durationSeconds) // 360.5 rounded
        assertEquals(RoutingEngineType.OSRM, metric1.engineUsed)

        val metric2 = routes["station_2"]
        assertNotNull(metric2)
        assertEquals(5000L, metric2!!.distanceMeters)
        assertEquals(720L, metric2.durationSeconds)
        assertEquals(RoutingEngineType.OSRM, metric2.engineUsed)

        // Verify request was sent to OSRM endpoint, not Google
        val recordedReq = mockServer.takeRequest()
        val reqPath = recordedReq.path.orEmpty()
        assertTrue(reqPath.contains("/table/v1/driving/"))
        assertEquals("EVCSFavorites-Android/1.0", recordedReq.getHeader("User-Agent"))
    }

    // =========================================================================
    // 4. OSRM HTTP 500 Error Triggers Haversine Fallback
    // =========================================================================

    @Test
    fun testOsrmFailure_http500_triggersHaversineFallbackWithValidDistance() = runTest(testDispatcher) {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )
        val coordinator = MultiTierRoutingCoordinator(
            osrmClient = osrmClient,
            ioDispatcher = testDispatcher
        )

        val destinations = listOf(
            RoutingDestination(id = "station_nearby", latitude = 10.7800, longitude = 106.7050)
        )

        val settings = RoutingSettings(
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = true
        )

        val routes = coordinator.calculateRoutes(originLat, originLng, destinations, settings)

        assertEquals(1, routes.size)
        val metric = routes["station_nearby"]
        assertNotNull(metric)
        assertEquals(RoutingEngineType.HAVERSINE, metric!!.engineUsed)
        assertTrue("Distance should be > 0", metric.distanceMeters > 0L)
        assertTrue("Duration should be > 0", metric.durationSeconds > 0L)
    }

    // =========================================================================
    // 5. OSRM Socket Disconnect / Timeout Triggers Haversine Fallback
    // =========================================================================

    @Test
    fun testOsrmFailure_networkError_triggersHaversineFallback() = runTest(testDispatcher) {
        mockServer.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )

        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )
        val coordinator = MultiTierRoutingCoordinator(
            osrmClient = osrmClient,
            ioDispatcher = testDispatcher
        )

        val destinations = listOf(
            RoutingDestination(id = "station_offline", latitude = 10.7800, longitude = 106.7050)
        )

        val settings = RoutingSettings(
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = true
        )

        val routes = coordinator.calculateRoutes(originLat, originLng, destinations, settings)

        assertEquals(1, routes.size)
        val metric = routes["station_offline"]
        assertNotNull(metric)
        assertEquals(RoutingEngineType.HAVERSINE, metric!!.engineUsed)
        assertTrue("Distance should be calculated", metric.distanceMeters > 0L)
    }

    // =========================================================================
    // 6. OSRM Failure with AutoFallback Disabled Returns Empty Map
    // =========================================================================

    @Test
    fun testOsrmFailure_autoFallbackDisabled_returnsEmptyMap() = runTest(testDispatcher) {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val osrmClient = OsrmRoutingClient(
            okHttpClient = okHttpClient,
            defaultBaseUrl = mockServer.url("/").toString()
        )
        val coordinator = MultiTierRoutingCoordinator(
            osrmClient = osrmClient,
            ioDispatcher = testDispatcher
        )

        val destinations = listOf(
            RoutingDestination(id = "station_fallback_disabled", latitude = 10.7800, longitude = 106.7050)
        )

        val settings = RoutingSettings(
            preferredEngine = RoutingEngineMode.OSRM_ONLY,
            autoFallbackEnabled = false
        )

        val routes = coordinator.calculateRoutes(originLat, originLng, destinations, settings)

        assertTrue("Should return empty map when auto-fallback is disabled", routes.isEmpty())
    }

    // =========================================================================
    // 7. Input Sanitization: Invalid Coordinates and Empty Destinations
    // =========================================================================

    @Test
    fun testInputSanitization_invalidCoordinatesAndEmptyDestinations_returnsEmptyMapSafely() = runTest(testDispatcher) {
        val coordinator = MultiTierRoutingCoordinator(ioDispatcher = testDispatcher)
        val settings = RoutingSettings()

        // Empty destinations
        val emptyResult = coordinator.calculateRoutes(originLat, originLng, emptyList(), settings)
        assertTrue(emptyResult.isEmpty())

        // Invalid origin coordinates (0.0, 0.0)
        val invalidOriginResult = coordinator.calculateRoutes(
            0.0,
            0.0,
            listOf(RoutingDestination("dest1", 10.78, 106.70)),
            settings
        )
        assertTrue(invalidOriginResult.isEmpty())

        // Invalid origin latitude > 90
        val outOfBoundsResult = coordinator.calculateRoutes(
            95.0,
            106.0,
            listOf(RoutingDestination("dest1", 10.78, 106.70)),
            settings
        )
        assertTrue(outOfBoundsResult.isEmpty())

        // Mixed destinations: invalid (0,0) and (100, 200) pruned, only valid destination routed
        val mixedDestinations = listOf(
            RoutingDestination("valid_dest", 10.78, 106.70),
            RoutingDestination("unresolved_dest", 0.0, 0.0),
            RoutingDestination("invalid_dest", 100.0, 200.0)
        )
        val mixedResult = coordinator.calculateRoutes(originLat, originLng, mixedDestinations, settings)
        assertEquals(1, mixedResult.size)
        assertTrue(mixedResult.containsKey("valid_dest"))
        assertFalse(mixedResult.containsKey("unresolved_dest"))
        assertFalse(mixedResult.containsKey("invalid_dest"))
    }

    // =========================================================================
    // 8. ViewModels Function Immediately Without API Key
    // =========================================================================

    @Test
    fun testViewModels_functionImmediatelyWithoutApiKey_usingDefaultSettings() = runTest(testDispatcher) {
        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("favorite.html") -> MockResponse().setResponseCode(200).setBody(favoritesResponseJson)
                    path.contains("search") -> MockResponse().setResponseCode(200).setBody(searchResponseJson)
                    path.contains("/table/v1/driving/") -> MockResponse().setResponseCode(200).setBody(osrmSuccessResponseJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val apiClient = EvcsApiClient(sessionManager, okHttpClient, baseUrl)
        val repository = EvcsRepository(apiClient = apiClient, cacheStorage = sessionStorage, autoResolveCoordinates = false)
        val authEngine = AuthEngine(sessionManager, okHttpClient, baseUrl)

        val osrmClient = OsrmRoutingClient(okHttpClient = okHttpClient, defaultBaseUrl = baseUrl)
        val coordinator = MultiTierRoutingCoordinator(osrmClient = osrmClient, ioDispatcher = testDispatcher)

        val prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        // Ensure default settings
        assertEquals(RoutingEngineMode.OSRM_ONLY, prefsManager.settings.value.preferredEngine)
        assertEquals("", prefsManager.settings.value.googleApiKey)

        val fakeLocation = object : Location("mock") {
            override fun getLatitude(): Double = originLat
            override fun getLongitude(): Double = originLng
        }

        val fakeLocationService = object : LocationService() {
            override fun hasLocationPermission(): Boolean = true
            override suspend fun getFreshLocation(): Location = fakeLocation
        }.apply {
            setLocation(fakeLocation)
        }

        // Test FavoritesViewModel
        val favoritesViewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            routingPreferencesManager = prefsManager,
            routingCoordinator = coordinator,
            ioDispatcher = testDispatcher
        )

        favoritesViewModel.fetchFavorites().join()
        favoritesViewModel.routingJob?.join()

        val favState = favoritesViewModel.uiState.value
        assertTrue("Favorites state should be Success, was: $favState", favState is FavoritesUiState.Success)
        val favStations = (favState as FavoritesUiState.Success).stations
        assertEquals(1, favStations.size)
        val favStation = favStations.first()
        assertNotNull("Favorites station should have driving metrics calculated without API key", favStation.drivingMetrics)
        assertTrue(favStation.drivingMetrics!!.distanceMeters > 0)

        // Test NearbyViewModel
        val nearbyViewModel = NearbyViewModel(
            repository = repository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            routingPreferencesManager = prefsManager,
            routingCoordinator = coordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        nearbyViewModel.scanNearbyStations().join()
        nearbyViewModel.routingJob?.join()

        val nearbyState = nearbyViewModel.uiState.value
        assertEquals(1, nearbyState.top10DisplayStations.size)
        val nearbyStation = nearbyState.top10DisplayStations.first()
        assertNotNull("Nearby station should have driving metrics calculated without API key", nearbyStation.drivingMetrics)
        assertTrue(nearbyStation.drivingMetrics!!.distanceMeters > 0)
        assertEquals(1, nearbyState.routingMetrics.size)
    }
}
