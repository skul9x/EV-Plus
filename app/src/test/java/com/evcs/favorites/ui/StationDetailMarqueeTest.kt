package com.evcs.favorites.ui

import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Single-Line Smooth Marquee for Station Name & Address.
 *
 * Verifies:
 * 1. Source code contract verifies `basicMarquee` modifier presence on both `station.name` and `station.address`.
 * 2. Verifies `maxLines = 1` and `softWrap = false` on both text fields.
 * 3. Verifies marquee constants in NativeStationDetailSheetHelper (iterations = Int.MAX_VALUE, delay >= 1500ms, velocity between 30dp-40dp).
 * 4. Verifies that ellipsis truncation is eliminated in favor of marquee animation on both title and address.
 * 5. Verifies imports and layout stability contracts.
 */
class StationDetailMarqueeTest {

    private fun resolveFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        val parent = File("../$relativePath")
        if (parent.exists()) return parent
        return direct
    }

    @Test
    fun testPhase02_StationNameAndAddressSingleLineMarqueeContract() {
        // 1. Locate NativeStationDetailSheet.kt
        val sheetFile = resolveFile("app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt")
        assertTrue("NativeStationDetailSheet.kt must exist", sheetFile.exists())

        val sheetContent = sheetFile.readText()

        // 2. Verify Imports for Compose Foundation Marquee
        assertTrue(
            "NativeStationDetailSheet must import basicMarquee",
            sheetContent.contains("import androidx.compose.foundation.basicMarquee")
        )
        assertTrue(
            "NativeStationDetailSheet must import MarqueeSpacing",
            sheetContent.contains("import androidx.compose.foundation.MarqueeSpacing")
        )
        assertTrue(
            "NativeStationDetailSheet must import MarqueeAnimationMode",
            sheetContent.contains("import androidx.compose.foundation.MarqueeAnimationMode")
        )

        // 3. Verify Automotive Marquee Configuration Constants in NativeStationDetailSheetHelper
        assertEquals(
            "Marquee iterations must be infinite (Int.MAX_VALUE) for automotive glanceability",
            Int.MAX_VALUE,
            NativeStationDetailSheetHelper.MARQUEE_ITERATIONS
        )
        assertTrue(
            "Initial delay must be >= 1500ms to allow drivers to read initial segment before scrolling starts",
            NativeStationDetailSheetHelper.MARQUEE_INITIAL_DELAY_MS >= 1500
        )
        assertEquals(
            "Initial delay is configured to exactly 2000ms for distraction-free reading",
            2000,
            NativeStationDetailSheetHelper.MARQUEE_INITIAL_DELAY_MS
        )
        assertTrue(
            "Scroll velocity must be between 30.dp and 40.dp per second for smooth readability without motion blur",
            NativeStationDetailSheetHelper.MARQUEE_VELOCITY_DP in 30f..40f
        )
        assertEquals(
            "Scroll velocity is tuned to 35.dp per second",
            35f,
            NativeStationDetailSheetHelper.MARQUEE_VELOCITY_DP,
            0.001f
        )
        assertEquals(
            "Scroll velocity Dp value matches 35.dp",
            35f,
            NativeStationDetailSheetHelper.MARQUEE_VELOCITY.value,
            0.001f
        )
        assertEquals(
            "Marquee repeat spacing fraction is 1/4 (0.25f) of container width",
            0.25f,
            NativeStationDetailSheetHelper.MARQUEE_SPACING_FRACTION,
            0.001f
        )

        // 4. Extract Station Header Block
        val headerMarker = "// 2. Station Header: Name & Address"
        val headerIndex = sheetContent.indexOf(headerMarker)
        assertTrue("Station Header section must exist in NativeStationDetailSheet", headerIndex != -1)

        val nextSectionMarker = "// 3. Charging Ports Section"
        val nextSectionIndex = sheetContent.indexOf(nextSectionMarker, headerIndex)
        assertTrue("Charging Ports section must follow Station Header", nextSectionIndex != -1)

        val headerBlock = sheetContent.substring(headerIndex, nextSectionIndex)

        // 5. Verify Station Name Marquee Contract
        val nameIndex = headerBlock.indexOf("text = station.name")
        assertTrue("station.name Text composable must exist", nameIndex != -1)

        val addressIndex = headerBlock.indexOf("text = station.address")
        assertTrue("station.address Text composable must exist", addressIndex != -1)

        val nameBlock = headerBlock.substring(nameIndex, addressIndex)

        assertTrue(
            "station.name must be constrained to exactly maxLines = 1",
            nameBlock.contains("maxLines = 1")
        )
        assertTrue(
            "station.name must have softWrap = false to avoid multi-line line-breaks",
            nameBlock.contains("softWrap = false")
        )
        assertFalse(
            "station.name must NOT have TextOverflow.Ellipsis so the full name scrolls completely via marquee",
            nameBlock.contains("overflow = TextOverflow.Ellipsis")
        )
        assertTrue(
            "station.name must apply basicMarquee modifier",
            nameBlock.contains(".basicMarquee(")
        )
        assertTrue(
            "station.name marquee must pass iterations, delayMillis, velocity, and spacing",
            nameBlock.contains("iterations = NativeStationDetailSheetHelper.MARQUEE_ITERATIONS") &&
                nameBlock.contains("delayMillis = NativeStationDetailSheetHelper.MARQUEE_INITIAL_DELAY_MS") &&
                nameBlock.contains("velocity = NativeStationDetailSheetHelper.MARQUEE_VELOCITY") &&
                nameBlock.contains("spacing = MarqueeSpacing.fractionOfContainer(NativeStationDetailSheetHelper.MARQUEE_SPACING_FRACTION)")
        )

        // 6. Verify Station Address Marquee Contract
        val ratingMarker = "if (!ratingText"
        val ratingIndex = headerBlock.indexOf(ratingMarker, addressIndex)
        val addressBlock = if (ratingIndex != -1) {
            headerBlock.substring(addressIndex, ratingIndex)
        } else {
            headerBlock.substring(addressIndex)
        }

        assertTrue(
            "station.address must be constrained to exactly maxLines = 1",
            addressBlock.contains("maxLines = 1")
        )
        assertTrue(
            "station.address must have softWrap = false to prevent two-line wrapping",
            addressBlock.contains("softWrap = false")
        )
        assertFalse(
            "station.address must NOT have TextOverflow.Ellipsis to allow full address traversal",
            addressBlock.contains("overflow = TextOverflow.Ellipsis")
        )
        assertTrue(
            "station.address must apply basicMarquee modifier",
            addressBlock.contains(".basicMarquee(")
        )
        assertTrue(
            "station.address marquee must pass iterations, delayMillis, velocity, and spacing",
            addressBlock.contains("iterations = NativeStationDetailSheetHelper.MARQUEE_ITERATIONS") &&
                addressBlock.contains("delayMillis = NativeStationDetailSheetHelper.MARQUEE_INITIAL_DELAY_MS") &&
                addressBlock.contains("velocity = NativeStationDetailSheetHelper.MARQUEE_VELOCITY") &&
                addressBlock.contains("spacing = MarqueeSpacing.fractionOfContainer(NativeStationDetailSheetHelper.MARQUEE_SPACING_FRACTION)")
        )
    }
}
