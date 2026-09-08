package com.evcs.favorites.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.components.StationCardHelper
import com.evcs.favorites.ui.theme.AutomotiveDimens
import com.evcs.favorites.ui.theme.AutomotiveThemeHelper
import com.evcs.favorites.ui.theme.CAR_BUTTON_HEIGHT
import com.evcs.favorites.ui.theme.CAR_CARD_MIN_HEIGHT
import com.evcs.favorites.ui.theme.CAR_CHIP_HEIGHT
import com.evcs.favorites.ui.theme.CAR_HERO_METRIC_TEXT_SIZE
import com.evcs.favorites.ui.theme.CAR_ICON_SIZE
import com.evcs.favorites.ui.theme.CAR_PADDING_SPACER
import com.evcs.favorites.ui.theme.CarAccentCyan
import com.evcs.favorites.ui.theme.CarAccentGreen
import com.evcs.favorites.ui.theme.CarDarkBackground
import com.evcs.favorites.ui.theme.CarDarkOutline
import com.evcs.favorites.ui.theme.CarDarkSurface
import com.evcs.favorites.ui.theme.CarDarkSurfaceVariant
import com.evcs.favorites.ui.theme.CarStatusOffline
import com.evcs.favorites.ui.theme.CarStatusWarning
import com.evcs.favorites.ui.theme.CarTextPrimary
import com.evcs.favorites.ui.theme.MIN_CAR_TOUCH_TARGET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 03:
 * Automotive Touch Target Sizing (>= 56dp) & High-Contrast Car Dark Mode.
 *
 * Verifies:
 * 1. All automotive touch target tokens meet or exceed 56dp (Google Automotive safety standard).
 * 2. WCAG AAA relative luminance and contrast ratio calculations for:
 *    - CarAccentGreen (#00E676) against CarDarkBackground (#121216) >= 7:1 (exceeds 10:1).
 *    - CarAccentCyan (#00E5FF) against CarDarkBackground (#121216) >= 7:1 (exceeds 11:1).
 *    - CarTextPrimary (#FFFFFF) against CarDarkBackground (#121216) >= 7:1 (exceeds 15:1).
 * 3. StationCard Hero Metric typography is configured to 24sp Bold in car mode and formats correctly.
 * 4. Button height specifications across landscape (56dp) and portrait (44dp) modes.
 * 5. Detail sheet combined primary CTA ("⚡ DẪN ĐƯỜNG & THEO DÕI") configuration and 56dp enforcement.
 * 6. High-contrast color palette declaration and token correctness.
 */
class AutomotiveTouchTargetAndThemeTest {

    // =========================================================================
    // 1. Automotive Touch Target Tokens (>= 56dp) & Dimension Constraints
    // =========================================================================
    @Test
    fun testAutomotiveTouchTargetTokens_MeetOrExceedFiftySixDp() {
        // Minimum touch target token (Google Automotive standard: >= 56dp)
        assertEquals(56.dp, MIN_CAR_TOUCH_TARGET)
        assertEquals(56.dp, AutomotiveDimens.MIN_CAR_TOUCH_TARGET)
        assertEquals(56f, AutomotiveDimens.MIN_CAR_TOUCH_TARGET_DP, 0.001f)
        assertTrue(
            "MIN_CAR_TOUCH_TARGET must be >= 56dp",
            AutomotiveDimens.MIN_CAR_TOUCH_TARGET_DP >= 56f
        )

        // Automotive button height token (>= 56dp)
        assertEquals(56.dp, CAR_BUTTON_HEIGHT)
        assertEquals(56.dp, AutomotiveDimens.CAR_BUTTON_HEIGHT)
        assertEquals(56f, AutomotiveDimens.CAR_BUTTON_HEIGHT_DP, 0.001f)
        assertTrue(
            "CAR_BUTTON_HEIGHT must be >= 56dp",
            AutomotiveDimens.CAR_BUTTON_HEIGHT_DP >= 56f
        )

        // Automotive card minimum height token (>= 76dp)
        assertEquals(76.dp, CAR_CARD_MIN_HEIGHT)
        assertEquals(76.dp, AutomotiveDimens.CAR_CARD_MIN_HEIGHT)
        assertEquals(76f, AutomotiveDimens.CAR_CARD_MIN_HEIGHT_DP, 0.001f)
        assertTrue(
            "CAR_CARD_MIN_HEIGHT must be >= 76dp",
            AutomotiveDimens.CAR_CARD_MIN_HEIGHT_DP >= 76f
        )
        assertTrue(
            "CAR_CARD_MIN_HEIGHT must also meet general 56dp touch target threshold",
            AutomotiveDimens.isTouchTargetCompliant(AutomotiveDimens.CAR_CARD_MIN_HEIGHT_DP)
        )

        // Iconography size token readable from 90cm (28dp)
        assertEquals(28.dp, CAR_ICON_SIZE)
        assertEquals(28.dp, AutomotiveDimens.CAR_ICON_SIZE)
        assertEquals(28f, AutomotiveDimens.CAR_ICON_SIZE_DP, 0.001f)

        // Hero metric text size (24sp)
        assertEquals(24.sp, CAR_HERO_METRIC_TEXT_SIZE)
        assertEquals(24.sp, AutomotiveDimens.CAR_HERO_METRIC_TEXT_SIZE)
        assertEquals(24f, AutomotiveDimens.CAR_HERO_METRIC_TEXT_SIZE_SP, 0.001f)

        // Layout spacer token (12dp)
        assertEquals(12.dp, CAR_PADDING_SPACER)
        assertEquals(12.dp, AutomotiveDimens.CAR_PADDING_SPACER)
        assertEquals(12f, AutomotiveDimens.CAR_PADDING_SPACER_DP, 0.001f)

        // Filter chip height token (48dp)
        assertEquals(48.dp, CAR_CHIP_HEIGHT)
        assertEquals(48.dp, AutomotiveDimens.CAR_CHIP_HEIGHT)
        assertEquals(48f, AutomotiveDimens.CAR_CHIP_HEIGHT_DP, 0.001f)

        // Compliance helper checks
        assertTrue("56dp must be touch-target compliant", AutomotiveDimens.isTouchTargetCompliant(56f))
        assertTrue("72dp must be touch-target compliant", AutomotiveDimens.isTouchTargetCompliant(72f))
        assertFalse("48dp is below 56dp automotive touch target standard", AutomotiveDimens.isTouchTargetCompliant(48f))

        assertTrue("76dp must be card-height compliant", AutomotiveDimens.isCardHeightCompliant(76f))
        assertFalse("60dp is below 76dp card target", AutomotiveDimens.isCardHeightCompliant(60f))

        assertTrue("48dp must be chip-height compliant", AutomotiveDimens.isChipHeightCompliant(48f))
        assertFalse("36dp is below 48dp chip target", AutomotiveDimens.isChipHeightCompliant(36f))
    }

    // =========================================================================
    // 2. High-Contrast Automotive Color Palette & WCAG AAA Verification
    // =========================================================================
    @Test
    fun testHighContrastColorPalette_WcagAaaCompliance() {
        // Verify color token values
        assertEquals(Color(0xFF121216), CarDarkBackground)
        assertEquals(Color(0xFF1B1B22), CarDarkSurface)
        assertEquals(Color(0xFF242430), CarDarkSurfaceVariant)
        assertEquals(Color(0xFF2E2E3E), CarDarkOutline)
        assertEquals(Color(0xFF00E676), CarAccentGreen)
        assertEquals(Color(0xFF00E5FF), CarAccentCyan)
        assertEquals(Color(0xFFFF3B30), CarStatusOffline)
        assertEquals(Color(0xFFFF9500), CarStatusWarning)
        assertEquals(Color(0xFFFFFFFF), CarTextPrimary)

        // Relative luminance calculations
        val bgLuminance = AutomotiveThemeHelper.calculateRelativeLuminance(0xFF121216L)
        val greenLuminance = AutomotiveThemeHelper.calculateRelativeLuminance(0xFF00E676L)
        val cyanLuminance = AutomotiveThemeHelper.calculateRelativeLuminance(0xFF00E5FFL)
        val textLuminance = AutomotiveThemeHelper.calculateRelativeLuminance(0xFFFFFFFFL)

        // Pure white luminance is 1.0; dark background is deep obsidian (< 0.01)
        assertEquals(1.0, textLuminance, 0.001)
        assertTrue("Background luminance must be deep obsidian (< 0.01)", bgLuminance < 0.01)
        assertTrue("Neon green luminance must be prominent (> 0.5)", greenLuminance > 0.5)
        assertTrue("Electric cyan luminance must be prominent (> 0.5)", cyanLuminance > 0.5)

        // 1. CarAccentGreen (#00E676) vs CarDarkBackground (#121216)
        val greenContrast = AutomotiveThemeHelper.calculateContrastRatio(0xFF00E676L, 0xFF121216L)
        assertTrue(
            "Neon Green contrast ratio must meet WCAG AAA (>= 7.0:1). Actual: $greenContrast",
            AutomotiveThemeHelper.isWcagAaaCompliant(greenContrast)
        )
        assertTrue(
            "Neon Green contrast ratio must exceed 10:1. Actual: $greenContrast",
            greenContrast > 10.0
        )

        // 2. CarAccentCyan (#00E5FF) vs CarDarkBackground (#121216)
        val cyanContrast = AutomotiveThemeHelper.calculateContrastRatio(0xFF00E5FFL, 0xFF121216L)
        assertTrue(
            "Electric Cyan contrast ratio must meet WCAG AAA (>= 7.0:1). Actual: $cyanContrast",
            AutomotiveThemeHelper.isWcagAaaCompliant(cyanContrast)
        )
        assertTrue(
            "Electric Cyan contrast ratio must exceed 11:1. Actual: $cyanContrast",
            cyanContrast > 11.0
        )

        // 3. CarTextPrimary (#FFFFFF) vs CarDarkBackground (#121216)
        val textContrast = AutomotiveThemeHelper.calculateContrastRatio(0xFFFFFFFFL, 0xFF121216L)
        assertTrue(
            "Pure White text contrast ratio must meet WCAG AAA (>= 7.0:1). Actual: $textContrast",
            AutomotiveThemeHelper.isWcagAaaCompliant(textContrast)
        )
        assertTrue(
            "Pure White text contrast ratio must exceed 15:1. Actual: $textContrast",
            textContrast > 15.0
        )

        // Overloads with Compose Color objects
        assertTrue(
            "Compose Color overload must verify WCAG AAA compliance for CarAccentGreen",
            AutomotiveThemeHelper.isWcagAaaCompliant(CarAccentGreen, CarDarkBackground)
        )
        assertTrue(
            "Compose Color overload must verify WCAG AAA compliance for CarAccentCyan",
            AutomotiveThemeHelper.isWcagAaaCompliant(CarAccentCyan, CarDarkBackground)
        )
        assertTrue(
            "Compose Color overload must verify WCAG AAA compliance for CarTextPrimary",
            AutomotiveThemeHelper.isWcagAaaCompliant(CarTextPrimary, CarDarkBackground)
        )
    }

    // =========================================================================
    // 3. StationCard Hero Metric Typography (24sp Bold) & String Formatting
    // =========================================================================
    @Test
    fun testStationCardHeroMetric_TypographyAndFormatting() {
        // Typography specifications
        assertEquals(24.sp, StationCardHelper.HERO_METRIC_FONT_SIZE)
        assertEquals(24f, StationCardHelper.HERO_METRIC_FONT_SIZE_SP, 0.001f)
        assertEquals(FontWeight.Bold, StationCardHelper.HERO_METRIC_FONT_WEIGHT)

        // Hero Metric String Formatting: Available plugs
        val availableHero = StationCardHelper.formatHeroMetric(
            totalAvailablePlugs = 4,
            totalPlugs = 8,
            depotStatus = "Normal"
        )
        assertEquals("🟢 4/8 TRỐNG", availableHero)

        // Hero Metric String Formatting: Fully occupied
        val fullHero = StationCardHelper.formatHeroMetric(
            totalAvailablePlugs = 0,
            totalPlugs = 8,
            depotStatus = "Normal"
        )
        assertEquals("🔴 0/8 HẾT CỔNG", fullHero)

        // Hero Metric String Formatting: Maintaining
        val maintainingHero = StationCardHelper.formatHeroMetric(
            totalAvailablePlugs = 2,
            totalPlugs = 4,
            depotStatus = "Maintaining"
        )
        assertEquals("🟡 BẢO TRÌ", maintainingHero)

        // Hero Metric String Formatting: Out of service
        val outOfServiceHero = StationCardHelper.formatHeroMetric(
            totalAvailablePlugs = 0,
            totalPlugs = 4,
            depotStatus = "OutOfService"
        )
        assertEquals("🔴 TẠM DỪNG", outOfServiceHero)

        // Hero Metric String Formatting: Zero plugs with Normal status
        val readyHero = StationCardHelper.formatHeroMetric(
            totalAvailablePlugs = 0,
            totalPlugs = 0,
            depotStatus = "Normal"
        )
        assertEquals("🟢 SẴN SÀNG", readyHero)

        // Hero Metric String Formatting: Fallback / Saved
        val savedHero = StationCardHelper.formatHeroMetric(
            totalAvailablePlugs = 0,
            totalPlugs = 0,
            depotStatus = "Other"
        )
        assertEquals("⚡ ĐÃ LƯU", savedHero)
    }

    // =========================================================================
    // 4. Button Height Specifications Across Landscape & Portrait Modes
    // =========================================================================
    @Test
    fun testButtonHeightSpecifications_LandscapeAndPortraitModes() {
        // Landscape / car mode: >= 56dp
        val landscapeButtonHeight = StationCardHelper.resolveButtonHeight(isCarMode = true)
        assertEquals(56.dp, landscapeButtonHeight)
        assertEquals(56f, StationCardHelper.resolveButtonHeightDp(isCarMode = true), 0.001f)
        assertTrue(
            "Landscape button height must be >= 56dp",
            StationCardHelper.resolveButtonHeightDp(isCarMode = true) >= 56f
        )

        // Portrait mode: 44dp
        val portraitButtonHeight = StationCardHelper.resolveButtonHeight(isCarMode = false)
        assertEquals(44.dp, portraitButtonHeight)
        assertEquals(44f, StationCardHelper.resolveButtonHeightDp(isCarMode = false), 0.001f)

        // Card min height constant
        assertEquals(76.dp, StationCardHelper.CAR_CARD_MIN_HEIGHT)
        assertEquals(76f, StationCardHelper.CAR_CARD_MIN_HEIGHT_DP, 0.001f)
    }

    // =========================================================================
    // 5. Detail Sheet Action Button Specs (56dp CTA & Combined Navigation)
    // =========================================================================
    @Test
    fun testDetailSheetActionButtons_CombinedCtaAndFiftySixDpEnforcement() {
        // Combined 1-tap navigation CTA button configuration
        assertEquals("⚡ DẪN ĐƯỜNG & THEO DÕI", NativeStationDetailSheetHelper.LABEL_NAVIGATE_AND_TRACK)
        assertEquals(56.dp, NativeStationDetailSheetHelper.CAR_BUTTON_HEIGHT)
        assertEquals(56f, NativeStationDetailSheetHelper.CAR_BUTTON_HEIGHT_DP, 0.001f)
        assertTrue(
            "Detail sheet primary CTA height must be >= 56dp",
            NativeStationDetailSheetHelper.CAR_BUTTON_HEIGHT_DP >= 56f
        )

        // Font size and weight specification (16sp Bold)
        assertEquals(16.sp, NativeStationDetailSheetHelper.CAR_BUTTON_TEXT_SIZE)
        assertEquals(16f, NativeStationDetailSheetHelper.CAR_BUTTON_TEXT_SIZE_SP, 0.001f)
        assertEquals(FontWeight.Bold, NativeStationDetailSheetHelper.CAR_BUTTON_FONT_WEIGHT)
    }
}
