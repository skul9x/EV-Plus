package com.evcs.favorites.data.cache

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.repository.EvcsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite for Phase 04: Bounded LRU Memory Caching.
 * Verifies:
 * 1. Bounded capacity enforcement and eldest-entry eviction.
 * 2. Least-Recently-Used access order promotion upon get/put operations.
 * 3. Thread-safe concurrent operations without ConcurrentModificationException or deadlocks.
 * 4. Snapshot immutability and snapshot-based collection accessors.
 * 5. EvcsRepository coordinate cache bounds, eviction, and clearing integration.
 */
class BoundedLruMemoryCacheTest {

    @Test
    fun testCapacityConstraintAndEldestEviction() {
        val cache = BoundedLruMap<String, Int>(maxCapacity = 3)
        assertEquals(3, cache.maxCapacity)
        assertTrue(cache.isEmpty())
        assertEquals(0, cache.size)

        cache["k1"] = 1
        cache["k2"] = 2
        cache["k3"] = 3
        assertEquals(3, cache.size)
        assertTrue(cache.containsKey("k1"))
        assertTrue(cache.containsKey("k2"))
        assertTrue(cache.containsKey("k3"))

        // Exceed capacity: k1 is eldest and must be evicted
        cache["k4"] = 4
        assertEquals(3, cache.size)
        assertFalse(cache.containsKey("k1"))
        assertNull(cache["k1"])
        assertTrue(cache.containsKey("k2"))
        assertTrue(cache.containsKey("k3"))
        assertTrue(cache.containsKey("k4"))
    }

    @Test
    fun testAccessOrderPromotesRecentlyUsedEntries() {
        val cache = BoundedLruMap<String, String>(maxCapacity = 3)
        cache["A"] = "Alpha"
        cache["B"] = "Beta"
        cache["C"] = "Gamma"

        // Access "A" via get -> moves "A" to most-recently-used position
        assertEquals("Alpha", cache["A"])

        // Insert "D" -> "B" is now the eldest (not "A") and should be evicted
        cache["D"] = "Delta"
        assertEquals(3, cache.size)
        assertTrue(cache.containsKey("A"))
        assertFalse(cache.containsKey("B"))
        assertTrue(cache.containsKey("C"))
        assertTrue(cache.containsKey("D"))

        // Update "C" via put -> moves "C" to most-recently-used position: order becomes [A, D, C]
        cache["C"] = "Gamma_Updated"

        // Insert "E" -> "A" is at the head of [A, D, C] and must be evicted
        cache["E"] = "Epsilon"
        assertEquals(3, cache.size)
        assertFalse(cache.containsKey("A"))
        assertTrue(cache.containsKey("D"))
        assertTrue(cache.containsKey("C"))
        assertTrue(cache.containsKey("E"))
    }

    @Test
    fun testSnapshotAndCollectionAccessorsPreventConcurrentModification() {
        val cache = BoundedLruMap<String, Int>(maxCapacity = 5)
        cache["one"] = 1
        cache["two"] = 2
        cache["three"] = 3

        val snap = cache.snapshot()
        assertEquals(3, snap.size)
        assertEquals(1, snap["one"])

        // Modifying original does not affect existing snapshot
        cache["four"] = 4
        assertEquals(4, cache.size)
        assertEquals(3, snap.size)
        assertFalse(snap.containsKey("four"))

        // Snapshot-based keys, values, and entries can be iterated while modifying the cache
        val keySet = cache.keys
        val valueList = cache.values
        val entrySet = cache.entries

        cache["five"] = 5
        cache.remove("one")

        // Iteration on previously taken keys/values/entries remains safe
        assertEquals(4, keySet.size)
        assertEquals(4, valueList.size)
        assertEquals(4, entrySet.size)
    }

    @Test
    fun testConcurrentCoroutineReadsAndWrites() = runBlocking {
        val cache = BoundedLruMap<String, Int>(maxCapacity = 50)

        coroutineScope {
            val writers = (1..20).map { workerId ->
                async(Dispatchers.Default) {
                    repeat(100) { i ->
                        cache["key_${workerId}_$i"] = i
                        if (i % 10 == 0) {
                            cache.remove("key_${workerId}_${i - 5}")
                        }
                    }
                }
            }

            val readers = (1..20).map {
                async(Dispatchers.Default) {
                    repeat(100) {
                        val snap = cache.snapshot()
                        val keys = cache.keys
                        val values = cache.values
                        val entries = cache.entries
                        assertTrue(snap.size <= 50)
                        assertTrue(keys.size <= 50)
                        assertTrue(values.size <= 50)
                        assertTrue(entries.size <= 50)
                        assertTrue(cache.size <= 50)
                    }
                }
            }

            (writers + readers).awaitAll()
        }

        assertTrue(cache.size <= 50)
    }

    @Test
    fun testEvcsRepositoryBoundedCoordinateCacheIntegration() {
        val sessionStorage = InMemorySessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val apiClient = EvcsApiClient(sessionManager)

        // Inject a BoundedLruMap with maxCapacity = 2
        val boundedCache = BoundedLruMap<String, Pair<Double, Double>>(maxCapacity = 2)
        val repository = EvcsRepository(
            apiClient = apiClient,
            coordinateCache = boundedCache,
            cacheStorage = sessionStorage
        )

        // Directly populate coordinate cache
        boundedCache["loc_1"] = Pair(21.0, 105.0)
        boundedCache["loc_2"] = Pair(21.1, 105.1)
        assertEquals(2, repository.getCachedCoordinates().size)

        // Add 3rd coordinate -> evicts loc_1
        boundedCache["loc_3"] = Pair(21.2, 105.2)
        val currentCoords = repository.getCachedCoordinates()
        assertEquals(2, currentCoords.size)
        assertFalse(currentCoords.containsKey("loc_1"))
        assertTrue(currentCoords.containsKey("loc_2"))
        assertTrue(currentCoords.containsKey("loc_3"))

        // Verify repository clearCoordinateCache wipes bounded map
        repository.clearCoordinateCache()
        assertEquals(0, repository.getCachedCoordinates().size)
        assertTrue(boundedCache.isEmpty())
    }

    @Test
    fun testInvalidMaxCapacityThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedLruMap<String, String>(maxCapacity = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedLruMap<String, String>(maxCapacity = -1)
        }
    }
}
