package com.evcs.favorites.ui.components

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.domain.model.isAc
import com.evcs.favorites.domain.model.matchesQuickChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive verification test for Phase 03:
 * UI Strings, Settings Live Preview, Wattage Options Alignment & Regression Verification.
 *
 * Verifies:
 * 1. Settings Live Preview string for QuickChipOption.AC matches "👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống".
 * 2. CustomFilterConfig summary for QuickChipOption.AC matches "Cổng AC (11kW, 22kW)".
 * 3. NearbyUiHelper.SORTED_WATTAGE_OPTIONS contains exactly 13 power tiers.
 * 4. SORTED_WATTAGE_OPTIONS strictly descends from 360kW down to 11kW (lowest tier is KW_11).
 * 5. Wattage option chip labels and toggle selections behave consistently.
 * 6. QuickChipOption.AC matches car AC ports (11kW, 22kW) and rejects motorbike/home AC ports (3.5kW, 7kW).
 */
class AcFilterUiAndSettingsSyncTest {

    @Test
    fun testAcFilterLivePreviewAndDisplaySummary() {
        val state = CustomFilterFormState()

        // 1. Live preview when QuickChipOption.AC is selected
        state.selectQuickChip(QuickChipOption.AC)
        assertEquals(
            "👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống",
            state.livePreview
        )

        // 2. BuildConfig produces valid custom filter config
        val config = state.buildConfig()
        assertNotNull(config)
        assertEquals(CustomFilterMode.QUICK_CHIP, config?.mode)
        assertEquals(QuickChipOption.AC, config?.quickChip)
        assertTrue(config!!.isValid())

        // 3. Domain display summary alignment
        assertEquals(
            "Cổng AC (11kW, 22kW)",
            config.toDisplaySummary()
        )

        // 4. Initial state with AC config
        val preloadedState = CustomFilterFormState(initialConfig = config)
        assertEquals(CustomFilterMode.QUICK_CHIP, preloadedState.mode)
        assertEquals(QuickChipOption.AC, preloadedState.selectedChip)
        assertEquals(
            "👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống",
            preloadedState.livePreview
        )
    }

    @Test
    fun testSortedWattageOptionsThirteenTiersDownTo11kW() {
        val sortedOptions = NearbyUiHelper.getSortedWattageOptions()

        // Exactly 13 car power tiers modeled
        assertEquals("Total count of SORTED_WATTAGE_OPTIONS must be exactly 13", 13, sortedOptions.size)

        // Upper and lower bounds
        assertEquals("Highest wattage tier must be 360kW", WattageOption.KW_360, sortedOptions.first())
        assertEquals("Lowest wattage tier must be 11kW", WattageOption.KW_11, sortedOptions.last())

        assertEquals("360kW", NearbyUiHelper.formatWattageChipLabel(sortedOptions.first()))
        assertEquals("11kW", NearbyUiHelper.formatWattageChipLabel(sortedOptions.last()))

        // Verify strictly descending power ratings
        for (i in 0 until sortedOptions.size - 1) {
            val current = sortedOptions[i]
            val next = sortedOptions[i + 1]
            assertTrue(
                "Tier ${current.label} (${current.watts}W) must be strictly greater than ${next.label} (${next.watts}W)",
                current.watts > next.watts
            )
        }

        val expectedTiers = listOf(
            WattageOption.KW_360 to "360kW",
            WattageOption.KW_300 to "300kW",
            WattageOption.KW_250 to "250kW",
            WattageOption.KW_180 to "180kW",
            WattageOption.KW_150 to "150kW",
            WattageOption.KW_120 to "120kW",
            WattageOption.KW_80 to "80kW",
            WattageOption.KW_60 to "60kW",
            WattageOption.KW_40 to "40kW",
            WattageOption.KW_30 to "30kW",
            WattageOption.KW_22 to "22kW",
            WattageOption.KW_20 to "20kW",
            WattageOption.KW_11 to "11kW"
        )

        for (i in expectedTiers.indices) {
            val (tier, label) = expectedTiers[i]
            assertEquals("Option at index $i mismatch", tier, sortedOptions[i])
            assertEquals("Label at index $i mismatch", label, NearbyUiHelper.formatWattageChipLabel(sortedOptions[i]))
        }
    }

    @Test
    fun testWattageFilterSelectionAndClearInteractions() {
        var activeWattages = emptySet<WattageOption>()
        assertFalse(NearbyUiHelper.isClearFiltersVisible(activeWattages))

        // Select lowest tier (11kW)
        activeWattages = NearbyUiHelper.toggleWattageSelection(activeWattages, WattageOption.KW_11)
        assertTrue(activeWattages.contains(WattageOption.KW_11))
        assertTrue(NearbyUiHelper.isClearFiltersVisible(activeWattages))

        // Select 22kW
        activeWattages = NearbyUiHelper.toggleWattageSelection(activeWattages, WattageOption.KW_22)
        assertEquals(setOf(WattageOption.KW_11, WattageOption.KW_22), activeWattages)

        // Clear selection
        activeWattages = NearbyUiHelper.clearWattageSelection()
        assertTrue(activeWattages.isEmpty())
        assertFalse(NearbyUiHelper.isClearFiltersVisible(activeWattages))
    }

    @Test
    fun testQuickChipAcClassificationConsistency() {
        val ac11Port = PowerPort(11_000L, "11kW", availablePlugs = 1, totalPlugs = 2)
        val ac22Port = PowerPort(22_000L, "22kW", availablePlugs = 1, totalPlugs = 2)
        val ac3_5Port = PowerPort(3_500L, "3.5kW", availablePlugs = 1, totalPlugs = 2)
        val ac7Port = PowerPort(7_000L, "7kW", availablePlugs = 1, totalPlugs = 2)
        val dc60Port = PowerPort(60_000L, "60kW", availablePlugs = 1, totalPlugs = 2)

        // Car AC compatibility
        assertTrue(ac11Port.isAc())
        assertTrue(ac22Port.isAc())
        assertFalse("3.5kW must not be classified as car AC", ac3_5Port.isAc())
        assertFalse("7kW must not be classified as car AC", ac7Port.isAc())
        assertFalse("60kW DC must not be classified as AC", dc60Port.isAc())

        // QuickChipOption.AC matches strictly car AC
        assertTrue(ac11Port.matchesQuickChip(QuickChipOption.AC))
        assertTrue(ac22Port.matchesQuickChip(QuickChipOption.AC))
        assertFalse(ac3_5Port.matchesQuickChip(QuickChipOption.AC))
        assertFalse(ac7Port.matchesQuickChip(QuickChipOption.AC))
        assertFalse(dc60Port.matchesQuickChip(QuickChipOption.AC))
    }
}
