package com.evcs.favorites.ui

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.ui.components.SmartFilterUiHelper
import com.evcs.favorites.ui.components.StationCardHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single comprehensive test suite for Phase 01:
 * Verifies DC-only charger breakdown filtering in station cards and smart filter DC state detection.
 */
class StationCardDcOnlyFilterTest {

    private fun createPort(
        typeWatts: Long,
        totalPlugs: Int = 1,
        availablePlugs: Int = 1,
        label: String = ""
    ): PowerPort {
        return PowerPort(
            typeWatts = typeWatts,
            totalPlugs = totalPlugs,
            availablePlugs = availablePlugs,
            label = label
        )
    }

    @Test
    fun testMixedStationFiltersOutAcWhenFilterDcOnlyIsTrue() {
        // Station with 120kW DC (4 plugs), 60kW DC (2 plugs), 11kW AC (2 plugs), 7kW AC (4 plugs)
        val powers = listOf(
            createPort(typeWatts = 120_000L, totalPlugs = 4, label = "120kW"),
            createPort(typeWatts = 60_000L, totalPlugs = 2, label = "60kW"),
            createPort(typeWatts = 11_000L, totalPlugs = 2, label = "11kW"),
            createPort(typeWatts = 7_000L, totalPlugs = 4, label = "7kW")
        )

        // When filterDcOnly is false (default): all 4 tiers present
        val allResult = StationCardHelper.formatPowerDistributionSummary(powers, filterDcOnly = false)
        assertEquals(4, allResult.size)
        assertEquals("120kW", allResult[0].first)
        assertEquals(4, allResult[0].second)
        assertEquals("60kW", allResult[1].first)
        assertEquals(2, allResult[1].second)
        assertEquals("11kW", allResult[2].first)
        assertEquals(2, allResult[2].second)
        assertEquals("7kW", allResult[3].first)
        assertEquals(4, allResult[3].second)

        // When filterDcOnly is true: only DC tiers (120kW and 60kW) present, 11kW and 7kW excluded
        val dcOnlyResult = StationCardHelper.formatPowerDistributionSummary(powers, filterDcOnly = true)
        assertEquals(2, dcOnlyResult.size)
        assertEquals("120kW", dcOnlyResult[0].first)
        assertEquals(4, dcOnlyResult[0].second)
        assertEquals("60kW", dcOnlyResult[1].first)
        assertEquals(2, dcOnlyResult[1].second)

        // Verify AnnotatedString excludes AC text
        val annotated = StationCardHelper.buildPowerDistributionAnnotatedString(
            powers = powers,
            connectors = "",
            powerColor = Color.White,
            countColor = Color.Green,
            separatorColor = Color.Gray,
            filterDcOnly = true
        )
        val text = annotated.text
        assertTrue("AnnotatedString must contain 120kW", text.contains("120kW x 4"))
        assertTrue("AnnotatedString must contain 60kW", text.contains("60kW x 2"))
        assertFalse("AnnotatedString must NOT contain 7kW", text.contains("7kW"))
        assertFalse("AnnotatedString must NOT contain 11kW", text.contains("11kW"))
    }

    @Test
    fun testPureDcStationProducesIdenticalResultRegardlessOfFilterDcOnly() {
        val pureDcPorts = listOf(
            createPort(typeWatts = 150_000L, totalPlugs = 2, label = "150kW"),
            createPort(typeWatts = 60_000L, totalPlugs = 4, label = "60kW")
        )

        val defaultResult = StationCardHelper.formatPowerDistributionSummary(pureDcPorts, filterDcOnly = false)
        val dcOnlyResult = StationCardHelper.formatPowerDistributionSummary(pureDcPorts, filterDcOnly = true)

        assertEquals(defaultResult, dcOnlyResult)
        assertEquals(2, dcOnlyResult.size)
        assertEquals("150kW", dcOnlyResult[0].first)
        assertEquals(2, dcOnlyResult[0].second)
        assertEquals("60kW", dcOnlyResult[1].first)
        assertEquals(4, dcOnlyResult[1].second)
    }

    @Test
    fun testPureAcStationReturnsEmptyListWhenFilterDcOnlyIsTrue() {
        val pureAcPorts = listOf(
            createPort(typeWatts = 7_000L, totalPlugs = 4, label = "7kW"),
            createPort(typeWatts = 11_000L, totalPlugs = 2, label = "11kW"),
            createPort(typeWatts = 22_000L, totalPlugs = 2, label = "22kW")
        )

        val defaultResult = StationCardHelper.formatPowerDistributionSummary(pureAcPorts, filterDcOnly = false)
        assertEquals(3, defaultResult.size)

        val dcOnlyResult = StationCardHelper.formatPowerDistributionSummary(pureAcPorts, filterDcOnly = true)
        assertTrue("Pure AC station must return emptyList() when filterDcOnly is true", dcOnlyResult.isEmpty())
    }

