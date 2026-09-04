package com.evcs.favorites.domain.filter

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.isAc
import com.evcs.favorites.domain.model.isDc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification unit test for Phase 01:
 * - AC/DC power classification rules (including 22kW AC vs DC).
 * - AC mode station filtering (free AC plugs vs occupied/missing).
 * - DC tier filtering (≤ 30kW, 30 - 60kW, ≥ 60kW, ≥ 120kW).
 * - Mixed station rule: 0/2 on 60kW DC and 2/2 on 11kW AC excluded when filtering DC ≥ 60kW.
 * - Custom filter Quick Chips (ALL, AC, DC tiers) and manual Min/Max ranges (Min only, Max only, Min+Max).
 * - CustomFilterConfig validation and toDisplaySummary() Vietnamese localized format strings.
 * - Exclusion of maintaining and out-of-service stations across all modes.
 * - DC mode without tier selection (unfiltered until tier is chosen).
 */
class NearbyStationSmartFilterTest {

    private fun createTestStation(
        id: String,
        name: String = "Station $id",
        depotStatus: String = "Normal",
        powers: List<PowerPort> = emptyList()
    ): Station {
        val totalAvailable = powers.sumOf { it.availablePlugs }
        val total = powers.sumOf { it.totalPlugs }
        return Station(
            id = id,
            name = name,
            address = "Address $id",
            latitude = 10.762622,
            longitude = 106.660172,
            summary = "Summary",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = depotStatus,
            powers = powers,
            totalAvailablePlugs = totalAvailable,
            totalPlugs = total
        )
    }

    private fun createPort(
        watts: Long,
        available: Int = 1,
        total: Int = 2,
        label: String = "${watts / 1000}kW"
    ): PowerPort {
        return PowerPort(
            typeWatts = watts,
            label = label,
            availablePlugs = available,
            totalPlugs = total,
            displayString = "$label: trống $available/$total cổng"
        )
    }

    @Test
    fun testAcAndDcClassification() {
        // Motorbike / home AC (3.5kW, 7kW, 7.4kW) and unrated are excluded from car AC
        assertFalse(createPort(3_500L, label = "3.5kW").isAc())
        assertFalse(createPort(7_000L, label = "7kW").isAc())
        assertFalse(createPort(7_400L, label = "7.4kW").isAc())
        assertTrue(createPort(11_000L, label = "11kW").isAc())
        assertTrue(createPort(22_000L, label = "22kW").isAc())
        assertFalse(createPort(0L, label = "AC Type 2").isAc())

        // 22kW AC must NOT be classified as DC
        assertFalse(createPort(22_000L, label = "22kW").isDc())

        // DC power ratings
        assertTrue(createPort(20_000L, label = "20kW").isDc())
        assertTrue(createPort(30_000L, label = "30kW").isDc())
        assertTrue(createPort(60_000L, label = "60kW").isDc())
        assertTrue(createPort(120_000L, label = "120kW").isDc())
        assertTrue(createPort(150_000L, label = "150kW").isDc())
        assertTrue(createPort(250_000L, label = "250kW").isDc())
        assertTrue(createPort(0L, label = "DC Fast").isDc())

        // DC must NOT be AC
        assertFalse(createPort(60_000L, label = "60kW").isAc())
    }

    @Test
    fun testAcFilterMode() {
        val st1 = createTestStation("st1", powers = listOf(createPort(11_000L, available = 2, total = 2)))
        val st2 = createTestStation("st2", powers = listOf(createPort(11_000L, available = 0, total = 2)))
        val st3 = createTestStation("st3", powers = listOf(createPort(60_000L, available = 2, total = 2)))
        val st4 = createTestStation("st4", powers = listOf(createPort(22_000L, available = 1, total = 2)))
        val st5 = createTestStation("st5", powers = emptyList())

        val result = NearbyStationFilter.filterSmartStations(
            stations = listOf(st1, st2, st3, st4, st5),
            mode = SmartFilterMode.AC
        )

        assertEquals(listOf("st1", "st4"), result.map { it.id })
    }

