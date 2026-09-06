package com.evcs.favorites.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.EncryptedSharedPrefsStorage
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Comprehensive Verification Test for Phase 03:
 * Public Station Snapshot Storage Decoupling & Seamless Legacy Migration.
 *
 * Verifications:
 * 1. PlainSharedPrefsStorage operates cleanly with standard SharedPreferences (zero Keystore encryption).
 * 2. EvcsRepository writes and reads coordinates and offline favorites snapshots using PlainSharedPrefsStorage.
 * 3. Automatic seamless migration from legacyStorage to cacheStorage when cache is missing,
 *    clearing migrated data from legacy storage.
 * 4. Isolation of sensitive credentials: authentication cookies and session tokens remain strictly
 *    in secure storage and are never stored in public cache storage.
 * 5. Repository async initialization (initializeAsync) migrates legacy data and updates reactive StateFlows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicStationStorageDecouplingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var plainPrefs: TrackingFakeSharedPreferences
    private lateinit var encryptedPrefs: TrackingFakeSharedPreferences
    private lateinit var fakeContext: FakeApplicationContext
    private lateinit var apiClient: EvcsApiClient

    private val sampleStation1 = Station(
        id = "VF_001",
        name = "VinFast Landmark 81",
        address = "720A Dien Bien Phu, Binh Thanh, HCMC",
        latitude = 10.7950,
        longitude = 106.7218,
        summary = "24/7",
        connectors = "120kW, 60kW",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(typeWatts = 120000, label = "120kW", availablePlugs = 2, totalPlugs = 4, displayString = "120kW"),
            PowerPort(typeWatts = 60000, label = "60kW", availablePlugs = 1, totalPlugs = 2, displayString = "60kW")
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 6
    )

    private val sampleStation2 = Station(
        id = "VF_002",
        name = "VinFast Times City",
        address = "458 Minh Khai, Hai Ba Trung, Hanoi",
        latitude = 20.9950,
        longitude = 105.8680,
        summary = "24/7",
        connectors = "60kW",
        depotStatus = "Normal",
        powers = listOf(
            PowerPort(typeWatts = 60000, label = "60kW", availablePlugs = 2, totalPlugs = 2, displayString = "60kW")
        ),
        totalAvailablePlugs = 2,
        totalPlugs = 2
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        PlainSharedPrefsStorage.resetInstanceForTesting()
        EncryptedSharedPrefsStorage.resetInstanceForTesting()

        plainPrefs = TrackingFakeSharedPreferences()
        encryptedPrefs = TrackingFakeSharedPreferences()
        fakeContext = FakeApplicationContext(plainPrefs, encryptedPrefs)

        val sessionManager = SessionManager(InMemorySessionStorage())
        apiClient = EvcsApiClient(sessionManager, OkHttpClient(), "https://mock.test")
    }

    @After
    fun tearDown() {
        PlainSharedPrefsStorage.resetInstanceForTesting()
        EncryptedSharedPrefsStorage.resetInstanceForTesting()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. PlainSharedPrefsStorage CRUD, Singleton, & Warmup Verification
    // =========================================================================

    @Test
    fun testPlainSharedPrefsStorage_operatesWithoutKeystoreEncryption() = runTest(testDispatcher) {
        val storage = PlainSharedPrefsStorage(fakeContext)

        // Warmup completes asynchronously on Dispatchers.IO
        storage.warmUp()

        assertNull(storage.getString("missing_key"))

        storage.putString("coord_key", "{\"lat\":21.0,\"lon\":105.0}")
        assertEquals("{\"lat\":21.0,\"lon\":105.0}", storage.getString("coord_key"))

        // Putting null removes the key
        storage.putString("coord_key", null)
        assertNull(storage.getString("coord_key"))

        storage.putString("k1", "v1")
        storage.putString("k2", "v2")
        storage.remove("k1")
        assertNull(storage.getString("k1"))
        assertEquals("v2", storage.getString("k2"))

        storage.clear()
        assertNull(storage.getString("k2"))

        // Singleton instance test
        val instance1 = PlainSharedPrefsStorage.getInstance(fakeContext)
        val instance2 = PlainSharedPrefsStorage.getInstance(fakeContext)
        assertTrue("getInstance must return the same singleton instance", instance1 === instance2)
    }

    // =========================================================================
    // 2. EvcsRepository Reads/Writes via Plain Storage
    // =========================================================================

    @Test
    fun testEvcsRepository_persistsAndRestoresCoordinatesAndFavoritesInPlainStorage() = runTest(testDispatcher) {
        val plainStorage = PlainSharedPrefsStorage(fakeContext)
        val coordinateMap = ConcurrentHashMap<String, Pair<Double, Double>>()
        coordinateMap["vf_001"] = Pair(10.7950, 106.7218)
        coordinateMap["vf_002"] = Pair(20.9950, 105.8680)

        val repo = EvcsRepository(
            apiClient = apiClient,
            coordinateCache = coordinateMap,
            cacheStorage = plainStorage
        )

        // Save coordinates and verify in plain storage
        repo.saveCachedCoordinates()
        val plainCoordJson = plainStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE)
        assertNotNull("Coordinates must be persisted in plain storage", plainCoordJson)
        assertTrue(plainCoordJson!!.contains("10.795"))
        assertTrue(plainCoordJson.contains("106.7218"))

        // Save favorites and verify in plain storage
        repo.saveCachedFavorites(listOf(sampleStation1, sampleStation2))
        val plainFavJson = plainStorage.getString(EvcsRepository.KEY_OFFLINE_FAVORITES)
        assertNotNull("Offline favorites snapshot must be persisted in plain storage", plainFavJson)
        assertTrue(plainFavJson!!.contains("VF_001"))
        assertTrue(plainFavJson.contains("VinFast Landmark 81"))

        // Create new repository instance pointing to same plainStorage and verify retrieval
        val newRepo = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = plainStorage
        )
        val loadedCoords = newRepo.loadCachedCoordinates()
        assertEquals(2, loadedCoords.size)
        assertEquals(Pair(10.7950, 106.7218), loadedCoords["vf_001"])
        assertEquals(Pair(20.9950, 105.8680), loadedCoords["vf_002"])

        val loadedFavorites = newRepo.getCachedFavorites()
        assertEquals(2, loadedFavorites.size)
        assertEquals("VF_001", loadedFavorites[0].id)
        assertEquals("VinFast Landmark 81", loadedFavorites[0].name)
        assertEquals("VF_002", loadedFavorites[1].id)
        assertEquals("VinFast Times City", loadedFavorites[1].name)
    }

    // =========================================================================
    // 3. Automatic Seamless Migration from Legacy Storage
    // =========================================================================

    @Test
    fun testEvcsRepository_migratesLegacyCoordinatesAndFavoritesToPlainStorageAndPurgesLegacy() = runTest(testDispatcher) {
        val plainStorage = InMemorySessionStorage()
        val legacyStorage = InMemorySessionStorage()

        // Populate legacy storage with old encrypted snapshot
        val legacyCoordinates = mapOf(
            "vf_legacy_1" to CoordinatePair(21.0285, 105.8542),
            "vf_legacy_2" to CoordinatePair(10.8231, 106.6297)
        )
        val legacyCoordJson = EvcsApiClient.json.encodeToString(legacyCoordinates)
        legacyStorage.putString(EvcsRepository.KEY_COORDINATE_CACHE, legacyCoordJson)

        val legacyStations = listOf(sampleStation1, sampleStation2)
        val legacyFavJson = EvcsApiClient.json.encodeToString(legacyStations)
        legacyStorage.putString(EvcsRepository.KEY_OFFLINE_FAVORITES, legacyFavJson)

        // Plain storage starts completely empty
        assertNull(plainStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE))
        assertNull(plainStorage.getString(EvcsRepository.KEY_OFFLINE_FAVORITES))

        val repo = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = plainStorage,
            legacyStorage = legacyStorage
        )

        // 1. Verify coordinate migration
        val loadedCoords = repo.loadCachedCoordinates()
        assertEquals(2, loadedCoords.size)
        assertEquals(Pair(21.0285, 105.8542), loadedCoords["vf_legacy_1"])
        assertEquals(Pair(10.8231, 106.6297), loadedCoords["vf_legacy_2"])

        // Coordinates migrated to plainStorage and removed from legacyStorage
        assertNotNull("Coordinates must now be present in plainStorage", plainStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE))
        assertNull("Coordinates must be purged from legacyStorage", legacyStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE))

        // 2. Verify offline favorites migration
        val loadedFavorites = repo.getCachedFavorites()
        assertEquals(2, loadedFavorites.size)
        assertEquals("VF_001", loadedFavorites[0].id)
        assertEquals("VF_002", loadedFavorites[1].id)

        // Favorites migrated to plainStorage and removed from legacyStorage
        assertNotNull("Favorites snapshot must now be present in plainStorage", plainStorage.getString(EvcsRepository.KEY_OFFLINE_FAVORITES))
        assertNull("Favorites snapshot must be purged from legacyStorage", legacyStorage.getString(EvcsRepository.KEY_OFFLINE_FAVORITES))

        // 3. Subsequent reads should read directly from plainStorage without touching legacy
        val secondReadCoords = repo.loadCachedCoordinates()
        assertEquals(2, secondReadCoords.size)
        val secondReadFavs = repo.getCachedFavorites()
        assertEquals(2, secondReadFavs.size)
    }

    // =========================================================================
    // 4. initializeAsync Migrates Legacy Data and Updates StateFlows
    // =========================================================================

    @Test
    fun testEvcsRepository_initializeAsync_migratesLegacyDataAndPopulatesStateFlows() = runTest(testDispatcher) {
        val plainStorage = InMemorySessionStorage()
        val legacyStorage = InMemorySessionStorage()

        val legacyCoords = mapOf("loc_1" to CoordinatePair(21.0, 105.0))
        legacyStorage.putString(EvcsRepository.KEY_COORDINATE_CACHE, EvcsApiClient.json.encodeToString(legacyCoords))
        legacyStorage.putString(EvcsRepository.KEY_OFFLINE_FAVORITES, EvcsApiClient.json.encodeToString(listOf(sampleStation1)))

        val repo = EvcsRepository(
            apiClient = apiClient,
            cacheStorage = plainStorage,
            legacyStorage = legacyStorage,
            eagerLoadCache = false
        )

        assertFalse("isInitialized must be false prior to initializeAsync", repo.isInitialized.value)
        assertTrue("favoritesState must be empty initially", repo.favoritesState.value.isEmpty())

        // Run initializeAsync on testDispatcher
        repo.initializeAsync(testDispatcher)

        assertTrue("isInitialized must be true after initializeAsync", repo.isInitialized.value)
        assertEquals(1, repo.favoritesState.value.size)
        assertEquals("VF_001", repo.favoritesState.value[0].id)
        assertEquals(setOf("VF_001"), repo.favoriteIdsState.value)
        assertEquals(Pair(21.0, 105.0), repo.getCachedCoordinates()["loc_1"])

        // Verify data was migrated and purged from legacy
        assertNotNull(plainStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE))
        assertNotNull(plainStorage.getString(EvcsRepository.KEY_OFFLINE_FAVORITES))
        assertNull(legacyStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE))
        assertNull(legacyStorage.getString(EvcsRepository.KEY_OFFLINE_FAVORITES))
    }

    // =========================================================================
    // 5. Credential Isolation: Sensitive Auth Tokens Never Stored in Plain Cache
    // =========================================================================

    @Test
    fun testCredentialIsolation_authTokensKeptInSecureStorage_neverInPlainCache() = runTest(testDispatcher) {
        val secureStorage = InMemorySessionStorage()
        val plainCacheStorage = PlainSharedPrefsStorage(fakeContext)

        val sessionManager = SessionManager(secureStorage)
        sessionManager.saveAuthCookie("secret_evcs_auth_cookie_token")
        sessionManager.saveSession("secret_phpsessid", "secret_csrf_token")
        sessionManager.userEmail = "driver@evplus.vn"

        val repo = EvcsRepository(
            apiClient = EvcsApiClient(sessionManager),
            cacheStorage = plainCacheStorage
        )

        // Save public station data
        repo.saveCachedFavorites(listOf(sampleStation1))
        repo.saveCachedCoordinates()

        // 1. Verify credentials ARE in secureStorage
        assertEquals("secret_evcs_auth_cookie_token", secureStorage.getString(SessionManager.KEY_AUTH_COOKIE))
        assertEquals("secret_phpsessid", secureStorage.getString(SessionManager.KEY_PHP_SESSION))
        assertEquals("secret_csrf_token", secureStorage.getString(SessionManager.KEY_CSRF_TOKEN))
        assertEquals("driver@evplus.vn", secureStorage.getString(SessionManager.KEY_USER_EMAIL))

        // 2. Verify credentials ARE NOT in plainCacheStorage
        assertNull(plainCacheStorage.getString(SessionManager.KEY_AUTH_COOKIE))
        assertNull(plainCacheStorage.getString(SessionManager.KEY_PHP_SESSION))
        assertNull(plainCacheStorage.getString(SessionManager.KEY_CSRF_TOKEN))
        assertNull(plainCacheStorage.getString(SessionManager.KEY_USER_EMAIL))
        assertNull(plainCacheStorage.getString(SessionManager.KEY_DEVICE_ID))

        // 3. Verify public cache has station data
        assertNotNull(plainCacheStorage.getString(EvcsRepository.KEY_OFFLINE_FAVORITES))
        assertNotNull(plainCacheStorage.getString(EvcsRepository.KEY_COORDINATE_CACHE))
    }

    // =========================================================================
    // Test Doubles (SharedPreferences & Context)
    // =========================================================================

    private class FakeApplicationContext(
        private val plainPrefs: SharedPreferences,
        private val securePrefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return if (name == "evcs_public_cache") plainPrefs else securePrefs
        }
    }

    private class TrackingFakeSharedPreferences : SharedPreferences {
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
