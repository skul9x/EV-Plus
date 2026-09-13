package com.evcs.favorites.util

import android.content.res.Configuration
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Pure Kotlin helper and policy engine governing system bars appearance, insets behavior,
 * and edge-to-edge layout adaptation based on device configuration orientation.
 */
object AdaptiveSystemBarsHelper {

    /**
     * Orientation-driven system bars display modes.
     */
    enum class SystemBarsMode {
        /**
         * Sticky immersive mode for landscape: hides system bars and reveals them
         * transiently via swipe gestures without resizing content layout.
         */
        IMMERSIVE_STICKY_LANDSCAPE,

        /**
         * Edge-to-edge mode for portrait/unspecified: keeps system bars visible
         * while allowing app content to draw behind transparent status and navigation bars.
         */
        EDGE_TO_EDGE_PORTRAIT
    }

    /**
     * Resolves the target [SystemBarsMode] for a given [Configuration] orientation.
     *
     * - [Configuration.ORIENTATION_LANDSCAPE] -> [SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE]
     * - [Configuration.ORIENTATION_PORTRAIT] or any other value (undefined, square, etc.)
     *   -> [SystemBarsMode.EDGE_TO_EDGE_PORTRAIT]
     */
    fun resolveMode(configurationOrientation: Int): SystemBarsMode {
        return when (configurationOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE
            else -> SystemBarsMode.EDGE_TO_EDGE_PORTRAIT
        }
    }

    /**
     * Resolves the [WindowInsetsControllerCompat] system bars behavior constant for a given [SystemBarsMode].
     *
     * - [SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE] ->
     *     [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]
     * - [SystemBarsMode.EDGE_TO_EDGE_PORTRAIT] ->
     *     [WindowInsetsControllerCompat.BEHAVIOR_DEFAULT]
     */
    fun resolveSystemBarsBehavior(mode: SystemBarsMode): Int {
        return when (mode) {
            SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE ->
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT ->
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    /**
     * Determines whether system bars (status bar, navigation bar) should be hidden.
     *
     * - [SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE] -> true (bars hidden)
     * - [SystemBarsMode.EDGE_TO_EDGE_PORTRAIT] -> false (bars visible)
     */
    fun shouldHideSystemBars(mode: SystemBarsMode): Boolean {
        return when (mode) {
            SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE -> true
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT -> false
        }
    }

    /**
     * Insets type mask for system bars (status bars, navigation bars, caption bar).
     */
    val systemBarsTypeMask: Int
        get() = WindowInsetsCompat.Type.systemBars()

    /**
     * Orchestrates edge-to-edge layout and adaptive system bar visibility on the given [Window].
     *
     * Enables edge-to-edge rendering via [WindowCompat.setDecorFitsSystemWindows] and applies
     * orientation-specific system bar behavior and show/hide operations via [WindowInsetsControllerCompat].
     *
     * @param window The target window to configure.
     * @param configurationOrientation Device orientation from [Configuration.orientation].
     * @return The applied [SystemBarsMode].
     */
    fun applySystemBars(
        window: Window,
        configurationOrientation: Int
    ): SystemBarsMode {
        val mode = resolveMode(configurationOrientation)
        applySystemBars(window, mode)
        return mode
    }

    /**
     * Applies system bars configuration to the given [Window] for an explicit [SystemBarsMode].
     */
    fun applySystemBars(
        window: Window,
        mode: SystemBarsMode
    ) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        applySystemBars(insetsController, mode)
    }

    /**
     * Applies system bars behavior and visibility directly on a [WindowInsetsControllerCompat].
     */
    fun applySystemBars(
        insetsController: WindowInsetsControllerCompat,
        mode: SystemBarsMode
    ) {
        insetsController.systemBarsBehavior = resolveSystemBarsBehavior(mode)
        if (shouldHideSystemBars(mode)) {
            insetsController.hide(systemBarsTypeMask)
        } else {
            insetsController.show(systemBarsTypeMask)
        }
    }

    /**
     * Resolves whether appearance light bars (dark icons) should be used.
     * When dark theme or car mode is active, light bars must be false (light/white icons on dark surface).
     * When light theme is active and not car mode, light bars must be true (dark icons on light surface).
     */
    fun resolveAppearanceLightBars(darkTheme: Boolean, isCarMode: Boolean): Boolean {
        return !darkTheme && !isCarMode
    }

    /**
     * Configures system bars icon contrast on a [WindowInsetsControllerCompat].
     */
    fun applySystemBarsAppearance(
        insetsController: WindowInsetsControllerCompat,
        darkTheme: Boolean,
        isCarMode: Boolean
    ) {
        val isLight = resolveAppearanceLightBars(darkTheme, isCarMode)
        insetsController.isAppearanceLightStatusBars = isLight
        insetsController.isAppearanceLightNavigationBars = isLight
    }

    /**
     * Configures transparent edge-to-edge system bars and adaptive contrast on the target [Window].
     */
    fun applySystemBarsAppearance(
        window: Window,
        view: android.view.View,
        darkTheme: Boolean,
        isCarMode: Boolean
    ) {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val insetsController = WindowCompat.getInsetsController(window, view)
        applySystemBarsAppearance(insetsController, darkTheme, isCarMode)
    }
}
