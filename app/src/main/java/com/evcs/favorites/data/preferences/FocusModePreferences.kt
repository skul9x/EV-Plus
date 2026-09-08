package com.evcs.favorites.data.preferences

import android.content.Context
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages persistent storage and retrieval of Focus Mode preferences,
 * such as enabling or disabling voice announcements (TTS & Audio Ducking).
 *
 * Utilizes the [SessionStorage] abstraction:
 * - [PlainSharedPrefsStorage] for zero-overhead preferences in Android production.
 * - [com.evcs.favorites.data.auth.InMemorySessionStorage] for fast, deterministic JVM unit tests.
 */
class FocusModePreferences(
    private val storage: SessionStorage
) {
    companion object {
        const val PREFS_NAME = "evcs_focus_mode_prefs"
        const val KEY_VOICE_ALERT_ENABLED = "focus_voice_alert_enabled"
        const val DEFAULT_VOICE_ALERT_ENABLED = true

        /**
         * Factory helper for production Android application instantiation.
         */
        fun create(context: Context): FocusModePreferences {
            return FocusModePreferences(
                storage = PlainSharedPrefsStorage.getInstance(context, PREFS_NAME)
            )
        }
    }

    private val _voiceAlertEnabledFlow = MutableStateFlow(isVoiceAlertEnabled())

    /**
     * Observable [StateFlow] providing reactive updates for Jetpack Compose UI.
     */
    val voiceAlertEnabledFlow: StateFlow<Boolean> = _voiceAlertEnabledFlow.asStateFlow()

    /**
     * Retrieves whether voice alerts and audio ducking are enabled.
     * Defaults to [DEFAULT_VOICE_ALERT_ENABLED] (true).
     */
    fun isVoiceAlertEnabled(): Boolean {
        val raw = storage.getString(KEY_VOICE_ALERT_ENABLED) ?: return DEFAULT_VOICE_ALERT_ENABLED
        return raw.toBooleanStrictOrNull() ?: DEFAULT_VOICE_ALERT_ENABLED
    }

    /**
     * Persists the user preference for voice alerts and emits the update to [voiceAlertEnabledFlow].
     */
    fun setVoiceAlertEnabled(enabled: Boolean) {
        storage.putString(KEY_VOICE_ALERT_ENABLED, enabled.toString())
        _voiceAlertEnabledFlow.value = enabled
    }
}
