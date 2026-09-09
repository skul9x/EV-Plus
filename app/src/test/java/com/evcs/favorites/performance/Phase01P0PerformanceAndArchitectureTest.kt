package com.evcs.favorites.performance

import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.AuthService
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.data.repository.FirestoreFavoritesRepository
import com.evcs.favorites.di.AppContainer
import com.evcs.favorites.di.DefaultAppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single comprehensive verification test for Phase 01: P0 Critical Performance & Architecture Remediation.
 *
 * Verifies:
 * 1. [STARTUP-01] SessionManager & EncryptedSharedPrefsStorage have zero synchronous `.commit()` calls.
 * 2. [STARTUP-01] AuthEngine initializes non-blocking without reading disk storage or calling hasAuthCookie(),
 *    defaulting `isLoggedIn` to false, and updating asynchronously via `checkLoggedInAsync()`.
 * 3. [COMPOSE-02] StationCard basicMarquee iterations are capped to 2 with 2000ms delay and 30.dp velocity,
 *    with zero infinite Int.MAX_VALUE animation loops across portrait and landscape layouts.
 * 4. [ARCH-01] DefaultAppContainer properly exposes authService & firestoreFavoritesRepository singletons,
 *    injects firestoreFavoritesRepository into evcsRepository, and ensures singleton reference identity.
 * 5. [ARCH-01] MainActivity resolves dependencies from appContainer, eliminating detached duplicate instances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase01P0PerformanceAndArchitectureTest {

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

    // ---------------------------------------------------------------------------------------------
    // Test 1: [STARTUP-01] Verify EncryptedSharedPrefsStorage & SessionManager eliminate .commit()
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testSessionManagerSourceHasZeroSynchronousCommitCalls() {
        val sessionManagerFile = findProjectFile("app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt")
        val content = sessionManagerFile.readText()

        // 1. Must not contain any .commit() calls
        assertFalse(
            "SessionManager.kt must not contain synchronous .commit() disk operations on main/caller thread",
            content.contains(".commit()")
        )

        // 2. Must contain .apply() for asynchronous disk flushes
        assertTrue(
            "SessionManager.kt should use asynchronous .apply()",
            content.contains(".apply()")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2: [STARTUP-01] Verify AuthEngine initializes non-blocking without calling storage
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testAuthEngineInitializesNonBlockingWithoutStorageReads() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val storageReadCount = AtomicInteger(0)
        val trackingStorage = object : SessionStorage {
            private val delegate = InMemorySessionStorage()

            override fun getString(key: String): String? {
                storageReadCount.incrementAndGet()
                return if (key == SessionManager.KEY_AUTH_COOKIE) "mock_cookie_123" else delegate.getString(key)
            }

            override fun putString(key: String, value: String?) {
                delegate.putString(key, value)
            }

            override fun remove(key: String) {
                delegate.remove(key)
            }

            override fun clear() {
                delegate.clear()
            }
        }

        val sessionManager = SessionManager(trackingStorage)

        // Instantiate AuthEngine - must NOT read storage during construction
        val authEngine = AuthEngine(sessionManager)

        assertEquals(
            "AuthEngine constructor must not perform blocking reads to storage during startup",
            0,
            storageReadCount.get()
        )
        assertFalse(
            "AuthEngine.isLoggedIn must default to false before async verification",
            authEngine.isLoggedIn.value
        )

        // Now trigger checkLoggedInAsync off caller thread
        val result = authEngine.checkLoggedInAsync(testDispatcher)
        testScheduler.advanceUntilIdle()

        assertTrue("checkLoggedInAsync should return true after finding cookie", result)
        assertTrue("AuthEngine.isLoggedIn should reflect true after async verification", authEngine.isLoggedIn.value)
        assertTrue("Storage read should have occurred asynchronously", storageReadCount.get() > 0)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 3: [COMPOSE-02] Verify StationCard basicMarquee iterations capped to 2
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testStationCardMarqueeIterationsAreCappedToTwo() {
        val stationCardFile = findProjectFile("app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt")
        val content = stationCardFile.readText()

        // 1. Zero infinite Int.MAX_VALUE iterations
        assertFalse(
            "StationCard.kt must not use Int.MAX_VALUE for basicMarquee (causes infinite frame invalidation loop)",
            content.contains("iterations = Int.MAX_VALUE")
        )

        // 2. Both portrait and landscape should use iterations = 2
        val marqueeIterationsMatches = Regex("""iterations\s*=\s*(\d+)""").findAll(content).toList()
        assertTrue(
            "StationCard.kt must configure basicMarquee iterations (found ${marqueeIterationsMatches.size})",
            marqueeIterationsMatches.size >= 2
        )
        for (match in marqueeIterationsMatches) {
            val iter = match.groupValues[1].toInt()
            assertEquals("Marquee iterations must be capped to 2", 2, iter)
        }

        // 3. Verify delayMillis = 2000 and velocity = 30.dp
        assertTrue("StationCard marquee must include delayMillis = 2000", content.contains("delayMillis = 2000"))
        assertTrue("StationCard marquee must include velocity = 30.dp", content.contains("velocity = 30.dp"))
    }

    // ---------------------------------------------------------------------------------------------
    // Test 4: [ARCH-01] Verify DefaultAppContainer singletons & repository wiring
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testAppContainerExposesAndWiresSingletonsCorrectly() {
        val container: AppContainer = DefaultAppContainer(null)

        // Verify interface exposure and instantiation
        val authService: AuthService = container.authService
        assertNotNull("AppContainer must expose non-null authService", authService)

        val firestoreRepo: FirestoreFavoritesRepository = container.firestoreFavoritesRepository
        assertNotNull("AppContainer must expose non-null firestoreFavoritesRepository", firestoreRepo)

        val evcsRepo = container.evcsRepository
        assertNotNull("AppContainer must expose non-null evcsRepository", evcsRepo)

        // Verify that DefaultAppContainer injects firestoreFavoritesRepository into evcsRepository
        assertSame(
            "DefaultAppContainer must inject its firestoreFavoritesRepository singleton into evcsRepository",
            firestoreRepo,
            evcsRepo.firestoreFavoritesRepository
        )

        // Verify singletons return the same instance across multiple invocations
        assertSame("authService must be a lazy singleton", authService, container.authService)
        assertSame("firestoreFavoritesRepository must be a lazy singleton", firestoreRepo, container.firestoreFavoritesRepository)
        assertSame("evcsRepository must be a lazy singleton", evcsRepo, container.evcsRepository)
        assertSame("sessionManager must be a lazy singleton", container.sessionManager, container.sessionManager)
        assertSame("evcsApiClient must be a lazy singleton", container.evcsApiClient, container.evcsApiClient)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 5: [ARCH-01] Verify MainActivity binds dependencies to appContainer
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testMainActivityResolvesDependenciesFromAppContainer() {
        val mainActivityFile = findProjectFile("app/src/main/java/com/evcs/favorites/MainActivity.kt")
        val content = mainActivityFile.readText()

        // 1. MainActivity must resolve appContainer from application
        assertTrue(
            "MainActivity must resolve appContainer from (application as EvPlusApplication).appContainer",
            content.contains("(application as EvPlusApplication).appContainer")
        )

        // 2. Dependencies must delegate to appContainer
        assertTrue("sessionManager must reference appContainer", content.contains("appContainer.sessionManager"))
        assertTrue("apiClient must reference appContainer", content.contains("appContainer.evcsApiClient"))
        assertTrue("authService must reference appContainer", content.contains("appContainer.authService"))
        assertTrue("firestoreFavoritesRepository must reference appContainer", content.contains("appContainer.firestoreFavoritesRepository"))
        assertTrue("repository must reference appContainer", content.contains("appContainer.evcsRepository"))

        // 3. MainActivity must no longer construct its own detached repository or storage
        assertFalse(
            "MainActivity must not directly instantiate EvcsRepository",
            content.contains("EvcsRepository(")
        )
        assertFalse(
            "MainActivity must not directly instantiate SessionManager.create",
            content.contains("SessionManager.create(")
        )
        assertFalse(
            "MainActivity must not directly instantiate FirestoreFavoritesDataSource.create",
            content.contains("FirestoreFavoritesDataSource.create(")
        )
    }
}