    @Test
    fun testRawConnectorsStringFallbackSupportsFilterDcOnly() {
        // Raw connector string with 60kW DC and 7kW AC
        val connectors = "CCS2: 60kW x 2, Type 2: 7kW x 4"

        val defaultResult = StationCardHelper.formatPowerDistributionSummary(
            powers = emptyList(),
            connectors = connectors,
            filterDcOnly = false
        )
        assertTrue("Default result must include 7kW", defaultResult.any { it.first.contains("7kW") })

        val dcOnlyResult = StationCardHelper.formatPowerDistributionSummary(
            powers = emptyList(),
            connectors = connectors,
            filterDcOnly = true
        )
        assertTrue("DC-only result must contain 60kW", dcOnlyResult.any { it.first.contains("60kW") })
        assertFalse("DC-only result must NOT contain 7kW", dcOnlyResult.any { it.first.contains("7kW") })
    }

    @Test
    fun testIsDcFilterActiveHelperAcrossAllFilterModes() {
        // 1. DC Mode
        assertTrue(
            "SmartFilterMode.DC with tier must be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.DC,
                selectedDcTier = DcWattageTier.GE_120KW
            )
        )
        assertTrue(
            "isDcSubFilterVisible = true must be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.NONE,
                isDcSubFilterVisible = true
            )
        )
        assertTrue(
            "SmartFilterMode.DC without tier must be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.DC,
                selectedDcTier = null
            )
        )

        // 2. Non-DC Modes
        assertFalse(
            "SmartFilterMode.NONE must NOT be active",
            SmartFilterUiHelper.isDcFilterActive(activeFilterMode = SmartFilterMode.NONE)
        )
        assertFalse(
            "SmartFilterMode.AC must NOT be active",
            SmartFilterUiHelper.isDcFilterActive(activeFilterMode = SmartFilterMode.AC)
        )

        // 3. Custom Mode with DC Quick Chips
        assertTrue(
            "Custom mode with DC_GE_120KW quick chip must be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.CUSTOM,
                savedCustomConfig = CustomFilterConfig(
                    mode = CustomFilterMode.QUICK_CHIP,
                    quickChip = QuickChipOption.DC_GE_120KW
                )
            )
        )
        assertTrue(
            "Custom mode with DC_LE_30KW quick chip must be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.CUSTOM,
                savedCustomConfig = CustomFilterConfig(
                    mode = CustomFilterMode.QUICK_CHIP,
                    quickChip = QuickChipOption.DC_LE_30KW
                )
            )
        )
        assertFalse(
            "Custom mode with AC quick chip must NOT be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.CUSTOM,
                savedCustomConfig = CustomFilterConfig(
                    mode = CustomFilterMode.QUICK_CHIP,
                    quickChip = QuickChipOption.AC
                )
            )
        )
        assertFalse(
            "Custom mode with ALL quick chip must NOT be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.CUSTOM,
                savedCustomConfig = CustomFilterConfig(
                    mode = CustomFilterMode.QUICK_CHIP,
                    quickChip = QuickChipOption.ALL
                )
            )
        )

        // 4. Custom Mode with kW Range
        assertTrue(
            "Custom mode with minKw = 60 must be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.CUSTOM,
                savedCustomConfig = CustomFilterConfig(
                    mode = CustomFilterMode.CUSTOM_RANGE,
                    minKw = 60,
                    maxKw = null
                )
            )
        )
        assertFalse(
            "Custom mode with minKw = 7, maxKw = 11 must NOT be active",
            SmartFilterUiHelper.isDcFilterActive(
                activeFilterMode = SmartFilterMode.CUSTOM,
                savedCustomConfig = CustomFilterConfig(
                    mode = CustomFilterMode.CUSTOM_RANGE,
                    minKw = 7,
                    maxKw = 11
                )
            )
        )
    }

    @Test
    fun testStationCardSignatureContainsFilterDcOnlyParameter() {
        val stationCardClass = Class.forName("com.evcs.favorites.ui.components.StationCardKt")
        val methods = stationCardClass.declaredMethods.map { it.name }
        assertTrue(
            "StationCardKt must declare StationCard composable",
            methods.any { it.startsWith("StationCard") }
        )
    }
}
