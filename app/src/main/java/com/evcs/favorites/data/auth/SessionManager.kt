package com.evcs.favorites.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Storage interface to abstract persistent key-value storage.
 * Enables zero-overhead unit testing on standard JVM while supporting
 * Android's EncryptedSharedPreferences in production.
 */
interface SessionStorage {
    suspend fun warmUp() {}
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
    fun clear()
}

/**
 * Production storage backed by Android's EncryptedSharedPreferences.
 */
class EncryptedSharedPrefsStorage(context: Context) : SessionStorage {
    private val appContext: Context = context.applicationContext ?: context
    private val lock = Any()

    override suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                prefs
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val esp = EncryptedSharedPreferences.create(
                appContext,
                "evcs_secure_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val testKey = "__esp_probe__"
            esp.edit().putString(testKey, "1").commit()
            if (esp.getString(testKey, null) != "1") {
                throw IllegalStateException("EncryptedSharedPreferences probe failed")
            }
            esp.edit().remove(testKey).commit()
            esp
        } catch (e: Throwable) {
            appContext.getSharedPreferences("evcs_session_prefs", Context.MODE_PRIVATE)
        }
    }

    override fun getString(key: String): String? = synchronized(lock) {
        prefs.getString(key, null)
    }

    override fun putString(key: String, value: String?) {
        synchronized(lock) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(key)
            } else {
                editor.putString(key, value)
            }
            editor.apply()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            prefs.edit().remove(key).apply()
        }
    }

    override fun clear() {
        synchronized(lock) {
            prefs.edit().clear().apply()
        }
    }

    companion object {
        @Volatile
        private var instance: EncryptedSharedPrefsStorage? = null

        fun getInstance(context: Context): EncryptedSharedPrefsStorage {
            return instance ?: synchronized(this) {
                instance ?: EncryptedSharedPrefsStorage(context.applicationContext ?: context).also {
                    instance = it
                }
            }
        }

        internal fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}

/**
 * Production storage backed by standard unencrypted SharedPreferences for non-sensitive public caches.
 * Eliminates CPU-heavy AES-GCM Keystore crypto overhead for public offline snapshots and coordinates.
 */
class PlainSharedPrefsStorage(
    context: Context,
    prefsName: String = "evcs_public_cache"
) : SessionStorage {
    private val appContext: Context = context.applicationContext ?: context
    private val lock = Any()
    private val prefs by lazy { appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    override suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                prefs
            }
        }
    }

    override fun getString(key: String): String? = synchronized(lock) {
        prefs.getString(key, null)
    }

    override fun putString(key: String, value: String?) {
        synchronized(lock) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(key)
            } else {
                editor.putString(key, value)
            }
            editor.apply()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            prefs.edit().remove(key).apply()
        }
    }

    override fun clear() {
        synchronized(lock) {
            prefs.edit().clear().apply()
        }
    }

    companion object {
        private val instances = java.util.concurrent.ConcurrentHashMap<String, PlainSharedPrefsStorage>()

        fun getInstance(context: Context, prefsName: String = "evcs_public_cache"): PlainSharedPrefsStorage {
            return instances.computeIfAbsent(prefsName) { name ->
                PlainSharedPrefsStorage(context.applicationContext ?: context, name)
            }
        }

        internal fun resetInstanceForTesting() {
            instances.clear()
        }
    }
}

/**
 * In-memory storage useful for unit testing and fast testing.
 */
class InMemorySessionStorage(
    private val map: MutableMap<String, String> = mutableMapOf()
) : SessionStorage {
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
    override fun clear() {
        map.clear()
    }
}

/**
 * Manages EVCS authentication credentials and session tokens:
 * - Persistent auth cookie `evcs` (1-year validity)
 * - PHP session cookie `PHPSESSID`
 * - CSRF token
 * - Client device UUID `evcs_did`
 *
 * Generates formatted Cookie headers for authenticated requests:
 * `PHPSESSID=...; evcs=...; evcs_did=...`
 */
