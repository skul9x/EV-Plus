package com.evcs.favorites.data.preferences

import android.content.Context
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages persistent storage and retrieval of user-selected smart filter modes,
 * DC power wattage tiers, and custom filter configurations.
 *
 * Utilizes the [SessionStorage] abstraction:
 * - [PlainSharedPrefsStorage] for zero-overhead preferences in Android production.
 * - [com.evcs.favorites.data.auth.InMemorySessionStorage] for fast JVM unit tests.
 */
class SmartFilterPreferences(
    private val storage: SessionStorage
) {
    companion object {
        const val KEY_ACTIVE_FILTER_MODE = "smart_filter_active_mode"
        const val KEY_SELECTED_DC_TIER = "smart_filter_dc_tier"
        const val KEY_CUSTOM_CONFIG = "smart_filter_custom_config"

        /**
         * Factory helper for production Android application instantiation.
         */
        fun create(context: Context): SmartFilterPreferences {
            return SmartFilterPreferences(
                storage = PlainSharedPrefsStorage.getInstance(context, "evcs_smart_filter_prefs")
            )
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Persists active filter mode.
     */
    fun saveActiveFilterMode(mode: SmartFilterMode) {
        storage.putString(KEY_ACTIVE_FILTER_MODE, mode.name)
    }

    /**
     * Retrieves active filter mode, defaulting to [SmartFilterMode.NONE] if not found or invalid.
     */
    fun getActiveFilterMode(): SmartFilterMode {
        val raw = storage.getString(KEY_ACTIVE_FILTER_MODE) ?: return SmartFilterMode.NONE
        return try {
            SmartFilterMode.valueOf(raw)
        } catch (e: Exception) {
            SmartFilterMode.NONE
        }
    }

    /**
     * Persists active DC wattage tier. Removes key if [tier] is null.
     */
    fun saveSelectedDcTier(tier: DcWattageTier?) {
        if (tier == null) {
            storage.remove(KEY_SELECTED_DC_TIER)
        } else {
            storage.putString(KEY_SELECTED_DC_TIER, tier.name)
        }
    }

    /**
     * Retrieves active DC wattage tier, or null if unset or invalid.
     */
    fun getSelectedDcTier(): DcWattageTier? {
        val raw = storage.getString(KEY_SELECTED_DC_TIER) ?: return null
        return try {
            DcWattageTier.valueOf(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Persists user custom filter configuration as JSON. Removes key if [config] is null.
     */
    fun saveCustomConfig(config: CustomFilterConfig?) {
        if (config == null) {
            storage.remove(KEY_CUSTOM_CONFIG)
        } else {
            try {
                val encoded = json.encodeToString(config)
                storage.putString(KEY_CUSTOM_CONFIG, encoded)
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }

    /**
     * Retrieves custom filter configuration, or null if unset or corrupted.
     */
    fun getCustomConfig(): CustomFilterConfig? {
        val raw = storage.getString(KEY_CUSTOM_CONFIG) ?: return null
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString<CustomFilterConfig>(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verifies if the user has established a valid custom configuration.
     */
    fun hasCustomConfig(): Boolean {
        return getCustomConfig() != null
    }

    /**
     * Clears active filter mode and selected DC tier (resets to NONE).
     */
    fun clearActiveFilter() {
        saveActiveFilterMode(SmartFilterMode.NONE)
        saveSelectedDcTier(null)
    }

    /**
     * Clears all smart filter data including custom configuration.
     */
    fun clear() {
        storage.remove(KEY_ACTIVE_FILTER_MODE)
        storage.remove(KEY_SELECTED_DC_TIER)
        storage.remove(KEY_CUSTOM_CONFIG)
    }
}
