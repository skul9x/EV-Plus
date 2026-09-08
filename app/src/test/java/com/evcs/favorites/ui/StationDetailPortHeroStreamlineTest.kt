package com.evcs.favorites.ui

import com.evcs.favorites.domain.StationPortStatus
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Remove Redundant "Cổng sạc" Label & Compact Hero Section.
 *
 * Verifies:
 * 1. Source code contract in NativeStationDetailSheet.kt strictly verifies that the redundant
 *    static "Cổng sạc" label has been completely eliminated.
 * 2. The charging ports section layout retains FlowRow with 8dp spacing and renders PortStatusPill.
 * 3. Empty state fallback text ("Đang cập nhật danh sách cổng sạc...") is preserved.
 * 4. PortStatusPill badge resolution contract independently communicates full port details
 *    (power kW and available/total counts), proving the section header was redundant.
 */
class StationDetailPortHeroStreamlineTest {

    private fun resolveFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        val parent = File("../$relativePath")
        if (parent.exists()) return parent
        return direct
    }

    @Test
    fun testPhase01_RedundantPortLabelRemoved_AndLayoutContractRetained() {
        // 1. Locate NativeStationDetailSheet.kt
        val sheetFile = resolveFile("app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt")
        assertTrue("NativeStationDetailSheet.kt must exist", sheetFile.exists())

        val sheetContent = sheetFile.readText()

        // 2. Strict Verification: Static "Cổng sạc" label must NOT exist in NativeStationDetailSheet
        assertFalse(
            "NativeStationDetailSheet must not contain static 'Cổng sạc' text header",
            sheetContent.contains("\"Cổng sạc\"")
        )
        assertFalse(
            "NativeStationDetailSheet must not contain Text composable for Cổng sạc",
            sheetContent.contains("text = \"Cổng sạc\"")
        )

        // 3. FlowRow & Pill Layout Contract Retained
        assertTrue(
            "Charging ports section must retain FlowRow for port pill wrapping",
            sheetContent.contains("FlowRow(")
        )
        assertTrue(
            "PortStatusPill must be used to render port statuses",
            sheetContent.contains("PortStatusPill(")
        )
        assertTrue(
            "FlowRow must maintain 8.dp horizontal and vertical spacing for automotive touch and glanceability",
            sheetContent.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)") &&
                sheetContent.contains("verticalArrangement = Arrangement.spacedBy(8.dp)")
        )

        // 4. Empty State Fallback Contract Retained
        assertTrue(
            "Empty state fallback message must be preserved when portStatuses is empty",
            sheetContent.contains("\"Đang cập nhật danh sách cổng sạc...\"")
        )

        // 5. Functional Port Badge Contract: Port pills are fully self-describing without a title header
        val normalPort = StationPortStatus(
            kw = 60,
            availablePorts = 1,
            totalPorts = 2,
            busyCount = 1
        )
        val normalBadge = NativeStationDetailSheetHelper.resolvePortBadge(normalPort, "Normal")
        assertEquals(
            "Port badge must clearly state power and vacancy without needing an extra section header",
            "60kW: Trống 1/2 cổng",
            normalBadge.label
        )

        val fullPort = StationPortStatus(
            kw = 150,
            availablePorts = 0,
            totalPorts = 2,
            busyCount = 2
        )
        val fullBadge = NativeStationDetailSheetHelper.resolvePortBadge(fullPort, "Normal")
        assertEquals(
            "Full port badge must clearly indicate 0 available slots",
            "150kW: Hết chỗ (0/2)",
            fullBadge.label
        )
    }
}
