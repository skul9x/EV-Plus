package com.evcs.favorites.data.preferences

import android.content.Context
import com.evcs.favorites.data.auth.PlainSharedPrefsStorage
import com.evcs.favorites.data.auth.SessionStorage
import com.evcs.favorites.domain.model.WattageOption

/**
 * Manages persistent storage and retrieval of user-selected wattage filter chips
 * for the Nearby Charging Stations screen.
 *
 * Utilizes the [SessionStorage] abstraction:
 * - [PlainSharedPrefsStorage] for zero-overhead preferences in Android production.
 * - [com.evcs.favorites.data.auth.InMemorySessionStorage] for fast JVM unit tests.
 */
class NearbyFilterPreferences(
    private val storage: SessionStorage
) {
    companion object {
        const val KEY_SELECTED_WATTAGES = "nearby_selected_wattages"

        /**
         * Factory helper for production Android application instantiation.
         */
        fun create(context: Context): NearbyFilterPreferences {
            return NearbyFilterPreferences(
                storage = PlainSharedPrefsStorage.getInstance(context, "evcs_nearby_filter_prefs")
            )
        }
    }

    /**
     * Encodes enum names as comma-separated string (e.g. "KW_250,KW_180")
     * or removes the key if empty.
     */
    fun saveSelectedWattages(wattages: Set<WattageOption>) {
        if (wattages.isEmpty()) {
            storage.remove(KEY_SELECTED_WATTAGES)
        } else {
            val encoded = wattages.joinToString(",") { it.name }
            storage.putString(KEY_SELECTED_WATTAGES, encoded)
        }
    }

    /**
     * Reads string, splits by comma, trims tokens, and safely parses into
     * [Set<WattageOption>] (ignoring unknown or corrupted tokens).
     */
    fun getSelectedWattages(): Set<WattageOption> {
        val raw = storage.getString(KEY_SELECTED_WATTAGES) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                try {
                    WattageOption.valueOf(token)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .toSet()
    }

    /**
     * Removes stored preference.
     */
    fun clear() {
        storage.remove(KEY_SELECTED_WATTAGES)
    }

    /**
     * Returns true if persisted key exists.
     */
    fun hasPersistedFilters(): Boolean {
        return storage.getString(KEY_SELECTED_WATTAGES) != null
    }
}
