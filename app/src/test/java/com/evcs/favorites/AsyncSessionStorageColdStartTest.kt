package com.evcs.favorites

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.AuthEngine
import com.evcs.favorites.data.auth.EncryptedSharedPrefsStorage
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.viewmodel.FavoritesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Async Keystore Warmup & Non-Blocking ViewModel Startup.
 *
 * Core Verifications:
 * 1. EncryptedSharedPrefsStorage / SessionStorage warms up and initializes asynchronously without deadlocks.
 * 2. AuthEngine.checkLoggedInAsync() correctly reflects authenticated state and updates isLoggedIn StateFlow asynchronously.
 * 3. FavoritesViewModel initializes immediately with FavoritesUiState.Loading on the Main Thread without blocking on session storage.
 * 4. Once checkLoggedInAsync() completes in background, FavoritesViewModel properly transitions to LoggedOut (when no cookie exists)
 *    or starts fetchFavorites() (when authenticated).
 * 5. Concurrency: Concurrent requests during warmup return correct session values once resolved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncSessionStorageColdStartTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var baseUrl: String
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeAppContext: FakeApplicationContext

    private val sampleFavoritesJson = """
    {
      "sync": true,
      "csrf": "csrf_token_test",
      "server": [
        {
          "locationId": "C.STATION01",
          "name": "VinFast Test Station",
          "address": "123 Test Street, Hanoi",
          "summary": "24/7",
          "connectors": "60kW",
          "image": null
        }
      ]
    }
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        EncryptedSharedPrefsStorage.resetInstanceForTesting()

        fakePrefs = FakeSharedPreferences()
        fakeAppContext = FakeApplicationContext(fakePrefs)

        mockWebServer = MockWebServer()
        mockWebServer.start()
        baseUrl = mockWebServer.url("/").toString().removeSuffix("/")

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        EncryptedSharedPrefsStorage.resetInstanceForTesting()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Storage warmup & async initialization without deadlocks
    // =========================================================================

    @Test
    fun testSessionStorageAndEncryptedPrefs_warmUpAsynchronouslyWithoutDeadlocks() = runTest(testDispatcher) {
        val encryptedStorage = EncryptedSharedPrefsStorage(fakeAppContext)

        // Verify warmup executes and triggers lazy prefs initialization asynchronously
        encryptedStorage.warmUp()

        encryptedStorage.putString("warmup_key", "warmup_value")
        assertEquals("warmup_value", encryptedStorage.getString("warmup_key"))

        val inMemoryStorage = InMemorySessionStorage()
        inMemoryStorage.warmUp()
        inMemoryStorage.putString("mem_key", "mem_val")
        assertEquals("mem_val", inMemoryStorage.getString("mem_key"))
    }

    // =========================================================================
    // 2. AuthEngine.checkLoggedInAsync reflects auth state and updates StateFlow
    // =========================================================================

    @Test
    fun testAuthEngine_checkLoggedInAsync_reflectsAuthStateAndUpdatesFlowAsynchronously() = runTest(testDispatcher) {
        val storage = InMemorySessionStorage()
        val sessionManager = SessionManager(storage)
        val authEngine = AuthEngine(
            sessionManager = sessionManager,
            client = okHttpClient,
            baseUrl = baseUrl
        )

        // Initially isLoggedIn is false (no synchronous blocking check on constructor)
        assertFalse("AuthEngine initial isLoggedIn must be false without disk I/O", authEngine.isLoggedIn.value)

        // Check async when unauthenticated
        val unauthResult = authEngine.checkLoggedInAsync(testDispatcher)
        assertFalse("checkLoggedInAsync must return false when no auth cookie is present", unauthResult)
        assertFalse("isLoggedIn StateFlow must remain false", authEngine.isLoggedIn.value)

        // Set auth cookie
        sessionManager.saveAuthCookie("test_auth_cookie_value")

        // Check async when authenticated
        val authResult = authEngine.checkLoggedInAsync(testDispatcher)
        assertTrue("checkLoggedInAsync must return true when auth cookie is present", authResult)
        assertTrue("isLoggedIn StateFlow must be updated to true", authEngine.isLoggedIn.value)
    }

    // =========================================================================
    // 3. FavoritesViewModel immediate Loading state and transition to LoggedOut
    // =========================================================================

    @Test
    fun testFavoritesViewModel_initializesImmediatelyWithLoading_andTransitionsToLoggedOutWhenUnauthenticated() = runTest(testDispatcher) {
        val storage = InMemorySessionStorage()
        val sessionManager = SessionManager(storage)
        val authEngine = AuthEngine(sessionManager, okHttpClient, baseUrl)
        val apiClient = EvcsApiClient(sessionManager, okHttpClient, baseUrl)
        val repository = EvcsRepository(apiClient)

        // Instantiate FavoritesViewModel with testDispatcher for both UI dispatcher and ioDispatcher
        val viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Immediately upon construction: MUST be FavoritesUiState.Loading
        assertEquals(
            "FavoritesViewModel must initialize immediately with Loading state",
            FavoritesUiState.Loading,
            viewModel.uiState.value
        )

        // Advance dispatcher to allow background checkLoggedInAsync to complete
        testDispatcher.scheduler.advanceUntilIdle()

        // After completion: must transition to LoggedOut since unauthenticated
        assertEquals(
            "FavoritesViewModel must transition to LoggedOut when unauthenticated",
            FavoritesUiState.LoggedOut,
            viewModel.uiState.value
        )
    }

    // =========================================================================
    // 4. FavoritesViewModel immediate Loading state and transition to Success when authenticated
    // =========================================================================

    @Test
    fun testFavoritesViewModel_initializesImmediatelyWithLoading_andTriggersFetchFavoritesWhenAuthenticated() = runTest(testDispatcher) {
        val storage = InMemorySessionStorage()
        storage.putString(SessionManager.KEY_AUTH_COOKIE, "valid_session_cookie")
        val sessionManager = SessionManager(storage)
        val authEngine = AuthEngine(sessionManager, okHttpClient, baseUrl)
        val apiClient = EvcsApiClient(sessionManager, okHttpClient, baseUrl)
        val repository = EvcsRepository(apiClient = apiClient, ioDispatcher = testDispatcher)

        // Enqueue successful response for favorites fetch
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sampleFavoritesJson)
        )

        val viewModel = FavoritesViewModel(
            repository = repository,
            authEngine = authEngine,
            dispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Immediately upon construction: MUST be FavoritesUiState.Loading
        assertEquals(
            "FavoritesViewModel must initialize immediately with Loading state",
            FavoritesUiState.Loading,
            viewModel.uiState.value
        )

        // Run pending background coroutines (auth check + fetch favorites)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.favoritesLoadJob?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        // After background resolution: must have triggered fetchFavorites and reached Success state
        assertNotNull("favoritesLoadJob must be triggered on authenticated startup", viewModel.favoritesLoadJob)
        assertTrue(
            "FavoritesViewModel must transition to Success when authenticated, was: ${viewModel.uiState.value}",
            viewModel.uiState.value is FavoritesUiState.Success
        )
        val success = viewModel.uiState.value as FavoritesUiState.Success
        assertEquals(1, success.stations.size)
        assertEquals("C.STATION01", success.stations[0].id)
    }

    // =========================================================================
    // 5. Concurrency: Concurrent requests during warmup return correct session values
    // =========================================================================

    @Test
    fun testConcurrentRequestsDuringWarmup_resolveCorrectSessionValuesWithoutDeadlocks() = runTest(testDispatcher) {
        val encryptedStorage = EncryptedSharedPrefsStorage(fakeAppContext)
        val sessionManager = SessionManager(encryptedStorage)

        sessionManager.saveAuthCookie("concurrent_auth_token")
        sessionManager.saveSession("concurrent_php_sess", "concurrent_csrf")

        val totalJobs = 40
        val deferredList = (1..totalJobs).map { id ->
            async(Dispatchers.IO) {
                if (id % 3 == 0) {
                    sessionManager.warmUp()
                }
                val hasAuth = sessionManager.checkAuthCookieAsync(Dispatchers.IO)
                val cookie = sessionManager.authCookie
                val header = sessionManager.getCookieHeader()
                Triple(hasAuth, cookie, header)
            }
        }

        val results = deferredList.awaitAll()
        for ((hasAuth, cookie, header) in results) {
            assertTrue("hasAuth must be true for all concurrent requests", hasAuth)
            assertEquals("concurrent_auth_token", cookie)
            assertTrue("header must contain evcs cookie", header.contains("evcs=concurrent_auth_token"))
        }
    }

    // =========================================================================
    // Test Doubles (JVM Thread-Safe SharedPreferences & Context)
    // =========================================================================

    private class FakeApplicationContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)

        override fun getString(key: String?, defValue: String?): String? {
            val value = data[key]
            return (value as? String) ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            val value = data[key] as? Set<String>
            return value?.toMutableSet() ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val target: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) staged[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) staged[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) staged[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) staged[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) staged[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) staged[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) staged[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                synchronized(target) {
                    if (clearFlag) {
                        target.clear()
                    }
                    for ((k, v) in staged) {
                        if (v == null) {
                            target.remove(k)
                        } else {
                            target[k] = v
                        }
                    }
                }
            }
        }
    }
}
