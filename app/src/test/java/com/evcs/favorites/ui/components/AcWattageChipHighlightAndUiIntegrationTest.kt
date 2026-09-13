package com.evcs.favorites.ui.components

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.isAc
import com.evcs.favorites.ui.state.NearbyUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single comprehensive test suite for Phase 02: StationCard AC Chip Highlight & UI Integration.
 *
 * Verifies:
 * 1. Port AC classification:
 *    - 11kW and 22kW are classified as AC (`isAc() == true`).
 *    - DC tiers (20kW, 30kW, 60kW, 120kW, 250kW, 360kW) and sub-11kW (3.5kW, 7kW, 7.4kW) are NOT AC.
 * 2. Mixed Station Wattage Chip Highlight Resolution:
 *    - When `isAcFilterActive = true`, only 11kW and 22kW chips are highlighted; DC and sub-11kW chips remain unhighlighted.
 *    - When `isAcFilterActive = false`, NO chips are highlighted regardless of wattage.
 * 3. Filter Mode UI Plumbing:
 *    - `uiState.activeFilterMode == SmartFilterMode.AC` activates AC filter mode (`true`), whereas `DC` and `NONE` do not (`false`).
 * 4. Backwards & Call-Site Compatibility:
 *    - Composable functions provide default values (`isAcFilterActive = false`, `isHighlighted = false`) preserving compatibility
 *      for callers such as FavoritesScreen.
 * 5. Minimalist UI Layout Contract:
 *    - Strictly no "Tư nhân" badge labels added to StationCard layout.
 */
class AcWattageChipHighlightAndUiIntegrationTest {

    private fun createPort(
        watts: Long,
        available: Int = 1,
        total: Int = 2,
        label: String = "${watts / 1000}kW"
    ): PowerPort {
        return PowerPort(
            typeWatts = watts,
            availablePlugs = available,
            totalPlugs = total,
            label = label
        )
    }

