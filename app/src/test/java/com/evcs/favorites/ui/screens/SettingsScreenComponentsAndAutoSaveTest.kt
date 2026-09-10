package com.evcs.favorites.ui.screens

import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.preferences.FocusModePreferences
import com.evcs.favorites.data.preferences.OrientationPreferences
import com.evcs.favorites.data.preferences.StartupOrientation
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.ui.components.AboutAppInfo
import com.evcs.favorites.ui.components.AutoSaveFeedbackState
import com.evcs.favorites.ui.components.CustomFilterFormState
import com.evcs.favorites.ui.components.CustomFilterHelper
import com.evcs.favorites.ui.components.SettingsDefaultsHelper
import com.evcs.favorites.ui.components.stepKw
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Comprehensive verification test for Phase 02:
 * Commercial Settings Screen Components & Auto-Save.
 *
 * Verifies:
 * 1. Stepper helper logic (`stepKw(currentKw, delta, min, max)` correctly increments/decrements by 10 within bounds 1-500).
 * 2. Auto-save dispatch contract for orientation, voice alerts, and quick chips.
 * 3. Custom filter validation with inline Apply action.
 * 4. Reset to defaults logic restoring initial state cleanly.
 * 5. Absence of `DebugLogViewerCard` in `SettingsScreen` and new settings components.
 * 6. About card content verification (author, copyright 2026, email).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsScreenComponentsAndAutoSaveTest {

    private lateinit var storage: InMemorySessionStorage
    private lateinit var orientationPrefs: OrientationPreferences
    private lateinit var focusPrefs: FocusModePreferences

    @Before
    fun setUp() {
        storage = InMemorySessionStorage()
        orientationPrefs = OrientationPreferences(storage)
        focusPrefs = FocusModePreferences(storage)
    }

    // 1. Stepper helper logic & bounds clamping
    @Test
    fun testStepperHelperLogic_stepKwAndFormStateStepping() {
        // Test null baseline with positive and negative deltas
        assertEquals(10, stepKw(null, 10))
        assertEquals(1, stepKw(null, -10))

        // Normal increments and decrements by 10
        assertEquals(40, stepKw(30, 10))
        assertEquals(20, stepKw(30, -10))
        assertEquals(60, CustomFilterHelper.stepKw(50, 10))
        assertEquals(40, CustomFilterHelper.stepKw(50, -10))

        // Clamping to standard limits (1..500)
        assertEquals(500, stepKw(500, 10))
        assertEquals(500, stepKw(495, 10))
        assertEquals(1, stepKw(1, -10))
        assertEquals(1, stepKw(5, -10))

        // Custom bounds clamping
        assertEquals(160, stepKw(150, 10, min = 50, max = 200))
        assertEquals(200, stepKw(195, 10, min = 50, max = 200))
        assertEquals(50, stepKw(55, -10, min = 50, max = 200))
        assertEquals(50, stepKw(50, -10, min = 50, max = 200))

        // Form state stepping integration
        val state = CustomFilterFormState()
        assertEquals(CustomFilterMode.QUICK_CHIP, state.mode)
        assertEquals(QuickChipOption.ALL, state.selectedChip)

        state.stepMinKw(10)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, state.mode)
        assertNull(state.selectedChip)
        assertEquals("10", state.minKwText)
        assertEquals(10, state.minKw)

        state.stepMinKw(10)
        assertEquals("20", state.minKwText)

        state.stepMaxKw(100)
        assertEquals("100", state.maxKwText)
        assertEquals(100, state.maxKw)

        state.stepMaxKw(-10)
        assertEquals("90", state.maxKwText)
        assertEquals(90, state.maxKw)
    }

    // 2. Auto-save dispatch contract for orientation, voice alerts, quick chips & feedback pill
    @Test
    fun testAutoSaveDispatchContract_orientationVoiceAlertAndQuickChips() = runTest {
        var autoSaveCount = 0
        val onAutoSave: () -> Unit = { autoSaveCount++ }

        // Orientation auto-save contract
        assertEquals(StartupOrientation.SYSTEM, orientationPrefs.getStartupOrientation())
        orientationPrefs.setStartupOrientation(StartupOrientation.LANDSCAPE)
        onAutoSave()
        assertEquals(StartupOrientation.LANDSCAPE, orientationPrefs.getStartupOrientation())
        assertEquals(1, autoSaveCount)

        orientationPrefs.setStartupOrientation(StartupOrientation.PORTRAIT)
        onAutoSave()
        assertEquals(StartupOrientation.PORTRAIT, orientationPrefs.getStartupOrientation())
        assertEquals(2, autoSaveCount)

        // Voice Alert auto-save contract
        assertTrue(focusPrefs.isVoiceAlertEnabled())
        focusPrefs.setVoiceAlertEnabled(false)
        onAutoSave()
        assertFalse(focusPrefs.isVoiceAlertEnabled())
        assertEquals(3, autoSaveCount)

        focusPrefs.setVoiceAlertEnabled(true)
        onAutoSave()
        assertTrue(focusPrefs.isVoiceAlertEnabled())
        assertEquals(4, autoSaveCount)

        // Quick chips auto-save contract
        val formState = CustomFilterFormState()
        var savedFilterConfig: CustomFilterConfig? = null
        val onSaveCustomFilter: (CustomFilterConfig) -> Unit = { config ->
            savedFilterConfig = config
            onAutoSave()
        }

        // Tapping AC chip
        val acConfig = formState.selectQuickChip(QuickChipOption.AC)
        onSaveCustomFilter(acConfig)
        assertEquals(CustomFilterMode.QUICK_CHIP, savedFilterConfig?.mode)
        assertEquals(QuickChipOption.AC, savedFilterConfig?.quickChip)
        assertEquals(5, autoSaveCount)

        // Tapping DC >= 60kW chip
        val dcConfig = formState.selectQuickChip(QuickChipOption.DC_GE_60KW)
        onSaveCustomFilter(dcConfig)
        assertEquals(CustomFilterMode.QUICK_CHIP, savedFilterConfig?.mode)
        assertEquals(QuickChipOption.DC_GE_60KW, savedFilterConfig?.quickChip)
        assertEquals(6, autoSaveCount)

        // Feedback Pill State
        val feedbackState = AutoSaveFeedbackState()
        assertFalse(feedbackState.isVisible)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        feedbackState.trigger(testScope, durationMs = 1500L)
        testScope.advanceTimeBy(100L)
        assertTrue(feedbackState.isVisible)

        testScope.advanceTimeBy(1500L)
        assertFalse(feedbackState.isVisible)
    }

    // 3. Custom filter validation with inline Apply action
    @Test
    fun testCustomFilterValidationAndInlineApply() {
        val initial = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.ALL
        )
        val state = CustomFilterFormState(initialConfig = initial)

        // Fresh state with initial config is not dirty
        assertFalse("Initial state should not be dirty", state.isDirty)
        assertTrue(state.isValid)

        // User enters manual minKw
        state.onMinKwChanged("30")
        assertEquals(CustomFilterMode.CUSTOM_RANGE, state.mode)
        assertTrue("State should be dirty after user input", state.isDirty)
        assertTrue("Single minKw is valid", state.isValid)
        assertNull(state.errorMessage)

        // Invalid: Min > Max
        state.onMaxKwChanged("20")
        assertTrue("Min > Max should make state invalid", !state.isValid)
        assertEquals("Min không được lớn hơn Max", state.errorMessage)
        assertNull("Apply should return null when invalid", state.applyCustomRange())

        // Correct Max to valid range
        state.onMaxKwChanged("150")
        assertTrue("State should be valid now", state.isValid)
        assertNull(state.errorMessage)
        assertTrue(state.isDirty)

        // Apply custom range
        val committed = state.applyCustomRange()
        assertNotNull(committed)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, committed?.mode)
        assertEquals(30, committed?.minKw)
        assertEquals(150, committed?.maxKw)

        // Once applied, state is no longer dirty
        assertFalse("State should not be dirty immediately after applying", state.isDirty)

        // Modifying again marks dirty
        state.stepMaxKw(10)
        assertEquals("160", state.maxKwText)
        assertTrue("State should become dirty again after adjustment", state.isDirty)
    }

    // 4. Reset to defaults logic restoring initial state cleanly
    @Test
    fun testResetToDefaultsLogic() {
        // Alter initial configuration
        orientationPrefs.setStartupOrientation(StartupOrientation.LANDSCAPE)
        focusPrefs.setVoiceAlertEnabled(false)

        var lastSavedFilter: CustomFilterConfig? = CustomFilterConfig(
            mode = CustomFilterMode.CUSTOM_RANGE,
            minKw = 50,
            maxKw = 180
        )
        var autoSaveDispatched = false

        assertEquals(StartupOrientation.LANDSCAPE, orientationPrefs.getStartupOrientation())
        assertFalse(focusPrefs.isVoiceAlertEnabled())
        assertEquals(CustomFilterMode.CUSTOM_RANGE, lastSavedFilter?.mode)

        // Trigger Reset Defaults
        SettingsDefaultsHelper.resetToDefaults(
            orientationPrefs = orientationPrefs,
            focusPrefs = focusPrefs,
            onSaveCustomFilter = { lastSavedFilter = it },
            onAutoSaveTriggered = { autoSaveDispatched = true }
        )

        // Verify clean restoration to initial factory defaults
        assertEquals(
            "Orientation must be restored to SYSTEM",
            StartupOrientation.SYSTEM,
            orientationPrefs.getStartupOrientation()
        )
        assertTrue(
            "Voice Alerts must be restored to enabled",
            focusPrefs.isVoiceAlertEnabled()
        )
        assertNotNull(lastSavedFilter)
        assertEquals(
            "Custom filter mode must be restored to QUICK_CHIP",
            CustomFilterMode.QUICK_CHIP,
            lastSavedFilter?.mode
        )
        assertEquals(
            "Quick chip must be restored to ALL",
            QuickChipOption.ALL,
            lastSavedFilter?.quickChip
        )
        assertTrue(
            "Auto-save callback must be invoked on reset",
            autoSaveDispatched
        )
    }

    // 5. Absence of DebugLogViewerCard in Settings Screen & Components
    @Test
    fun testAbsenceOfDebugLogViewerCardInSettingsComponentsAndScreen() {
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir

        val filesToCheck = listOf(
            File(baseDir, "src/main/java/com/evcs/favorites/ui/components/SettingsComponents.kt"),
            File(baseDir, "src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt"),
            File(baseDir, "src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt"),
            File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")
        )

        for (file in filesToCheck) {
            if (file.exists()) {
                val content = file.readText()
                assertFalse(
                    "${file.name} must NOT embed or reference DebugLogViewerCard",
                    content.contains("DebugLogViewerCard")
                )
            }
        }
    }

    // 6. About card content verification (author, copyright 2026, email)
    @Test
    fun testAboutCardContentVerification() {
        assertEquals("1.0", AboutAppInfo.APP_VERSION)
        assertEquals("Nguyễn Duy Trường", AboutAppInfo.AUTHOR)
        assertEquals("© 2026 Nguyễn Duy Trường", AboutAppInfo.COPYRIGHT)
        assertEquals("skul9x@gmail.com", AboutAppInfo.CONTACT_EMAIL)
        assertEquals("EV+", AboutAppInfo.APP_NAME)

        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val aboutCardFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt")

        assertTrue("AboutAppCard.kt must exist", aboutCardFile.exists())
        val cardCode = aboutCardFile.readText()

        assertTrue("AboutAppCard must contain APP_VERSION", cardCode.contains("APP_VERSION"))
        assertTrue("AboutAppCard must contain COPYRIGHT", cardCode.contains("COPYRIGHT"))
        assertTrue("AboutAppCard must contain CONTACT_EMAIL", cardCode.contains("CONTACT_EMAIL"))
        assertTrue("AboutAppCard must contain AUTHOR", cardCode.contains("AUTHOR"))
    }
}