class SessionManager(
    private val storage: SessionStorage
) {
    companion object {
        const val KEY_AUTH_COOKIE = "evcs"
        const val KEY_PHP_SESSION = "PHPSESSID"
        const val KEY_CSRF_TOKEN = "csrf_token"
        const val KEY_DEVICE_ID = "evcs_did"
        const val KEY_USER_EMAIL = "user_email"

        private val EVCS_COOKIE_REGEX = Regex("""(?:^|;\s*)evcs=([^;]+)""")
        private val PHPSESSID_COOKIE_REGEX = Regex("""(?:^|;\s*)PHPSESSID=([^;]+)""")
        private val COOKIE_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        fun extractCookieValue(headerOrCookie: String, name: String): String? {
            val regex = when (name) {
                KEY_AUTH_COOKIE -> EVCS_COOKIE_REGEX
                KEY_PHP_SESSION -> PHPSESSID_COOKIE_REGEX
                else -> COOKIE_REGEX_CACHE.computeIfAbsent(name) {
                    Regex("""(?:^|;\s*)$it=([^;]+)""")
                }
            }
            return regex.find(headerOrCookie)?.groupValues?.get(1)?.trim()
        }

        fun create(context: Context): SessionManager {
            return SessionManager(EncryptedSharedPrefsStorage.getInstance(context))
        }
    }

    /**
     * Long-lived 1-year authentication cookie (evcs).
     */
    var authCookie: String?
        get() = storage.getString(KEY_AUTH_COOKIE)
        set(value) = storage.putString(KEY_AUTH_COOKIE, value)

    /**
     * Session cookie (PHPSESSID).
     */
    var phpSessionId: String?
        get() = storage.getString(KEY_PHP_SESSION)
        set(value) = storage.putString(KEY_PHP_SESSION, value)

    /**
     * CSRF protection token for reward and favorite actions.
     */
    var csrfToken: String?
        get() = storage.getString(KEY_CSRF_TOKEN)
        set(value) = storage.putString(KEY_CSRF_TOKEN, value)

    /**
     * User's authenticated email.
     */
    var userEmail: String?
        get() = storage.getString(KEY_USER_EMAIL)
        set(value) = storage.putString(KEY_USER_EMAIL, value)

    /**
     * Client device UUID (`evcs_did`). Persisted across sessions; generated on first access if absent.
     */
    val deviceId: String
        get() {
            val existing = storage.getString(KEY_DEVICE_ID)
            if (!existing.isNullOrBlank()) {
                return existing
            }
            val newId = UUID.randomUUID().toString()
            storage.putString(KEY_DEVICE_ID, newId)
            return newId
        }

    /**
     * Saves the 1-year auth cookie `evcs`. Parses out raw header attribute values if needed.
     */
    fun saveAuthCookie(cookie: String) {
        val parsed = extractCookieValue(cookie, "evcs") ?: cookie.trim()
        authCookie = parsed
    }

    /**
     * Saves PHP session ID and CSRF token.
     */
    fun saveSession(phpSession: String?, csrf: String?) {
        if (phpSession != null) {
            val parsed = extractCookieValue(phpSession, "PHPSESSID") ?: phpSession.trim()
            phpSessionId = parsed
        }
        if (csrf != null) {
            csrfToken = csrf.trim()
        }
    }

    /**
     * Parses Set-Cookie header string and saves any detected `evcs` or `PHPSESSID` cookies.
     */
    fun saveFromSetCookieHeader(setCookieHeader: String) {
        val evcsVal = extractCookieValue(setCookieHeader, "evcs")
        if (evcsVal != null) {
            authCookie = evcsVal
        }
        val phpVal = extractCookieValue(setCookieHeader, "PHPSESSID")
        if (phpVal != null) {
            phpSessionId = phpVal
        }
    }

    /**
     * Checks if a valid persistent auth cookie exists.
     */
    fun hasAuthCookie(): Boolean = !authCookie.isNullOrBlank()

    /**
     * Proactively warms up the underlying storage off the Main Thread.
     */
    suspend fun warmUp() {
        storage.warmUp()
    }

    /**
     * Asynchronously checks whether an auth cookie exists off the Main Thread on [dispatcher].
     */
    suspend fun checkAuthCookieAsync(dispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean = withContext(dispatcher) {
        storage.warmUp()
        hasAuthCookie()
    }

    /**
     * Generates standard Cookie header string for authenticated requests.
     * Format: `PHPSESSID=...; evcs=...; evcs_did=...`
     */
    fun getCookieHeader(): String {
        val pairs = mutableListOf<String>()

        val php = phpSessionId
        if (!php.isNullOrBlank()) {
            pairs.add("PHPSESSID=$php")
        }

        val evcs = authCookie
        if (!evcs.isNullOrBlank()) {
            pairs.add("evcs=$evcs")
        }

        val did = deviceId
        if (did.isNotBlank()) {
            pairs.add("evcs_did=$did")
        }

        return pairs.joinToString("; ")
    }

    /**
     * Clears user session (auth cookie, php session, csrf, email) while keeping device ID.
     */
    fun clearSession() {
        storage.remove(KEY_AUTH_COOKIE)
        storage.remove(KEY_PHP_SESSION)
        storage.remove(KEY_CSRF_TOKEN)
        storage.remove(KEY_USER_EMAIL)
    }

    /**
     * Clears all persisted data including device ID.
     */
    fun clearAll() {
        storage.clear()
    }

    internal fun extractCookieValue(headerOrCookie: String, name: String): String? =
        Companion.extractCookieValue(headerOrCookie, name)
}
