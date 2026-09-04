package com.evcs.favorites.domain.filter

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.domain.model.isAc
import com.evcs.favorites.domain.model.matchesCustomRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification unit test for Phase 01:
 * - Restrict AC charging classification strictly to 11kW and 22kW tiers.
 * - Exclude 3.5kW, 7kW, 7.4kW from isAc().
 * - Reject unrated (typeWatts <= 0L) ports from AC classification even if labeled "AC" / "Type 2".
 * - Preserve raw numeric matching for matchesCustomRange(minKw, maxKw).
 * - Verify CustomFilterConfig.toDisplaySummary() for QuickChipOption.AC displays "Cổng AC (11kW, 22kW)".
 * - Verify WattageOption contains exactly 13 car power tiers (360kW down to 11kW) without 3.5kW / 7kW.
 * - Verify WattageOption.fromWatts(7000L / 3500L) returns null.
 */
class AcPortClassificationAndWattageTierTest {

    @Test
    fun testAcStandardTiers_11kwAnd22kw_classifiedAsAc() {
        val port11kw = PowerPort(typeWatts = 11_000L, label = "11kW")
        val port22kw = PowerPort(typeWatts = 22_000L, label = "22kW")

        assertTrue("11kW port must be classified as AC", port11kw.isAc())
        assertTrue("22kW port must be classified as AC", port22kw.isAc())
    }

    @Test
    fun testLowPowerPorts_3_5kwAnd7kwAnd7_4kw_excludedFromAc() {
        val port3_5kw = PowerPort(typeWatts = 3_500L, label = "3.5kW")
        val port7kw = PowerPort(typeWatts = 7_000L, label = "7kW")
        val port7_4kw = PowerPort(typeWatts = 7_400L, label = "7.4kW")

        assertFalse("3.5kW port must NOT be classified as AC", port3_5kw.isAc())
        assertFalse("7kW port must NOT be classified as AC", port7kw.isAc())
        assertFalse("7.4kW port must NOT be classified as AC", port7_4kw.isAc())
    }

    @Test
    fun testLowPowerPortsWithAcLabel_stillExcludedFromAc() {
        val port3_5kwAc = PowerPort(typeWatts = 3_500L, label = "AC 3.5kW")
        val port7kwAc = PowerPort(typeWatts = 7_000L, label = "AC 7kW Type 2")

        assertFalse("3.5kW port labeled AC must NOT be classified as AC", port3_5kwAc.isAc())
        assertFalse("7kW port labeled AC must NOT be classified as AC", port7kwAc.isAc())
    }

    @Test
    fun testUnratedPort_typeWattsZeroOrNegative_rejectedFromAc() {
        val portZeroWattsWithAc = PowerPort(typeWatts = 0L, label = "AC Type 2")
        val portZeroWattsPlain = PowerPort(typeWatts = 0L, label = "AC")
        val portNegativeWatts = PowerPort(typeWatts = -100L, label = "AC")

        assertFalse("Unrated 0W port must NOT be classified as AC even if labeled AC Type 2", portZeroWattsWithAc.isAc())
        assertFalse("Unrated 0W port must NOT be classified as AC", portZeroWattsPlain.isAc())
        assertFalse("Negative wattage port must NOT be classified as AC", portNegativeWatts.isAc())
    }

    @Test
    fun testDcLabel_overridesAcStandardWatts() {
        val dcLabeled11kw = PowerPort(typeWatts = 11_000L, label = "DC 11kW")
        val dcLabeled22kw = PowerPort(typeWatts = 22_000L, label = "DC 22kW")

        assertFalse("Port labeled DC must NOT be classified as AC even with 11kW rating", dcLabeled11kw.isAc())
        assertFalse("Port labeled DC must NOT be classified as AC even with 22kW rating", dcLabeled22kw.isAc())
    }

    @Test
    fun testMatchesCustomRange_preservesRawNumericMatching() {
        val port3_5kw = PowerPort(typeWatts = 3_500L, label = "3.5kW")
        val port7kw = PowerPort(typeWatts = 7_000L, label = "7kW")
        val port11kw = PowerPort(typeWatts = 11_000L, label = "11kW")
        val portUnrated = PowerPort(typeWatts = 0L, label = "0kW")

        // Filter range: 3kW - 7kW
        assertTrue("3.5kW port matches range 3kW..7kW", port3_5kw.matchesCustomRange(minKw = 3, maxKw = 7))
        assertTrue("7kW port matches range 3kW..7kW", port7kw.matchesCustomRange(minKw = 3, maxKw = 7))
        assertFalse("11kW port exceeds range 3kW..7kW", port11kw.matchesCustomRange(minKw = 3, maxKw = 7))
        assertFalse("Unrated port does not match numeric range", portUnrated.matchesCustomRange(minKw = 3, maxKw = 7))

        // Open min / max ranges
        assertTrue("7kW port matches range <= 10kW", port7kw.matchesCustomRange(minKw = null, maxKw = 10))
        assertTrue("7kW port matches range >= 5kW", port7kw.matchesCustomRange(minKw = 5, maxKw = null))
    }

    @Test
    fun testCustomFilterConfig_acDisplaySummary() {
        val acConfig = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = QuickChipOption.AC
        )
        assertEquals("Cổng AC (11kW, 22kW)", acConfig.toDisplaySummary())
    }

    @Test
    fun testWattageOption_entriesCountAndBoundaries() {
        val entries = WattageOption.entries
        assertEquals("WattageOption must contain exactly 13 car power tiers", 13, entries.size)
        assertEquals("Highest tier must be 360kW", WattageOption.KW_360, entries.first())
        assertEquals("Lowest tier must be 11kW", WattageOption.KW_11, entries.last())

        // Verify all 13 tiers are present
        val expectedTiers = listOf(
            WattageOption.KW_360,
            WattageOption.KW_300,
            WattageOption.KW_250,
            WattageOption.KW_180,
            WattageOption.KW_150,
            WattageOption.KW_120,
            WattageOption.KW_80,
            WattageOption.KW_60,
            WattageOption.KW_40,
            WattageOption.KW_30,
            WattageOption.KW_20,
            WattageOption.KW_22,
            WattageOption.KW_11
        )
        assertEquals(expectedTiers, entries)
    }

    @Test
    fun testWattageOption_fromWatts_resolvesCorrectlyOrNull() {
        assertNull("7kW (7,000W) should not map to any WattageOption", WattageOption.fromWatts(7_000L))
        assertNull("7.4kW (7,400W) should not map to any WattageOption", WattageOption.fromWatts(7_400L))
        assertNull("3.5kW (3,500W) should not map to any WattageOption", WattageOption.fromWatts(3_500L))

        assertEquals(WattageOption.KW_11, WattageOption.fromWatts(11_000L))
        assertEquals(WattageOption.KW_22, WattageOption.fromWatts(22_000L))
        assertEquals(WattageOption.KW_360, WattageOption.fromWatts(360_000L))
    }
}
