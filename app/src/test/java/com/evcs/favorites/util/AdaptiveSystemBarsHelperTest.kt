package com.evcs.favorites.util

import android.content.res.Configuration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.evcs.favorites.util.AdaptiveSystemBarsHelper.SystemBarsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01: Adaptive System Bars Helper & Policy.
 *
 * Verifies:
 * 1. Enum integrity and completeness for [SystemBarsMode].
 * 2. Accurate resolution of [SystemBarsMode] for landscape, portrait, undefined, and edge orientation values.
 * 3. Accurate mapping of [WindowInsetsControllerCompat] behaviors (transient swipe vs default).
 * 4. Insets visibility resolution: shouldHideSystemBars is true for landscape and false for portrait.
 * 5. Insets type mask matches [WindowInsetsCompat.Type.systemBars()].
 * 6. Contract consistency between mode, behavior, and visibility decisions.
 */
class AdaptiveSystemBarsHelperTest {

    @Test
    fun testSystemBarsMode_enumDefinitions() {
        val modes = SystemBarsMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE))
        assertTrue(modes.contains(SystemBarsMode.EDGE_TO_EDGE_PORTRAIT))

        assertEquals(
            SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE,
            SystemBarsMode.valueOf("IMMERSIVE_STICKY_LANDSCAPE")
        )
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            SystemBarsMode.valueOf("EDGE_TO_EDGE_PORTRAIT")
        )
    }

    @Test
    fun testResolveMode_landscapeReturnsImmersiveSticky() {
        val resolved = AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_LANDSCAPE)
        assertEquals(SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE, resolved)
    }

    @Test
    fun testResolveMode_portraitReturnsEdgeToEdge() {
        val resolved = AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_PORTRAIT)
        assertEquals(SystemBarsMode.EDGE_TO_EDGE_PORTRAIT, resolved)
    }

    @Test
    @Suppress("DEPRECATION")
    fun testResolveMode_undefinedAndArbitraryValuesDefaultToEdgeToEdgePortrait() {
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_UNDEFINED)
        )
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_SQUARE)
        )
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            AdaptiveSystemBarsHelper.resolveMode(-1)
        )
        assertEquals(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT,
            AdaptiveSystemBarsHelper.resolveMode(99)
        )
    }

    @Test
    fun testResolveSystemBarsBehavior_mapsCorrectlyForModes() {
        val landscapeBehavior = AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(
            SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE
        )
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            landscapeBehavior
        )

        val portraitBehavior = AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(
            SystemBarsMode.EDGE_TO_EDGE_PORTRAIT
        )
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            portraitBehavior
        )
    }

    @Test
    fun testShouldHideSystemBars_trueForLandscape_falseForPortrait() {
        assertTrue(
            AdaptiveSystemBarsHelper.shouldHideSystemBars(SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE)
        )
        assertFalse(
            AdaptiveSystemBarsHelper.shouldHideSystemBars(SystemBarsMode.EDGE_TO_EDGE_PORTRAIT)
        )
    }

    @Test
    fun testSystemBarsTypeMask_matchesWindowInsetsTypeSystemBars() {
        val expected = WindowInsetsCompat.Type.systemBars()
        assertEquals(expected, AdaptiveSystemBarsHelper.systemBarsTypeMask)
    }

    @Test
    fun testContractCohesion_orientationToModeToBehaviorAndVisibility() {
        // Landscape orientation -> Immersive Sticky -> Transient Swipe Behavior -> Hide System Bars
        val landscapeMode = AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_LANDSCAPE)
        assertEquals(SystemBarsMode.IMMERSIVE_STICKY_LANDSCAPE, landscapeMode)
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(landscapeMode)
        )
        assertTrue(AdaptiveSystemBarsHelper.shouldHideSystemBars(landscapeMode))

        // Portrait orientation -> Edge-to-edge -> Default Behavior -> Show System Bars (hide=false)
        val portraitMode = AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_PORTRAIT)
        assertEquals(SystemBarsMode.EDGE_TO_EDGE_PORTRAIT, portraitMode)
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(portraitMode)
        )
        assertFalse(AdaptiveSystemBarsHelper.shouldHideSystemBars(portraitMode))

        // Fallback orientation -> Edge-to-edge -> Default Behavior -> Show System Bars (hide=false)
        val fallbackMode = AdaptiveSystemBarsHelper.resolveMode(Configuration.ORIENTATION_UNDEFINED)
        assertEquals(SystemBarsMode.EDGE_TO_EDGE_PORTRAIT, fallbackMode)
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            AdaptiveSystemBarsHelper.resolveSystemBarsBehavior(fallbackMode)
        )
        assertFalse(AdaptiveSystemBarsHelper.shouldHideSystemBars(fallbackMode))
    }
}