    @Test
    fun testDcTierFiltering() {
        val stLe30 = createTestStation("st_le30", powers = listOf(createPort(30_000L, available = 1, total = 2)))
        val stMid = createTestStation("st_mid", powers = listOf(createPort(60_000L, available = 1, total = 2)))
        val stGe60 = createTestStation("st_ge60", powers = listOf(createPort(80_000L, available = 1, total = 2)))
        val stGe120 = createTestStation("st_ge120", powers = listOf(createPort(150_000L, available = 1, total = 2)))
        val stAc22 = createTestStation("st_ac22", powers = listOf(createPort(22_000L, available = 1, total = 2)))

        val allStations = listOf(stLe30, stMid, stGe60, stGe120, stAc22)

        // ≤ 30kW: matches stLe30, but NEVER stAc22 (even though 22_000 <= 30_000, 22kW is AC)
        val le30Result = NearbyStationFilter.filterSmartStations(allStations, SmartFilterMode.DC, DcWattageTier.LE_30KW)
        assertEquals(listOf("st_le30"), le30Result.map { it.id })

        // 30 - 60kW: matches stLe30 (30kW) and stMid (60kW)
        val midResult = NearbyStationFilter.filterSmartStations(allStations, SmartFilterMode.DC, DcWattageTier.BETWEEN_30_60KW)
        assertEquals(listOf("st_le30", "st_mid"), midResult.map { it.id })

        // ≥ 60kW: matches stMid (60kW), stGe60 (80kW), stGe120 (150kW)
        val ge60Result = NearbyStationFilter.filterSmartStations(allStations, SmartFilterMode.DC, DcWattageTier.GE_60KW)
        assertEquals(listOf("st_mid", "st_ge60", "st_ge120"), ge60Result.map { it.id })

        // ≥ 120kW: matches stGe120 (150kW)
        val ge120Result = NearbyStationFilter.filterSmartStations(allStations, SmartFilterMode.DC, DcWattageTier.GE_120KW)
        assertEquals(listOf("st_ge120"), ge120Result.map { it.id })
    }

    @Test
    fun testMixedStationExclusionRule() {
        // Station with 0/2 on 60kW DC and 2/2 on 11kW AC
        val mixedStation = createTestStation(
            id = "mixed_0dc_2ac",
            powers = listOf(
                createPort(60_000L, available = 0, total = 2),
                createPort(11_000L, available = 2, total = 2)
            )
        )

        // In DC >= 60kW mode: must be EXCLUDED because matching 60kW DC has 0 free plugs
        val dcResult = NearbyStationFilter.filterSmartStations(
            stations = listOf(mixedStation),
            mode = SmartFilterMode.DC,
            dcTier = DcWattageTier.GE_60KW
        )
        assertTrue("Mixed station must be excluded when matching DC port is full", dcResult.isEmpty())

        // In AC mode: must be INCLUDED because matching 11kW AC has 2 free plugs
        val acResult = NearbyStationFilter.filterSmartStations(
            stations = listOf(mixedStation),
            mode = SmartFilterMode.AC
        )
        assertEquals(listOf("mixed_0dc_2ac"), acResult.map { it.id })

        // In NONE mode: must be INCLUDED because totalAvailablePlugs = 2 > 0
        val noneResult = NearbyStationFilter.filterSmartStations(
            stations = listOf(mixedStation),
            mode = SmartFilterMode.NONE
        )
        assertEquals(listOf("mixed_0dc_2ac"), noneResult.map { it.id })
    }

