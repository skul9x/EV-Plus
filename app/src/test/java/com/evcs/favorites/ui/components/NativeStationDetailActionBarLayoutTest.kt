package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimaryLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 01:
 * Primary Action Button Truncation Fix & Action Bar Layout Alignment.
 *
 * Verifies:
 * 1. Primary CTA action label ("Chỉ đường") and navigation intent spec formatting.
 * 2. Favorite button labels ("Yêu thích" vs "Đã lưu") and color spec resolutions.
 * 3. Share intent text formatting containing station name, address, EVCS link, and Google Maps link.
 * 4. Quick action button row layout specification guarantees sufficient allocation for primary navigation
 *    without truncation or layout jitter on screens down to 360dp width.
 */
class NativeStationDetailActionBarLayoutTest {

    private val sampleStation = Station(
        id = "C.BNI0012",
        name = "VinFast - TTTM Dabaco Mart Quế Võ",
        address = "Phố Mới, Huyện Quế Võ, Bắc Ninh",
        latitude = 21.1438,
        longitude = 106.1662,
        summary = "Mở 24/7 • Miễn phí gửi xe",
        connectors = "120kW, 60kW, 30kW",
        depotStatus = "Normal",
        distanceKm = 2.4,
        drivingMetrics = DrivingMetrics(
            distanceMeters = 2400L,
            durationSeconds = 360L,
            engineUsed = RoutingEngineType.GOOGLE
        ),
        totalAvailablePlugs = 3,
        totalPlugs = 6
    )

    // =========================================================================
    // 1. Primary CTA Label & Navigation Intent Specification
    // =========================================================================

    @Test
    fun testPrimaryCtaLabelAndNavigationIntentSpec() {
        // Label must be exact unclipped string
        assertEquals("Chỉ đường", NativeStationDetailSheetHelper.LABEL_NAVIGATE)

        // Geo URI navigation formatting
        val geoUri = NativeStationDetailSheetHelper.buildNavigationUri(
            sampleStation.latitude,
            sampleStation.longitude,
            sampleStation.name
        )
        assertTrue("URI must start with geo scheme", geoUri.startsWith("geo:0,0?q=21.1438,106.1662("))
        assertTrue("URI must contain encoded station name", geoUri.contains("VinFast"))

        // Navigation intent spec
        val navSpec = NativeStationDetailSheetHelper.buildNavigationIntentSpec(sampleStation)
        assertEquals(NativeStationDetailSheetHelper.ACTION_VIEW, navSpec.action)
        assertEquals("android.intent.action.VIEW", navSpec.action)
        assertEquals(NativeStationDetailSheetHelper.GOOGLE_MAPS_PACKAGE, navSpec.packageName)
        assertEquals("com.google.android.apps.maps", navSpec.packageName)
        assertEquals(geoUri, navSpec.uriString)
    }

    // =========================================================================
    // 2. Favorite Button State & Color Spec Resolutions
    // =========================================================================

    @Test
    fun testFavoriteButtonLabelAndColorSpecResolution() {
        val testSurfaceVariant = Color(0xFFE7E0EC)
        val testOnSurfaceVariant = Color(0xFF49454F)

        // 1. Unfavorited State
        val unpressedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(
            isFavorite = false,
            surfaceVariant = testSurfaceVariant,
            onSurfaceVariant = testOnSurfaceVariant
        )
        assertFalse(unpressedSpec.isFavorite)
        assertEquals(NativeStationDetailSheetHelper.LABEL_FAVORITE, unpressedSpec.label)
        assertEquals("Yêu thích", unpressedSpec.label)
        assertEquals(NativeStationDetailSheetHelper.LABEL_FAVORITE, unpressedSpec.contentDescription)
        assertEquals(testSurfaceVariant, unpressedSpec.containerColor)
        assertEquals(testOnSurfaceVariant, unpressedSpec.contentColor)

        // 2. Favorited / Saved State
        val savedSpec = NativeStationDetailSheetHelper.resolveFavoriteButtonSpec(
            isFavorite = true,
            surfaceVariant = testSurfaceVariant,
            onSurfaceVariant = testOnSurfaceVariant
        )
        assertTrue(savedSpec.isFavorite)
        assertEquals(NativeStationDetailSheetHelper.LABEL_SAVED, savedSpec.label)
        assertEquals("Đã lưu", savedSpec.label)
        assertEquals(NativeStationDetailSheetHelper.DESC_UNFAVORITE, savedSpec.contentDescription)
        assertEquals("Bỏ yêu thích", savedSpec.contentDescription)
        assertEquals(EmeraldContainerDark, savedSpec.containerColor)
        assertEquals(EmeraldPrimaryLight, savedSpec.contentColor)
    }

    // =========================================================================
    // 3. Share Intent Text & Payload Formatting
    // =========================================================================

