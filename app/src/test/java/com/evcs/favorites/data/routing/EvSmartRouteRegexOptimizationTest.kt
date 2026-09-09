package com.evcs.favorites.data.routing

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 03:
 * Pre-compiled Static Regexes & Power Extraction Deduplication.
 *
 * Verifies:
 * 1. extractStationMaxPowerKw accurately extracts power across all variations
 *    (raw typeWatts, integer kW, decimal kW, "DC"/"Super" heuristics, default AC fallback).
 * 2. Detour distance extraction in evaluateHighwayDetour correctly matches highway detour patterns
 *    case-insensitively using the static pre-compiled regex.
 * 3. Parity assertion: extractMaxPowerKw and extractStationMaxPowerKw produce identical outputs across all test stations.
 * 4. High-frequency benchmark: 10,000 successive invocations execute rapidly (< 50ms) with zero per-call regex compilation.
 */
class EvSmartRouteRegexOptimizationTest {

    private lateinit var planner: EvSmartRoutePlanner

    @Before
    fun setUp() {
        planner = EvSmartRoutePlanner()
    }

    private fun createStation(
        id: String = "station_1",
        name: String = "Test Station",
        summary: String = "",
        connectors: String = "CCS2",
        powers: List<PowerPort> = emptyList()
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "123 Test Street",
            latitude = 21.0,
            longitude = 105.8,
            summary = summary,
            connectors = connectors,
            depotStatus = "Normal",
            powers = powers
        )
    }

    @Test
    fun testPowerExtraction_allVariations() {
        // 1. Raw typeWatts takes precedence
        val rawWattsStation = createStation(
            powers = listOf(
                PowerPort(typeWatts = 150_000L, label = "Fast DC", availablePlugs = 2, totalPlugs = 2, displayString = "150kW"),
                PowerPort(typeWatts = 250_000L, label = "Ultra DC", availablePlugs = 1, totalPlugs = 1, displayString = "250kW")
            )
        )
        assertEquals(250.0, extractStationMaxPowerKw(rawWattsStation), 1e-4)

        // 2. Text patterns: Integer kW ("60 kW", "30kW", "250 kW")
        val intKwStation = createStation(
            name = "Trạm sạc VinFast 60 kW",
            summary = "Trụ sạc 30kW và 250 kW",
            powers = listOf(PowerPort(typeWatts = 0L, label = "Trụ sạc", availablePlugs = 1, totalPlugs = 1, displayString = ""))
        )
        assertEquals(250.0, extractStationMaxPowerKw(intKwStation), 1e-4)

        // 3. Text patterns: Decimal kW ("120.5kw", "43.5 kW")
        val decimalKwStation = createStation(
            name = "Trạm sạc 43.5 kW",
            summary = "Trụ nâng cấp 120.5kw siêu nhanh",
            powers = emptyList()
        )
        assertEquals(120.5, extractStationMaxPowerKw(decimalKwStation), 1e-4)

        // 4. Heuristic: "DC" keyword default (60.0 kW)
        val dcHeuristicStation = createStation(
            name = "Trạm sạc DC cao tốc",
            summary = "Sạc nhanh VinFast",
            powers = emptyList()
        )
        assertEquals(60.0, extractStationMaxPowerKw(dcHeuristicStation), 1e-4)

        // 5. Heuristic: "Super" keyword default (60.0 kW)
        val superHeuristicStation = createStation(
            name = "Supercharger Station",
            summary = "Điểm sạc nhanh",
            powers = emptyList()
        )
        assertEquals(60.0, extractStationMaxPowerKw(superHeuristicStation), 1e-4)

        // 6. Default fallback: AC / unspecified plugs -> 11.0 kW
        val fallbackStation = createStation(
            name = "Trạm sạc chậm",
            summary = "Sạc xe máy điện và ô tô qua đêm",
            powers = emptyList()
        )
        assertEquals(11.0, extractStationMaxPowerKw(fallbackStation), 1e-4)
    }

    @Test
    fun testHighwayDetourRegex_caseInsensitiveMatching() {
        // Detour pattern: "detour: 4.5km" (> 3.0 km default threshold)
        val stationExceeds = createStation(
            name = "Trạm dừng nghỉ",
            summary = "Trạm nằm bên kia đường, detour: 4.5km"
        )
        val resultExceeds = planner.evaluateHighwayDetour(stationExceeds, perpendicularDistanceKm = 0.5)
        assertTrue("Detour > 3.0km should be flagged as highway trap", resultExceeds.first)
        assertEquals(4.5, resultExceeds.second, 1e-4)

        // Case insensitivity: "DETOUR: 5.2 KM"
        val stationUpper = createStation(
            name = "Trạm Cao Tốc",
            summary = "Lối vào DETOUR: 5.2 KM quay đầu"
        )
        val resultUpper = planner.evaluateHighwayDetour(stationUpper, perpendicularDistanceKm = 0.8)
        assertTrue("Case-insensitive DETOUR should be recognized", resultUpper.first)
        assertEquals(5.2, resultUpper.second, 1e-4)

        // Mixed case and spacing: "Detour 1.5 km" (<= 3.0 km threshold -> not a trap)
        val stationWithinThreshold = createStation(
            name = "Trạm Ven Đường",
            summary = "Đoạn detour 1.5 km rẽ phải"
        )
        val resultWithin = planner.evaluateHighwayDetour(stationWithinThreshold, perpendicularDistanceKm = 0.6)
        assertFalse("Detour <= 3.0km should not be a trap", resultWithin.first)
        // Perpendicular distance is returned when not a trap
        assertEquals(0.6, resultWithin.second, 1e-4)
    }

    @Test
    fun testParity_extractMaxPowerKw_and_extractStationMaxPowerKw() {
        val testStations = listOf(
            createStation(powers = listOf(PowerPort(typeWatts = 180_000L, label = "180kW", availablePlugs = 1, totalPlugs = 1, displayString = "180kW"))),
            createStation(powers = listOf(PowerPort(typeWatts = 60_000L, label = "60kW", availablePlugs = 1, totalPlugs = 1, displayString = "60kW"))),
            createStation(name = "Station 120kW fast", summary = "Power 250 kW"),
            createStation(name = "Station with 80.5 kw charger"),
            createStation(name = "DC Fast Charger"),
            createStation(name = "Super Charge Hub"),
            createStation(name = "Standard AC wallbox")
        )

        for (st in testStations) {
            val plannerPower = planner.extractMaxPowerKw(st)
            val modelPower = extractStationMaxPowerKw(st)
            assertEquals("Power extraction parity failed for station: ${st.name}", modelPower, plannerPower, 1e-4)
        }
    }

    @Test
    fun testBenchmark_highFrequencyExecution() {
        val testStation = createStation(
            name = "Trạm sạc cao tốc 180.5 kW",
            summary = "Detour: 4.2 km quay đầu",
            powers = listOf(PowerPort(typeWatts = 0L, label = "180.5kW", availablePlugs = 1, totalPlugs = 1, displayString = "Trụ 180.5 kW"))
        )

        // Warmup JIT compiler
        repeat(10_000) {
            extractStationMaxPowerKw(testStation)
        }

        // Benchmark 10,000 successive invocations of extractStationMaxPowerKw
        val startTime = System.nanoTime()
        repeat(10_000) {
            extractStationMaxPowerKw(testStation)
        }
        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertTrue("10,000 invocations should execute in < 50ms, took ${durationMs}ms", durationMs < 50.0)
    }
}