    @Test
    fun testCustomFilterWithQuickChips() {
        val stAc = createTestStation("st_ac", powers = listOf(createPort(11_000L, available = 1, total = 1)))
        val stDc30 = createTestStation("st_dc30", powers = listOf(createPort(30_000L, available = 1, total = 1)))
        val stDc60 = createTestStation("st_dc60", powers = listOf(createPort(60_000L, available = 1, total = 1)))
        val stDc120 = createTestStation("st_dc120", powers = listOf(createPort(120_000L, available = 1, total = 1)))
        val stFull = createTestStation("st_full", powers = listOf(createPort(120_000L, available = 0, total = 1)))

        val stations = listOf(stAc, stDc30, stDc60, stDc120, stFull)

        // ALL
        val allConfig = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.ALL)
        val allRes = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = allConfig)
        assertEquals(listOf("st_ac", "st_dc30", "st_dc60", "st_dc120"), allRes.map { it.id })

        // AC
        val acConfig = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.AC)
        val acRes = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = acConfig)
        assertEquals(listOf("st_ac"), acRes.map { it.id })

        // DC ≤ 30kW
        val dc30Config = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_LE_30KW)
        val dc30Res = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = dc30Config)
        assertEquals(listOf("st_dc30"), dc30Res.map { it.id })

        // DC 30 - 60kW
        val dcMidConfig = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_BETWEEN_30_60KW)
        val dcMidRes = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = dcMidConfig)
        assertEquals(listOf("st_dc30", "st_dc60"), dcMidRes.map { it.id })

        // DC ≥ 60kW
        val dc60Config = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_GE_60KW)
        val dc60Res = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = dc60Config)
        assertEquals(listOf("st_dc60", "st_dc120"), dc60Res.map { it.id })

        // DC ≥ 120kW
        val dc120Config = CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_GE_120KW)
        val dc120Res = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = dc120Config)
        assertEquals(listOf("st_dc120"), dc120Res.map { it.id })
    }

    @Test
    fun testCustomFilterWithManualMinMaxRanges() {
        val st11 = createTestStation("st11", powers = listOf(createPort(11_000L, available = 1, total = 1)))
        val st30 = createTestStation("st30", powers = listOf(createPort(30_000L, available = 1, total = 1)))
        val st60 = createTestStation("st60", powers = listOf(createPort(60_000L, available = 1, total = 1)))
        val st120 = createTestStation("st120", powers = listOf(createPort(120_000L, available = 1, total = 1)))
        val st250 = createTestStation("st250", powers = listOf(createPort(250_000L, available = 1, total = 1)))

        val stations = listOf(st11, st30, st60, st120, st250)

        // Range [60..150] kW
        val range60to150 = CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 60, maxKw = 150)
        assertTrue(range60to150.isValid())
        val resRange = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = range60to150)
        assertEquals(listOf("st60", "st120"), resRange.map { it.id })

        // Min only: ≥ 60 kW
        val min60 = CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 60, maxKw = null)
        assertTrue(min60.isValid())
        val resMin = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = min60)
        assertEquals(listOf("st60", "st120", "st250"), resMin.map { it.id })

        // Max only: ≤ 30 kW
        val max30 = CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = null, maxKw = 30)
        assertTrue(max30.isValid())
        val resMax = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = max30)
        assertEquals(listOf("st11", "st30"), resMax.map { it.id })

        // Range validation checks
        assertFalse("Min > Max should be invalid", CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 150, maxKw = 60).isValid())
        assertFalse("Min <= 0 should be invalid", CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 0, maxKw = 60).isValid())
        assertFalse("Max > 500 should be invalid", CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 10, maxKw = 501).isValid())
        assertFalse("Both null in custom range should be invalid", CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = null, maxKw = null).isValid())
    }

    @Test
    fun testCustomFilterToDisplaySummary() {
        // Quick Chips
        assertEquals(
            "Tất cả các trạm có cổng trống",
            CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.ALL).toDisplaySummary()
        )
        assertEquals(
            "Cổng AC (11kW, 22kW)",
            CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.AC).toDisplaySummary()
        )
        assertEquals(
            "Cổng DC công suất ≤ 30kW",
            CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_LE_30KW).toDisplaySummary()
        )
        assertEquals(
            "Cổng DC từ 30kW - 60kW",
            CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_BETWEEN_30_60KW).toDisplaySummary()
        )
        assertEquals(
            "Cổng DC công suất ≥ 60kW",
            CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_GE_60KW).toDisplaySummary()
        )
        assertEquals(
            "Cổng DC công suất ≥ 120kW",
            CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.DC_GE_120KW).toDisplaySummary()
        )

        // Custom Ranges
        assertEquals(
            "Cổng từ 60 kW đến 150 kW",
            CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 60, maxKw = 150).toDisplaySummary()
        )
        assertEquals(
            "Cổng công suất ≥ 60 kW",
            CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 60, maxKw = null).toDisplaySummary()
        )
        assertEquals(
            "Cổng công suất ≤ 30 kW",
            CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = null, maxKw = 30).toDisplaySummary()
        )
        assertEquals(
            "Tất cả các trạm có cổng trống",
            CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = null, maxKw = null).toDisplaySummary()
        )
    }

    @Test
    fun testMaintenanceAndOutOfServiceExcludedAcrossAllModes() {
        val maintainingSt = createTestStation(
            id = "maint",
            depotStatus = "Maintaining",
            powers = listOf(createPort(60_000L, available = 2, total = 2), createPort(11_000L, available = 2, total = 2))
        )
        val outOfServiceSt = createTestStation(
            id = "oos",
            depotStatus = "OutOfService",
            powers = listOf(createPort(60_000L, available = 2, total = 2), createPort(11_000L, available = 2, total = 2))
        )
        val normalSt = createTestStation(
            id = "normal",
            depotStatus = "Normal",
            powers = listOf(createPort(60_000L, available = 2, total = 2), createPort(11_000L, available = 2, total = 2))
        )

        val stations = listOf(maintainingSt, outOfServiceSt, normalSt)

        // NONE
        val resNone = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.NONE)
        assertEquals(listOf("normal"), resNone.map { it.id })

        // AC
        val resAc = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.AC)
        assertEquals(listOf("normal"), resAc.map { it.id })

        // DC
        val resDc = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.DC, DcWattageTier.GE_60KW)
        assertEquals(listOf("normal"), resDc.map { it.id })

        // CUSTOM
        val customConfig = CustomFilterConfig(mode = CustomFilterMode.CUSTOM_RANGE, minKw = 50, maxKw = 100)
        val resCustom = NearbyStationFilter.filterSmartStations(stations, SmartFilterMode.CUSTOM, customConfig = customConfig)
        assertEquals(listOf("normal"), resCustom.map { it.id })
    }

    @Test
    fun testDcModeWithoutTierIsUnfiltered() {
        val stAc = createTestStation("st_ac", powers = listOf(createPort(11_000L, available = 1, total = 1)))
        val stDc = createTestStation("st_dc", powers = listOf(createPort(60_000L, available = 1, total = 1)))
        val stFull = createTestStation("st_full", powers = listOf(createPort(60_000L, available = 0, total = 1)))

        // Entering DC mode without selecting a tier shows all available stations
        val result = NearbyStationFilter.filterSmartStations(
            stations = listOf(stAc, stDc, stFull),
            mode = SmartFilterMode.DC,
            dcTier = null
        )
        assertEquals(listOf("st_ac", "st_dc"), result.map { it.id })
    }
}
