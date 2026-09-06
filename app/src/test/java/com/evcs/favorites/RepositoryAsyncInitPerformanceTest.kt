package com.evcs.favorites

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.CoordinatePair
import com.evcs.favorites.data.repository.EvcsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 01 Performance & Concurrency Verification Test:
 * 1. Instantiating EvcsRepository with eagerLoadCache = false performs 0ms synchronous disk reads and leaves main thread unblocked.
 * 2. Calling initializeAsync restores coordinates and favorites accurately from storage onto Dispatchers.IO.
 * 3. Reactive StateFlows (favoritesState, favoriteIdsState, isInitialized) reflect loaded cache items upon completion.
 * 4. Helper initialize(scope, dispatcher) returns a Job that properly executes initialization.
 * 5. Concurrent multi-threaded reads/writes to coordinateCache succeed without ConcurrentModificationException.
 * 6. Backward compatibility: eagerLoadCache = true populates cache synchronously.
 */
class RepositoryAsyncInitPerformanceTest {

    private lateinit var sessionStorage: TrackingSessionStorage
    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EvcsApiClient

    class TrackingSessionStorage(
        private val delegate: InMemorySessionStorage = InMemorySessionStorage()
    ) : SessionStorage {
        val getStringCalls = AtomicInteger(0)
        val putStringCalls = AtomicInteger(0)

        override suspend fun warmUp() {
            delegate.warmUp()
        }

        override fun getString(key: String): String? {
            getStringCalls.incrementAndGet()
            return delegate.getString(key)
        }

        override fun putString(key: String, value: String?) {
            putStringCalls.incrementAndGet()
            delegate.putString(key, value)
        }

        override fun remove(key: String) {
            delegate.remove(key)
        }

        override fun clear() {
            delegate.clear()
        }
    }

    @Before
    fun setUp() {
        sessionStorage = TrackingSessionStorage()
        sessionManager = SessionManager(sessionStorage)
        apiClient = EvcsApiClient(sessionManager)

        // Seed storage with coordinates
        val sampleCoords = mapOf(
            "st_01" to CoordinatePair(21.0285, 105.8542),
            "st_02" to CoordinatePair(20.9935, 105.7981)
        )
        sessionStorage.putString(
            EvcsRepository.KEY_COORDINATE_CACHE,
            EvcsApiClient.json.encodeToString(sampleCoords)
        )

        // Seed storage with favorite stations
        val sampleStations = listOf(
            Station(
                id = "st_01",
                name = "Trạm VinFast Hoàn Kiếm",
                address = "Hà Nội",
                latitude = 21.0285,
                longitude = 105.8542,
                summary = "24/7",
                connectors = "60kW, 120kW",
                depotStatus = "Normal"
            ),
            Station(
                id = "st_02",
                name = "Trạm VinFast Thanh Xuân",
                address = "Hà Nội",
                latitude = 20.9935,
                longitude = 105.7981,
                summary = "24/7",
                connectors = "30kW",
                depotStatus = "Normal"
            )
        )
        sessionStorage.putString(
            EvcsRepository.KEY_OFFLINE_FAVORITES,
            EvcsApiClient.json.encodeToString(sampleStations)
        )

        // Reset tracking counter after seeding
        sessionStorage.getStringCalls.set(0)
        sessionStorage.putStringCalls.set(0)
    }

    @Test
    fun testPhase01CoreFunctionality() = runBlocking {
        // -------------------------------------------------------------
        // 1. Zero disk I/O on construction with default eagerLoadCache = false
        // -------------------------------------------------------------
        val repo = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            eagerLoadCache = false
        )

        assertEquals("Should perform 0 disk reads during init with eagerLoadCache = false", 0, sessionStorage.getStringCalls.get())
        assertFalse("isInitialized must be false before initializeAsync is called", repo.isInitialized.value)
        assertTrue("favoritesState must be empty before initialization", repo.favoritesState.value.isEmpty())
        assertTrue("favoriteIdsState must be empty before initialization", repo.favoriteIdsState.value.isEmpty())
        assertTrue("coordinateCache must be empty before initialization", repo.getCachedCoordinates().isEmpty())

        // -------------------------------------------------------------
        // 2. initializeAsync populates cache on Dispatchers.IO and sets isInitialized
        // -------------------------------------------------------------
        repo.initializeAsync(Dispatchers.IO)

        assertTrue("isInitialized must be true after initializeAsync", repo.isInitialized.value)
        assertTrue("Storage reads must have occurred during initializeAsync", sessionStorage.getStringCalls.get() > 0)

        // Verify coordinates restored
        val coords = repo.getCachedCoordinates()
        assertEquals(2, coords.size)
        assertEquals(Pair(21.0285, 105.8542), coords["st_01"])
        assertEquals(Pair(20.9935, 105.7981), coords["st_02"])

        // Verify reactive StateFlows updated
        val favorites = repo.favoritesState.value
        assertEquals(2, favorites.size)
        assertEquals("st_01", favorites[0].id)
        assertEquals("Trạm VinFast Hoàn Kiếm", favorites[0].name)

        val favoriteIds = repo.favoriteIdsState.value
        assertEquals(setOf("st_01", "st_02"), favoriteIds)

        // -------------------------------------------------------------
        // 3. Helper initialize(scope) for non-suspending callers
        // -------------------------------------------------------------
        val repoNonSuspending = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            eagerLoadCache = false
        )
        assertFalse(repoNonSuspending.isInitialized.value)
        val job = repoNonSuspending.initialize(this, Dispatchers.IO)
        job.join()
        assertTrue(repoNonSuspending.isInitialized.value)
        assertEquals(2, repoNonSuspending.favoritesState.value.size)

        // -------------------------------------------------------------
        // 4. Backward compatibility: eagerLoadCache = true populates synchronously
        // -------------------------------------------------------------
        val callsBefore = sessionStorage.getStringCalls.get()
        val repoEager = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            eagerLoadCache = true
        )
        assertTrue("eagerLoadCache = true must immediately read storage", sessionStorage.getStringCalls.get() > callsBefore)
        assertTrue("isInitialized must be true for eagerLoadCache = true", repoEager.isInitialized.value)
        assertEquals(2, repoEager.favoritesState.value.size)
        assertEquals(2, repoEager.getCachedCoordinates().size)

        // -------------------------------------------------------------
        // 5. Concurrent multi-threaded reads/writes to coordinateCache
        // -------------------------------------------------------------
        coroutineScope {
            val writers = (1..50).map { i ->
                async(Dispatchers.Default) {
                    for (j in 1..100) {
                        val key = "concurrent_station_${(i * 100 + j) % 20}"
                        val fav = FavoriteStationRaw(locationId = key, name = "Name $key", address = "Addr")
                        val search = SearchStationRaw(locationId = key, latitude = 21.0 + (j * 0.001), longitude = 105.0 + (j * 0.001))
                        repo.mergeToDomainStation(fav, search)
                    }
                }
            }
            val readers = (1..50).map {
                async(Dispatchers.Default) {
                    for (j in 1..100) {
                        val current = repo.getCachedCoordinates()
                        for ((_, pair) in current) {
                            assertTrue(pair.first != 0.0 || pair.second != 0.0)
                        }
                    }
                }
            }
            (writers + readers).awaitAll()
        }

        // Successfully completed without ConcurrentModificationException
        assertTrue(repo.isInitialized.value)
        assertTrue(repo.getCachedCoordinates().size >= 20)
    }
}
