package com.evcs.favorites.ui

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.domain.StationPortStatus
import com.evcs.favorites.domain.StationRating
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusAvailableContainer
import com.evcs.favorites.ui.theme.StatusOffline
import com.evcs.favorites.ui.theme.StatusOfflineContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Station Detail Redesign: Compact Horizontal Pills, Rating Removal & Scroll Reset.
 *
 * Verifies:
 * 1. Port badge label format strictly conforms to "${kw}kW  ${avail}/${total}".
 * 2. Zero-availability ports return StatusOffline red color tokens (StatusOffline, StatusOfflineContainer).
 * 3. Maintained / OutOfService ports return StatusOffline red color tokens and "${kw}kW  Bảo trì".
 * 4. Unverified ports (total == 0) return clean kW text without synthetic plug counts ("${kw}kW").
 * 5. Deprecated rating helper preserves contract for backwards compatibility.
 * 6. UI layout source contract verifies:
 *    - Instantaneous scroll offset reset (LaunchedEffect(station.id) { scrollState.scrollTo(0) }).
 *    - Rating badge UI block removal from NativeStationDetailContent layout.
 *    - Compact padding (8.dp horizontal, 4.dp vertical) in PortStatusPill.
 */
class StationDetailAutomotiveFormattingTest {

    private fun resolveFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        val parent = File("../$relativePath")
        if (parent.exists()) return parent
        return direct
    }

    @Test
    fun testPhase02_AutomotivePortPillsFormatting_AndColorMatrix() {
        val customSurface = Color(0xFF1E1B16)
        val customOnSurfaceVariant = Color(0xFFD4C4B5)

        // 1. Available Port Badge (avail > 0 -> green dot & container)
        val availablePort = StationPortStatus(kw = 30, availablePorts = 2, totalPorts = 4, busyCount = 2)
        val availableBadge = NativeStationDetailSheetHelper.resolvePortBadge(
            portStatus = availablePort,
            depotStatus = "Normal",
            surfaceVariant = customSurface,
            onSurfaceVariant = customOnSurfaceVariant
        )
        assertEquals("Available port badge label format must be '${'$'}{kw}kW  ${'$'}{avail}/${'$'}{total}'", "30kW  2/4", availableBadge.label)
        assertEquals("Available port dot color must be StatusAvailable (green)", StatusAvailable, availableBadge.dotColor)
        assertEquals("Available port container color must be StatusAvailableContainer", StatusAvailableContainer, availableBadge.containerColor)

        // 2. Exhausted Port Badge (avail == 0 && total > 0 -> StatusOffline red alert)
        val exhaustedPort = StationPortStatus(kw = 60, availablePorts = 0, totalPorts = 2, busyCount = 2)
        val exhaustedBadge = NativeStationDetailSheetHelper.resolvePortBadge(
            portStatus = exhaustedPort,
            depotStatus = "Normal",
            surfaceVariant = customSurface,
            onSurfaceVariant = customOnSurfaceVariant
        )
        assertEquals("Exhausted port badge label must be '${'$'}{kw}kW  0/${'$'}{total}'", "60kW  0/2", exhaustedBadge.label)
        assertEquals("Exhausted port dot color must be StatusOffline (warning red)", StatusOffline, exhaustedBadge.dotColor)
        assertEquals("Exhausted port container color must be StatusOfflineContainer", StatusOfflineContainer, exhaustedBadge.containerColor)

        // 3. Maintaining / OutOfService Port Badge
        val maintainingPort = StationPortStatus(kw = 120, availablePorts = 2, totalPorts = 4, busyCount = 2)
        val maintainingBadge = NativeStationDetailSheetHelper.resolvePortBadge(
            portStatus = maintainingPort,
            depotStatus = "Maintaining",
            surfaceVariant = customSurface,
            onSurfaceVariant = customOnSurfaceVariant
        )
        assertEquals("Maintaining port badge label must be '${'$'}{kw}kW  Bảo trì'", "120kW  Bảo trì", maintainingBadge.label)
        assertEquals("Maintaining port dot color must be StatusOffline", StatusOffline, maintainingBadge.dotColor)
        assertEquals("Maintaining port container color must be StatusOfflineContainer", StatusOfflineContainer, maintainingBadge.containerColor)

        val outOfServiceBadge = NativeStationDetailSheetHelper.resolvePortBadge(
            portStatus = maintainingPort,
            depotStatus = "OutOfService",
            surfaceVariant = customSurface,
            onSurfaceVariant = customOnSurfaceVariant
        )
        assertEquals("OutOfService port badge label must be '${'$'}{kw}kW  Bảo trì'", "120kW  Bảo trì", outOfServiceBadge.label)
        assertEquals(StatusOffline, outOfServiceBadge.dotColor)
        assertEquals(StatusOfflineContainer, outOfServiceBadge.containerColor)

        // 4. Unverified Port Badge (total == 0 -> clean '${kw}kW')
        val unverifiedPort = StationPortStatus(kw = 11, availablePorts = 0, totalPorts = 0, busyCount = 0)
        val unverifiedBadge = NativeStationDetailSheetHelper.resolvePortBadge(
            portStatus = unverifiedPort,
            depotStatus = "Normal",
            surfaceVariant = customSurface,
            onSurfaceVariant = customOnSurfaceVariant
        )
        assertEquals("Unverified port badge must output clean '${'$'}{kw}kW' without synthetic 0/0", "11kW", unverifiedBadge.label)
        assertEquals(customOnSurfaceVariant, unverifiedBadge.dotColor)
        assertEquals(customSurface, unverifiedBadge.containerColor)

        // 5. Backwards Compatibility: Deprecated formatRatingBadge helper
        val rating = StationRating(avg = 4.82, count = 25, mine = 5)
        val ratingBadge = NativeStationDetailSheetHelper.formatRatingBadge(rating)
        assertEquals("⭐ 4.8 (25 đánh giá)", ratingBadge)
        assertNull(NativeStationDetailSheetHelper.formatRatingBadge(null))
        assertNull(NativeStationDetailSheetHelper.formatRatingBadge(StationRating(avg = 0.0, count = 0, mine = 0)))

        // 6. UI Source Invariants Verification in NativeStationDetailSheet.kt
        val sheetFile = resolveFile("app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt")
        assertTrue("NativeStationDetailSheet.kt must exist", sheetFile.exists())
        val content = sheetFile.readText()

        // Verify LaunchedEffect scroll reset
        assertTrue(
            "NativeStationDetailContent must include LaunchedEffect(station.id) scroll reset",
            content.contains("LaunchedEffect(station.id)") && content.contains("scrollState.scrollTo(0)")
        )

        // Verify rating badge composable removal from NativeStationDetailContent
        assertFalse(
            "ratingText variable must be removed from NativeStationDetailContent",
            content.contains("val ratingText =")
        )
        assertFalse(
            "ratingText Surface block must be removed from NativeStationDetailContent",
            content.contains("if (!ratingText.isNullOrBlank())")
        )

        // Verify compact padding in PortStatusPill
        assertTrue(
            "PortStatusPill must use compact padding (8.dp horizontal, 4.dp vertical)",
            content.contains("padding(horizontal = 8.dp, vertical = 4.dp)")
        )
    }
}
