package com.evcs.favorites.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 01 Comprehensive Verification Test:
 * Landscape Scrolling Contract & Visual Hierarchy Remediation.
 *
 * Verifies:
 * 1. Landscape master-detail layout in `SettingsScreen.kt` implements vertical scrolling on the Right Detail Content Canvas.
 * 2. Left master sidebar supports safe vertical scrolling to prevent bottom clipping on low-resolution automotive displays.
 * 3. `StartupOrientationCard` container uses `DarkCardBackground` instead of `EmeraldContainerDark` to resolve green visual clutter.
 * 4. Option item title typography uses high-contrast `onSurface` instead of `EmeraldPrimary` when selected.
 * 5. Automotive badge ("Khuyên dùng cho Android Box ô tô") eliminates garish multi-layer neon borders.
 * 6. `FocusModeVoiceAlertCard` container uses `DarkCardBackground` for visual unity.
 */
class LandscapeScrollAndVisualContractTest {

    @Test
    fun testLandscapeScrollingAndVisualHierarchyContract() {
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir

        val settingsScreenFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsScreenFile.exists())
        val settingsCode = settingsScreenFile.readText()

        // 1. Right Detail Canvas must have verticalScroll in SettingsScreenLandscape
        assertTrue(
            "SettingsScreenLandscape must declare detailScrollState with rememberScrollState()",
            settingsCode.contains("val detailScrollState = rememberScrollState()")
        )
        assertTrue(
            "SettingsScreenLandscape right detail canvas must have verticalScroll(detailScrollState)",
            settingsCode.contains(".verticalScroll(detailScrollState)")
        )
        assertTrue(
            "SettingsScreenLandscape right detail canvas must have bottom padding for safe reachability",
            settingsCode.contains("padding(bottom = 24.dp)")
        )

        // 2. Left Master Sidebar must have safe vertical scrolling
        assertTrue(
            "Left Master Sidebar must declare sidebarScrollState with rememberScrollState()",
            settingsCode.contains("val sidebarScrollState = rememberScrollState()")
        )
        assertTrue(
            "Left Master Sidebar column must have verticalScroll(sidebarScrollState)",
            settingsCode.contains(".verticalScroll(sidebarScrollState)")
        )

        // 3. SettingsComponents visual remediation check
        val componentsFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/components/SettingsComponents.kt")
        assertTrue("SettingsComponents.kt must exist", componentsFile.exists())
        val componentsCode = componentsFile.readText()

        // Verify DarkCardBackground is imported and applied to StartupOrientationCard
        assertTrue(
            "SettingsComponents.kt must import DarkCardBackground",
            componentsCode.contains("import com.evcs.favorites.ui.theme.DarkCardBackground")
        )

        val startupCardSection = componentsCode.substringAfter("fun StartupOrientationCard")
            .substringBefore("fun FocusModeVoiceAlertCard")

        assertTrue(
            "StartupOrientationCard must use DarkCardBackground for containerColor",
            startupCardSection.contains("containerColor = DarkCardBackground")
        )
        assertFalse(
            "StartupOrientationCard must NOT use EmeraldContainerDark as its card containerColor",
            startupCardSection.contains("containerColor = EmeraldContainerDark")
        )

        // 4. Option title must use onSurface, not EmeraldPrimary
        assertTrue(
            "StartupOrientationCard option title must use MaterialTheme.colorScheme.onSurface",
            startupCardSection.contains("color = MaterialTheme.colorScheme.onSurface")
        )
        assertFalse(
            "StartupOrientationCard option title must NOT conditionally color text in EmeraldPrimary",
            startupCardSection.contains("color = if (isSelected) EmeraldPrimary")
        )

        // 5. Badge must not use heavy neon green border
        assertFalse(
            "StartupOrientationCard badge must NOT have neon green 0.6f border",
            startupCardSection.contains("EmeraldPrimary.copy(alpha = 0.6f)")
        )

        // 6. FocusModeVoiceAlertCard must also use DarkCardBackground
        val voiceCardSection = componentsCode.substringAfter("fun FocusModeVoiceAlertCard")
            .substringBefore("}")
        assertTrue(
            "FocusModeVoiceAlertCard must use DarkCardBackground for containerColor",
            voiceCardSection.contains("containerColor = DarkCardBackground")
        )
    }
}
