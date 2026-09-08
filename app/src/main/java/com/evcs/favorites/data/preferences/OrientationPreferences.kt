package com.evcs.favorites.data.preferences

import android.content.Context
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages persistent storage and retrieval of startup screen orientation preferences.
 *
 * Utilizes the [SessionStorage] abstraction:
 * - [PlainSharedPrefsStorage] for zero-overhead preferences in Android production.
 * - [com.evcs.favorites.data.auth.InMemorySessionStorage] for fast, deterministic JVM unit tests.
 */
class OrientationPreferences(
    private val storage: SessionStorage
) {
    companion object {
        const val PREFS_NAME = "evcs_orientation_prefs"
        const val KEY_STARTUP_ORIENTATION = "startup_orientation"
        val DEFAULT_ORIENTATION = StartupOrientation.SYSTEM

        @Volatile
        private var defaultInstance: OrientationPreferences? = null

        /**
         * Singleton accessor ensuring consistent state sharing between Activity and Modal bottom sheets.
         */
        fun getInstance(context: Context): OrientationPreferences {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: OrientationPreferences(
                    storage = PlainSharedPrefsStorage.getInstance(context, PREFS_NAME)
                ).also { defaultInstance = it }
            }
        }

        /**
         * Factory helper for production Android application instantiation.
         */
        fun create(context: Context): OrientationPreferences = getInstance(context)

        internal fun resetInstanceForTesting() {
            defaultInstance = null
        }
    }

    private val _startupOrientationFlow = MutableStateFlow(getStartupOrientation())

    /**
     * Observable [StateFlow] providing reactive updates for Jetpack Compose UI and Activity lifecycle.
     */
    val startupOrientationFlow: StateFlow<StartupOrientation> = _startupOrientationFlow.asStateFlow()

    /**
     * Retrieves the configured startup orientation synchronously.
     * Guaranteed zero-delay read for cold start enforcement.
     * Safely falls back to [DEFAULT_ORIENTATION] if uninitialized or corrupted.
     */
    fun getStartupOrientation(): StartupOrientation {
        val raw = storage.getString(KEY_STARTUP_ORIENTATION)
        return StartupOrientation.fromStorageKey(raw)
    }

    /**
     * Persists the startup orientation preference and emits the update to [startupOrientationFlow].
     */
    fun setStartupOrientation(orientation: StartupOrientation) {
        storage.putString(KEY_STARTUP_ORIENTATION, orientation.name)
        _startupOrientationFlow.value = orientation
    }
}
