package com.evcs.favorites.ui.components

import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.QuickChipOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test file for Phase 03: Custom Filter Settings, Validation,
 * Mutual Exclusion, Live Preview, and Config Persistence.
 *
 * Verifies:
 * 1. Quick chip selection mode toggles correctly and clears manual text fields.
 * 2. Typing numbers in Min/Max clears quick chip selection.
 * 3. Clear icon clears text and resets state to empty.
 * 4. Live preview dynamic description updates accurately for quick chips and manual numbers.
 * 5. Validation fails and error is displayed when Min > Max.
 * 6. Save action creates valid CustomFilterConfig for Min only, Max only, and Min+Max.
 * 7. Values <= 0 or > 500 are rejected by the validator.
 */
class CustomFilterSettingsValidationTest {

    @Test
    fun testQuickChipSelectionMode_togglesCorrectly_andClearsManualTextFields() {
        val state = CustomFilterFormState(
            initialConfig = CustomFilterConfig(
                mode = CustomFilterMode.CUSTOM_RANGE,
                minKw = 50,
                maxKw = 150
            )
        )
        assertEquals(CustomFilterMode.CUSTOM_RANGE, state.mode)
        assertEquals("50", state.minKwText)
        assertEquals("150", state.maxKwText)

        // Select a quick chip
        state.selectQuickChip(QuickChipOption.AC)

        assertEquals(CustomFilterMode.QUICK_CHIP, state.mode)
        assertEquals(QuickChipOption.AC, state.selectedChip)
        assertEquals("", state.minKwText)
        assertEquals("", state.maxKwText)
        assertNull(state.minKw)
        assertNull(state.maxKw)

        // Switch to another quick chip
        state.selectQuickChip(QuickChipOption.DC_GE_60KW)
        assertEquals(CustomFilterMode.QUICK_CHIP, state.mode)
        assertEquals(QuickChipOption.DC_GE_60KW, state.selectedChip)
        assertTrue(state.isValid)
    }

    @Test
    fun testTypingNumbersInMinOrMax_clearsQuickChipSelection() {
        val state = CustomFilterFormState(
            initialConfig = CustomFilterConfig(
                mode = CustomFilterMode.QUICK_CHIP,
                quickChip = QuickChipOption.DC_LE_30KW
            )
        )
        assertEquals(QuickChipOption.DC_LE_30KW, state.selectedChip)

        // Typing in Min kW
        state.onMinKwChanged("60")
        assertEquals(CustomFilterMode.CUSTOM_RANGE, state.mode)
        assertNull(state.selectedChip)
        assertEquals("60", state.minKwText)
        assertEquals(60, state.minKw)

        // Select quick chip again, then type in Max kW
        state.selectQuickChip(QuickChipOption.ALL)
        assertEquals(QuickChipOption.ALL, state.selectedChip)

        state.onMaxKwChanged("120")
        assertEquals(CustomFilterMode.CUSTOM_RANGE, state.mode)
        assertNull(state.selectedChip)
        assertEquals("120", state.maxKwText)
        assertEquals(120, state.maxKw)
    }

    @Test
    fun testClearMethods_clearsTextAndResetsStateToEmpty() {
        val state = CustomFilterFormState()
        state.onMinKwChanged("60")
        state.onMaxKwChanged("180")

        assertEquals(60, state.minKw)
        assertEquals(180, state.maxKw)

        // Clear min
        state.clearMinKw()
        assertEquals("", state.minKwText)
        assertNull(state.minKw)
        assertEquals(180, state.maxKw)

        // Clear max
        state.clearMaxKw()
        assertEquals("", state.maxKwText)
        assertNull(state.maxKw)

        // Digits-only filtering in input
        state.onMinKwChanged("abc45xyz!@#")
        assertEquals("45", state.minKwText)
        assertEquals(45, state.minKw)

        state.clearMinKw()
        assertEquals("", state.minKwText)
    }

    @Test
    fun testLivePreviewDynamicDescription_updatesAccurately() {
        val state = CustomFilterFormState()

        // Quick chip live preview formats
        state.selectQuickChip(QuickChipOption.ALL)
        assertEquals("👉 Đang lọc: Tất cả các trạm có cổng trống", state.livePreview)

        state.selectQuickChip(QuickChipOption.AC)
        assertEquals("👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống", state.livePreview)

        state.selectQuickChip(QuickChipOption.DC_LE_30KW)
        assertEquals("👉 Đang lọc: Cổng DC ≤ 30kW còn trống", state.livePreview)

        state.selectQuickChip(QuickChipOption.DC_BETWEEN_30_60KW)
        assertEquals("👉 Đang lọc: Cổng DC từ 30kW - 60kW còn trống", state.livePreview)

        state.selectQuickChip(QuickChipOption.DC_GE_60KW)
        assertEquals("👉 Đang lọc: Cổng DC ≥ 60kW còn trống", state.livePreview)

        state.selectQuickChip(QuickChipOption.DC_GE_120KW)
        assertEquals("👉 Đang lọc: Cổng DC ≥ 120kW còn trống", state.livePreview)

        // Manual range live preview formats
        state.onMinKwChanged("60")
        state.onMaxKwChanged("150")
        assertEquals("👉 Đang lọc: Cổng từ 60 kW đến 150 kW còn trống", state.livePreview)

        state.clearMaxKw()
        assertEquals("👉 Đang lọc: Cổng công suất ≥ 60 kW còn trống", state.livePreview)

        state.clearMinKw()
        state.onMaxKwChanged("150")
        assertEquals("👉 Đang lọc: Cổng công suất ≤ 150 kW còn trống", state.livePreview)

        state.clearMaxKw()
        assertEquals("👉 Đang lọc: Tất cả các trạm có cổng trống", state.livePreview)
    }

