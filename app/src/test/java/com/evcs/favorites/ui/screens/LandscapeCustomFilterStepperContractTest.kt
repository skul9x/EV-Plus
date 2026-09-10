package com.evcs.favorites.ui.screens

import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.ui.components.CustomFilterFormState
import com.evcs.favorites.ui.components.CustomFilterHelper
import com.evcs.favorites.ui.components.stepKw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 02 Comprehensive Verification Test:
 * Automotive Power Filter Steppers & Contract Verification.
 *
 * Verifies:
 * 1. `CustomFilterSettingsCard` accepts `isLandscape: Boolean = false` parameter.
 * 2. In landscape mode (`isLandscape == true`), virtual keyboard numeric fields (`OutlinedTextField`) are NOT used;
 *    instead, distraction-free `AutomotiveStepperBox` with >= 56dp height touch targets is rendered.
 * 3. Steppers increment and decrement properly by 10 kW in `CustomFilterFormState` and `CustomFilterHelper.stepKw`.
 * 4. Value bounds are clamped strictly within 1..500 kW.
 * 5. `SettingsScreenPortrait` passes `isLandscape = false` while `SettingsScreenLandscape` passes `isLandscape = true`.
 */
class LandscapeCustomFilterStepperContractTest {

    @Test
    fun testLandscapeCustomFilterStepperContractAndLogic() {
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir

        // 1. Verify source contract in CustomFilterSettingsCard.kt
        val cardFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt")
        assertTrue("CustomFilterSettingsCard.kt must exist", cardFile.exists())
        val cardCode = cardFile.readText()

        // Must accept isLandscape parameter with default false
        assertTrue(
            "CustomFilterSettingsCard must accept isLandscape: Boolean = false",
            cardCode.contains("isLandscape: Boolean = false")
        )

        // Must define AutomotiveStepperBox with >= 56dp height buttons
        assertTrue(
            "CustomFilterSettingsCard.kt must define AutomotiveStepperBox",
            cardCode.contains("fun AutomotiveStepperBox")
        )
        assertTrue(
            "Automotive stepper buttons must have minimum 56dp touch height",
            cardCode.contains("heightIn(min = 56.dp)")
        )

        // Landscape branch must use AutomotiveStepperBox and NOT OutlinedTextField in the landscape branch
        assertTrue(
            "CustomFilterSettingsCard must branch on isLandscape",
            cardCode.contains("if (isLandscape) {")
        )
        val landscapeBranch = cardCode.substringAfter("if (isLandscape) {")
            .substringBefore("} else {")
        assertTrue(
            "Landscape branch must use AutomotiveStepperBox",
            landscapeBranch.contains("AutomotiveStepperBox")
        )
        assertFalse(
            "Landscape branch must NOT contain OutlinedTextField",
            landscapeBranch.contains("OutlinedTextField")
        )

        // 2. Verify SettingsScreen passes isLandscape to CustomFilterSettingsCard
        val settingsFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsFile.exists())
        val settingsCode = settingsFile.readText()

        val portraitSection = settingsCode.substringAfter("fun SettingsScreenPortrait")
            .substringBefore("fun SettingsScreenLandscape")
        assertTrue(
            "SettingsScreenPortrait must pass isLandscape = false to CustomFilterSettingsCard",
            portraitSection.contains("isLandscape = false")
        )

        val landscapeSection = settingsCode.substringAfter("fun SettingsScreenLandscape")
            .substringBefore("fun SettingsCategoryTile")
        assertTrue(
            "SettingsScreenLandscape must pass isLandscape = true to CustomFilterSettingsCard",
            landscapeSection.contains("isLandscape = true")
        )

        // 3. Functional verification of Stepper clamping & FormState
        // Test stepKw top-level helper and clamping
        assertEquals(40, stepKw(30, 10))
        assertEquals(20, stepKw(30, -10))
        assertEquals(1, stepKw(5, -10)) // Clamped to MIN_KW (1)
        assertEquals(500, stepKw(495, 10)) // Clamped to MAX_KW (500)
        assertEquals(10, stepKw(null, 10)) // Unbounded base + 10 -> 10

        // Test CustomFilterFormState stepping and transitions
        val state = CustomFilterFormState()
        assertEquals(CustomFilterMode.QUICK_CHIP, state.mode)
        assertNull(state.minKw)
        assertNull(state.maxKw)

        // Stepping min switches to CUSTOM_RANGE
        state.stepMinKw(10)
        assertEquals(10, state.minKw)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, state.mode)
        assertNull(state.selectedChip)

        state.stepMinKw(10)
        assertEquals(20, state.minKw)

        state.stepMinKw(-10)
        assertEquals(10, state.minKw)

        // Stepping max
        state.stepMaxKw(10)
        assertEquals(10, state.maxKw)
        state.stepMaxKw(10)
        assertEquals(20, state.maxKw)

        // Clear min and max
        state.clearMinKw()
        assertNull(state.minKw)
        assertEquals("", state.minKwText)

        state.clearMaxKw()
        assertNull(state.maxKw)
        assertEquals("", state.maxKwText)
    }
}
