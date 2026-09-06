package com.evcs.favorites.performance

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.EncryptedSharedPrefsStorage
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.network.AppOkHttpClientProvider
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.preferences.SmartFilterPreferences
import com.evcs.favorites.data.repository.FakeFirestoreFavoritesDataSource
import com.evcs.favorites.data.repository.FirestoreFavoritesRepository
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Single comprehensive verification test for Phase 01: Cold Start & Main Thread Unblocking (PERF-001, PERF-009).
 *
 * Verifies:
 * 1. SmartFilterPreferences, NearbyFilterPreferences, and RoutingPreferencesManager factories
 *    produce instances backed by PlainSharedPrefsStorage and read/write data with zero Keystore overhead.
 * 2. PlainSharedPrefsStorage.getInstance properly segregates independent storage files by name and reuses instances.
 * 3. FirestoreFavoritesRepository loads cached offline favorites asynchronously via coroutines without synchronous execution blocking the caller thread.
 * 4. AppOkHttpClientProvider.installDiskCache can be safely invoked in background dispatchers without thread contention or crashing getSharedClient().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartAndMainThreadUnblockingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeContext: TrackingFakeContext
    private var tempCacheDir: File? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        PlainSharedPrefsStorage.resetInstanceForTesting()
        EncryptedSharedPrefsStorage.resetInstanceForTesting()
        AppOkHttpClientProvider.resetForTesting()
        fakeContext = TrackingFakeContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        PlainSharedPrefsStorage.resetInstanceForTesting()
        EncryptedSharedPrefsStorage.resetInstanceForTesting()
        AppOkHttpClientProvider.resetForTesting()
        tempCacheDir?.deleteRecursively()
    }

    // =========================================================================
    // 1. Preference Factories & Zero-Keystore Overhead Verification
    // =========================================================================

    @Test
    fun testPreferenceFactoriesUsePlainSharedPrefsWithoutKeystoreOverhead() {
        // --- A. SmartFilterPreferences ---
        val smartPrefs = SmartFilterPreferences.create(fakeContext)

        // Read/Write roundtrip
        smartPrefs.saveActiveFilterMode(SmartFilterMode.DC)
        smartPrefs.saveSelectedDcTier(DcWattageTier.GE_120KW)
        val customConfig = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 60,
            maxKw = 180
        )
        smartPrefs.saveCustomConfig(customConfig)

        assertTrue(
            "SmartFilterPreferences must access 'evcs_smart_filter_prefs'",
            fakeContext.createdPrefsNames.contains("evcs_smart_filter_prefs")
        )
        assertEquals(SmartFilterMode.DC, smartPrefs.getActiveFilterMode())
        assertEquals(DcWattageTier.GE_120KW, smartPrefs.getSelectedDcTier())
        assertEquals(customConfig, smartPrefs.getCustomConfig())

        // --- B. NearbyFilterPreferences ---
        val nearbyPrefs = NearbyFilterPreferences.create(fakeContext)

        val selectedWattages = setOf(WattageOption.KW_250, WattageOption.KW_180, WattageOption.KW_60)
        nearbyPrefs.saveSelectedWattages(selectedWattages)

        assertTrue(
            "NearbyFilterPreferences must access 'evcs_nearby_filter_prefs'",
            fakeContext.createdPrefsNames.contains("evcs_nearby_filter_prefs")
        )
        assertTrue(nearbyPrefs.hasPersistedFilters())
        assertEquals(selectedWattages, nearbyPrefs.getSelectedWattages())

        // --- C. RoutingPreferencesManager ---
        val routingManager = RoutingPreferencesManager.create(fakeContext)

        val newSettings = RoutingSettings(
            googleApiKey = "AIzaSyFakeGoogleKeyForTest123",
            preferredEngine = RoutingEngineMode.GOOGLE_ONLY,
            autoFallbackEnabled = false,
            customOsrmServerUrl = "https://osrm.custom.domain.org"
        )
        routingManager.saveSettings(newSettings)

        assertTrue(
            "RoutingPreferencesManager must access 'evcs_routing_prefs'",
            fakeContext.createdPrefsNames.contains("evcs_routing_prefs")
        )
        val loaded = routingManager.loadSettings()
        assertEquals("AIzaSyFakeGoogleKeyForTest123", loaded.googleApiKey)
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, loaded.preferredEngine)
        assertFalse(loaded.autoFallbackEnabled)
        assertEquals("https://osrm.custom.domain.org", loaded.customOsrmServerUrl)

        // Confirm EncryptedSharedPreferences was NEVER accessed
        assertFalse(
            "EncryptedSharedPreferences 'evcs_secure_session' must NOT be accessed for UI/Routing preferences",
            fakeContext.createdPrefsNames.contains("evcs_secure_session")
        )
    }

    // =========================================================================
    // 2. PlainSharedPrefsStorage Multi-Instance Registry & Name Segregation
    // =========================================================================

    @Test
    fun testPlainSharedPrefsStorage_segregatesByPrefsNameAndReusesInstances() {
        val storageA = PlainSharedPrefsStorage.getInstance(fakeContext, "prefs_namespace_a")
        val storageB = PlainSharedPrefsStorage.getInstance(fakeContext, "prefs_namespace_b")
        val storageA2 = PlainSharedPrefsStorage.getInstance(fakeContext, "prefs_namespace_a")

        // Thread-safe registry instance reuse
        assertSame("Same preference name must return identical cached instance", storageA, storageA2)

        // Data segregation between distinct files
        storageA.putString("shared_key", "value_for_A")
        storageB.putString("shared_key", "value_for_B")

        assertEquals("value_for_A", storageA.getString("shared_key"))
        assertEquals("value_for_B", storageB.getString("shared_key"))

        storageA.remove("shared_key")
        assertEquals(null, storageA.getString("shared_key"))
        assertEquals("value_for_B", storageB.getString("shared_key"))
    }

    // =========================================================================
    // 3. FirestoreFavoritesRepository Asynchronous Non-Blocking Init
    // =========================================================================

    @Test
    fun testFirestoreFavoritesRepository_loadsCacheAsynchronouslyWithoutBlockingCallerThread() = runTest(testDispatcher) {
        val storage = PlainSharedPrefsStorage.getInstance(fakeContext, "evcs_test_favorites_cache")
        val testStations = listOf(
            Station(
                id = "VF_TEST_01",
                name = "VinFast Thao Dien",
                address = "12 Thao Dien, District 2, HCMC",
                latitude = 10.8031,
                longitude = 106.7324,
                summary = "24/7",
                connectors = "180kW, 60kW",
                depotStatus = "Normal",
                powers = listOf(PowerPort(180000, "180kW", 2, 2, "180kW")),
                addedAt = 1700000000L
            ),
            Station(
                id = "VF_TEST_02",
                name = "VinFast Royal City",
                address = "72A Nguyen Trai, Thanh Xuan, Hanoi",
                latitude = 20.9998,
                longitude = 105.8152,
                summary = "24/7",
                connectors = "250kW",
                depotStatus = "Normal",
                powers = listOf(PowerPort(250000, "250kW", 4, 4, "250kW")),
                addedAt = 1700000100L
            )
        )
        // Store pre-cached stations as JSON
        val encodedJson = EvcsApiClient.json.encodeToString(testStations)
        storage.putString(FirestoreFavoritesRepository.KEY_OFFLINE_FAVORITES, encodedJson)

        val fakeRemoteDataSource = FakeFirestoreFavoritesDataSource()

        // Construct repository with testDispatcher
        val repo = FirestoreFavoritesRepository(
            remoteDataSource = fakeRemoteDataSource,
            localStorage = storage,
            authService = null,
            ioDispatcher = testDispatcher
        )

        // 1. Immediately upon constructor exit, the caller thread was NOT blocked:
        //    StateFlow remains initial empty state until the coroutine runs
        assertTrue(
            "StateFlow must NOT be populated synchronously on constructor thread",
            repo.favoritesState.value.isEmpty()
        )
        assertTrue(
            "Favorite IDs must NOT be populated synchronously on constructor thread",
            repo.favoriteIdsState.value.isEmpty()
        )

        // 2. Advance the coroutine scheduler on ioDispatcher
        testScheduler.runCurrent()

        // 3. Verify state is now asynchronously populated
        val loadedStations = repo.favoritesState.value
        assertEquals(2, loadedStations.size)
        assertEquals("VF_TEST_01", loadedStations[0].id)
        assertEquals("VF_TEST_02", loadedStations[1].id)

        val loadedIds = repo.favoriteIdsState.value
        assertEquals(setOf("VF_TEST_01", "VF_TEST_02"), loadedIds)
    }

    // =========================================================================
    // 4. AppOkHttpClientProvider Thread-Safe Background Disk Cache Installation
    // =========================================================================

    @Test
    fun testAppOkHttpClientProvider_threadSafeBackgroundDiskCacheInstallation() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("okhttp_test_cache").toFile()
        tempCacheDir = tempDir

        // Concurrently invoke installDiskCache and getSharedClient across multiple dispatchers
        val jobs = (1..10).map { index ->
            async(Dispatchers.IO) {
                if (index % 2 == 0) {
                    AppOkHttpClientProvider.installDiskCache(tempDir, 10L * 1024 * 1024)
                }
                val client = AppOkHttpClientProvider.getSharedClient()
                assertNotNull("Shared OkHttpClient must not be null", client)
                val derived = AppOkHttpClientProvider.newSharedClientBuilder().build()
                assertNotNull("Derived OkHttpClient must not be null", derived)
                assertSame(
                    "Connection pool must remain shared across clients",
                    AppOkHttpClientProvider.connectionPool,
                    client.connectionPool
                )
            }
        }

        jobs.awaitAll()

        val finalClient = AppOkHttpClientProvider.getSharedClient()
        assertNotNull(finalClient)
        assertNotNull("Disk cache should be installed on base client", finalClient.cache)
    }

    // =========================================================================
    // Test Doubles (SharedPreferences & Context)
    // =========================================================================

    private class TrackingFakeContext : ContextWrapper(null) {
        val createdPrefsNames = ConcurrentHashMap.newKeySet<String>()
        private val prefsRegistry = ConcurrentHashMap<String, TrackingFakeSharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val safeName = name ?: "default_prefs"
            createdPrefsNames.add(safeName)
            return prefsRegistry.computeIfAbsent(safeName) { TrackingFakeSharedPreferences() }
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
