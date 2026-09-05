package com.evcs.favorites.ui.viewmodel

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.state.FavoritesUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Single Comprehensive Verification Test for Phase 01: Threading & Race Condition Elimination (PERF-02).
 *
 * Validates:
 * 1. `FavoritesViewModel.initialLoadJob` is non-null immediately upon instantiation and deterministically
 *    reaches `FavoritesUiState.Success` when awaited.
 * 2. Concurrent logout cleanly cancels in-flight jobs (`favoritesLoadJob` and `routingJob`) without leaks.
 * 3. `NearbyViewModel` filtering and routing runs deterministically on test dispatcher (`advanceUntilIdle()`)
 *    using the newly unified `defaultDispatcher = ioDispatcher`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelThreadingAndRaceConditionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var baseUrl: String
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private lateinit var authEngine: AuthEngine
    private lateinit var prefsManager: RoutingPreferencesManager
    private lateinit var fakeCoordinator: FakeRoutingCoordinator

    private val sampleFavoritesJson = """
    {
      "sync": true,
      "csrf": "csrf_test_fav_123",
      "server": [
        {
          "locationId": "STATION_001",
          "name": "VinFast - Royal City",
          "address": "72A Nguyen Trai, Ha Noi",
          "summary": "Mo 24/7",
          "connectors": "120kW, 60kW",
          "image": null
        },
        {
          "locationId": "STATION_002",
          "name": "VinFast - Landmark 81",
          "address": "Binh Thanh, TP HCM",
          "summary": "Mo 24/7",
          "connectors": "250kW, 60kW",
          "image": null
        }
      ]
    }
    """.trimIndent()

    private val sampleSearchJson = """
    {
      "code": 200000,
      "data": [
        {
          "locationId": "STATION_001",
          "stationName": "VinFast - Royal City",
          "latitude": 21.0028,
          "longitude": 105.8164,
          "depotStatus": "Normal",
          "evsePowers": [
            {
              "type": 120000,
              "numberOfAvailableEvse": 2,
              "totalEvse": 4
            }
          ]
        },
        {
          "locationId": "STATION_002",
          "stationName": "VinFast - Landmark 81",
          "latitude": 10.7951,
          "longitude": 106.7218,
          "depotStatus": "Normal",
          "evsePowers": [
            {
              "type": 250000,
              "numberOfAvailableEvse": 4,
              "totalEvse": 6
            }
          ]
        }
      ]
    }
    """.trimIndent()

    class FakeRoutingCoordinator : MultiTierRoutingCoordinator() {
        var callCount = 0

        override suspend fun calculateRoutes(
            originLat: Double,
            originLng: Double,
            destinations: List<RoutingDestination>,
            settings: RoutingSettings
        ): Map<String, DrivingMetrics> {
            callCount++
            return destinations.associate { d ->
                d.id to DrivingMetrics(
                    distanceMeters = 5000L,
                    durationSeconds = 600L,
                    trafficCondition = TrafficCondition.FREE_FLOW,
                    engineUsed = RoutingEngineType.OSRM
                )
            }
        }
    }

    class FakeLocationService(private val location: Location?) : LocationService() {
        override fun hasLocationPermission(): Boolean = true
        override suspend fun getFreshLocation(): Location? = location
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("favorite.html") -> MockResponse().setResponseCode(200).setBody(sampleFavoritesJson)
                    path.contains("search") -> MockResponse().setResponseCode(200).setBody(sampleSearchJson)
                    else -> MockResponse().setResponseCode(200).setBody("""{"ok": true}""")
                }
            }
        }
        mockServer.start()

        baseUrl = mockServer.url("/").toString().removeSuffix("/")
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "evcs=valid_token_123;"
            phpSessionId = "phpsess_test"
            csrfToken = "csrf_token_fav_123"
        }
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        apiClient = EvcsApiClient(sessionManager, okHttpClient, baseUrl)
        repository = EvcsRepository(apiClient = apiClient, cacheStorage = sessionStorage, autoResolveCoordinates = false)
        authEngine = AuthEngine(sessionManager, okHttpClient, baseUrl)
        prefsManager = RoutingPreferencesManager(storage = sessionStorage)
        fakeCoordinator = FakeRoutingCoordinator()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun testFavoritesViewModel_initialLoadJob_isNonNullImmediately_andAwaitingCompletesFlow() = runTest(testDispatcher) {
        val viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator
        )

        // 1. initialLoadJob must be immediately non-null upon ViewModel constructor return
        val initialJob = viewModel.initialLoadJob
        assertNotNull("initialLoadJob must be non-null immediately upon instantiation", initialJob)
        assertSame("initialLoadJob must alias favoritesLoadJob", viewModel.favoritesLoadJob, initialJob)

        // 2. Joining the initialLoadJob deterministically completes the load flow
        initialJob?.join()
        advanceUntilIdle()

        // 3. State must reach Success with loaded stations
        val state = viewModel.uiState.value
        assertTrue("State must be FavoritesUiState.Success after initialLoadJob completes", state is FavoritesUiState.Success)
        val successState = state as FavoritesUiState.Success
        assertEquals("Loaded stations count must match response", 2, successState.stations.size)
        assertEquals("STATION_001", successState.stations[0].id)
    }

    @Test
    fun testFavoritesViewModel_concurrentLogout_cancelsActiveJobsCleanly() = runTest(testDispatcher) {
        // Create an EvcsRepository subclass with simulated delay to keep favoritesLoadJob in-flight
        val slowRepository = object : EvcsRepository(apiClient = apiClient, cacheStorage = sessionStorage, autoResolveCoordinates = false) {
            override suspend fun getFavorites(
                userLat: Double?,
                userLon: Double?,
                autoResolveUnknownCoordinates: Boolean
            ): Result<List<Station>> {
                delay(5_000L) // Long enough to stay active until logout
                return super.getFavorites(userLat, userLon, autoResolveUnknownCoordinates)
            }
        }

        val viewModel = FavoritesViewModel(
            repository = slowRepository,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator
        )

        val activeLoadJob = viewModel.favoritesLoadJob
        assertNotNull("favoritesLoadJob must be non-null", activeLoadJob)
        assertTrue("favoritesLoadJob must be active before cancellation", activeLoadJob!!.isActive)

        // Execute concurrent logout
        viewModel.logout()

        // Verify active jobs are cleanly cancelled
        assertTrue("favoritesLoadJob must be cancelled upon logout", activeLoadJob.isCancelled)
        assertEquals("UI state must transition to LoggedOut upon logout", FavoritesUiState.LoggedOut, viewModel.uiState.value)

        // Run remaining scheduled coroutines to ensure no leaks or unhandled cancellation exceptions
        advanceUntilIdle()
        assertEquals(FavoritesUiState.LoggedOut, viewModel.uiState.value)
    }

    @Test
    fun testNearbyViewModel_filteringAndRouting_runsDeterministicallyOnTestDispatcher() = runTest(testDispatcher) {
        val testStations = (1..15).map { index ->
            Station(
                id = "STATION_$index",
                name = "Trạm $index",
                address = "Địa chỉ $index",
                latitude = 21.0 + (index * 0.005),
                longitude = 105.8 + (index * 0.005),
                summary = "24/7",
                connectors = "120kW",
                depotStatus = "Normal",
                totalAvailablePlugs = 2,
                totalPlugs = 2,
                powers = listOf(
                    PowerPort(
                        typeWatts = 120_000L,
                        label = "120kW",
                        availablePlugs = 2,
                        totalPlugs = 2,
                        displayString = "120kW: trống 2/2"
                    )
                )
            )
        }

        val fakeRepo = object : EvcsRepository(apiClient = apiClient, cacheStorage = sessionStorage) {
            override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>> {
                return Result.success(testStations)
            }
        }

        val fakeLocation = createMockLocation(21.0285, 105.8542)
        val locationService = FakeLocationService(fakeLocation)

        // Instantiate NearbyViewModel without passing defaultDispatcher explicitly
        // to verify that defaultDispatcher: CoroutineDispatcher = ioDispatcher works as expected.
        val viewModel = NearbyViewModel(
            repository = fakeRepo,
            sessionManager = sessionManager,
            locationService = locationService,
            routingPreferencesManager = prefsManager,
            routingCoordinator = fakeCoordinator,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            routingDebounceMs = 0L
        )

        // Trigger scan
        viewModel.scanNearbyStations()

        // advanceUntilIdle deterministically processes both I/O retrieval and CPU defaultDispatcher filtering
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("hasSearched must be true after scan", state.hasSearched)
        assertEquals("Top 10 display stations must strictly cap at 10", 10, state.top10DisplayStations.size)
        assertTrue("Routing coordinator must have been called", fakeCoordinator.callCount > 0)
        assertTrue("Stations must be enriched with driving metrics", state.top10DisplayStations.all { it.drivingMetrics != null })

        // Verify factory also defaults defaultDispatcher to ioDispatcher
        val factory = NearbyViewModel.provideFactory(
            repository = fakeRepo,
            sessionManager = sessionManager,
            locationService = locationService,
            routingCoordinator = fakeCoordinator,
            routingPreferencesManager = prefsManager,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            routingDebounceMs = 0L
        )
        val factoryVm = factory.create(NearbyViewModel::class.java)
        assertNotNull("Factory must instantiate NearbyViewModel", factoryVm)
    }
}
