package com.evcs.favorites.data.repository

import android.location.Location
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.LocationService
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import com.evcs.favorites.ui.viewmodel.NearbyViewModel
import com.evcs.favorites.util.SingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightRequestDeduplicationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: EvcsApiClient
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_php_session"
            authCookie = "test_auth_cookie"
            csrfToken = "test_csrf_token"
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        apiClient = EvcsApiClient(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun createSampleStation(id: String, name: String): Station {
        return Station(
            id = id,
            name = name,
            address = "123 Test Street",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "Mở 24/7",
            connectors = "60kW",
            depotStatus = "Available",
            powers = listOf(
                PowerPort(
                    typeWatts = 60_000L,
                    label = "60kW",
                    availablePlugs = 1,
                    totalPlugs = 2,
                    displayString = "60kW (1/2 rảnh)"
                )
            ),
            totalAvailablePlugs = 1,
            totalPlugs = 2
        )
    }

    private fun createMockLocation(lat: Double, lon: Double): Location {
        return object : Location("gps") {
            override fun getLatitude(): Double = lat
            override fun getLongitude(): Double = lon
        }
    }

    // -------------------------------------------------------------
    // 1. SingleFlight Core Concurrency Engine Tests
    // -------------------------------------------------------------

    @Test
    fun testSingleFlight_concurrentCallersShareSingleExecution() = runBlocking {
        val singleFlight = SingleFlight(Dispatchers.IO)
        val executionCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val deferred1 = async(Dispatchers.IO) {
            singleFlight.execute("key_1") {
                executionCount.incrementAndGet()
                latch.await(3, TimeUnit.SECONDS)
                "result_val"
            }
        }

        // Give deferred1 time to register in singleFlight
        delay(50)

        val deferred2 = async(Dispatchers.IO) {
            singleFlight.execute("key_1") {
                executionCount.incrementAndGet()
                "second_val"
            }
        }

        delay(50)
        latch.countDown()

        val r1 = deferred1.await()
        val r2 = deferred2.await()

        assertEquals("result_val", r1)
        assertEquals("result_val", r2)
        assertEquals("Block must execute exactly once for concurrent callers", 1, executionCount.get())
        assertTrue("In-flight map must be cleaned up after execution", singleFlight.inFlight.isEmpty())
    }

    @Test
    fun testSingleFlight_sequentialCallersExecuteAfresh() = runBlocking {
        val singleFlight = SingleFlight(Dispatchers.IO)
        val executionCount = AtomicInteger(0)

        val r1 = singleFlight.execute("seq_key") {
            executionCount.incrementAndGet()
            "res_1"
        }
        assertEquals("res_1", r1)
        assertEquals(1, executionCount.get())

        val r2 = singleFlight.execute("seq_key") {
            executionCount.incrementAndGet()
            "res_2"
        }
        assertEquals("res_2", r2)
        assertEquals("Sequential call must execute afresh", 2, executionCount.get())
    }

    @Test
    fun testSingleFlight_cancellationOfOneCallerDoesNotCancelUnderlyingOrRemainingCallers() = runBlocking {
        val singleFlight = SingleFlight(Dispatchers.IO)
        val executionCount = AtomicInteger(0)
        val holdLatch = CountDownLatch(1)

        // Caller 1 starts and gets cancelled
        val caller1Job = launch(Dispatchers.IO) {
            singleFlight.execute<String>("cancel_key") {
                executionCount.incrementAndGet()
                holdLatch.await(3, TimeUnit.SECONDS)
                "shared_payload"
            }
        }

        delay(50)

        // Caller 2 awaits the same key
        val caller2 = async(Dispatchers.IO) {
            singleFlight.execute("cancel_key") {
                executionCount.incrementAndGet()
                "fallback_payload"
            }
        }

        delay(50)
        // Cancel caller 1
        caller1Job.cancel()

        // Unblock background operation
        holdLatch.countDown()

        // Caller 2 should complete successfully with the shared payload
        val r2 = caller2.await()
        assertEquals("shared_payload", r2)
        assertEquals(1, executionCount.get())
        assertTrue("Map should be cleaned up", singleFlight.inFlight.isEmpty())
    }

    // -------------------------------------------------------------
    // 2. Repository In-Flight Deduplication Tests (/favorite, /search, /forecast)
    // -------------------------------------------------------------

    @Test
    fun testEvcsRepository_concurrentGetFavorites_deduplicatesToSingleHttpCall() = runBlocking {
        val favoriteCallCount = AtomicInteger(0)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.contains("favorite.html")) {
                    favoriteCallCount.incrementAndGet()
                    Thread.sleep(150)
                    return MockResponse().setResponseCode(200).setBody("""{"server": []}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repository = EvcsRepository(apiClient = apiClient, ioDispatcher = Dispatchers.IO)

        // Launch concurrent calls with identical coordinates
        val call1 = async(Dispatchers.IO) { repository.getFavorites(21.0285, 105.8542) }
        val call2 = async(Dispatchers.IO) { repository.getFavorites(21.0285, 105.8542) }

        val res1 = call1.await()
        val res2 = call2.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals("Concurrent getFavorites calls must result in exactly 1 HTTP call", 1, favoriteCallCount.get())

        // Subsequent sequential call triggers a fresh network call
        val seqRes = repository.getFavorites(21.0285, 105.8542)
        assertTrue(seqRes.isSuccess)
        assertEquals("Sequential getFavorites call must trigger a fresh HTTP call", 2, favoriteCallCount.get())
    }

    @Test
    fun testEvcsRepository_concurrentSearchNearbyVinFast_deduplicatesToSingleHttpCall() = runBlocking {
        val searchCallCount = AtomicInteger(0)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.contains("search")) {
                    searchCallCount.incrementAndGet()
                    Thread.sleep(150)
                    return MockResponse().setResponseCode(200).setBody("""{"server": []}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repository = EvcsRepository(apiClient = apiClient, ioDispatcher = Dispatchers.IO)

        val call1 = async(Dispatchers.IO) { repository.searchNearbyVinFast(21.0285, 105.8542) }
        val call2 = async(Dispatchers.IO) { repository.searchNearbyVinFast(21.0285, 105.8542) }

        val res1 = call1.await()
        val res2 = call2.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals("Concurrent searchNearbyVinFast calls must produce exactly 1 HTTP call", 1, searchCallCount.get())

        // Sequential call
        val seqRes = repository.searchNearbyVinFast(21.0285, 105.8542)
        assertTrue(seqRes.isSuccess)
        assertEquals("Sequential search call must trigger fresh HTTP call", 2, searchCallCount.get())
    }

    // -------------------------------------------------------------
    // 3. ViewModel Coalescing & Scan Guard Tests
    // -------------------------------------------------------------

    @Test
    fun testFavoritesViewModel_coalescesUnderFavoritesLoadJobAndCancelsInFlight() = runBlocking {
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(100)
                return MockResponse().setResponseCode(200).setBody("""{"server": []}""")
            }
        }

        val authEngine = AuthEngine(sessionManager, okHttpClient, mockServer.url("/").toString().removeSuffix("/"))
        val repository = EvcsRepository(apiClient = apiClient, ioDispatcher = Dispatchers.IO)
        val vm = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = Dispatchers.IO,
            ioDispatcher = Dispatchers.IO
        )

        val job1 = vm.fetchFavorites()
        val job2 = vm.refresh()

        assertTrue("First job should be cancelled by refresh", job1.isCancelled)
        assertSame("favoritesLoadJob should track the active refresh job", job2, vm.favoritesLoadJob)
        assertSame("initialLoadJob should delegate to favoritesLoadJob", job2, vm.initialLoadJob)

        job2.join()
    }

    @Test
    fun testNearbyViewModel_scanNearbyStations_coalescesWhenLocatingOrSearching() = runBlocking {
        val searchCount = AtomicInteger(0)
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                searchCount.incrementAndGet()
                Thread.sleep(200)
                return MockResponse().setResponseCode(200).setBody("""{"server": []}""")
            }
        }

        val fakeLocationService = object : LocationService(null, null) {
            override fun hasLocationPermission(): Boolean = true
            override suspend fun getFreshLocation(): Location? {
                delay(100)
                return createMockLocation(21.0285, 105.8542)
            }
        }

        val repository = EvcsRepository(apiClient = apiClient, ioDispatcher = Dispatchers.IO)
        val nearbyVm = NearbyViewModel(
            repository = repository,
            sessionManager = sessionManager,
            locationService = fakeLocationService,
            dispatcher = Dispatchers.IO,
            ioDispatcher = Dispatchers.IO
        )

        val scanJob1 = nearbyVm.scanNearbyStations()

        // Wait slightly so scanNearbyStations sets isLocating to true
        delay(30)
        assertTrue(
            "State should be locating or searching",
            nearbyVm.uiState.value.isLocating || nearbyVm.uiState.value.isSearching
        )

        // Invoke duplicate scan while first scan is active
        val scanJob2 = nearbyVm.scanNearbyStations()

        assertSame("Subsequent scan should return active scanJob without spinning duplicate", scanJob1, scanJob2)

        scanJob1.join()
        assertEquals("Network search should only be triggered once", 1, searchCount.get())
    }
}
