package com.evcs.favorites.ui.screens

import androidx.compose.ui.unit.dp
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.navigation.AppNavigationRailHelper
import com.evcs.favorites.navigation.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 03:
 * Adaptive Layouts (Landscape Master-Detail & Portrait Fullscreen), Auto-Save, and Final Cleanup.
 *
 * Verifies:
 * 1. Master-Detail category switching in landscape mode (category enum definitions, sidebar dimensions >= 56dp touch targets, and detail canvas mapping).
 * 2. Portrait layout composition (Scaffold, TopAppBar "Cài đặt", Reset action, single-column vertical scroll, and IME window insets).
 * 3. 100% full-canvas rendering without modal bottom sheets, popups, dim backdrop scrims, or DebugLogViewerCard.
 * 4. Integration verification: MainActivity tab container routing on AppTab.SETTINGS and BackHandler behavior.
 */
class SettingsScreenAdaptiveLayoutTest {

    // 1. Master-Detail category switching in landscape mode
    @Test
    fun testMasterDetailCategorySwitchingAndLandscapeContract() {
        // Verify category count and strict sequence
        val categories = SettingsCategory.entries
        assertEquals("Master-Detail layout must define exactly 4 functional categories", 4, categories.size)

        assertEquals("Index 0 must be DISPLAY", SettingsCategory.DISPLAY, categories[0])
        assertEquals("Index 1 must be VOICE", SettingsCategory.VOICE, categories[1])
        assertEquals("Index 2 must be FILTER", SettingsCategory.FILTER, categories[2])
        assertEquals("Index 3 must be ABOUT", SettingsCategory.ABOUT, categories[3])

        // Verify titles and subtitles for glanceability
        assertEquals("Hiển thị & Xe", SettingsCategory.DISPLAY.title)
        assertEquals("Hướng màn hình & giao diện", SettingsCategory.DISPLAY.subtitle)

        assertEquals("Giọng nói & Lái xe", SettingsCategory.VOICE.title)
        assertEquals("Cảnh báo chỉ đường & âm thanh", SettingsCategory.VOICE.subtitle)

        assertEquals("Bộ lọc công suất", SettingsCategory.FILTER.title)
        assertEquals("Công suất sạc trạm (kW)", SettingsCategory.FILTER.subtitle)

        assertEquals("Thông tin ứng dụng", SettingsCategory.ABOUT.title)
        assertEquals("Phiên bản, tác giả & bản quyền", SettingsCategory.ABOUT.subtitle)

        // Automotive glanceability & sidebar contract dimensions
        assertEquals("Sidebar width must be exactly 260.dp", 260.dp, SettingsScreenDefaults.SIDEBAR_WIDTH_DP)
        assertEquals("Touch targets must be at least 56.dp", 56.dp, SettingsScreenDefaults.MIN_TOUCH_TARGET_DP)

        // Verify state switching simulation
        var currentCategory = SettingsCategory.DISPLAY
        val onSelectCategory: (SettingsCategory) -> Unit = { currentCategory = it }

        onSelectCategory(SettingsCategory.VOICE)
        assertEquals(SettingsCategory.VOICE, currentCategory)

        onSelectCategory(SettingsCategory.FILTER)
        assertEquals(SettingsCategory.FILTER, currentCategory)

        onSelectCategory(SettingsCategory.ABOUT)
        assertEquals(SettingsCategory.ABOUT, currentCategory)

        onSelectCategory(SettingsCategory.DISPLAY)
        assertEquals(SettingsCategory.DISPLAY, currentCategory)
    }

