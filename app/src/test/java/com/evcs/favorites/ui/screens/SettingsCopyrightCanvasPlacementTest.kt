package com.evcs.favorites.ui.screens

import com.evcs.favorites.ui.components.AboutAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 03 Single Verification Test:
 * Settings Copyright Canvas Placement & Sidebar Cleanup Test.
 *
 * Verifies:
 * 1. `AboutAppInfo.COPYRIGHT` equals "© 2026 Nguyễn Duy Trường".
 * 2. `SettingsScreenLandscape` master sidebar does NOT contain copyright footer beneath "Đặt lại mặc định".
 * 3. `SettingsCategory.ABOUT` branch in `SettingsScreenLandscape` renders `AboutAppInfo.COPYRIGHT`
 *    directly on the dark detail canvas outside `AboutAppCard`.
 * 4. `SettingsScreenPortrait` and `SettingsCopyrightFooter` display `AboutAppInfo.COPYRIGHT` with
 *    `labelSmall` typography, `onSurfaceVariant` color, and centered alignment.
 */
class SettingsCopyrightCanvasPlacementTest {

    @Test
    fun testSettingsCopyrightCanvasPlacementAndSidebarCleanup() {
        // 1. Verify copyright string constant
        assertEquals("© 2026 Nguyễn Duy Trường", AboutAppInfo.COPYRIGHT)
        assertEquals("Nguyễn Duy Trường", AboutAppInfo.AUTHOR)

        // 2. Read SettingsScreen.kt source file
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val settingsFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsFile.exists())
        val settingsCode = settingsFile.readText()

        // 3. Landscape sidebar cleanup: No copyright footer beneath "Đặt lại mặc định"
        val landscapeSection = settingsCode.substringAfter("fun SettingsScreenLandscape")
            .substringBefore("fun SettingsCategoryTile")
        val sidebarSection = landscapeSection.substringBefore("Right Detail Content Canvas")

        assertTrue(
            "Sidebar must contain 'Đặt lại mặc định' reset defaults button",
            sidebarSection.contains("Đặt lại mặc định")
        )
        assertFalse(
            "Sidebar must NOT invoke SettingsCopyrightFooter",
            sidebarSection.contains("SettingsCopyrightFooter")
        )
        assertFalse(
            "Sidebar must NOT contain copyright constant",
            sidebarSection.contains("AboutAppInfo.COPYRIGHT")
        )
        assertFalse(
            "Sidebar must NOT contain copyright symbol or text",
            sidebarSection.contains("©")
        )

        // 4. Landscape detail canvas in ABOUT category renders copyright on canvas outside AboutAppCard
        val detailSection = landscapeSection.substringAfter("Right Detail Content Canvas")
        assertTrue(
            "Detail section must contain SettingsCategory.ABOUT branch",
            detailSection.contains("SettingsCategory.ABOUT ->")
        )
        val aboutBranch = detailSection.substringAfter("SettingsCategory.ABOUT ->")
            .substringBefore("}")

        assertTrue(
            "ABOUT category branch must embed AboutAppCard",
            aboutBranch.contains("AboutAppCard()")
        )
        assertTrue(
            "ABOUT category branch must render AboutAppInfo.COPYRIGHT",
            aboutBranch.contains("AboutAppInfo.COPYRIGHT")
        )

        val aboutCardIndex = aboutBranch.indexOf("AboutAppCard()")
        val copyrightIndex = aboutBranch.indexOf("AboutAppInfo.COPYRIGHT")
        assertTrue(
            "Copyright text must be positioned below AboutAppCard on canvas",
            copyrightIndex > aboutCardIndex
        )

        // Verify styling of copyright text on canvas
        assertTrue(
            "Canvas copyright text must use typography.labelSmall",
            aboutBranch.contains("MaterialTheme.typography.labelSmall")
        )
        assertTrue(
            "Canvas copyright text must use onSurfaceVariant color",
            aboutBranch.contains("MaterialTheme.colorScheme.onSurfaceVariant")
        )
        assertTrue(
            "Canvas copyright text must be center aligned",
            aboutBranch.contains("TextAlign.Center")
        )

        // 5. Portrait layout alignment and SettingsCopyrightFooter implementation
        val portraitSection = settingsCode.substringAfter("fun SettingsScreenPortrait")
            .substringBefore("fun SettingsScreenLandscape")
        assertTrue(
            "SettingsScreenPortrait must embed SettingsCopyrightFooter",
            portraitSection.contains("SettingsCopyrightFooter()")
        )

        val footerSection = settingsCode.substringAfter("fun SettingsCopyrightFooter")
        assertTrue(
            "SettingsCopyrightFooter must render AboutAppInfo.COPYRIGHT",
            footerSection.contains("AboutAppInfo.COPYRIGHT")
        )
        assertTrue(
            "SettingsCopyrightFooter must use typography.labelSmall",
            footerSection.contains("MaterialTheme.typography.labelSmall")
        )
        assertTrue(
            "SettingsCopyrightFooter must use onSurfaceVariant color",
            footerSection.contains("MaterialTheme.colorScheme.onSurfaceVariant")
        )
        assertTrue(
            "SettingsCopyrightFooter must be center aligned",
            footerSection.contains("TextAlign.Center")
        )
    }
}
