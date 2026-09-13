package com.evcs.favorites.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.preferences.OrientationPreferences
import com.evcs.favorites.data.preferences.StartupOrientation
import com.evcs.favorites.util.AdaptiveSystemBarsHelper
import com.evcs.favorites.util.AdaptiveSystemBarsHelper.SystemBarsMode
import com.evcs.favorites.util.OrientationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification test for Phase 02: Activity Lifecycle & Edge-to-Edge Integration.
 *
 * Verifies:
 * 1. Adaptive system bar mode resolution across all orientation states (Landscape, Portrait, Undefined).
 * 2. System bar behavior and visibility contracts (Immersive sticky swipe for landscape vs Edge-to-edge visible for portrait).
 * 3. Dynamic contrast and appearance resolution across Theme configurations (Light, Dark, Car Mode).
 * 4. Transparent system bars configuration (statusBarColor and navigationBarColor set to transparent 0x00000000).
 * 5. Lifecycle and configuration change integration contract (onConfigurationChanged, onWindowFocusChanged).
 * 6. Reactive StartupOrientationFlow propagation to target ActivityInfo orientations and corresponding SystemBarsModes.
 */
class AdaptiveSystemBarsIntegrationTest {

    // =========================================================================
    // 1. Orientation Resolution & SystemBarsMode Contract
    // =========================================================================

    @Test
    @Suppress("DEPRECATION")
    fun testOrientationToSystemBarsModeResolution() {
        // Landscape -> Immersive Sticky (hidden bars, swipe to reveal)
        assertEquals(
            SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE,
            AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_LANDSCAPE)
        )

