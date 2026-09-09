package com.evcs.favorites.performance

import com.evcs.favorites.data.repository.CoordinatePair
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single comprehensive verification test for Phase 02: P1 UI Recomposition & Startup Cache Optimization.
 *
 * Verifies:
 * 1. [COMPOSE-01] Elimination of `cookieHeader` parameter from `FavoritesScreen` and `NearbyScreen`.
 * 2. [COMPOSE-01] Elimination of synchronous `viewModel.getCookieHeader()` disk reads in `MainActivity` Compose tree.
 * 3. [STARTUP-02] Default `eagerLoadCache = false` prevents synchronous storage reads during `EvcsRepository` construction.
 * 4. [STARTUP-02] `initializeAsync()` executes asynchronously on `Dispatchers.IO`, properly restoring coordinates & favorites.
 * 5. [STARTUP-02] Backward compatibility when `eagerLoadCache = true` is explicitly provided.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase02P1RecompositionAndStartupCacheTest {

    private fun findProjectFile(relativePath: String): File {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val candidate1 = File(projectDir, relativePath)
        if (candidate1.exists()) return candidate1
        val candidate2 = File(projectDir, "app/$relativePath")
        if (candidate2.exists()) return candidate2
        val candidate3 = File(projectDir, relativePath.removePrefix("app/"))
        if (candidate3.exists()) return candidate3
        throw IllegalArgumentException("Could not locate file: $relativePath from user.dir: ${projectDir.absolutePath}")
    }

    private class TrackingSessionStorage : SessionStorage {
        val getStringCalls = AtomicInteger(0)
        val putStringCalls = AtomicInteger(0)
        private val delegate = InMemorySessionStorage()

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

    // ---------------------------------------------------------------------------------------------
    // Test 1: [COMPOSE-01] Verify cookieHeader parameter removed from FavoritesScreen Composable
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testFavoritesScreenComposableRemovesCookieHeaderParameter() {
        val favoritesScreenClass = Class.forName("com.evcs.favorites.ui.screens.FavoritesScreenKt")
        val favoritesScreenMethod = favoritesScreenClass.declaredMethods.firstOrNull { it.name == "FavoritesScreen" }
        assertNotNull("FavoritesScreen composable function must exist", favoritesScreenMethod)

        val paramNames = favoritesScreenMethod!!.parameters.map { it.name }
        assertFalse(
            "FavoritesScreen must not have a cookieHeader parameter in method signature",
            paramNames.contains("cookieHeader")
        )

        val favoritesFile = findProjectFile("app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt")
        val fileContent = favoritesFile.readText()
        assertFalse(
            "FavoritesScreen.kt source must not declare cookieHeader parameter",
            fileContent.contains("cookieHeader")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2: [COMPOSE-01] Verify cookieHeader parameter removed from NearbyScreen Composable
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testNearbyScreenComposableRemovesCookieHeaderParameter() {
        val nearbyScreenClass = Class.forName("com.evcs.favorites.ui.screens.NearbyScreenKt")
        val nearbyScreenMethod = nearbyScreenClass.declaredMethods.firstOrNull { it.name == "NearbyScreen" }
        assertNotNull("NearbyScreen composable function must exist", nearbyScreenMethod)

        val paramNames = nearbyScreenMethod!!.parameters.map { it.name }
        assertFalse(
            "NearbyScreen must not have a cookieHeader parameter in method signature",
            paramNames.contains("cookieHeader")
        )

        val nearbyFile = findProjectFile("app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt")
        val fileContent = nearbyFile.readText()
        assertFalse(
            "NearbyScreen.kt source must not declare cookieHeader parameter",
            fileContent.contains("cookieHeader")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test 3: [COMPOSE-01] Verify MainActivity Composable tree does not execute getCookieHeader()
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testMainActivityComposableTreeEliminatesCookieHeaderRecompositionCalls() {
        val mainActivityFile = findProjectFile("app/src/main/java/com/evcs/favorites/MainActivity.kt")
        val fileContent = mainActivityFile.readText()

        assertFalse(
            "MainActivity.kt must not pass cookieHeader to Composable screens",
            fileContent.contains("cookieHeader =")
        )

        assertFalse(
            "MainActivity.kt Composable hierarchy must not invoke viewModel.getCookieHeader() on recomposition",
            fileContent.contains("viewModel.getCookieHeader()")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test 4: [STARTUP-02] Default eagerLoadCache = false has zero synchronous storage reads
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testEvcsRepositoryDefaultConstructorPerformsZeroSynchronousStorageReads() {
        val sessionStorage = TrackingSessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val apiClient = EvcsApiClient(sessionManager)

        // Seed storage with coordinates
        val sampleCoords = mapOf(
            "st_01" to CoordinatePair(21.0285, 105.8542),
            "st_02" to CoordinatePair(20.9935, 105.7981)
        )
        sessionStorage.putString(
            EvcsRepository.KEY_COORDINATE_CACHE,
            EvcsApiClient.json.encodeToString(sampleCoords)
        )
        sessionStorage.getStringCalls.set(0)

        // Construct repo using DEFAULT constructor parameters (eagerLoadCache should default to false)
        val repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage
        )

        assertEquals(
            "Repository constructor must perform 0 storage reads when eagerLoadCache defaults to false",
            0,
            sessionStorage.getStringCalls.get()
        )
        assertFalse(
            "isInitialized must remain false prior to asynchronous initialization",
            repository.isInitialized.value
        )
        assertTrue(
            "Coordinate cache in memory must remain unpopulated prior to initializeAsync",
            repository.getCachedCoordinates().isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test 5: [STARTUP-02] initializeAsync() populates coordinates and state off the main thread
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testEvcsRepositoryInitializeAsyncPopulatesCacheOnDispatcher() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sessionStorage = TrackingSessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val apiClient = EvcsApiClient(sessionManager)

        val sampleCoords = mapOf(
            "st_01" to CoordinatePair(21.0285, 105.8542),
            "st_02" to CoordinatePair(20.9935, 105.7981)
        )
        sessionStorage.putString(
            EvcsRepository.KEY_COORDINATE_CACHE,
            EvcsApiClient.json.encodeToString(sampleCoords)
        )

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
            )
        )
        sessionStorage.putString(
            EvcsRepository.KEY_OFFLINE_FAVORITES,
            EvcsApiClient.json.encodeToString(sampleStations)
        )
        sessionStorage.getStringCalls.set(0)

        val repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage
        )

        assertEquals(0, sessionStorage.getStringCalls.get())
        assertFalse(repository.isInitialized.value)

        // Perform asynchronous initialization on testDispatcher
        repository.initializeAsync(testDispatcher)

        assertTrue("isInitialized must be true after initializeAsync completes", repository.isInitialized.value)
        assertTrue("Storage reads must have occurred asynchronously", sessionStorage.getStringCalls.get() > 0)

        val restoredCoords = repository.getCachedCoordinates()
        assertEquals(2, restoredCoords.size)
        assertEquals(Pair(21.0285, 105.8542), restoredCoords["st_01"])
        assertEquals(Pair(20.9935, 105.7981), restoredCoords["st_02"])

        val restoredFavorites = repository.favoritesState.value
        assertEquals(1, restoredFavorites.size)
        assertEquals("st_01", restoredFavorites[0].id)
        assertEquals(setOf("st_01"), repository.favoriteIdsState.value)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 6: [STARTUP-02] Backward compatibility: eagerLoadCache = true synchronously populates
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testEvcsRepositoryEagerLoadCacheTrueMaintainsSynchronousInitialization() {
        val sessionStorage = TrackingSessionStorage()
        val sessionManager = SessionManager(sessionStorage)
        val apiClient = EvcsApiClient(sessionManager)

        val sampleCoords = mapOf("st_01" to CoordinatePair(21.0285, 105.8542))
        sessionStorage.putString(
            EvcsRepository.KEY_COORDINATE_CACHE,
            EvcsApiClient.json.encodeToString(sampleCoords)
        )
        sessionStorage.getStringCalls.set(0)

        val repository = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = sessionStorage,
            eagerLoadCache = true
        )

        assertTrue(
            "eagerLoadCache = true must execute storage reads during construction",
            sessionStorage.getStringCalls.get() > 0
        )
        assertTrue(
            "isInitialized must immediately be true for eagerLoadCache = true",
            repository.isInitialized.value
        )
        assertEquals(
            1,
            repository.getCachedCoordinates().size
        )
    }
}