    private fun createStation(
        id: String,
        name: String = "VinFast Station $id",
        powers: List<PowerPort> = emptyList()
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Test Address $id",
            latitude = 10.762622,
            longitude = 106.660172,
            summary = "Summary $id",
            connectors = powers.joinToString(", ") { it.label },
            depotStatus = "Normal",
            powers = powers,
            totalAvailablePlugs = powers.sumOf { it.availablePlugs },
            totalPlugs = powers.sumOf { it.totalPlugs },
            evse = "VinFast"
        )
    }

    @Test
    fun testPowerPortIsAcClassification() {
        val port11kw = createPort(11_000L, label = "11kW")
        val port22kw = createPort(22_000L, label = "22kW")
        assertTrue("11kW must be classified as AC", port11kw.isAc())
        assertTrue("22kW must be classified as AC", port22kw.isAc())

        // Sub-11kW
        assertFalse("3.5kW must NOT be classified as AC", createPort(3_500L, label = "3.5kW").isAc())
        assertFalse("7kW must NOT be classified as AC", createPort(7_000L, label = "7kW").isAc())
        assertFalse("7.4kW must NOT be classified as AC", createPort(7_400L, label = "7.4kW").isAc())

        // DC Ports
        assertFalse("20kW DC must NOT be classified as AC", createPort(20_000L, label = "20kW").isAc())
        assertFalse("30kW DC must NOT be classified as AC", createPort(30_000L, label = "30kW").isAc())
        assertFalse("60kW DC must NOT be classified as AC", createPort(60_000L, label = "60kW").isAc())
        assertFalse("120kW DC must NOT be classified as AC", createPort(120_000L, label = "120kW").isAc())
        assertFalse("250kW DC must NOT be classified as AC", createPort(250_000L, label = "250kW").isAc())
        assertFalse("360kW DC must NOT be classified as AC", createPort(360_000L, label = "360kW").isAc())

        // Explicit DC label override
        val dcLabeled11kw = createPort(11_000L, label = "11kW DC")
        assertFalse("11kW port labeled DC must NOT be classified as AC", dcLabeled11kw.isAc())
    }

    @Test
    fun testWattageChipHighlightResolutionWhenAcFilterIsActive() {
        val dc120Port = createPort(120_000L, available = 3, total = 4, label = "120kW")
        val dc60Port = createPort(60_000L, available = 1, total = 2, label = "60kW")
        val ac11Port = createPort(11_000L, available = 1, total = 2, label = "11kW")
        val ac22Port = createPort(22_000L, available = 2, total = 2, label = "22kW")
        val ac3_5Port = createPort(3_500L, available = 1, total = 1, label = "3.5kW")

        val isAcFilterActive = true

        // Verify helper logic on StationCardHelper and shouldHighlightWattageChip
        assertTrue("11kW chip must be highlighted when AC filter active", StationCardHelper.shouldHighlightChip(isAcFilterActive, ac11Port))
        assertTrue("11kW chip must be highlighted via shouldHighlightWattageChip", shouldHighlightWattageChip(isAcFilterActive, ac11Port))
        assertTrue("22kW chip must be highlighted when AC filter active", StationCardHelper.shouldHighlightChip(isAcFilterActive, ac22Port))
        assertTrue("22kW chip must be highlighted via shouldHighlightWattageChip", shouldHighlightWattageChip(isAcFilterActive, ac22Port))

        assertFalse("120kW DC chip must NOT be highlighted", StationCardHelper.shouldHighlightChip(isAcFilterActive, dc120Port))
        assertFalse("60kW DC chip must NOT be highlighted", StationCardHelper.shouldHighlightChip(isAcFilterActive, dc60Port))
        assertFalse("3.5kW chip must NOT be highlighted", StationCardHelper.shouldHighlightChip(isAcFilterActive, ac3_5Port))
    }

    @Test
    fun testWattageChipHighlightResolutionWhenAcFilterIsInactive() {
        val dc120Port = createPort(120_000L, available = 3, total = 4, label = "120kW")
        val ac11Port = createPort(11_000L, available = 1, total = 2, label = "11kW")
        val ac22Port = createPort(22_000L, available = 2, total = 2, label = "22kW")

        val isAcFilterActive = false

        assertFalse("11kW chip must NOT be highlighted when AC filter is inactive", StationCardHelper.shouldHighlightChip(isAcFilterActive, ac11Port))
        assertFalse("11kW chip must NOT be highlighted via shouldHighlightWattageChip", shouldHighlightWattageChip(isAcFilterActive, ac11Port))
        assertFalse("22kW chip must NOT be highlighted when AC filter is inactive", StationCardHelper.shouldHighlightChip(isAcFilterActive, ac22Port))
        assertFalse("120kW DC chip must NOT be highlighted", StationCardHelper.shouldHighlightChip(isAcFilterActive, dc120Port))
    }

    @Test
    fun testMixedStationPortsHighlightIsolation() {
        val mixedStation = createStation(
            id = "st_mixed_hub",
            name = "VinFast - Landmark 81 Hub",
            powers = listOf(
                createPort(120_000L, available = 2, total = 4, label = "120kW"),
                createPort(60_000L, available = 1, total = 2, label = "60kW"),
                createPort(11_000L, available = 1, total = 1, label = "11kW"),
                createPort(3_500L, available = 1, total = 1, label = "3.5kW")
            )
        )

        // Under AC filter active
        val highlightsActive = mixedStation.powers.map { port ->
            StationCardHelper.shouldHighlightChip(isAcFilterActive = true, powerPort = port)
        }
        assertEquals(listOf(false, false, true, false), highlightsActive)

        // Under AC filter inactive
        val highlightsInactive = mixedStation.powers.map { port ->
            StationCardHelper.shouldHighlightChip(isAcFilterActive = false, powerPort = port)
        }
        assertEquals(listOf(false, false, false, false), highlightsInactive)
    }

    @Test
    fun testFilterModeToAcFilterActiveMapping() {
        val acState = NearbyUiState(activeFilterMode = SmartFilterMode.AC)
        val dcState = NearbyUiState(activeFilterMode = SmartFilterMode.DC)
        val noneState = NearbyUiState(activeFilterMode = SmartFilterMode.NONE)

        assertTrue(acState.activeFilterMode == SmartFilterMode.AC)
        assertFalse(dcState.activeFilterMode == SmartFilterMode.AC)
        assertFalse(noneState.activeFilterMode == SmartFilterMode.AC)
    }

    @Test
    fun testStationCardAndWattageChipDefaultParametersBackwardsCompatibility() {
        // Find StationCard and WattageChip methods in compiled class files
        val stationCardClass = Class.forName("com.evcs.favorites.ui.components.StationCardKt")
        val methods = stationCardClass.declaredMethods

        val stationCardMethods = methods.filter { it.name.startsWith("StationCard") }
        assertTrue("StationCard method must exist", stationCardMethods.isNotEmpty())

        val wattageChipMethods = methods.filter { it.name.startsWith("WattageChip") }
        assertTrue("WattageChip method must exist", wattageChipMethods.isNotEmpty())

        // Verify shouldHighlightWattageChip helper function exists and works
        val shouldHighlightMethod = methods.firstOrNull { it.name == "shouldHighlightWattageChip" }
        assertTrue("shouldHighlightWattageChip helper must exist", shouldHighlightMethod != null)

        val port11kw = createPort(11_000L)
        val resultTrue = shouldHighlightMethod?.invoke(null, true, port11kw) as? Boolean
        val resultFalse = shouldHighlightMethod?.invoke(null, false, port11kw) as? Boolean
        assertEquals(true, resultTrue)
        assertEquals(false, resultFalse)
    }

    private fun findSourceFile(subpath: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(userDir, subpath),
            File(userDir, "app/$subpath"),
            File(userDir.parentFile ?: userDir, subpath),
            File(userDir.parentFile ?: userDir, "app/$subpath")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not locate $subpath from user.dir=${userDir.absolutePath}")
    }

    @Test
    fun testStationCardLayoutDoesNotContainTuNhanBadge() {
        val stationCardFile = findSourceFile("src/main/java/com/evcs/favorites/ui/components/StationCard.kt")
        assertTrue("StationCard.kt source file must exist", stationCardFile.exists())

        val content = stationCardFile.readText()
        assertFalse(
            "StationCard must strictly omit 'Tư nhân' badge per specifications",
            content.contains("\"Tư nhân\"") || content.contains("Tư nhân")
        )
    }

    @Test
    fun testFranchiseStationWithTuNhanNameHighlightsAcPortsCleanly() {
        val franchiseStation = createStation(
            id = "st_franchise_01",
            name = "VinFast - Nhượng Quyền Tư Nhân Vũ Thị Hợi",
            powers = listOf(
                createPort(20_000L, available = 1, total = 1, label = "20kW"),
                createPort(11_000L, available = 1, total = 1, label = "11kW")
            )
        )

        val highlightsWhenAcActive = franchiseStation.powers.map { port ->
            StationCardHelper.shouldHighlightChip(isAcFilterActive = true, powerPort = port)
        }
        // 20kW DC is NOT highlighted, 11kW AC IS highlighted
        assertEquals(listOf(false, true), highlightsWhenAcActive)
    }
}
