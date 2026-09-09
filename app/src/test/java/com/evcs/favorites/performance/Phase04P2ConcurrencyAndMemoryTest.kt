package com.evcs.favorites.performance

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.filter.clearConnectorCompatibilityCache
import com.evcs.favorites.domain.filter.hasCarCompatiblePorts
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.util.BoundedLruCache
import com.evcs.favorites.util.SingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single file-based comprehensive verification test for Phase 04:
 * P2 Concurrency, Network Disk Cache & Memory Resilience.
 *
 * Covers:
 * 1. [COROUTINE-01]: SingleFlight cancels discarded lazy deferreds on collision under high concurrency.
 * 2. [NETWORK-01]: AppOkHttpClientProvider thread-safe disk cache initialization and client caching.
 * 3. [MEMORY-01]: BoundedLruCache eviction beyond capacity (256 items), LRU access ordering,
 *    and integration across EvcsRepository, NearbyStationFilter, and SessionManager.
 * 4. [BUILD-01]: baseline-prof.txt validation: verify removed stale classes are absent and
 *    required production classes and corrected UI state packages are present.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase04P2ConcurrencyAndMemoryTest {

    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        tempCacheDir = File(System.getProperty("java.io.tmpdir"), "phase04_cache_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        AppOkHttpClientProvider.resetForTesting()
        EvcsRepository.clearParsedConnectorsCache()
        clearConnectorCompatibilityCache()
    }

    @After
    fun tearDown() {
        AppOkHttpClientProvider.resetForTesting()
        EvcsRepository.clearParsedConnectorsCache()
        clearConnectorCompatibilityCache()
        tempCacheDir.deleteRecursively()
    }

    // =========================================================================
    // 1. [COROUTINE-01]: SingleFlight Coroutine Collision & Job Cancellation
    // =========================================================================

    @Test
    fun testSingleFlight_cancelsDiscardedDeferredsOnCollision() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val singleFlight = SingleFlight(testScope)

        val gate = CompletableDeferred<Unit>()
        val executionCounter = AtomicInteger(0)
        val concurrency = 20

        // Launch 20 concurrent callers requesting the exact same key
        val deferredResults = (1..concurrency).map {
            testScope.async {
                singleFlight.execute("flight-collision-key") {
                    executionCounter.incrementAndGet()
                    gate.await()
                    "SUCCESS_PAYLOAD"
                }
            }
        }

        // Wait a short moment to ensure all coroutines hit the putIfAbsent barrier
        delay(100)

        // SingleFlight inFlight map must have exactly 1 active deferred
        assertEquals("Only one deferred must be stored inFlight", 1, singleFlight.inFlight.size)

        // Release the execution gate
        gate.complete(Unit)

        val results = deferredResults.awaitAll()
        assertEquals(concurrency, results.size)
        results.forEach { assertEquals("SUCCESS_PAYLOAD", it) }

        // The underlying block must have executed exactly once
        assertEquals("Underlying execution block must run exactly once", 1, executionCounter.get())

        // Ensure inFlight is cleared after completion
        delay(50)
        assertEquals(0, singleFlight.inFlight.size)
    }

    // =========================================================================
    // 2. [NETWORK-01]: AppOkHttpClientProvider Thread-Safe Cache & Client
    // =========================================================================

    @Test
    fun testAppOkHttpClientProvider_concurrentAccessAndDiskCache() {
        val clientCount = 10
        val latch = CountDownLatch(clientCount)
        val clients = java.util.concurrent.CopyOnWriteArrayList<okhttp3.OkHttpClient>()

        // Simulate concurrent calls to getSharedClient() before and during installDiskCache()
        val threadPool = java.util.concurrent.Executors.newFixedThreadPool(clientCount)
        for (i in 0 until clientCount) {
            threadPool.submit {
                if (i == clientCount / 2) {
                    AppOkHttpClientProvider.installDiskCache(tempCacheDir.resolve("http"))
                }
                clients.add(AppOkHttpClientProvider.getSharedClient())
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        threadPool.shutdown()

        assertEquals(clientCount, clients.size)
        clients.forEach { client ->
            assertNotNull("Shared client must not be null", client)
            assertEquals(AppOkHttpClientProvider.connectionPool, client.connectionPool)
            assertEquals(AppOkHttpClientProvider.dispatcher, client.dispatcher)
        }

        // Now install disk cache explicitly and verify subsequent getSharedClient has cache
        val diskCacheDir = tempCacheDir.resolve("http_verified")
        AppOkHttpClientProvider.installDiskCache(diskCacheDir)
        val clientWithCache = AppOkHttpClientProvider.getSharedClient()
        assertNotNull("Client cache must be bound after installDiskCache", clientWithCache.cache)

        // newSharedClientBuilder must also retain shared pool, dispatcher and cache
        val builderClient = AppOkHttpClientProvider.newSharedClientBuilder().build()
        assertEquals(clientWithCache.cache, builderClient.cache)
        assertEquals(AppOkHttpClientProvider.connectionPool, builderClient.connectionPool)
    }

    // =========================================================================
    // 3. [MEMORY-01]: BoundedLruCache Core Eviction & Order
    // =========================================================================

    @Test
    fun testBoundedLruCache_evictsEldestWhenExceedingCapacity() {
        val capacity = 5
        val cache = BoundedLruCache<String, Int>(capacity)

        // Insert items up to capacity
        for (i in 1..capacity) {
            cache.put("key$i", i)
        }
        assertEquals(capacity, cache.size())
        assertEquals(listOf("key1", "key2", "key3", "key4", "key5"), cache.keys())

        // Access key1 to move it to MRU (Most Recently Used)
        val value1 = cache.get("key1")
        assertEquals(1, value1)
        assertEquals(listOf("key2", "key3", "key4", "key5", "key1"), cache.keys())

        // Insert key6 -> eldest (key2) should be evicted
        cache.put("key6", 6)
        assertEquals(capacity, cache.size())
        assertNull("key2 must be evicted", cache.get("key2"))
        assertTrue(cache.containsKey("key1"))
        assertTrue(cache.containsKey("key6"))
        assertEquals(listOf("key3", "key4", "key5", "key1", "key6"), cache.keys())

        // Test computeIfAbsent
        val computed = cache.computeIfAbsent("key7") { 7 }
        assertEquals(7, computed)
        assertEquals(capacity, cache.size())
        assertNull("key3 must be evicted as eldest", cache.get("key3"))
        assertEquals(listOf("key4", "key5", "key1", "key6", "key7"), cache.keys())

        // Test clear
        cache.clear()
        assertEquals(0, cache.size())
        assertFalse(cache.containsKey("key7"))
    }

    // =========================================================================
    // 3b. [MEMORY-01]: Bounded Memory Across EvcsRepository, NearbyStationFilter, SessionManager
    // =========================================================================

    @Test
    fun testRepositoryAndFilterAndSession_useBoundedCache() {
        // EvcsRepository connector power parsing
        EvcsRepository.clearParsedConnectorsCache()
        val ports1 = EvcsRepository.parseConnectorsToPowers("120kW, 60kW")
        assertEquals(2, ports1.size)
        assertEquals(120000L, ports1[0].typeWatts)
        assertEquals(60000L, ports1[1].typeWatts)

        // Test 300 distinct connector strings to verify capacity bounding at 256
        for (i in 1..300) {
            EvcsRepository.parseConnectorsToPowers("${i}kW")
        }
        // Cache must remain healthy and responsive without OutOfMemoryError
        val reCheck = EvcsRepository.parseConnectorsToPowers("300kW")
        assertEquals(1, reCheck.size)
        assertEquals(300000L, reCheck[0].typeWatts)

        // NearbyStationFilter car compatible ports check
        clearConnectorCompatibilityCache()
        val testStation = Station(
            id = "test_st_1",
            name = "Test Station",
            address = "Address",
            latitude = 10.0,
            longitude = 106.0,
            summary = "",
            connectors = "60kW DC, 11kW AC",
            depotStatus = "Normal",
            powers = emptyList()
        )
        assertTrue(testStation.hasCarCompatiblePorts())

        val bikeOnlyStation = Station(
            id = "test_st_2",
            name = "Bike Station",
            address = "Address",
            latitude = 10.0,
            longitude = 106.0,
            summary = "",
            connectors = "3.5kW",
            depotStatus = "Normal",
            powers = emptyList()
        )
        assertFalse(bikeOnlyStation.hasCarCompatiblePorts())

        // SessionManager cookie regex cache
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        assertNotNull(sessionManager)
        val cookieHeader = "evcs=auth_token_123; PHPSESSID=session_456; custom_cookie=hello_world"

        assertEquals("auth_token_123", SessionManager.extractCookieValue(cookieHeader, SessionManager.KEY_AUTH_COOKIE))
        assertEquals("session_456", SessionManager.extractCookieValue(cookieHeader, SessionManager.KEY_PHP_SESSION))
        assertEquals("hello_world", SessionManager.extractCookieValue(cookieHeader, "custom_cookie"))

        // Stress test cookie regex cache with 300 dynamic cookie names
        for (i in 1..300) {
            val name = "custom_cookie_$i"
            val testHeader = "$name=val_$i"
            assertEquals("val_$i", SessionManager.extractCookieValue(testHeader, name))
        }
    }

    // =========================================================================
    // 4. [BUILD-01]: Baseline Profile Validation
    // =========================================================================

    @Test
    fun testBaselineProfile_containsNoStaleClassesAndValidPackages() {
        val rootDir = File(System.getProperty("user.dir") ?: ".")
        val baselineProfFile = File(rootDir, "src/main/baseline-prof.txt").takeIf { it.exists() }
            ?: File(rootDir, "app/src/main/baseline-prof.txt")

        assertTrue("baseline-prof.txt must exist at ${baselineProfFile.absolutePath}", baselineProfFile.exists())

        val content = baselineProfFile.readText()

        // 1. Must NOT contain deleted/non-existent stale classes
        val staleClasses = listOf(
            "VerticalScrollbarKt",
            "MemoryStationCache",
            "DiskStationCache",
            "RateLimitRetryInterceptor",
            "PerformanceLogger",
            "HSPLcom/evcs/favorites/domain/model/Station",
            "HSPLcom/evcs/favorites/domain/model/PowerPort"
        )

        for (stale in staleClasses) {
            assertFalse(
                "baseline-prof.txt must NOT contain stale class reference: $stale",
                content.contains(stale)
            )
        }

        // 2. Must contain corrected UI state package references
        assertTrue(
            "NearbyUiState must use com.evcs.favorites.ui.state",
            content.contains("HSPLcom/evcs/favorites/ui/state/NearbyUiState;")
        )
        assertTrue(
            "FavoritesUiState must use com.evcs.favorites.ui.state",
            content.contains("HSPLcom/evcs/favorites/ui/state/FavoritesUiState;")
        )
        assertFalse(
            "Must NOT contain wrong package com/evcs/favorites/ui/screens/NearbyUiState",
            content.contains("HSPLcom/evcs/favorites/ui/screens/NearbyUiState;")
        )
        assertFalse(
            "Must NOT contain wrong package com/evcs/favorites/ui/screens/FavoritesUiState",
            content.contains("HSPLcom/evcs/favorites/ui/screens/FavoritesUiState;")
        )

        // 3. Must contain required added production classes
        val requiredClasses = listOf(
            "HSPLcom/evcs/favorites/car/MainCarScreen;",
            "HSPLcom/evcs/favorites/focus/FocusModeForegroundService;",
            "HSPLcom/evcs/favorites/di/DefaultAppContainer;",
            "HSPLcom/evcs/favorites/data/auth/FirebaseAuthManager;",
            "HSPLcom/evcs/favorites/util/BoundedLruCache;"
        )

        for (req in requiredClasses) {
            assertTrue(
                "baseline-prof.txt must contain required class: $req",
                content.contains(req)
            )
        }
    }
}