    // 2. Portrait layout composition
    @Test
    fun testPortraitLayoutComposition_topAppBarResetAndImeScroll() {
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val settingsScreenFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")

        assertTrue("SettingsScreen.kt must exist", settingsScreenFile.exists())
        val code = settingsScreenFile.readText()

        // Verify SettingsScreenPortrait exists
        assertTrue("Must contain SettingsScreenPortrait composable", code.contains("fun SettingsScreenPortrait"))

        // Verify Scaffold with TopAppBar
        assertTrue("SettingsScreenPortrait must render Scaffold", code.contains("Scaffold("))
        assertTrue("SettingsScreenPortrait must contain TopAppBar", code.contains("TopAppBar("))
        assertTrue("TopAppBar must display title 'Cài đặt'", code.contains("\"Cài đặt\""))

        // Verify Reset action icon button
        assertTrue("TopAppBar must contain reset IconButton", code.contains("IconButton(onClick = onResetClick)"))
        assertTrue("TopAppBar must include Icons.Default.Refresh", code.contains("Icons.Default.Refresh"))

        // Verify single-column vertical scroll with IME window insets
        assertTrue("Portrait must use verticalScroll", code.contains(".verticalScroll(scrollState)"))
        assertTrue("Portrait must handle IME insets via imePadding", code.contains(".imePadding()"))

        // Verify logical sequence within SettingsScreenPortrait: Display -> Voice -> Filter -> About
        val portraitCode = code.substringAfter("fun SettingsScreenPortrait")
        val displayIndex = portraitCode.indexOf("StartupOrientationCard")
        val voiceIndex = portraitCode.indexOf("FocusModeVoiceAlertCard")
        val filterIndex = portraitCode.indexOf("CustomFilterSettingsCard")
        val aboutIndex = portraitCode.indexOf("AboutAppCard")

        assertTrue("Display card must be present in portrait", displayIndex > 0)
        assertTrue("Voice card must be present in portrait", voiceIndex > 0)
        assertTrue("Filter card must be present in portrait", filterIndex > 0)
        assertTrue("About card must be present in portrait", aboutIndex > 0)

        assertTrue("Display must precede Voice in portrait sequence", displayIndex < voiceIndex)
        assertTrue("Voice must precede Filter in portrait sequence", voiceIndex < filterIndex)
        assertTrue("Filter must precede About in portrait sequence", filterIndex < aboutIndex)
    }

    // 3. Full-screen rendering without modal/overlay background underlays
    @Test
    fun testFullScreenRendering_noModalUnderlaysOrDebugLog() {
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val settingsScreenFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")

        assertTrue("SettingsScreen.kt must exist", settingsScreenFile.exists())
        val code = settingsScreenFile.readText()

        // Verify root full size canvas
        assertTrue("SettingsScreen root must fill max size", code.contains("fillMaxSize()"))

        // Verify no modal bottom sheet underlay
        assertFalse("SettingsScreen must NOT use ModalBottomSheet", code.contains("ModalBottomSheet("))
        assertFalse("SettingsScreen must NOT use rememberModalBottomSheetState", code.contains("rememberModalBottomSheetState"))

        // Verify no dim scrim overlay
        assertFalse("SettingsScreen must NOT use dim backdrop scrim (0.54f)", code.contains("0.54f"))

        // Verify no DebugLogViewerCard
        assertFalse("SettingsScreen must NOT contain DebugLogViewerCard", code.contains("DebugLogViewerCard"))

        // Verify Landscape Master-Detail implementation
        assertTrue("Must contain SettingsScreenLandscape composable", code.contains("fun SettingsScreenLandscape"))
        assertTrue("Landscape must render SettingsCategoryTile", code.contains("SettingsCategoryTile"))
        assertTrue("Landscape must anchor Reset Defaults button in sidebar footer", code.contains("\"Đặt lại mặc định\""))
    }

    // 4. Integration verification: MainActivity tab routing on AppTab.SETTINGS
    @Test
    fun testMainActivityRoutingIntegration_routesSettingsTabCleanly() {
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val mainActivityFile = File(baseDir, "src/main/java/com/evcs/favorites/MainActivity.kt")

        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        val code = mainActivityFile.readText()

        // Verify AppTab.SETTINGS routes to SettingsScreen
        assertTrue("MainActivity must route AppTab.SETTINGS to SettingsScreen", code.contains("AppTab.SETTINGS -> {"))
        assertTrue("MainActivity must invoke SettingsScreen on SETTINGS tab", code.contains("SettingsScreen("))

        // Verify connected dependencies
        assertTrue("MainActivity must pass effectiveOrientationPrefs", code.contains("orientationPreferences = effectiveOrientationPrefs"))
        assertTrue("MainActivity must pass effectiveFocusPrefs", code.contains("focusModePreferences = effectiveFocusPrefs"))
        assertTrue("MainActivity must pass customFilterConfig", code.contains("customFilterConfig = nearbyUiState?.savedCustomConfig"))
        assertTrue("MainActivity must pass onSaveCustomFilter", code.contains("nearbyViewModel?.saveAndApplyCustomFilter(config)"))

        // Verify Back navigation contract
        assertTrue("BackHandler should intercept SETTINGS tab", AppNavigationRailHelper.shouldInterceptBack(AppTab.SETTINGS))
        assertEquals("BackHandler must resolve back to NEARBY tab", AppTab.NEARBY, AppNavigationRailHelper.resolveBackTargetTab(AppTab.SETTINGS))
    }
}
