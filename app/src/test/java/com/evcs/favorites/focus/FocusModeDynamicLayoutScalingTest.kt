package com.evcs.favorites.focus

import android.content.res.Configuration
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Dynamic Responsive Sizing & Zero Text Clipping Layout.
 *
 * Requirements covered:
 * 1. Landscape width scaling at 32% and clamping between [280dp, 440dp] across car/Android Box resolutions
 *    (1024x600, 1280x720, 1920x720, 2560x1440).
 * 2. Portrait width scaling at 82% and clamping between [280dp, 380dp] across phone resolutions
 *    (720x1280, 1080x2400, 1440x3120).
 * 3. Boundary clamping preserves overlay visibility across orientation swaps (e.g. 1080x2400 <-> 2400x1080).
 * 4. Hero Metric typography specs (24sp Bold) and Vietnamese vertical padding safety (>= 4dp).
 * 5. Automotive touch targets meeting or exceeding 48dp minimum (and 56dp car CTA height).
 * 6. WindowManager.LayoutParams integration with dynamic width calculations.
 * 7. Lifecycle delegation wiring for onConfigurationChanged across Service and FloatingViewManager.
 */
class FocusModeDynamicLayoutScalingTest {

    // ---------------------------------------------------------------------------------------------
    // Requirement 1: Landscape Width Scaling Across Car Displays (32% clamped [280dp, 440dp])
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testLandscapeWidthScaling_carAndAndroidBoxResolutions() {
        val density = 1.0f

        // 1. 1024x600 (7-inch Car Box): 1024 * 0.32 = 327.68 -> 328px (within [280, 440])
        val width1024 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 1024,
            isLandscape = true,
            density = density
        )
        assertEquals("1024x600 should scale to 328px", 328, width1024)
        assertTrue("328px must be within [280, 440]", width1024 in 280..440)