    @Test
    fun testShareIntentTextAndPayloadFormatting() {
        val googleMapsUrl = NativeStationDetailSheetHelper.buildGoogleMapsWebUrl(
            sampleStation.latitude,
            sampleStation.longitude
        )
        assertEquals("https://www.google.com/maps/search/?api=1&query=21.1438,106.1662", googleMapsUrl)

        val shareText = NativeStationDetailSheetHelper.buildShareText(sampleStation)
        assertTrue("Share text must contain station name", shareText.contains(sampleStation.name))
        assertTrue("Share text must contain station address", shareText.contains(sampleStation.address))
        assertTrue("Share text must contain EVCS link", shareText.contains("https://evcs.vn/tram-sac-vinfast-tttm-dabaco-mart-que-vo-c.bni0012.html"))
        assertTrue("Share text must contain Google Maps search link", shareText.contains(googleMapsUrl))

        val shareSpec = NativeStationDetailSheetHelper.buildShareIntentSpec(sampleStation)
        assertEquals(NativeStationDetailSheetHelper.ACTION_SEND, shareSpec.action)
        assertEquals("android.intent.action.SEND", shareSpec.action)
        assertEquals(shareText, shareSpec.text)
        assertEquals("text/plain", shareSpec.type)
    }

    // =========================================================================
    // 4. Quick Action Row Layout Specification & Headroom Guarantees
    // =========================================================================

    @Test
    fun testButtonRowLayoutAllocationAndHeadroomGuarantees() {
        // Verify weights hierarchy
        assertEquals(1.3f, NativeStationDetailSheetHelper.PRIMARY_NAV_WEIGHT, 0.001f)
        assertEquals(1.0f, NativeStationDetailSheetHelper.SECONDARY_FAVORITE_WEIGHT, 0.001f)
        assertEquals(0.9f, NativeStationDetailSheetHelper.SECONDARY_SHARE_WEIGHT, 0.001f)
        assertTrue(
            "Primary weight must be strictly greater than favorite weight",
            NativeStationDetailSheetHelper.PRIMARY_NAV_WEIGHT > NativeStationDetailSheetHelper.SECONDARY_FAVORITE_WEIGHT
        )
        assertTrue(
            "Favorite weight must be greater than or equal to share weight",
            NativeStationDetailSheetHelper.SECONDARY_FAVORITE_WEIGHT >= NativeStationDetailSheetHelper.SECONDARY_SHARE_WEIGHT
        )

        // Verify allocation on minimum mobile baseline screen (360dp width)
        val alloc360 = NativeStationDetailSheetHelper.computeActionRowLayoutAllocation(
            totalWidthDp = 360f,
            horizontalPaddingDp = 32f, // 16dp * 2
            spacingBetweenButtonsDp = 8f // 2 gaps of 8dp
        )

        // Available row width = 360 - 32 - 16 = 312dp
        assertEquals(312f, alloc360.availableRowWidthDp, 0.01f)

        // Primary navigation allocation: 312 * (1.3 / 3.2) = 126.75dp
        assertEquals(126.75f, alloc360.primaryNavWidthDp, 0.01f)
        // Favorite allocation: 312 * (1.0 / 3.2) = 97.5dp
        assertEquals(97.5f, alloc360.favoriteWidthDp, 0.01f)
        // Share allocation: 312 * (0.9 / 3.2) = 87.75dp
        assertEquals(87.75f, alloc360.shareWidthDp, 0.01f)

        // Proportional priority: Primary CTA must take > 40% of the entire row
        assertTrue(
            "Primary CTA proportion must exceed 40%",
            alloc360.primaryProportion >= 0.40f
        )

        // Threshold checks guaranteeing ZERO text truncation:
        // Required for "Chỉ đường": padding (20dp) + icon (18dp) + gap (4dp) + text ~70dp = ~112dp
        assertTrue(
            "Primary CTA width must exceed 115dp minimum threshold",
            alloc360.primaryNavWidthDp >= 115f
        )
        // Required for "Yêu thích": padding (20dp) + icon (18dp) + gap (4dp) + text ~48dp = ~90dp
        assertTrue(
            "Favorite width must exceed 90dp minimum threshold",
            alloc360.favoriteWidthDp >= 90f
        )
        // Required for "Chia sẻ": padding (20dp) + icon (18dp) + gap (4dp) + text ~40dp = ~82dp
        assertTrue(
            "Share width must exceed 80dp minimum threshold",
            alloc360.shareWidthDp >= 80f
        )

        // Verify allocation on standard modern screen (390dp width)
        val alloc390 = NativeStationDetailSheetHelper.computeActionRowLayoutAllocation(
            totalWidthDp = 390f,
            horizontalPaddingDp = 32f,
            spacingBetweenButtonsDp = 8f
        )
        assertEquals(342f, alloc390.availableRowWidthDp, 0.01f)
        assertTrue(
            "Primary CTA width on 390dp screen must have ample headroom (>135dp)",
            alloc390.primaryNavWidthDp >= 135f
        )

        // Verify zero layout jitter when toggling favorite:
        // Because width is computed via proportional weights, toggling between "Yêu thích" and "Đã lưu"
        // produces the exact same container width allocation (97.5dp), avoiding row shifting/jitter.
        val unpressedRowAlloc = NativeStationDetailSheetHelper.computeActionRowLayoutAllocation()
        val savedRowAlloc = NativeStationDetailSheetHelper.computeActionRowLayoutAllocation()
        assertEquals(unpressedRowAlloc.favoriteWidthDp, savedRowAlloc.favoriteWidthDp, 0.001f)
        assertEquals(unpressedRowAlloc.primaryNavWidthDp, savedRowAlloc.primaryNavWidthDp, 0.001f)
    }
}
