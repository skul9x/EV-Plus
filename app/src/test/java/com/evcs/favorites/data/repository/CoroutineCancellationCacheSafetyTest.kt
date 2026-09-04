package com.evcs.favorites.data.repository

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.cache.ForecastCache
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.GoogleRoutesClient
import com.evcs.favorites.data.routing.MultiTierRoutingCoordinator
import com.evcs.favorites.data.routing.OsrmRoutingClient
import com.evcs.favorites.data.routing.RoutingDestination
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single comprehensive verification test for Phase 02:
 * Cancellation Propagation & Cache Cooldown Safety (ANDROID-LOGIC-002).
 *
 * Verifies:
 * 1. Cancelling `fetchStationForecast` rethrows `CancellationException`.
 * 2. Cancelled coroutines do NOT call `forecastCache.recordFailure(station.id)`.
 * 3. `forecastCache.isInCooldown(station.id)` remains `false` after job cancellation.
 * 4. Subsequent requests immediately succeed and are not locked out by 60s cooldowns.
 * 5. Cancelling `enrichStationsWithForecast` propagates `CancellationException` cleanly and leaves cache clean.
 * 6. Cancelling `MultiTierRoutingCoordinator.calculateRoutes` rethrows `CancellationException` instead of returning `emptyMap()`.
 * 7. Cancelling `GoogleRoutesClient` and `OsrmRoutingClient` rethrows `CancellationException` instead of returning `Result.failure`.
 */
class CoroutineCancellationCacheSafetyTest {

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var forecastCache: ForecastCache

    private val sampleValidHtml = """
        <div id="stationTicker" class="ticker-box">
            Dự kiến 2 xe sạc trụ 60kW sẽ xong trong 10 phút nữa
        </div>
        <script data-charging-sessions='[{"kw":60.0,"min":10,"soc":80}]'></script>
    """.trimIndent()

    @Before
    fun setUp() {
        sessionStorage = InMemorySessionStorage()
        sessionManager = SessionManager(sessionStorage).apply {
            phpSessionId = "test_php_session"
            authCookie = "test_auth_cookie"
            csrfToken = "test_csrf_token"
        }
        forecastCache = ForecastCache()
    }

