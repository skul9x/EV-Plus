package com.evcs.favorites.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Station Name Sanitizer VinFast Strip & Marquee Text Support.
 *
 * Verifies:
 * 1. Standard VinFast brand prefix stripping with hyphen and casing variations.
 * 2. Uppercase VinFast prefix stripping.
 * 3. Brand prefix without hyphen / space delimiter stripping.
 * 4. Compound station and post prefixes with VinFast brand prefixes.
 * 5. VinFast prefix followed by secondary station prefix.
 * 6. Alternative delimiters (colon, en-dash, em-dash) and dangling delimiter cleanup.
 * 7. Distance and guillemet prefix stripping combined with VinFast brand stripping.
 * 8. Preservation of non-VinFast station names (e.g. Vincom, EV One, highway milestones).
 * 9. Null, empty, and blank string safety.
 * 10. Fallback safety when sanitization would leave an empty string.
 * 11. Source code contract verifying basicMarquee configuration in StationCard.kt.
 */
class StationNameSanitizerTest {

    private fun resolveFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        val parent = File("../$relativePath")
        if (parent.exists()) return parent
        return direct
    }

    // =========================================================================
    // 1. Standard and Case-Insensitive VinFast Brand Prefixes
    // =========================================================================

    @Test
    fun testStandardVinFastPrefixStripped() {
        assertEquals(
            "TTTM Dabaco Mart Quế Võ",
            StationNameSanitizer.sanitize("Vinfast - TTTM Dabaco Mart Quế Võ")
        )
        assertEquals(
            "TTTM Dabaco Mart Quế Võ",
            StationNameSanitizer.sanitize("VinFast - TTTM Dabaco Mart Quế Võ")
        )
        assertEquals(
            "Showroom Long Biên",
            StationNameSanitizer.sanitize("vinfast - Showroom Long Biên")
        )
    }

    @Test
    fun testUppercaseVinFastPrefixStripped() {
        assertEquals(
            "TƯ NHÂN VŨ TIẾN LỰC",
            StationNameSanitizer.sanitize("VINFAST - TƯ NHÂN VŨ TIẾN LỰC")
        )
        assertEquals(
            "CHXD SỐ 12",
            StationNameSanitizer.sanitize("VIN FAST - CHXD SỐ 12")
        )
        assertEquals(
            "ĐẠI LÝ 3S",
            StationNameSanitizer.sanitize("VIN-FAST - ĐẠI LÝ 3S")
        )
    }

    @Test
    fun testSpacedAndHyphenatedVinFastVariations() {
        assertEquals(
            "TTTM Aeon Mall",
            StationNameSanitizer.sanitize("Vin Fast - TTTM Aeon Mall")
        )
        assertEquals(
            "TTTM Aeon Mall",
            StationNameSanitizer.sanitize("Vin-Fast - TTTM Aeon Mall")
        )
        assertEquals(
            "TTTM Aeon Mall",
            StationNameSanitizer.sanitize("Vin - Fast - TTTM Aeon Mall")
        )
    }

    // =========================================================================
    // 2. Colon, Dash, and Delimiter Variations
    // =========================================================================

    @Test
    fun testColonAndEnEmDashDelimiters() {
        assertEquals(
            "Showroom Mỹ Đình",
            StationNameSanitizer.sanitize("VinFast: Showroom Mỹ Đình")
        )
        assertEquals(
            "Showroom Mỹ Đình",
            StationNameSanitizer.sanitize("Vinfast: Showroom Mỹ Đình")
        )
        assertEquals(
            "Khách sạn Mường Thanh",
            StationNameSanitizer.sanitize("VinFast – Khách sạn Mường Thanh")
        )
        assertEquals(
            "Khách sạn Mường Thanh",
            StationNameSanitizer.sanitize("VinFast — Khách sạn Mường Thanh")
        )
    }

    @Test
    fun testBrandPrefixWithoutHyphen() {
        assertEquals(
            "TTTM Dabaco Mart Quế Võ",
            StationNameSanitizer.sanitize("VinFast TTTM Dabaco Mart Quế Võ")
        )
        assertEquals(
            "TTTM Dabaco Mart Quế Võ",
            StationNameSanitizer.sanitize("Vinfast TTTM Dabaco Mart Quế Võ")
        )
    }

    // =========================================================================
    // 3. Compound Station + Brand Prefixes
    // =========================================================================

    @Test
    fun testCompoundStationAndBrandPrefixes() {
        assertEquals(
            "Cửa hàng xăng dầu Cách Bi",
            StationNameSanitizer.sanitize("Trạm sạc VinFast - Cửa hàng xăng dầu Cách Bi")
        )
        assertEquals(
            "Showroom Long Biên",
            StationNameSanitizer.sanitize("Trụ sạc VinFast: Showroom Long Biên")
        )
        assertEquals(
            "TTTM Go! Thăng Long",
            StationNameSanitizer.sanitize("Trạm sạc xe điện VinFast - TTTM Go! Thăng Long")
        )
        assertEquals(
            "Bến xe Miền Đông",
            StationNameSanitizer.sanitize("Tru sac VinFast - Bến xe Miền Đông")
        )
        assertEquals(
            "Chợ Lớn",
            StationNameSanitizer.sanitize("Tram sac VinFast: Chợ Lớn")
        )
    }

    @Test
    fun testBrandPrefixFollowedByStationPrefix() {
        assertEquals(
            "Cửa hàng xăng dầu Cách Bi",
            StationNameSanitizer.sanitize("VinFast - Trạm sạc Cửa hàng xăng dầu Cách Bi")
        )
        assertEquals(
            "Showroom Giải Phóng",
            StationNameSanitizer.sanitize("VinFast - Trụ sạc Showroom Giải Phóng")
        )
    }

    // =========================================================================
    // 4. Combined Distance, Guillemet, and Delimiter Trimming
    // =========================================================================

    @Test
    fun testDistanceAndGuillemetCombinedWithBrandPrefix() {
        assertEquals(
            "CHXD Petrolimex",
            StationNameSanitizer.sanitize("5.4km » VinFast - CHXD Petrolimex")
        )
        assertEquals(
            "TTTM Vincom",
            StationNameSanitizer.sanitize("~9.1km : VinFast - TTTM Vincom")
        )
        assertEquals(
            "Bãi đỗ xe Big C",
            StationNameSanitizer.sanitize("500m - Trạm sạc VinFast - Bãi đỗ xe Big C")
        )
    }

    @Test
    fun testDanglingLeadingDelimitersTrimming() {
        assertEquals(
            "TTTM Dabaco",
            StationNameSanitizer.sanitize("VinFast - : - TTTM Dabaco")
        )
        assertEquals(
            "Bệnh viện Đa khoa",
            StationNameSanitizer.sanitize("- - Bệnh viện Đa khoa")
        )
        assertEquals(
            "UBND Phường",
            StationNameSanitizer.sanitize(": » UBND Phường")
        )
    }

    // =========================================================================
    // 5. Preserving Non-VinFast Names & Edge Cases
    // =========================================================================

    @Test
    fun testNonVinFastNamesPreservedUntouched() {
        assertEquals(
            "TTTM Vincom Mega Mall",
            StationNameSanitizer.sanitize("TTTM Vincom Mega Mall")
        )
        assertEquals(
            "Vincom Plaza Bắc Ninh",
            StationNameSanitizer.sanitize("Vincom Plaza Bắc Ninh")
        )
        assertEquals(
            "EV One - CHXD Số 1",
            StationNameSanitizer.sanitize("EV One - CHXD Số 1")
        )
        assertEquals(
            "Km 12 Quốc lộ 1A",
            StationNameSanitizer.sanitize("Km 12 Quốc lộ 1A")
        )
    }

    @Test
    fun testNullAndBlankInputSafety() {
        assertEquals("", StationNameSanitizer.sanitize(null))
        assertEquals("", StationNameSanitizer.sanitize(""))
        assertEquals("", StationNameSanitizer.sanitize("   "))
    }

    @Test
    fun testFallbackSafetyWhenSanitizationProducesEmptyString() {
        assertEquals("VinFast", StationNameSanitizer.sanitize("VinFast"))
        assertEquals("VinFast -", StationNameSanitizer.sanitize("VinFast -"))
        assertEquals("Trạm sạc VinFast", StationNameSanitizer.sanitize("Trạm sạc VinFast"))
        assertEquals("---", StationNameSanitizer.sanitize("---"))
    }

    // =========================================================================
    // 6. StationCard BasicMarquee Source Code Contract Verification
    // =========================================================================

    @Test
    fun testStationCardBasicMarqueeContract() {
        val cardFile = resolveFile("app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt")
        assertTrue("StationCard.kt must exist", cardFile.exists())

        val cardContent = cardFile.readText()

        // 1. Verify Import
        assertTrue(
            "StationCard.kt must import basicMarquee",
            cardContent.contains("import androidx.compose.foundation.basicMarquee")
        )

        // 2. Verify standard card title text configuration
        val standardHeaderMarker = "// Header Row: Station Name + Status Badge"
        val standardHeaderIndex = cardContent.indexOf(standardHeaderMarker)
        assertTrue("Header Row section must exist in StationCard", standardHeaderIndex != -1)

        val subHeaderMarker = "// Sub-header FlowRow: Rich Journey Badge"
        val subHeaderIndex = cardContent.indexOf(subHeaderMarker, standardHeaderIndex)
        assertTrue("Sub-header section must follow Header Row", subHeaderIndex != -1)

        val standardTitleBlock = cardContent.substring(standardHeaderIndex, subHeaderIndex)

        assertTrue(
            "Standard card station name must have maxLines = 1",
            standardTitleBlock.contains("maxLines = 1")
        )
        assertTrue(
            "Standard card station name must have softWrap = false",
            standardTitleBlock.contains("softWrap = false")
        )
        assertFalse(
            "Standard card station name must not have TextOverflow.Ellipsis to allow marquee",
            standardTitleBlock.contains("overflow = TextOverflow.Ellipsis")
        )
        assertTrue(
            "Standard card station name must apply basicMarquee modifier",
            standardTitleBlock.contains(".basicMarquee(")
        )
        assertTrue(
            "Standard card basicMarquee must have infinite iterations",
            standardTitleBlock.contains("iterations = Int.MAX_VALUE")
        )
        assertTrue(
            "Standard card basicMarquee must have delayMillis = 2000",
            standardTitleBlock.contains("delayMillis = 2000")
        )
        assertTrue(
            "Standard card basicMarquee must have velocity = 30.dp",
            standardTitleBlock.contains("velocity = 30.dp")
        )

        // 3. Verify compact card title text configuration
        val compactFunctionMarker = "fun CompactStationCardContent("
        val compactFunctionIndex = cardContent.indexOf(compactFunctionMarker)
        assertTrue("CompactStationCardContent must exist in StationCard.kt", compactFunctionIndex != -1)

        val compactTitleMarker = "text = station.name"
        val compactTitleIndex = cardContent.indexOf(compactTitleMarker, compactFunctionIndex)
        assertTrue("Compact card station.name Text composable must exist", compactTitleIndex != -1)

        val compactSubtitleMarker = "val journeyBadgeInfo"
        val compactSubtitleIndex = cardContent.indexOf(compactSubtitleMarker, compactTitleIndex)
        assertTrue("journeyBadgeInfo must follow compact station title", compactSubtitleIndex != -1)

        val compactTitleBlock = cardContent.substring(compactTitleIndex, compactSubtitleIndex)

        assertTrue(
            "Compact card station name must have maxLines = 1",
            compactTitleBlock.contains("maxLines = 1")
        )
        assertTrue(
            "Compact card station name must have softWrap = false",
            compactTitleBlock.contains("softWrap = false")
        )
        assertFalse(
            "Compact card station name must not have TextOverflow.Ellipsis to allow marquee",
            compactTitleBlock.contains("overflow = TextOverflow.Ellipsis")
        )
        assertTrue(
            "Compact card station name must apply basicMarquee modifier",
            compactTitleBlock.contains(".basicMarquee(")
        )
        assertTrue(
            "Compact card basicMarquee must have infinite iterations",
            compactTitleBlock.contains("iterations = Int.MAX_VALUE")
        )
        assertTrue(
            "Compact card basicMarquee must have delayMillis = 2000",
            compactTitleBlock.contains("delayMillis = 2000")
        )
        assertTrue(
            "Compact card basicMarquee must have velocity = 30.dp",
            compactTitleBlock.contains("velocity = 30.dp")
        )
    }
}