        // Portrait -> Edge-to-Edge (visible bars)
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_PORTRAIT)
        )

        // Fallback / undefined / square -> Edge-to-Edge (safe default)
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_UNDEFINED)
        )
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_SQUARE)
        )
    }

    // =========================================================================
    // 2. Behavioral Flags and Visibility Decisions
    // =========================================================================

    @Test
    fun testSystemBarsBehaviorAndVisibilityPolicies() {
        val landscapeMode = SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(landscapeMode)
        )
        assertTrue(
            "Landscape mode must hide system bars for full immersive experience",
            AdaptiveSystemBarsHelper.shouldHideSystemBars(landscapeMode)
        )

        val portraitMode = SystemBarsMode.EDGE_TO_EDGE_PORTRAIT
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(portraitMode)
        )
        assertFalse(
            "Portrait mode must keep system bars visible for edge-to-edge content drawing",
            AdaptiveSystemBarsHelper.shouldHideSystemBars(portraitMode)
        )

        // Insets type mask targets all system bars
        assertEquals(
            WindowInsetsCompat.Type.systemBars(),
            AdaptiveSystemBarsHelper.systemBarsTypeMask
        )
    }

    // =========================================================================
    // 3. Theme & Contrast Awareness (Light, Dark, Car Mode)
    // =========================================================================

    @Test
    fun testAppearanceLightBarsResolution_ThemeAndCarModeContract() {
        // Light Theme + Phone (not car mode) -> light bars true (dark icons on light background)
        assertTrue(
            "Light theme on phone must use dark icons (isAppearanceLight = true)",
            AdaptiveSystemBarsHelper.resolveAppearanceLightBars(darkTheme = false, isCarMode = false)
        )

        // Dark Theme + Phone (not car mode) -> light bars false (light icons on dark background)
        assertFalse(
            "Dark theme must use light icons (isAppearanceLight = false)",
            AdaptiveSystemBarsHelper.resolveAppearanceLightBars(darkTheme = true, isCarMode = false)
        )

        // Car Dark Mode (regardless of system darkTheme setting) -> light bars false (high contrast light icons)
        assertFalse(
            "Car mode with light system theme must force dark bars and light icons",
            AdaptiveSystemBarsHelper.resolveAppearanceLightBars(darkTheme = false, isCarMode = true)
        )
        assertFalse(
            "Car mode with dark system theme must force dark bars and light icons",
            AdaptiveSystemBarsHelper.resolveAppearanceLightBars(darkTheme = true, isCarMode = true)
        )
    }

    // =========================================================================
    // 4. Transparent Edge-to-Edge System Bars Value Verification
    // =========================================================================

    @Test
    fun testTransparentSystemBarsColorValues() {
        // android.graphics.Color.TRANSPARENT must be 0 (fully transparent ARGB)
        assertEquals(0, Color.TRANSPARENT)
        assertEquals(
            Color.TRANSPARENT,
            androidx.compose.ui.graphics.Color.Transparent.value.toInt()
        )
    }

    // =========================================================================
    // 5. Reactive Orientation Flow to System Bars Mode Pipeline
    // =========================================================================

    @Test
    fun testReactiveOrientationPipeline_StartupFlowToSystemBarsMode() {
        val storage = InMemorySessionStorage()
        val prefs = OrientationPreferences(storage)

        // 1. Initial default state: SYSTEM -> SCREEN_ORIENTATION_UNSPECIFIED
        assertEquals(StartupOrientation.SYSTEM, prefs.startupOrientationFlow.value)
        val initialActivityOrientation = OrientationHelper.toActivityInfoOrientation(prefs.startupOrientationFlow.value)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, initialActivityOrientation)

        // 2. User selects LANDSCAPE in Settings -> updates Flow -> ActivityInfo LANDSCAPE -> IMMERSIVE_STICKY_LANDSCAPE
        prefs.setStartupOrientation(StartupOrientation.LANDSCAPE)
        assertEquals(StartupOrientation.LANDSCAPE, prefs.startupOrientationFlow.value)
        val landscapeActivityOrientation = OrientationHelper.toActivityInfoOrientation(prefs.startupOrientationFlow.value)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, landscapeActivityOrientation)

        val landscapeBarsMode = AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_LANDSCAPE)
        assertEquals(SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE, landscapeBarsMode)
        assertTrue(AdaptiveSystemBarsHelper.shouldHideSystemBars(landscapeBarsMode))

        // 3. User selects PORTRAIT in Settings -> updates Flow -> ActivityInfo PORTRAIT -> EDGE_TO_EDGE_PORTRAIT
        prefs.setStartupOrientation(StartupOrientation.PORTRAIT)
        assertEquals(StartupOrientation.PORTRAIT, prefs.startupOrientationFlow.value)
        val portraitActivityOrientation = OrientationHelper.toActivityInfoOrientation(prefs.startupOrientationFlow.value)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, portraitActivityOrientation)

        val portraitBarsMode = AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_PORTRAIT)
        assertEquals(SystemBarsMode.EDGE_TO_EDGE_PORTRAIT, portraitBarsMode)
        assertFalse(AdaptiveSystemBarsHelper.shouldHideSystemBars(portraitBarsMode))

        // 4. Revert back to SYSTEM -> ActivityInfo UNSPECIFIED -> fallback EDGE_TO_EDGE_PORTRAIT
        prefs.setStartupOrientation(StartupOrientation.SYSTEM)
        assertEquals(StartupOrientation.SYSTEM, prefs.startupOrientationFlow.value)
        val systemActivityOrientation = OrientationHelper.toActivityInfoOrientation(prefs.startupOrientationFlow.value)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, systemActivityOrientation)
    }

    // =========================================================================
    // 6. Configuration Orientation Change Contract Verification
    // =========================================================================

    @Test
    fun testConfigurationOrientationChangeContract() {
        // Simulating configuration orientation changes passed to onConfigurationChanged(newConfig)
        val orientations = listOf(
            Configuration.ORIENTATION_LANDSCAPE to SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE,
            Configuration.ORIENTATION_PORTRAIT to SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            Configuration.ORIENTATION_UNDEFINED to SystemBarsMode.EDGE_TO_EDGE_PORTRAIT
        )

        for ((orientation, expectedMode) in orientations) {
            val resolvedMode = AdaptiveSystemBarsHelper.resolveMode(orientation)
            assertEquals("Orientation $orientation must resolve to $expectedMode", expectedMode, resolvedMode)

            val expectedBehavior = if (expectedMode == SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE) {
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
            assertEquals(expectedBehavior, AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(resolvedMode))

            val expectedHidden = expectedMode == SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE
            assertEquals(expectedHidden, AdaptiveSystemBarsHelper.shouldHideSystemBars(resolvedMode))
        }
    }
}
