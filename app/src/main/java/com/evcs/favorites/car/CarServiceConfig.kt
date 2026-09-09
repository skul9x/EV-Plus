package com.evcs.favorites.car

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.car.app.validation.HostValidator

/**
 * Configuration, constants, and host validation policies for EV-Plus Android Auto integration.
 *
 * Implements Google Car App Library host validation policies:
 * - Debuggable builds / DHU: Allow all hosts for frictionless Desktop Head Unit simulation.
 * - Release builds (Android 15 sideload): Validate against Android Auto allowlist sample
 *   (e.g., com.google.android.projection.gearhead) without requiring Play Store signature restriction,
 *   supporting "Unknown Sources" developer distribution per 1.md Section 3.
 */
object CarServiceConfig {

    const val MIN_CAR_API_LEVEL = 1
    const val CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
    const val CATEGORY_POI = "androidx.car.app.category.POI"
    const val PERMISSION_MAP_TEMPLATES = "androidx.car.app.MAP_TEMPLATES"
    const val DESCRIPTOR_RESOURCE_NAME = "automotive_app_desc"
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"

    /**
     * Checks if the application package is built in debuggable mode.
     */
    fun isAppDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Creates a [HostValidator] appropriate for the current runtime environment.
     *
     * @param context Application context used for resource loading
     * @param isDebuggable When true, permits all hosts (DHU simulation). Defaults to checking [ApplicationInfo].
     */
    fun createHostValidator(
        context: Context,
        isDebuggable: Boolean = isAppDebuggable(context)
    ): HostValidator {
        return if (isDebuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(context)
                .addAllowedHosts(com.evcs.favorites.R.array.car_hosts_allowlist)
                .build()
        }
    }

    /**
     * Validates whether a target Car API level is supported by this client configuration.
     */
    fun isCarApiLevelSupported(currentLevel: Int, minLevel: Int = MIN_CAR_API_LEVEL): Boolean {
        return currentLevel >= minLevel
    }
}