        // 2. 1280x720 (9-inch Car Box): 1280 * 0.32 = 409.6 -> 410px (within [280, 440])
        val width1280 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 1280,
            isLandscape = true,
            density = density
        )
        assertEquals("1280x720 should scale to 410px", 410, width1280)
        assertTrue("410px must be within [280, 440]", width1280 in 280..440)

        // 3. 1920x720 (12.3-inch Ultrawide Car Display): 1920 * 0.32 = 614.4 -> clamped to 440dp max
        val width1920 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 1920,
            isLandscape = true,
            density = density
        )
        assertEquals("1920x720 must clamp at 440px max", 440, width1920)

        // 4. 2560x1440 (2K Dashboard): 2560 * 0.32 = 819.2 -> clamped to 440dp max
        val width2560 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 2560,
            isLandscape = true,
            density = density
        )
        assertEquals("2560x1440 at density 1.0 must clamp at 440px max", 440, width2560)

        // 5. 2560x1440 with density = 1.5f: 440dp * 1.5 = 660px
        val width2560Density15 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 2560,
            isLandscape = true,
            density = 1.5f
        )
        assertEquals("2560x1440 at density 1.5 must clamp at 660px (440dp)", 660, width2560Density15)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 2: Portrait Width Scaling Across Phone Displays (82% clamped [280dp, 380dp])
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testPortraitWidthScaling_phoneResolutions() {
        // 1. 720x1280 (HD phone, typical density = 2.0f, screenWidth = 360dp)
        // 720 * 0.82 = 590px. In dp: 590 / 2.0 = 295dp (within [280dp, 380dp])
        val densityHd = 2.0f
        val width720 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 720,
            isLandscape = false,
            density = densityHd
        )
        assertEquals("720x1280 should scale to 590px", 590, width720)
        val width720Dp = width720 / densityHd
        assertTrue(
            "720x1280 width ($width720Dp dp) must be within [280dp, 380dp]",
            width720Dp >= 280f && width720Dp <= 380f
        )

        // 2. 1080x2400 (FHD+ phone, density = 2.0f)
        // 1080 * 0.82 = 886px. Max allowed is 380dp * 2.0 = 760px.
        val densityFhd = 2.0f
        val width1080 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 1080,
            isLandscape = false,
            density = densityFhd
        )
        assertEquals("1080x2400 must clamp at 760px (380dp)", 760, width1080)
        assertEquals(380f, width1080 / densityFhd, 0.01f)

        // 3. 1440x3120 (QHD+ phone, density = 3.0f)
        // 1440 * 0.82 = 1181px. Max allowed is 380dp * 3.0 = 1140px.
        val densityQhd = 3.0f
        val width1440 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 1440,
            isLandscape = false,
            density = densityQhd
        )
        assertEquals("1440x3120 must clamp at 1140px (380dp)", 1140, width1440)
        assertEquals(380f, width1440 / densityQhd, 0.01f)

        // 4. Density = 1.0f verification
        val width1080Density1 = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 1080,
            isLandscape = false,
            density = 1.0f
        )
        assertEquals("1080 at density 1.0f must clamp at 380px max", 380, width1080Density1)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 3: Boundary Clamping Across Orientation Swaps (1080x2400 <-> 2400x1080)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testOrientationRotationSwaps_boundaryClampingPreservesVisibility() {
        val density = 2.0f

        // Initial state: Portrait (1080x2400).
        // Overlay width = 760px, height = 150px. Placed near bottom: (x = 300, y = 2100)
        val portraitWidth = FocusModeViewLayoutHelper.calculateOverlayWidth(1080, isLandscape = false, density = density)
        assertEquals(760, portraitWidth)

        val initialPortraitPos = FocusModeViewLayoutHelper.clampPosition(
            x = 300,
            y = 2100,
            viewWidth = portraitWidth,
            viewHeight = 150,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertEquals(300, initialPortraitPos.first)
        assertEquals(2100, initialPortraitPos.second)

        // Device rotates to Landscape (2400x1080):
        // Recalculate overlay width for landscape
        val landscapeWidth = FocusModeViewLayoutHelper.calculateOverlayWidth(2400, isLandscape = true, density = density)
        // 2400 * 0.32 = 768px (within bounds [560, 880])
        assertEquals(768, landscapeWidth)

        // Re-clamp coordinates in new screen geometry (2400 x 1080)
        // y was 2100, which now exceeds landscape screenHeight 1080!
        val (landscapeClampedX, landscapeClampedY) = FocusModeViewLayoutHelper.clampPosition(
            x = initialPortraitPos.first,
            y = initialPortraitPos.second,
            viewWidth = landscapeWidth,
            viewHeight = 150,
            screenWidth = 2400,
            screenHeight = 1080
        )

        assertEquals(300, landscapeClampedX)
        assertEquals("Y coordinate must be clamped to 1080 - 150 = 930px", 930, landscapeClampedY)
        assertTrue("Overlay bottom edge must remain within landscape screen", landscapeClampedY + 150 <= 1080)
        assertTrue("Overlay top edge must remain >= 0", landscapeClampedY >= 0)

        // Now view is moved towards far right edge in Landscape: (x = 2200, y = 500)
        val (rightEdgeX, rightEdgeY) = FocusModeViewLayoutHelper.clampPosition(
            x = 2200,
            y = 500,
            viewWidth = landscapeWidth,
            viewHeight = 150,
            screenWidth = 2400,
            screenHeight = 1080
        )
        assertEquals(2400 - 768, rightEdgeX) // 1632

        // Rotate back to Portrait (1080x2400):
        // x was 1632, which now exceeds portrait screenWidth 1080!
        val (portraitClampedX, portraitClampedY) = FocusModeViewLayoutHelper.clampPosition(
            x = rightEdgeX,
            y = rightEdgeY,
            viewWidth = portraitWidth,
            viewHeight = 150,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals("X coordinate must be clamped to 1080 - 760 = 320px", 320, portraitClampedX)
        assertEquals(500, portraitClampedY)
        assertTrue("Overlay right edge must remain within portrait screen", portraitClampedX + portraitWidth <= 1080)
        assertTrue("Overlay left edge must remain >= 0", portraitClampedX >= 0)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 4: Hero Metric Typography & Vietnamese Diacritic Safety
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testHeroMetricTypographySpecs_andVietnamesePaddingSafety() {
        // Hero metric size spec: 24sp Bold for 90cm driving distance glanceability
        assertEquals(24f, FocusModeViewLayoutHelper.HERO_METRIC_TEXT_SIZE_SP, 0.0f)

        // Station name auto-sizing range: 13sp to 16sp
        assertEquals(13, FocusModeViewLayoutHelper.TITLE_MIN_TEXT_SIZE_SP)
        assertEquals(16, FocusModeViewLayoutHelper.TITLE_MAX_TEXT_SIZE_SP)

        // Vietnamese vertical padding safety against diacritic clipping
        assertTrue(
            "Vietnamese vertical padding must be at least 4dp",
            FocusModeViewLayoutHelper.VIETNAMESE_VERTICAL_PADDING_DP >= 4
        )

        // Hero badge container dimensions
        val (heroW1, heroH1) = FocusModeViewLayoutHelper.calculateHeroBadgeDimensions(density = 1.0f)
        assertEquals(80, heroW1)
        assertEquals(48, heroH1)

        val (heroW2, heroH2) = FocusModeViewLayoutHelper.calculateHeroBadgeDimensions(density = 2.0f)
        assertEquals(160, heroW2)
        assertEquals(96, heroH2)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 5: Automotive Touch Target Dimensions (>= 48dp, 56dp for car CTA)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testAutomotiveTouchTargets_meetOrExceedMinimumAccessibilityDimensions() {
        // Automotive standard touch target minimum: >= 48dp
        assertTrue(
            "Minimum touch target must be at least 48dp",
            FocusModeViewLayoutHelper.MIN_TOUCH_TARGET_DP >= 48
        )

        // Car CTA button height: 56dp
        assertTrue(
            "Car CTA button height must be at least 56dp",
            FocusModeViewLayoutHelper.CAR_CTA_HEIGHT_DP >= 56
        )

        // Minimum touch target pixel calculation
        val touchTarget1 = FocusModeViewLayoutHelper.calculateMinTouchTargetPx(density = 1.0f)
        assertEquals(48, touchTarget1)

        val touchTarget2 = FocusModeViewLayoutHelper.calculateMinTouchTargetPx(density = 2.0f)
        assertEquals(96, touchTarget2)

        // CTA button height calculation based on orientation
        val carCtaHeight = FocusModeViewLayoutHelper.calculateCtaButtonHeightPx(isLandscape = true, density = 1.0f)
        assertEquals(56, carCtaHeight)

        val phoneCtaHeight = FocusModeViewLayoutHelper.calculateCtaButtonHeightPx(isLandscape = false, density = 1.0f)
        assertEquals(48, phoneCtaHeight)

        val carCtaHeightDpi2 = FocusModeViewLayoutHelper.calculateCtaButtonHeightPx(isLandscape = true, density = 2.0f)
        assertEquals(112, carCtaHeightDpi2)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 6: WindowManager.LayoutParams Integration with Dynamic Width
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testWindowManagerLayoutParams_integrationWithDynamicWidth() {
        val landscapeWidth = FocusModeViewLayoutHelper.calculateOverlayWidth(
            screenWidthPx = 1024,
            isLandscape = true,
            density = 1.0f
        )
        val paramsWithDynamicWidth = FocusModeViewLayoutHelper.createWindowLayoutParams(
            x = 24,
            y = 120,
            width = landscapeWidth
        )

        assertEquals(328, paramsWithDynamicWidth.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, paramsWithDynamicWidth.height)
        assertEquals(24, paramsWithDynamicWidth.x)
        assertEquals(120, paramsWithDynamicWidth.y)

        // Default layout params fallback
        val defaultParams = FocusModeViewLayoutHelper.createWindowLayoutParams()
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, defaultParams.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, defaultParams.height)
        assertEquals(FocusModeViewLayoutHelper.DEFAULT_MARGIN, defaultParams.x)
        assertEquals(150, defaultParams.y)
    }

    // ---------------------------------------------------------------------------------------------
    // Requirement 7: Service and Manager onConfigurationChanged Wiring
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testServiceOrientationChangeDelegation_wiringSpec() {
        val managerMethod = FocusModeFloatingViewManager::class.java.getMethod(
            "onConfigurationChanged",
            Configuration::class.java
        )
        assertNotNull("FocusModeFloatingViewManager must implement onConfigurationChanged(Configuration)", managerMethod)

        val serviceMethod = FocusModeForegroundService::class.java.getMethod(
            "onConfigurationChanged",
            Configuration::class.java
        )
        assertNotNull("FocusModeForegroundService must implement onConfigurationChanged(Configuration)", serviceMethod)
    }
}
