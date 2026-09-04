package com.evcs.favorites.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.evcs.favorites.data.preferences.NearbyFilterPreferences
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.domain.model.WattageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Comprehensive verification test for Phase 05:
 * Thread-Safe EncryptedSharedPreferences Singleton.
 *
 * Verifies:
 * 1. Double-checked locking singleton identity:
 *    - getInstance(context) returns identical instance across repeated calls.
 *    - Context unwrap: Activity context vs Application context resolves to the exact same singleton instance.
 *    - Multi-threaded concurrent access returns the exact same singleton instance without race conditions.
 * 2. Unified storage visibility across domain managers:
 *    - Writes via SessionManager are immediately readable via RoutingPreferencesManager & NearbyFilterPreferences.
 *    - Writes via RoutingPreferencesManager and NearbyFilterPreferences are reflected in the underlying singleton storage.
 * 3. Concurrent read/write stress testing:
 *    - Hundreds of concurrent read and write operations across background coroutines complete without
 *      concurrency or Keystore exceptions.
 * 4. Atomic key removal and clear operations:
 *    - Targeted removal in one manager preserves independent keys stored by other managers.
 *    - Full storage clear wipes all keys across the unified shared preferences handle.
 */
class EncryptedSharedPrefsSingletonTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeAppContext: FakeApplicationContext

    @Before
    fun setUp() {
        EncryptedSharedPrefsStorage.resetInstanceForTesting()
        fakePrefs = FakeSharedPreferences()
        fakeAppContext = FakeApplicationContext(fakePrefs)
    }

    @After
    fun tearDown() {
        EncryptedSharedPrefsStorage.resetInstanceForTesting()
    }

    // =========================================================================
    // Criterion 1: Singleton instance consistency across calls and contexts
    // =========================================================================

    @Test
    fun testSingletonReturnsSameInstanceAcrossRepeatedCallsAndContexts() {
        val instance1 = EncryptedSharedPrefsStorage.getInstance(fakeAppContext)
        val instance2 = EncryptedSharedPrefsStorage.getInstance(fakeAppContext)

        assertSame("Subsequent calls with application context must return identical instance", instance1, instance2)

        val fakeActivityContext1 = FakeActivityContext(fakeAppContext)
        val fakeActivityContext2 = FakeActivityContext(fakeAppContext)

        val instanceFromActivity1 = EncryptedSharedPrefsStorage.getInstance(fakeActivityContext1)
        val instanceFromActivity2 = EncryptedSharedPrefsStorage.getInstance(fakeActivityContext2)

        assertSame("Calls with Activity context must unwrap to applicationContext and return singleton", instance1, instanceFromActivity1)
        assertSame("Multiple distinct Activity contexts must yield identical singleton instance", instanceFromActivity1, instanceFromActivity2)
    }

    @Test
    fun testConcurrentSingletonInstantiationThreadSafety() = runBlocking {
        val totalThreads = 50
        val results = (1..totalThreads).map {
            async(Dispatchers.Default) {
                val threadContext = FakeActivityContext(fakeAppContext)
                EncryptedSharedPrefsStorage.getInstance(threadContext)
            }
        }.awaitAll()

        val first = results.first()
        for (inst in results) {
            assertSame("All concurrent getInstance calls must resolve to the exact same instance", first, inst)
        }
    }

    // =========================================================================
    // Criterion 2: Unified storage visibility across domain managers
    // =========================================================================

    @Test
    fun testCrossManagerVisibilityWithSharedStorage() {
        val sessionManager = SessionManager.create(fakeAppContext)
        val routingManager = RoutingPreferencesManager.create(fakeAppContext)
        val filterPreferences = NearbyFilterPreferences.create(fakeAppContext)
        val rawStorage = EncryptedSharedPrefsStorage.getInstance(fakeAppContext)

        // 1. Write via SessionManager
        sessionManager.saveAuthCookie("evcs=cookie_token_xyz; Path=/")
        sessionManager.saveSession("PHPSESSID=php_sess_abc", "csrf_token_secret")
        sessionManager.userEmail = "evdriver@example.com"
        val deviceId = sessionManager.deviceId

        // 2. Write via RoutingPreferencesManager
        routingManager.updateGoogleApiKey("AIzaSyTestApiKey_999")
        routingManager.updatePreferredEngine(RoutingEngineMode.GOOGLE_ONLY)
        routingManager.updateAutoFallback(false)

        // 3. Write via NearbyFilterPreferences
        val targetWattages = setOf(WattageOption.KW_250, WattageOption.KW_180, WattageOption.KW_60)
        filterPreferences.saveSelectedWattages(targetWattages)

        // Verify direct storage reflections
        assertEquals("cookie_token_xyz", rawStorage.getString(SessionManager.KEY_AUTH_COOKIE))
        assertEquals("php_sess_abc", rawStorage.getString(SessionManager.KEY_PHP_SESSION))
        assertEquals("csrf_token_secret", rawStorage.getString(SessionManager.KEY_CSRF_TOKEN))
        assertEquals("evdriver@example.com", rawStorage.getString(SessionManager.KEY_USER_EMAIL))
        assertEquals(deviceId, rawStorage.getString(SessionManager.KEY_DEVICE_ID))
        assertEquals("AIzaSyTestApiKey_999", rawStorage.getString(RoutingPreferencesManager.KEY_GOOGLE_API_KEY))
        assertEquals("GOOGLE_ONLY", rawStorage.getString(RoutingPreferencesManager.KEY_PREFERRED_ENGINE))
        assertEquals("false", rawStorage.getString(RoutingPreferencesManager.KEY_AUTO_FALLBACK))

        // Verify cross-manager immediate visibility
        assertTrue(sessionManager.hasAuthCookie())
        assertEquals("cookie_token_xyz", sessionManager.authCookie)
        assertEquals("php_sess_abc", sessionManager.phpSessionId)
        assertEquals("csrf_token_secret", sessionManager.csrfToken)
        assertEquals("evdriver@example.com", sessionManager.userEmail)

        val routingSettings = routingManager.loadSettings()
        assertEquals("AIzaSyTestApiKey_999", routingSettings.googleApiKey)
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, routingSettings.preferredEngine)
        assertEquals(false, routingSettings.autoFallbackEnabled)

        val restoredWattages = filterPreferences.getSelectedWattages()
        assertEquals(targetWattages, restoredWattages)
        assertTrue(filterPreferences.hasPersistedFilters())
    }

    // =========================================================================
    // Criterion 3: Concurrent read/write stress testing
    // =========================================================================

    @Test
    fun testConcurrentReadAndWriteAcrossCoroutines() = runBlocking {
        val sessionManager = SessionManager.create(fakeAppContext)
        val routingManager = RoutingPreferencesManager.create(fakeAppContext)
        val filterPreferences = NearbyFilterPreferences.create(fakeAppContext)
        val rawStorage = EncryptedSharedPrefsStorage.getInstance(fakeAppContext)

        val jobs = (1..60).map { id ->
            async(Dispatchers.IO) {
                // Interleaved reads and writes across managers
                val key = "stress_test_key_$id"
                rawStorage.putString(key, "val_$id")
                rawStorage.getString(key)

                sessionManager.authCookie = "token_$id"
                sessionManager.hasAuthCookie()
                sessionManager.getCookieHeader()

                routingManager.updateGoogleApiKey("AIzaSyKey_$id")
                routingManager.loadSettings()

                filterPreferences.saveSelectedWattages(
                    if (id % 2 == 0) setOf(WattageOption.KW_250) else setOf(WattageOption.KW_180, WattageOption.KW_60)
                )
                filterPreferences.getSelectedWattages()
            }
        }

        // Must complete without throwing concurrency or Keystore exceptions
        jobs.awaitAll()

        assertNotNull(rawStorage.getString("stress_test_key_1"))
        assertNotNull(sessionManager.authCookie)
        assertNotNull(routingManager.loadSettings().googleApiKey)
        assertTrue(filterPreferences.getSelectedWattages().isNotEmpty())
    }

    // =========================================================================
    // Criterion 4: Atomic key removal and clear operations
    // =========================================================================

    @Test
    fun testKeyRemovalAndClearOperationsOnUnifiedStorage() {
        val sessionManager = SessionManager.create(fakeAppContext)
        val routingManager = RoutingPreferencesManager.create(fakeAppContext)
        val filterPreferences = NearbyFilterPreferences.create(fakeAppContext)
        val rawStorage = EncryptedSharedPrefsStorage.getInstance(fakeAppContext)

        // Seed data
        sessionManager.saveAuthCookie("evcs_auth_val")
        sessionManager.saveSession("PHPSESSID_val", "csrf_val")
        sessionManager.userEmail = "test@domain.com"
        val deviceId = sessionManager.deviceId

        routingManager.updateGoogleApiKey("AIzaSyMyKey")
        filterPreferences.saveSelectedWattages(setOf(WattageOption.KW_120))

        // 1. Partial clear: filterPreferences.clear() removes only its own key
        filterPreferences.clear()
        assertNull("Filter preferences key must be removed", rawStorage.getString(NearbyFilterPreferences.KEY_SELECTED_WATTAGES))
        assertEquals("evcs_auth_val", rawStorage.getString(SessionManager.KEY_AUTH_COOKIE))
        assertEquals("AIzaSyMyKey", rawStorage.getString(RoutingPreferencesManager.KEY_GOOGLE_API_KEY))

        // 2. Partial clear: sessionManager.clearSession() removes session tokens but preserves deviceId and routing settings
        sessionManager.clearSession()
        assertNull("Auth cookie must be removed", sessionManager.authCookie)
        assertNull("PHP session must be removed", sessionManager.phpSessionId)
        assertNull("CSRF token must be removed", sessionManager.csrfToken)
        assertNull("User email must be removed", sessionManager.userEmail)

        assertEquals("Device ID must be preserved after clearSession()", deviceId, sessionManager.deviceId)
        assertEquals("Routing settings must remain intact after clearSession()", "AIzaSyMyKey", routingManager.loadSettings().googleApiKey)

        // 3. Complete clear: sessionManager.clearAll() or rawStorage.clear()
        sessionManager.clearAll()
        assertNull("Storage must be empty after clearAll", rawStorage.getString(SessionManager.KEY_DEVICE_ID))
        assertNull("Storage must be empty after clearAll", rawStorage.getString(RoutingPreferencesManager.KEY_GOOGLE_API_KEY))
        assertEquals("", routingManager.loadSettings().googleApiKey)
        assertTrue(filterPreferences.getSelectedWattages().isEmpty())
    }

    // =========================================================================
    // Test Double Implementations (JVM Thread-Safe SharedPreferences & Context)
    // =========================================================================

    private class FakeApplicationContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeActivityContext(private val appCtx: Context) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = appCtx
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = appCtx.getSharedPreferences(name, mode)
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