    @Test
    fun testValidationFails_andErrorIsDisplayed_whenMinGreaterThanMax() {
        val state = CustomFilterFormState()
        state.onMinKwChanged("120")
        state.onMaxKwChanged("60")

        assertFalse(state.isValid)
        assertEquals("Min không được lớn hơn Max", state.errorMessage)
        assertNull(state.buildConfig())
    }

    @Test
    fun testSaveAction_createsValidCustomFilterConfig() {
        val state = CustomFilterFormState()

        // 1. Min only
        state.onMinKwChanged("60")
        assertTrue(state.isValid)
        assertNull(state.errorMessage)
        val configMinOnly = state.buildConfig()
        assertNotNull(configMinOnly)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, configMinOnly?.mode)
        assertEquals(60, configMinOnly?.minKw)
        assertNull(configMinOnly?.maxKw)
        assertTrue(configMinOnly!!.isValid())

        // 2. Max only
        state.clearMinKw()
        state.onMaxKwChanged("150")
        assertTrue(state.isValid)
        assertNull(state.errorMessage)
        val configMaxOnly = state.buildConfig()
        assertNotNull(configMaxOnly)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, configMaxOnly?.mode)
        assertNull(configMaxOnly?.minKw)
        assertEquals(150, configMaxOnly?.maxKw)
        assertTrue(configMaxOnly!!.isValid())

        // 3. Min + Max
        state.onMinKwChanged("60")
        assertTrue(state.isValid)
        assertNull(state.errorMessage)
        val configMinMax = state.buildConfig()
        assertNotNull(configMinMax)
        assertEquals(CustomFilterMode.CUSTOM_RANGE, configMinMax?.mode)
        assertEquals(60, configMinMax?.minKw)
        assertEquals(150, configMinMax?.maxKw)
        assertTrue(configMinMax!!.isValid())

        // 4. Quick chip mode
        state.selectQuickChip(QuickChipOption.DC_GE_60KW)
        assertTrue(state.isValid)
        val configQuick = state.buildConfig()
        assertNotNull(configQuick)
        assertEquals(CustomFilterMode.QUICK_CHIP, configQuick?.mode)
        assertEquals(QuickChipOption.DC_GE_60KW, configQuick?.quickChip)
        assertTrue(configQuick!!.isValid())
    }

    @Test
    fun testValuesLessThanOrEqualToZeroOrGreaterThan500_rejectedByValidator() {
        val state = CustomFilterFormState()

        // min = 0
        state.onMinKwChanged("0")
        assertFalse(state.isValid)
        assertEquals("Min phải từ 1 đến 500 kW", state.errorMessage)
        assertNull(state.buildConfig())

        // min = 501
        state.onMinKwChanged("501")
        assertFalse(state.isValid)
        assertEquals("Min phải từ 1 đến 500 kW", state.errorMessage)
        assertNull(state.buildConfig())

        // valid min = 500
        state.onMinKwChanged("500")
        assertTrue(state.isValid)
        assertNull(state.errorMessage)

        // max = 0
        state.clearMinKw()
        state.onMaxKwChanged("0")
        assertFalse(state.isValid)
        assertEquals("Max phải từ 1 đến 500 kW", state.errorMessage)
        assertNull(state.buildConfig())

        // max = 501
        state.onMaxKwChanged("501")
        assertFalse(state.isValid)
        assertEquals("Max phải từ 1 đến 500 kW", state.errorMessage)
        assertNull(state.buildConfig())

        // min and max boundary valid: 1 and 500
        state.onMinKwChanged("1")
        state.onMaxKwChanged("500")
        assertTrue(state.isValid)
        assertNull(state.errorMessage)
        assertNotNull(state.buildConfig())

        // Both empty in CUSTOM_RANGE
        state.clearMinKw()
        state.clearMaxKw()
        assertFalse(state.isValid)
        assertEquals("Vui lòng nhập Min hoặc Max", state.errorMessage)
        assertNull(state.buildConfig())

        // Direct CustomFilterConfig domain model validation
        assertFalse(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 0).isValid())
        assertFalse(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = -5).isValid())
        assertFalse(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 501).isValid())
        assertFalse(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, maxKw = 0).isValid())
        assertFalse(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, maxKw = -1).isValid())
        assertFalse(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, maxKw = 600).isValid())
        assertFalse(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = null, maxKw = null).isValid())
        assertTrue(CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 1, maxKw = 500).isValid())
    }
}
