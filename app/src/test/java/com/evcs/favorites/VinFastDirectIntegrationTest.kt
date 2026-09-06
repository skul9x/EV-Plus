package com.evcs.favorites

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.network.vinfast.VinFastCAppApiClient
import com.evcs.favorites.data.network.vinfast.VinFastDeviceIdProvider
import com.evcs.favorites.data.network.vinfast.VinFastHeaderInterceptor
import com.evcs.favorites.data.network.vinfast.VinFastStationMapper
import com.evcs.favorites.data.repository.DualTierStationRepository
import com.evcs.favorites.data.repository.FakeFirestoreFavoritesDataSource
import com.evcs.favorites.data.repository.FirestoreFavoritesRepository
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * File-based integration test for Phase 04: Integration, UI Wiring & Verification.
 *
 * Verifies:
 * 1. testFullFlow_VinFastDirectToViewModel:
 *    - End-to-end integration: VinFast CAPP API -> VinFastStationMapper -> DualTierStationRepository -> NearbyViewModel.
 *    - Filters out motorbike plugs (3.5kW, 7kW) and retains car plugs (11kW, 60kW).
 *    - Re-aggregates live available/total plug counts strictly from car bays.
 *    - Validates sourceTier == "VINFAST_DIRECT".
 * 2. testFullFlow_FallbackToEvcsWhenVinFastUnavailable:
 *    - Automated failover from Tier-1 (HTTP 503/401) to Tier-2 EVCS aggregator.
 *    - Sanitizes fallback stations and tags sourceTier == "EVCS_FALLBACK".
 *    - Asserts UI state contains rawStations without crash or error message.
 * 3. testFirestoreFavoritesCompatibility:
 *    - Documents stored under Firestore favorites (/users/{uid}/userdata/favorites) using ID "C.HNO11417".
 *    - Matches Tier-1 VinFast CAPP location-info seamlessly without ID mismatch or data corruption.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VinFastDirectIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeLocationService: TestLocationService
    private lateinit var fakeFirestoreDataSource: FakeFirestoreFavoritesDataSource
    private lateinit var firestoreFavoritesRepo: FirestoreFavoritesRepository
    private lateinit var vinFastApiClient: VinFastCAppApiClient
    private lateinit var evcsApiClient: EvcsApiClient
    private lateinit var repository: DualTierStationRepository
    private lateinit var nearbyViewModel: NearbyViewModel

    // Dynamic MockWebServer failure simulation flags
    private var vinFastSearchFailureCode: Int? = null
    private var vinFastLocationInfoFailureCode: Int? = null

    companion object {
        private const val SAMPLE_VINFAST_SEARCH_JSON = """
        {
            "code": 200,
            "message": "Success",
            "data": [
                {
                    "locationId": "C.HNO11417",
                    "stationName": "Trạm Sạc VinFast Vincom Long Biên",
                    "stationAddress": "Khu đô thị Vinhomes Riverside, Long Biên, Hà Nội",
                    "hereId": "HERE-12345",
                    "latitude": 21.0456,
                    "longitude": 105.9012,
                    "numberOfAvailableEvse": 8,
                    "totalEvse": 12,
                    "connectors": [
                        {
                            "type": 3500,
                            "status": "Available",
                            "count": 2,
                            "total": 2,
                            "isLink": false
                        },
                        {
                            "type": 7000,
                            "status": "Available",
                            "count": 2,
                            "total": 2,
                            "isLink": false
                        },
                        {
                            "type": 11000,
                            "status": "Available",
                            "count": 1,
                            "total": 2,
                            "isLink": false
                        },
                        {
                            "type": 60000,
                            "status": "Available",
                            "count": 3,
                            "total": 6,
                            "isLink": false
                        }
                    ],
                    "depotStatus": "Normal",
                    "isPublic": true,
                    "isFreeParking": true,
                    "workingTimeDescription": "24/7"
                }
            ]
        }
        """

        private const val SAMPLE_VINFAST_LOCATION_INFO_JSON = """
        {
            "code": 200,
            "message": "Success",
            "data": [
                {
                    "locationId": "C.HNO11417",
                    "stationName": "Trạm Sạc VinFast Vincom Long Biên",
                    "stationAddress": "Khu đô thị Vinhomes Riverside, Long Biên, Hà Nội",
                    "latitude": 21.0456,
                    "longitude": 105.9012,
                    "numberOfAvailableEvse": 5,
                    "totalEvse": 8,
                    "connectors": [
                        {
                            "type": 60000,
                            "status": "Available",
                            "count": 5,
                            "total": 8,
                            "isLink": false
                        }
                    ],
                    "depotStatus": "Normal",
                    "isPublic": true,
                    "isFreeParking": true,
                    "workingTimeDescription": "24/7"
                }
            ]
        }
        """

        private const val SAMPLE_EVCS_FALLBACK_SEARCH_JSON = """
        {
            "code": 200000,
            "data": [
                {
                    "locationId": "C.HNO9999",
                    "stationName": "EVCS Fallback Charging Station",
                    "stationAddress": "456 Đường Dự Phòng, Hà Nội",
                    "latitude": 21.0285,
                    "longitude": 105.8542,
                    "depotStatus": "Normal",
                    "evse": "VinFast",
                    "evsePowers": [
                        {
                            "type": 3500,
                            "numberOfAvailableEvse": 4,
                            "totalEvse": 4
                        },
                        {
                            "type": 60000,
                            "numberOfAvailableEvse": 2,
                            "totalEvse": 4
                        }
                    ]
                }
            ]
        }
        """
    }

    class TestLocationService : LocationService() {
        var permissionGranted: Boolean = true
        var locationToReturn: Location? = null

        override fun hasLocationPermission(): Boolean = permissionGranted

        override suspend fun getFreshLocation(): Location? = locationToReturn
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
        AppDebugLogger.clear()
        AppOkHttpClientProvider.reset()
        VinFastDeviceIdProvider.setDeviceIdForTesting("integration-device-uuid-999")

        mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/ccarcharging/api/v1/stations/search") -> {
                        val failCode = vinFastSearchFailureCode
                        if (failCode != null) {
                            MockResponse()
                                .setResponseCode(failCode)
                                .setBody("""{"code":$failCode,"message":"Service Unavailable"}""")
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(SAMPLE_VINFAST_SEARCH_JSON)
                        }
                    }
                    path.contains("/ccarcharging/api/v1/stations/location-info") -> {
                        val failCode = vinFastLocationInfoFailureCode
                        if (failCode != null) {
                            MockResponse()
                                .setResponseCode(failCode)
                                .setBody("""{"code":$failCode,"message":"Service Unavailable"}""")
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(SAMPLE_VINFAST_LOCATION_INFO_JSON)
                        }
                    }
                    path.contains("/search") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(SAMPLE_EVCS_FALLBACK_SEARCH_JSON)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockServer.start()

        val mockBaseUrl = mockServer.url("").toString().removeSuffix("/")

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_php_session"
            authCookie = "test_auth_cookie"
            csrfToken = "test_csrf_token"
        }

        fakeLocationService = TestLocationService().apply {
            permissionGranted = true
            locationToReturn = createMockLocation(21.0285, 105.8542)
        }

        fakeFirestoreDataSource = FakeFirestoreFavoritesDataSource()
        firestoreFavoritesRepo = FirestoreFavoritesRepository(
            remoteDataSource = fakeFirestoreDataSource,
            localStorage = sessionStorage,
            ioDispatcher = testDispatcher
        )

        // OkHttpClient connecting to MockWebServer
        val testOkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        vinFastApiClient = VinFastCAppApiClient(
            baseUrl = mockBaseUrl,
            client = testOkHttpClient,
            ioDispatcher = testDispatcher
        )

        evcsApiClient = EvcsApiClient(
            sessionManager = sessionManager,
            baseUrl = mockBaseUrl,
            client = testOkHttpClient
        )

        repository = DualTierStationRepository(
            apiClient = evcsApiClient,
            vinFastApiClient = vinFastApiClient,
            stationMapper = VinFastStationMapper,
            cacheStorage = sessionStorage,
            autoResolveCoordinates = true,
            ioDispatcher = testDispatcher,
            firestoreFavoritesRepository = firestoreFavoritesRepo
        )

        nearbyViewModel = NearbyViewModel(
            repository = repository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        AppOkHttpClientProvider.reset()
        VinFastDeviceIdProvider.setDeviceIdForTesting(null)
        AppDebugLogger.clear()
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------
    // 1. FULL FLOW: VINFAST DIRECT TO VIEWMODEL
    // -------------------------------------------------------------

    @Test
    fun testFullFlow_VinFastDirectToViewModel() = runTest(testDispatcher) {
        vinFastSearchFailureCode = null

        val job = nearbyViewModel.scanNearbyStations()
        job.join()
        advanceUntilIdle()

        val uiState = nearbyViewModel.uiState.value
        assertFalse("isSearching should be false after completion", uiState.isSearching)
        assertTrue("hasSearched should be true", uiState.hasSearched)
        assertNull("errorMessage should be null on success", uiState.errorMessage)

        val stations = uiState.rawStations
        assertEquals("Should receive exactly 1 station from VinFast Direct API", 1, stations.size)

        val station = stations.first()
        assertEquals("C.HNO11417", station.id)
        assertEquals(
            "Expected exactly 2 car ports (11kW and 60kW), bike ports (3.5kW, 7kW) filtered out",
            2,
            station.powers.size
        )

        assertEquals("11kW", station.powers[0].label)
        assertEquals(11000L, station.powers[0].typeWatts)
        assertEquals(1, station.powers[0].availablePlugs)
        assertEquals(2, station.powers[0].totalPlugs)

        assertEquals("60kW", station.powers[1].label)
        assertEquals(60000L, station.powers[1].typeWatts)
        assertEquals(3, station.powers[1].availablePlugs)
        assertEquals(6, station.powers[1].totalPlugs)

        // Live plug counts re-aggregated strictly from car bays: 1 + 3 = 4 available, 2 + 6 = 8 total
        assertEquals(4, station.totalAvailablePlugs)
        assertEquals(8, station.totalPlugs)

        // Telemetry source tier indicator
        assertEquals("VINFAST_DIRECT", station.sourceTier)
    }

    // -------------------------------------------------------------
    // 2. FULL FLOW: FALLBACK TO EVCS WHEN VINFAST UNAVAILABLE
    // -------------------------------------------------------------

    @Test
    fun testFullFlow_FallbackToEvcsWhenVinFastUnavailable() = runTest(testDispatcher) {
        // Configure VinFast endpoint to fail with HTTP 503
        vinFastSearchFailureCode = 503

        val job = nearbyViewModel.scanNearbyStations()
        job.join()
        advanceUntilIdle()

        val uiState = nearbyViewModel.uiState.value
        assertFalse("isSearching should be false after completion", uiState.isSearching)
        assertTrue("hasSearched should be true", uiState.hasSearched)
        assertNull("UI should not show error state or crash on seamless fallback", uiState.errorMessage)

        val stations = uiState.rawStations
        assertEquals("Should receive 1 fallback station from Tier-2 EVCS", 1, stations.size)

        val station = stations.first()
        assertEquals("C.HNO9999", station.id)
        assertEquals("EVCS_FALLBACK", station.sourceTier)

        // 3.5kW bike plug stripped, only 60kW car plug retained
        assertEquals(1, station.powers.size)
        assertEquals("60kW", station.powers.first().label)
        assertEquals(60000L, station.powers.first().typeWatts)
        assertEquals(2, station.totalAvailablePlugs)
        assertEquals(4, station.totalPlugs)

        // Fallback event verified in diagnostic logger
        val logHistory = AppDebugLogger.getLogs()
        assertTrue(
            "Fallback event should be logged to AppDebugLogger",
            logHistory.any { it.message.contains("Falling back to Tier 2 EVCS aggregator") }
        )
    }

    // -------------------------------------------------------------
    // 3. FIRESTORE FAVORITES COMPATIBILITY
    // -------------------------------------------------------------

    @Test
    fun testFirestoreFavoritesCompatibility() = runTest(testDispatcher) {
        val testUserId = "user_test_999"
        val expectedAddedAt = 1700000000L

        // Provide Firestore favorite document with ID "C.HNO11417"
        val initialDoc = mapOf(
            "favorites" to mapOf(
                "C.HNO11417" to mapOf(
                    "added_at" to expectedAddedAt,
                    "name" to "Trạm Sạc VinFast Vincom Long Biên",
                    "address" to "Khu đô thị Vinhomes Riverside, Long Biên, Hà Nội",
                    "lat" to 21.0456,
                    "lon" to 105.9012,
                    "summary" to "24/7",
                    "connectors" to "60kW"
                )
            ),
            "updated_at" to expectedAddedAt
        )
        fakeFirestoreDataSource.saveAllFavorites(testUserId, initialDoc["favorites"] as Map<String, Any>, expectedAddedAt)

        // Sync favorites on user login to populate local repository state
        val syncResult = firestoreFavoritesRepo.syncOnLogin(testUserId)
        assertTrue("Firestore syncOnLogin should succeed", syncResult.isSuccess)
        assertTrue(firestoreFavoritesRepo.favoriteIdsState.value.contains("C.HNO11417"))

        // Fetch favorites through DualTierStationRepository
        val result = repository.getFavorites(userLat = 21.0456, userLon = 105.9012)
        assertTrue("getFavorites should succeed", result.isSuccess)

        val favorites = result.getOrThrow()
        assertEquals("Expected exactly 1 favorite station", 1, favorites.size)

        val favStation = favorites.first()
        // Verify VinFast station with locationId matches Firestore ID without mismatch
        assertEquals("C.HNO11417", favStation.id)
        assertEquals("VinFast Vincom Long Biên", favStation.name)
        assertEquals("VINFAST_DIRECT", favStation.sourceTier)
        assertEquals("Added timestamp from Firestore must be preserved", expectedAddedAt, favStation.addedAt)
        assertEquals("Live telemetry plug count enriched from VinFast direct", 5, favStation.totalAvailablePlugs)
        assertEquals(8, favStation.totalPlugs)
    }
}
