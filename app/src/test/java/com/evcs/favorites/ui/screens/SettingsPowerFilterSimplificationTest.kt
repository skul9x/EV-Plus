package com.evcs.favorites.ui.screens

import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.ui.components.CustomFilterFormState
import com.evcs.favorites.ui.components.CustomFilterHelper
import com.evcs.favorites.ui.components.stepKw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 01 Single Comprehensive Verification Test:
 * Power Filter Simplification in Settings Screen.
 *
 * Verifies:
 * 1. `CustomFilterSettingsCard` defines `showQuickChips: Boolean = false` parameter defaulting to false.
 * 2. The subtitle text in `CustomFilterSettingsCard` is updated to "Tùy chỉnh khoảng công suất kW mong muốn".
 * 3. The quick chip selector header and grid are conditionally rendered inside `if (showQuickChips)`.
 * 4. `AutomotiveStepperBox` is present and utilized for power stepper adjustments.
 * 5. `SettingsScreen` invokes `CustomFilterSettingsCard` with `showQuickChips = false` in both portrait and landscape modes.
 * 6. Functional verification of stepper logic, clamping within [1..500] kW, form state transitions, and live preview.
 */
class SettingsPowerFilterSimplificationTest {

    @Test
    fun testPowerFilterSimplificationInSettingsContractAndFunctionality() {
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir

        // 1. Verify CustomFilterSettingsCard.kt contracts
        val cardFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt")
        assertTrue("CustomFilterSettingsCard.kt must exist", cardFile.exists())
        val cardCode = cardFile.readText()

        // Verify showQuickChips parameter exists and defaults to false
        assertTrue(
            "CustomFilterSettingsCard must declare showQuickChips: Boolean = false",
            cardCode.contains("showQuickChips: Boolean = false")
        )

        // Verify updated subtitle
        assertTrue(
            "CustomFilterSettingsCard must display updated subtitle 'Tùy chỉnh khoảng công suất kW mong muốn'",
            cardCode.contains("Tùy chỉnh khoảng công suất kW mong muốn")
        )

        // Verify old subtitle is removed
        assertFalse(
            "Old subtitle 'Lọc nhanh AC/DC hoặc tự đặt công suất kW mong muốn' must be removed",
            cardCode.contains("Lọc nhanh AC/DC hoặc tự đặt công suất kW mong muốn")
        )

        // Verify quick chips are wrapped in if (showQuickChips)
        assertTrue(
            "Quick chips section must be wrapped in if (showQuickChips)",
            cardCode.contains("if (showQuickChips)")
        )

        val quickChipSection = cardCode.substringAfter("if (showQuickChips)")
            .substringBefore("Spacer(modifier = Modifier.height(16.dp))\n\n            // 2. Manual Range Inputs")
        assertTrue(
            "Quick chips block must contain 'Chọn nhanh loại cổng / công suất:'",
            quickChipSection.contains("Chọn nhanh loại cổng / công suất:")
        )
        assertTrue(
            "Quick chips block must contain FlowRow with QuickChipOption chips",
            quickChipSection.contains("QuickChipOption.values().forEach")
        )

        // Verify AutomotiveStepperBox definition and utilization
        assertTrue(
            "CustomFilterSettingsCard.kt must define AutomotiveStepperBox",
            cardCode.contains("fun AutomotiveStepperBox")
        )
        assertTrue(
            "CustomFilterSettingsCard must utilize AutomotiveStepperBox",
            cardCode.contains("AutomotiveStepperBox(")
        )

        // 2. Verify SettingsScreen.kt passes showQuickChips = false in both layouts
        val settingsFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsFile.exists())
        val settingsCode = settingsFile.readText()

        val portraitSection = settingsCode.substringAfter("fun SettingsScreenPortrait")
            .substringBefore("fun SettingsScreenLandscape")
        assertTrue(
            "SettingsScreen portrait layout must pass showQuickChips = false",
            portraitSection.contains("showQuickChips = false")
        )

        val landscapeSection = settingsCode.substringAfter("fun SettingsScreenLandscape")
            .substringBefore("fun SettingsCategoryTile")
        assertTrue(
            "SettingsScreen landscape layout must pass showQuickChips = false",
            landscapeSection.contains("showQuickChips = false")
        )

        // 3. Functional verification of Stepper logic & FormState
        // Stepper calculations with +/- 10 kW step
        assertEquals(40, stepKw(30, 10))
        assertEquals(20, stepKw(30, -10))
        assertEquals(1, stepKw(5, -10)) // Clamped to MIN_KW (1)
        assertEquals(500, stepKw(495, 10)) // Clamped to MAX_KW (500)
        assertEquals(10, stepKw(null, 10)) // From null -> 10

        // CustomFilterFormState stepping and validation
        val formState = CustomFilterFormState()
        assertEquals(CustomFilterMode.QUICK_CHIP, formState.mode)

        // Stepping min kW sets mode to CUSTOM_RANGE
        formState.stepMinKw(10)
        assertEquals(10, formState.minKw)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, formState.mode)
        assertNull(formState.selectedChip)
        assertTrue(formState.isValid)
        assertTrue(formState.isDirty)
        assertTrue(formState.livePreview.contains("≥ 10 kW"))

        // Stepping max kW
        formState.stepMaxKw(60)
        assertEquals(60, formState.maxKw)
        assertTrue(formState.isValid)
        assertTrue(formState.livePreview.contains("từ 10 kW đến 60 kW"))

        // Apply custom range and verify config
        val committed = formState.applyCustomRange()
        assertNotNull(committed)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, committed?.mode)
        assertEquals(10, committed?.minKw)
        assertEquals(60, committed?.maxKw)
        assertFalse(formState.isDirty)

        // Clear min and max
        formState.clearMinKw()
        assertNull(formState.minKw)
        formState.clearMaxKw()
        assertNull(formState.maxKw)
        assertFalse(formState.isValid)
        assertEquals("Vui lòng nhập Min hoặc Max", formState.errorMessage)
    }
}