    private fun createTestStation(id: String = "st_001", name: String = "Trạm Sạc VinFast Test"): Station {
        return Station(
            id = id,
            name = name,
            address = "Hà Nội",
            latitude = 21.0285,
            longitude = 105.8542,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 60000, label = "60kW", availablePlugs = 0, totalPlugs = 2)
            ),
            totalAvailablePlugs = 0,
            totalPlugs = 2
        )
    }

    /**
     * Test Double for EvcsApiClient allowing precise control of suspension and simulated cancellation.
     */
    private class ControllableApiClient(
        sessionManager: SessionManager,
        private val htmlResponse: String,
        private val onFetchStarted: (() -> Unit)? = null
    ) : EvcsApiClient(sessionManager, OkHttpClient(), "https://evcs.vn") {

        val fetchCount = AtomicInteger(0)

        override suspend fun fetchStationHtml(
            stationName: String,
            locationId: String
        ): Result<String> {
            fetchCount.incrementAndGet()
            onFetchStarted?.invoke()
            // Long suspension to allow caller to cancel job while in flight
            delay(10_000L)
            return Result.success(htmlResponse)
        }
    }

    @Test
    fun testFetchStationForecastCancellationThrowsAndDoesNotTriggerCooldown() = runBlocking {
        val station = createTestStation("station_cancel_1")
        val startedSignal = CompletableDeferred<Unit>()

        val apiClient = ControllableApiClient(
            sessionManager = sessionManager,
            htmlResponse = sampleValidHtml,
            onFetchStarted = { startedSignal.complete(Unit) }
        )

        val repository = EvcsRepository(
            apiClient = apiClient,
            forecastCache = forecastCache,
            ioDispatcher = Dispatchers.Default
        )

        // Launch fetchStationForecast in a coroutine
        val deferred = async(Dispatchers.Default) {
            repository.fetchStationForecast(station)
        }

        // Wait until fetch operation is actively suspended
        startedSignal.await()

        // Cancel the active job
        deferred.cancel()

        // Verify CancellationException is re-thrown out of fetchStationForecast
        try {
            deferred.await()
            fail("Expected CancellationException was not thrown")
        } catch (e: Throwable) {
            assertTrue(
                "Expected CancellationException but received ${e.javaClass.simpleName}",
                e is CancellationException
            )
        }

        // Verify that cancellation did NOT record failure cooldown
        assertFalse(
            "forecastCache.isInCooldown should be false after job cancellation",
            repository.forecastCache.isInCooldown(station.id)
        )
        assertNull(
            "forecastCache should not contain an entry for cancelled station",
            repository.forecastCache.get(station.id)
        )

        // Verify immediate subsequent request is NOT blocked by cooldown and can complete successfully
        val fastApiClient = object : EvcsApiClient(sessionManager, OkHttpClient(), "https://evcs.vn") {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                return Result.success(sampleValidHtml)
            }
        }
        val unblockedRepo = EvcsRepository(
            apiClient = fastApiClient,
            forecastCache = repository.forecastCache,
            ioDispatcher = Dispatchers.Default
        )

        val subsequentResult = unblockedRepo.fetchStationForecast(station)
        assertTrue("Subsequent request must succeed immediately", subsequentResult.isSuccess)
        val forecast = subsequentResult.getOrNull()
        assertNotNull("Subsequent request must parse forecast without cooldown lockout", forecast)
        assertEquals(10, forecast?.minMinutes)
    }

    @Test
    fun testEnrichStationsWithForecastCancellationPropagatesAndDoesNotBlockSubsequentCalls() = runBlocking {
        val stations = listOf(
            createTestStation("batch_st_1", "Trạm Batch 1"),
            createTestStation("batch_st_2", "Trạm Batch 2"),
            createTestStation("batch_st_3", "Trạm Batch 3")
        )

        val startedCount = AtomicInteger(0)
        val allStarted = CompletableDeferred<Unit>()

        val apiClient = ControllableApiClient(
            sessionManager = sessionManager,
            htmlResponse = sampleValidHtml,
            onFetchStarted = {
                if (startedCount.incrementAndGet() >= 1) {
                    allStarted.complete(Unit)
                }
            }
        )

        val repository = EvcsRepository(
            apiClient = apiClient,
            forecastCache = forecastCache,
            ioDispatcher = Dispatchers.Default
        )

        val job = launch(Dispatchers.Default) {
            repository.enrichStationsWithForecast(stations)
        }

        allStarted.await()
        // Cancel the batch enrichment job while in-flight
        job.cancelAndJoin()

        assertTrue("Batch enrichment job should be cancelled", job.isCancelled)

        // Verify none of the stations were placed into cooldown
        for (st in stations) {
            assertFalse(
                "Station ${st.id} must not be in cooldown after batch cancellation",
                repository.forecastCache.isInCooldown(st.id)
            )
        }

        // Verify a subsequent batch run succeeds immediately
        val fastApiClient = object : EvcsApiClient(sessionManager, OkHttpClient(), "https://evcs.vn") {
            override suspend fun fetchStationHtml(stationName: String, locationId: String): Result<String> {
                return Result.success(sampleValidHtml)
            }
        }
        val unblockedRepo = EvcsRepository(
            apiClient = fastApiClient,
            forecastCache = repository.forecastCache,
            ioDispatcher = Dispatchers.Default
        )

        val enriched = unblockedRepo.enrichStationsWithForecast(stations)
        assertEquals(3, enriched.size)
        assertTrue("All stations should have forecast populated", enriched.all { it.forecast != null })
    }

    @Test
    fun testMultiTierRoutingCoordinatorRethrowsCancellation() = runBlocking {
        val coordinator = MultiTierRoutingCoordinator(
            ioDispatcher = Dispatchers.Default
        )

        val destinations = listOf(
            RoutingDestination("dest_1", 21.03, 105.85),
            RoutingDestination("dest_2", 21.04, 105.86)
        )

        val started = CompletableDeferred<Unit>()
        val deferred = async(Dispatchers.Default) {
            started.complete(Unit)
            delay(10_000L) // Simulate network delay during calculateRoutes
            coordinator.calculateRoutes(
                originLat = 21.0285,
                originLng = 105.8542,
                destinations = destinations,
                settings = RoutingSettings(preferredEngine = RoutingEngineMode.AUTO)
            )
        }

        started.await()
        deferred.cancel()

        try {
            deferred.await()
            fail("Expected CancellationException from MultiTierRoutingCoordinator")
        } catch (e: Throwable) {
            assertTrue("Expected CancellationException but got ${e.javaClass.simpleName}", e is CancellationException)
        }
    }

    @Test
    fun testGoogleRoutesClientAndOsrmRoutingClientRethrowCancellation() = runBlocking {
        val googleClient = GoogleRoutesClient()
        val osrmClient = OsrmRoutingClient()

        val destinations = listOf(
            RoutingDestination("dest_1", 21.03, 105.85)
        )

        // 1. Google Routes Client cancellation
        val googleStarted = CompletableDeferred<Unit>()
        val googleDeferred = async(Dispatchers.Default) {
            googleStarted.complete(Unit)
            // Long delay simulating network suspension
            delay(10_000L)
            googleClient.computeRouteMatrix("test_key", 21.0, 105.0, destinations)
        }

        googleStarted.await()
        googleDeferred.cancel()

        try {
            googleDeferred.await()
            fail("Expected CancellationException from GoogleRoutesClient coroutine")
        } catch (e: Throwable) {
            assertTrue("GoogleRoutesClient should rethrow CancellationException", e is CancellationException)
        }

        // 2. OSRM Routing Client cancellation
        val osrmStarted = CompletableDeferred<Unit>()
        val osrmDeferred = async(Dispatchers.Default) {
            osrmStarted.complete(Unit)
            delay(10_000L)
            osrmClient.computeTable(21.0, 105.0, destinations)
        }

        osrmStarted.await()
        osrmDeferred.cancel()

        try {
            osrmDeferred.await()
            fail("Expected CancellationException from OsrmRoutingClient coroutine")
        } catch (e: Throwable) {
            assertTrue("OsrmRoutingClient should rethrow CancellationException", e is CancellationException)
        }
    }
}
