package com.evcs.favorites.ui.screens

import com.evcs.favorites.ui.components.AboutAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 03 Comprehensive Verification Test:
 * Commercial Branding & Copyright Footer Contract Test.
 *
 * Verifies:
 * 1. `AboutAppInfo.APP_VERSION` is locked to "1.0".
 * 2. `AboutAppInfo.COPYRIGHT` contains "2026" and author "Nguyễn Duy Trường".
 * 3. `AboutAppCard.kt` displays commercial branding and author credit.
 * 4. `SettingsScreen.kt` implements and anchors `SettingsCopyrightFooter` in both
 *    portrait and landscape layouts (under the Reset Defaults button in sidebar).
 * 5. Copyright text adheres to typography (`labelSmall`) and color (`onSurfaceVariant`).
 */
class CommercialBrandingAndCopyrightContractTest {

    @Test
    fun testCommercialBrandingAndCopyrightContract() {
        // 1. Functional constants verification in AboutAppInfo
        assertEquals("1.0", AboutAppInfo.APP_VERSION)
        assertEquals("Nguyễn Duy Trường", AboutAppInfo.AUTHOR)
        assertTrue(
            "AboutAppInfo.COPYRIGHT must contain '2026'",
            AboutAppInfo.COPYRIGHT.contains("2026")
        )
        assertTrue(
            "AboutAppInfo.COPYRIGHT must contain author name 'Nguyễn Duy Trường'",
            AboutAppInfo.COPYRIGHT.contains("Nguyễn Duy Trường")
        )
        assertEquals("skul9x@gmail.com", AboutAppInfo.CONTACT_EMAIL)
        assertEquals("EV+", AboutAppInfo.APP_NAME)

        // 2. Source contract verification in AboutAppCard.kt
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val aboutCardFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt")
        assertTrue("AboutAppCard.kt must exist", aboutCardFile.exists())
        val cardCode = aboutCardFile.readText()

        assertTrue("AboutAppCard must display 'Phiên bản thương mại'", cardCode.contains("Phiên bản thương mại"))
        assertTrue("AboutAppCard must display APP_VERSION", cardCode.contains("AboutAppInfo.APP_VERSION"))
        assertTrue("AboutAppCard must display AUTHOR", cardCode.contains("AboutAppInfo.AUTHOR"))
        assertTrue("AboutAppCard.kt must define COPYRIGHT", cardCode.contains("COPYRIGHT"))
        assertTrue("AboutAppCard must display CONTACT_EMAIL", cardCode.contains("AboutAppInfo.CONTACT_EMAIL"))

        // 3. Source contract verification in SettingsScreen.kt
        val settingsFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsFile.exists())
        val settingsCode = settingsFile.readText()

        // Verify SettingsCopyrightFooter composable declaration
        assertTrue(
            "SettingsScreen.kt must define SettingsCopyrightFooter composable",
            settingsCode.contains("fun SettingsCopyrightFooter")
        )

        // Non-functional requirements: typography labelSmall and onSurfaceVariant color
        val footerDef = settingsCode.substringAfter("fun SettingsCopyrightFooter")
        assertTrue(
            "SettingsCopyrightFooter must use typography.labelSmall",
            footerDef.contains("MaterialTheme.typography.labelSmall")
        )
        assertTrue(
            "SettingsCopyrightFooter must use onSurfaceVariant color",
            footerDef.contains("MaterialTheme.colorScheme.onSurfaceVariant")
        )

        // Verify portrait layout includes SettingsCopyrightFooter
        val portraitSection = settingsCode.substringAfter("fun SettingsScreenPortrait")
            .substringBefore("fun SettingsScreenLandscape")
        assertTrue(
            "SettingsScreenPortrait must embed SettingsCopyrightFooter",
            portraitSection.contains("SettingsCopyrightFooter()")
        )

        // Verify landscape layout removes copyright footer from left sidebar
        val landscapeSection = settingsCode.substringAfter("fun SettingsScreenLandscape")
            .substringBefore("fun SettingsCategoryTile")
        val sidebarSection = landscapeSection.substringBefore("Right Detail Content Canvas")
        org.junit.Assert.assertFalse(
            "SettingsScreenLandscape sidebar must NOT embed SettingsCopyrightFooter",
            sidebarSection.contains("SettingsCopyrightFooter()")
        )

        // Reset Defaults button exists in sidebar
        val sidebarResetButtonIndex = sidebarSection.indexOf("Đặt lại mặc định")
        assertTrue(
            "Reset Defaults button must exist in SettingsScreenLandscape sidebar",
            sidebarResetButtonIndex != -1
        )

        // Verify landscape ABOUT tab renders copyright on canvas
        val detailSection = landscapeSection.substringAfter("Right Detail Content Canvas")
        val aboutBranch = detailSection.substringAfter("SettingsCategory.ABOUT ->")
            .substringBefore("}")
        assertTrue(
            "SettingsCategory.ABOUT branch must render AboutAppInfo.COPYRIGHT on canvas",
            aboutBranch.contains("AboutAppInfo.COPYRIGHT")
        )
    }
}
