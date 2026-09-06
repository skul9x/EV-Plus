package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.logging.DebugLoggingInterceptor
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Single comprehensive test file for Phase 03:
 * Data Layer and Network Forecast Decommissioning.
 *
 * Verifies:
 * 1. EvcsRepository loads stations without issuing `/charging` requests or acquiring charge tokens.
 * 2. Station data pipeline correctly maintains connectors, coordinates, and live status without forecast dependencies.
 * 3. No forecast caching or debug forecast logging events are emitted during normal repository operations.
 */
class ForecastDecommissionDataPipelineTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: EvcsApiClient
    private lateinit var repository: EvcsRepository
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        AppDebugLogger.clear()
        recordedRequests.clear()

        mockWebServer = MockWebServer()
        mockWebServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            authCookie = "test_auth_cookie_phase3"
            csrfToken = "test_csrf_token_phase3"
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(DebugLoggingInterceptor())
            .build()

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )

        repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            ioDispatcher = Dispatchers.IO
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        AppDebugLogger.clear()
    }

    @Test
    fun testRepository_loadsFavoritesAndSearchesWithZeroChargingRequestsAndZeroForecastLogs() = runBlocking {
        val favoritesJson = """
            {
                "server": [
                    {
                        "locationId": "station_hanoi_01",
                        "name": "VinFast Vincom Metropolis",
                        "address": "29 Lieu Giai, Ba Dinh, Hanoi",
                        "summary": "Mở 24/7",
                        "connectors": "60kW, 120kW",
                        "image": "https://evcs.vn/img1.jpg"
                    },
                    {
                        "locationId": "station_hanoi_02",
                        "name": "VinFast Times City",
                        "address": "458 Minh Khai, Hai Ba Trung, Hanoi",
                        "summary": "24/7",
                        "connectors": "250kW",
                        "image": "https://evcs.vn/img2.jpg"
                    }
                ]
            }
        """.trimIndent()

        val searchResponseJson = """
            {
                "code": 200000,
                "data": [
                    {
                        "stationName": "VinFast Vincom Metropolis",
                        "locationId": "station_hanoi_01",
                        "latitude": 21.0333,
                        "longitude": 105.8150,
                        "stationAddress": "29 Lieu Giai, Ba Dinh, Hanoi",
                        "depotStatus": "Available",
                        "isPublic": true,
                        "isFreeParking": false,
                        "workingTimeDescription": "24/7",
                        "evsePowers": [
                            {
                                "type": 60000,
                                "numberOfAvailableEvse": 2,
                                "totalEvse": 4
                            },
                            {
                                "type": 120000,
                                "numberOfAvailableEvse": 1,
                                "totalEvse": 2
                            }
                        ]
                    },
                    {
                        "stationName": "VinFast Times City",
                        "locationId": "station_hanoi_02",
                        "latitude": 20.9950,
                        "longitude": 105.8680,
                        "stationAddress": "458 Minh Khai, Hai Ba Trung, Hanoi",
                        "depotStatus": "Available",
                        "isPublic": true,
                        "isFreeParking": true,
                        "workingTimeDescription": "24/7",
                        "evsePowers": [
                            {
                                "type": 250000,
                                "numberOfAvailableEvse": 3,
                                "totalEvse": 4
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                val path = request.path.orEmpty()
                return when {
                    path.contains("favorite.html") -> {
                        MockResponse().setResponseCode(200).setBody(favoritesJson)
                    }
                    path.contains("search") -> {
                        MockResponse().setResponseCode(200).setBody(searchResponseJson)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // 1. Fetch favorites with GPS enrichment coordinates
        val favResult = repository.getFavorites(userLat = 21.0285, userLon = 105.8542)
        assertTrue("Repository getFavorites should succeed", favResult.isSuccess)

        val stations = favResult.getOrThrow()
        assertEquals(2, stations.size)

        // Verify Station 1 domain integrity
        val st1 = stations.first { it.id == "station_hanoi_01" }
        assertEquals("VinFast Vincom Metropolis", st1.name)
        assertEquals(21.0333, st1.latitude, 0.0001)
        assertEquals(105.8150, st1.longitude, 0.0001)
        assertEquals("Available", st1.depotStatus)
        assertEquals(3, st1.totalAvailablePlugs) // 2 + 1
        assertEquals(6, st1.totalPlugs)          // 4 + 2
        assertEquals(2, st1.powers.size)
        assertFalse(st1.isFreeParking)
        assertTrue("Distance should be attached", (st1.distanceKm ?: 0.0) > 0.0)

        // Verify Station 2 domain integrity
        val st2 = stations.first { it.id == "station_hanoi_02" }
        assertEquals("VinFast Times City", st2.name)
        assertEquals(20.9950, st2.latitude, 0.0001)
        assertEquals(105.8680, st2.longitude, 0.0001)
        assertEquals(3, st2.totalAvailablePlugs)
        assertEquals(4, st2.totalPlugs)

        // 2. Perform nearby VinFast search
        val searchResult = repository.searchNearbyVinFast(lat = 21.0285, lon = 105.8542)
        assertTrue("Nearby search should succeed", searchResult.isSuccess)
        val nearbyStations = searchResult.getOrThrow()
        assertEquals(2, nearbyStations.size)

        // 3. Verify NETWORK LAYER: zero requests to /charging or with X-Partial: user
        assertTrue("Requests must have been dispatched", recordedRequests.isNotEmpty())
        for (req in recordedRequests) {
            val path = req.path.orEmpty()
            val partial = req.getHeader("X-Partial").orEmpty()
            assertFalse(
                "No requests should hit /charging endpoint: $path",
                path.contains("/charging")
            )
            assertFalse(
                "No requests should have X-Partial: user header",
                partial.equals("user", ignoreCase = true)
            )
        }

        // 4. Verify LOGGING LAYER: only expected non-forecast tags emitted
        val allLogs = AppDebugLogger.getLogs()
        assertTrue("Should have recorded search and favorites logs", allLogs.isNotEmpty())
        val recordedTags = allLogs.map { it.tag }.toSet()
        assertTrue(recordedTags.contains(DebugLogTag.FAVORITES))
        assertTrue(recordedTags.contains(DebugLogTag.SEARCH))
        assertFalse(recordedTags.any { it.name == "FORECAST" })

        // 5. Verify OFFLINE STORAGE & PERSISTENCE without forecast fields
        val cachedFavs = repository.getCachedFavorites()
        assertEquals(2, cachedFavs.size)
        cachedFavs.forEach {
            assertEquals(it.id.isNotEmpty(), true)
            assertTrue(it.powers.isNotEmpty())
        }

        val cachedCoords = repository.getCachedCoordinates()
        assertEquals(2, cachedCoords.size)
        assertTrue(cachedCoords.containsKey("station_hanoi_01"))
        assertTrue(cachedCoords.containsKey("station_hanoi_02"))
    }

    @Test
    fun testDataPipeline_offlineFallbackGracefulDegradationMaintainsConnectorsWithoutForecast() = runBlocking {
        // Prepare offline cached favorites with parsed fallback connectors
        val offlineStation = Station(
            id = "offline_01",
            name = "Trạm Ngoại Tuyến",
            address = "Địa chỉ ngoại tuyến",
            latitude = 21.02,
            longitude = 105.85,
            summary = "24/7",
            connectors = "60kW, 30kW",
            depotStatus = "Unknown",
            powers = EvcsRepository.parseConnectorsToPowers("60kW, 30kW"),
            totalAvailablePlugs = 0,
            totalPlugs = 0
        )
        repository.saveCachedFavorites(listOf(offlineStation))

        // Configure mock server to fail all requests (HTTP 500)
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return MockResponse().setResponseCode(500).setBody("Server Error")
            }
        }

        // Fetch favorites when network fails
        val result = repository.getFavorites(userLat = 21.02, userLon = 105.85)
        assertTrue("Offline fallback must succeed from cached snapshot", result.isSuccess)

        val stations = result.getOrThrow()
        assertEquals(1, stations.size)
        val st = stations.first()
        assertEquals("offline_01", st.id)
        assertEquals("Trạm Ngoại Tuyến", st.name)
        assertEquals(2, st.powers.size)
        assertEquals(60_000L, st.powers[0].typeWatts)
        assertEquals(30_000L, st.powers[1].typeWatts)

        // Zero /charging requests and zero forecast logs
        assertEquals(0, recordedRequests.count { it.path.orEmpty().contains("/charging") })
        assertEquals(0, AppDebugLogger.getLogs().count { it.tag.name == "FORECAST" })
    }

    @Test
    fun testSingleFlightDeduplication_operatesCleanlyWithoutForecastKeys() = runBlocking {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                Thread.sleep(80)
                return MockResponse().setResponseCode(200).setBody("""{"server": []}""")
            }
        }

        // Concurrent favorites calls deduplicate to single network request
        val deferred1 = async(Dispatchers.IO) { repository.getFavorites(21.0, 105.0) }
        val deferred2 = async(Dispatchers.IO) { repository.getFavorites(21.0, 105.0) }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals("Concurrent calls deduplicate to 1 network request", 1, recordedRequests.size)
        assertEquals(0, repository.singleFlight.inFlight.size)
    }
}
