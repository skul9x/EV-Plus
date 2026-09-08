package com.evcs.favorites.util

import android.content.pm.ActivityInfo
import com.evcs.favorites.data.preferences.StartupOrientation

/**
 * Pure Kotlin helper mapping domain [StartupOrientation] to Android [ActivityInfo] screen orientation constants
 * and performing reverse mapping and validation.
 */
object OrientationHelper {

    /**
     * Maps a [StartupOrientation] enum value to the corresponding [ActivityInfo] screen orientation constant.
     */
    fun toActivityInfoOrientation(orientation: StartupOrientation): Int {
        return when (orientation) {
            StartupOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            StartupOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            StartupOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * Reverse maps an Android [ActivityInfo] screen orientation constant to [StartupOrientation].
     * Handles sensor and reverse orientation flags gracefully.
     */
    fun fromActivityInfoOrientation(activityInfoOrientation: Int): StartupOrientation {
        return when (activityInfoOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> StartupOrientation.LANDSCAPE

            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> StartupOrientation.PORTRAIT

            else -> StartupOrientation.SYSTEM
        }
    }
}
