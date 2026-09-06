package com.evcs.favorites.ui.components

import com.evcs.favorites.data.api.EvcsApiClient
import com.evcs.favorites.data.auth.InMemorySessionStorage
import com.evcs.favorites.data.auth.SessionManager
import com.evcs.favorites.data.model.FavoriteStationRaw
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.repository.toDomainStation
import com.evcs.favorites.util.VinFastCdnUrlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Single comprehensive test suite verifying Phase 02 core requirements:
 * 1. Multi-pass Base64 direct VinFast CloudFront S3 CDN URL extraction (`VinFastCdnUrlDecoder`).
 * 2. Corrupted, non-base64, empty, and direct HTTP fallback handling.
 * 3. JVM-safe parameter extraction without unmocked Android Uri dependencies.
 * 4. Station model backward compatibility with legacy `image` and new `images` / `addedAt`.
 * 5. Repository mapping of search media items to domain stations.
 * 6. Photo carousel visibility and indicator badge logic.
 * 7. Lightbox zoom, boundary-clamped pan, horizontal paging arbitration, and swipe-to-dismiss calculations.
 */
class StationPhotoGalleryAndDecoderTest {

    private fun createDoubleBase64Token(targetUrl: String): String {
        val urlEncoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name())
        val pass1Bytes = Base64.getEncoder().encode(urlEncoded.toByteArray(StandardCharsets.UTF_8))
        val pass1Str = String(pass1Bytes, StandardCharsets.UTF_8)
        val tokenBytes = Base64.getEncoder().encode(pass1Str.toByteArray(StandardCharsets.UTF_8))
        return String(tokenBytes, StandardCharsets.UTF_8)
    }

    // =========================================================================
    // 1. Multi-Pass Base64 Decoding Pipeline Tests
    // =========================================================================

    @Test
    fun testMultiPassBase64DecodeExtractsDirectVinFastCdnUrl() {
        val originalCdnUrl = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/station_landmark_81.jpg"
        val token = createDoubleBase64Token(originalCdnUrl)

        // Case A: Full URL with query param
        val fullUrl = "https://evcs.vn/media?file=$token"
        val decodedFromFullUrl = VinFastCdnUrlDecoder.decode(fullUrl)
        assertEquals(originalCdnUrl, decodedFromFullUrl)

        // Case B: Relative URL with query param
        val relativeUrl = "media?file=$token"
        val decodedFromRelative = VinFastCdnUrlDecoder.decode(relativeUrl)
        assertEquals(originalCdnUrl, decodedFromRelative)

        // Case C: Query string only
        val queryOnly = "?file=$token"
        val decodedFromQuery = VinFastCdnUrlDecoder.decode(queryOnly)
        assertEquals(originalCdnUrl, decodedFromQuery)

        // Case D: Raw double-base64 token directly
        val decodedFromRawToken = VinFastCdnUrlDecoder.decode(token)
        assertEquals(originalCdnUrl, decodedFromRawToken)

        // Case E: Query with multiple parameters
        val multiParamUrl = "https://evcs.vn/media?stationId=VF123&file=$token&session=xyz"
        val decodedFromMultiParam = VinFastCdnUrlDecoder.decode(multiParamUrl)
        assertEquals(originalCdnUrl, decodedFromMultiParam)
    }

    // =========================================================================
    // 2. Direct HTTP Fallback, Corrupted, Empty, and List Decoding
    // =========================================================================

    @Test
    fun testDecoderFallbackAndRobustness() {
        // Direct CloudFront S3 CDN URL should be returned untouched
        val directCdn = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/direct_image.png"
        assertEquals(directCdn, VinFastCdnUrlDecoder.decode(directCdn))

        // Direct HTTP image URL
        val genericHttp = "http://cpo-prod-s3.vinfastauto.com/images/station.jpg"
        assertEquals(genericHttp, VinFastCdnUrlDecoder.decode(genericHttp))

        // Corrupted or invalid strings must gracefully return null without throwing exceptions
        assertNull(VinFastCdnUrlDecoder.decode(null))
        assertNull(VinFastCdnUrlDecoder.decode(""))
        assertNull(VinFastCdnUrlDecoder.decode("   "))
        assertNull(VinFastCdnUrlDecoder.decode("not-a-valid-base64-string!@#$%^"))
        assertNull(VinFastCdnUrlDecoder.decode("media?file=not-base64!"))
        assertNull(VinFastCdnUrlDecoder.decode("media?other=123"))

        // Decode list filtering: drops nulls/invalids, retains valid decoded URLs
        val token = createDoubleBase64Token("https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/st1.jpg")
        val rawList = listOf(
            "https://evcs.vn/media?file=$token",
            "corrupted_token",
            "",
            "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/direct.jpg"
        )
        val decodedList = VinFastCdnUrlDecoder.decodeList(rawList)
        assertEquals(2, decodedList.size)
        assertEquals("https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/st1.jpg", decodedList[0])
        assertEquals("https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/direct.jpg", decodedList[1])

        assertTrue(VinFastCdnUrlDecoder.decodeList(null).isEmpty())
        assertTrue(VinFastCdnUrlDecoder.decodeList(emptyList()).isEmpty())
    }

    // =========================================================================
    // 3. JVM-Safe Token Extraction
    // =========================================================================

    @Test
    fun testExtractTokenWithoutAndroidUri() {
        assertEquals("abc123xyz", VinFastCdnUrlDecoder.extractToken("https://evcs.vn/media?file=abc123xyz"))
        assertEquals("tok_456", VinFastCdnUrlDecoder.extractToken("media?file=tok_456"))
        assertEquals("tok_789", VinFastCdnUrlDecoder.extractToken("?file=tok_789"))
        assertEquals("rawTokenVal", VinFastCdnUrlDecoder.extractToken("rawTokenVal"))
        assertEquals("multi1", VinFastCdnUrlDecoder.extractToken("https://domain.com/path?foo=bar&file=multi1&baz=qux"))
    }

    // =========================================================================
    // 4. Station Model Backward Compatibility & Repository Mapping
    // =========================================================================

    @Test
    fun testStationModelBackwardCompatibilityAndFields() {
        // Station created with multiple images: image property resolves to first image
        val photoList = listOf("https://cdn.com/photo1.jpg", "https://cdn.com/photo2.jpg")
        val stationWithPhotos = Station(
            id = "ST_01",
            name = "Trạm Vincom Landmark 81",
            address = "720A Điện Biên Phủ, TP.HCM",
            latitude = 10.795,
            longitude = 106.722,
            summary = "24/7",
            connectors = "60kW",
            depotStatus = "Normal",
            images = photoList,
            addedAt = 1718000000L
        )

        assertEquals("https://cdn.com/photo1.jpg", stationWithPhotos.image)
        assertEquals("https://cdn.com/photo1.jpg", stationWithPhotos.effectiveImage)
        assertEquals(2, stationWithPhotos.images.size)
        assertEquals(1718000000L, stationWithPhotos.addedAt)

        // Station created with legacy single image and empty images list
        val legacyStation = Station(
            id = "ST_02",
            name = "Trạm Vincom Mega Mall",
            address = "Hà Nội",
            latitude = 21.028,
            longitude = 105.854,
            summary = "24/7",
            connectors = "30kW",
            depotStatus = "Normal",
            image = "https://legacy.com/img.jpg"
        )
        assertEquals("https://legacy.com/img.jpg", legacyStation.image)
        assertEquals("https://legacy.com/img.jpg", legacyStation.effectiveImage)
        assertTrue(legacyStation.images.isEmpty())
        assertEquals(0L, legacyStation.addedAt)
    }

    @Test
    fun testRepositoryMappingDecodesSearchMediaToStationImages() {
        val cdnUrl1 = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/vinhomes1.jpg"
        val cdnUrl2 = "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/vinhomes2.jpg"
        val token1 = createDoubleBase64Token(cdnUrl1)
        val token2 = createDoubleBase64Token(cdnUrl2)

        val raw = SearchStationRaw(
            id = "LOC_VHM",
            stationName = "Trạm Sạc Vinhomes",
            stationAddress = "Hà Nội",
            latitude = 21.0,
            longitude = 105.8,
            media = listOf("https://evcs.vn/media?file=$token1", "https://evcs.vn/media?file=$token2")
        )

        // 1. toDomainStation mapping
        val domainStation = raw.toDomainStation(userLat = 21.0, userLon = 105.8)
        assertEquals(2, domainStation.images.size)
        assertEquals(cdnUrl1, domainStation.images[0])
        assertEquals(cdnUrl2, domainStation.images[1])
        assertEquals(cdnUrl1, domainStation.image)
        assertEquals(cdnUrl1, domainStation.effectiveImage)

        // 2. mergeToDomainStation mapping
        val sessionManager = SessionManager(InMemorySessionStorage())
        val apiClient = EvcsApiClient(sessionManager = sessionManager)
        val repository = EvcsRepository(apiClient = apiClient)
        val favRaw = FavoriteStationRaw(
            locationId = "LOC_VHM",
            name = "Trạm Sạc Vinhomes",
            address = "Hà Nội"
        )
        val mergedStation = repository.mergeToDomainStation(favRaw, raw)
        assertEquals(2, mergedStation.images.size)
        assertEquals(cdnUrl1, mergedStation.images[0])
        assertEquals(cdnUrl1, mergedStation.image)

        // 3. Fallback when search is null
        val fallbackFav = FavoriteStationRaw(
            locationId = "LOC_OFFLINE",
            name = "Trạm Ngoại Tuyến",
            address = "Đà Nẵng",
            image = "https://fallback.com/fav.jpg"
        )
        val fallbackMerged = repository.mergeToDomainStation(fallbackFav, null)
        assertEquals("https://fallback.com/fav.jpg", fallbackMerged.image)
        assertEquals(listOf("https://fallback.com/fav.jpg"), fallbackMerged.images)
    }

    // =========================================================================
    // 5. Carousel Visibility and Indicator Logic
    // =========================================================================

    @Test
    fun testCarouselVisibilityAndIndicatorLogic() {
        // Zero-height auto collapse rule: empty images -> shouldShowCarousel is false
        assertFalse(NativeStationDetailSheetHelper.shouldShowCarousel(emptyList()))
        assertFalse(StationPhotoViewerHelper.shouldShowCarousel(emptyList()))

        // Non-empty images -> visible
        val images = listOf("https://cdn.com/1.jpg", "https://cdn.com/2.jpg", "https://cdn.com/3.jpg")
        assertTrue(NativeStationDetailSheetHelper.shouldShowCarousel(images))
        assertTrue(StationPhotoViewerHelper.shouldShowCarousel(images))

        // Indicator pill badges
        assertEquals("1 / 3", NativeStationDetailSheetHelper.formatCarouselIndicator(0, 3))
        assertEquals("2 / 3", NativeStationDetailSheetHelper.formatCarouselIndicator(1, 3))
        assertEquals("3 / 3", NativeStationDetailSheetHelper.formatCarouselIndicator(2, 3))
        assertEquals("0 / 0", NativeStationDetailSheetHelper.formatCarouselIndicator(0, 0))

        assertEquals("1 / 3", StationPhotoViewerHelper.formatIndexBadge(0, 3))
        assertEquals("2 / 3", StationPhotoViewerHelper.formatIndexBadge(1, 3))
        assertEquals("0 / 0", StationPhotoViewerHelper.formatIndexBadge(0, 0))
    }

    // =========================================================================
    // 6. Lightbox Zoom, Pan, Paging, and Dismiss State Calculations
    // =========================================================================

    @Test
    fun testLightboxZoomAndPanCalculations() {
        // 1. Clamp zoom scale between 1.0f and 4.0f
        assertEquals(1.0f, StationPhotoViewerHelper.clampZoomScale(0.5f), 0.001f)
        assertEquals(1.0f, StationPhotoViewerHelper.clampZoomScale(1.0f), 0.001f)
        assertEquals(2.5f, StationPhotoViewerHelper.clampZoomScale(2.5f), 0.001f)
        assertEquals(4.0f, StationPhotoViewerHelper.clampZoomScale(4.0f), 0.001f)
        assertEquals(4.0f, StationPhotoViewerHelper.clampZoomScale(5.8f), 0.001f)

        // 2. Double-tap to zoom toggles between 1.0f and 2.5f
        assertEquals(2.5f, StationPhotoViewerHelper.toggleDoubleTapZoom(1.0f), 0.001f)
        assertEquals(2.5f, StationPhotoViewerHelper.toggleDoubleTapZoom(1.03f), 0.001f) // Within 1.05 threshold
        assertEquals(1.0f, StationPhotoViewerHelper.toggleDoubleTapZoom(1.10f), 0.001f) // Above threshold
        assertEquals(1.0f, StationPhotoViewerHelper.toggleDoubleTapZoom(2.5f), 0.001f)
        assertEquals(1.0f, StationPhotoViewerHelper.toggleDoubleTapZoom(4.0f), 0.001f)

        // 3. Horizontal paging is active strictly when scale <= 1.05f
        assertTrue(StationPhotoViewerHelper.isPagingAllowed(1.0f))
        assertTrue(StationPhotoViewerHelper.isPagingAllowed(1.05f))
        assertFalse(StationPhotoViewerHelper.isPagingAllowed(1.06f))
        assertFalse(StationPhotoViewerHelper.isPagingAllowed(2.0f))
        assertFalse(StationPhotoViewerHelper.isPagingAllowed(2.5f))

        // 4. Pan displacement clamping: boundary = ((scale - 1f) * size) / 2
        // Scale 1.0f -> no pan allowed (0 boundary)
        assertEquals(0f, StationPhotoViewerHelper.clampPanOffset(100f, scale = 1.0f, size = 1080f), 0.001f)

        // Scale 2.0f on 1080px -> boundary = (1.0 * 1080) / 2 = 540px
        assertEquals(300f, StationPhotoViewerHelper.clampPanOffset(300f, scale = 2.0f, size = 1080f), 0.001f)
        assertEquals(540f, StationPhotoViewerHelper.clampPanOffset(700f, scale = 2.0f, size = 1080f), 0.001f)
        assertEquals(-540f, StationPhotoViewerHelper.clampPanOffset(-900f, scale = 2.0f, size = 1080f), 0.001f)

        // Zero size safety
        assertEquals(0f, StationPhotoViewerHelper.clampPanOffset(100f, scale = 2.0f, size = 0f), 0.001f)
    }

    @Test
    fun testLightboxSwipeToDismissCalculations() {
        // Below 100.dp threshold and low velocity -> do not dismiss
        assertFalse(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 50f, velocityY = 0f))
        assertFalse(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 99.9f, velocityY = 500f))

        // Releasing past 100.dp threshold -> triggers dismiss
        assertTrue(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 100f, velocityY = 0f))
        assertTrue(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 150f, velocityY = 0f))

        // High downward velocity triggers dismiss even before 100.dp
        assertTrue(StationPhotoViewerHelper.shouldDismissOnDrag(dragOffsetY = 40f, velocityY = 1200f))

        // Progressive alpha fade: decreases smoothly as downward drag increases
        val alpha0 = StationPhotoViewerHelper.calculateDismissAlpha(dragOffsetY = 0f, maxDrag = 300f, baseAlpha = 0.95f)
        assertEquals(0.95f, alpha0, 0.001f)

        val alpha100 = StationPhotoViewerHelper.calculateDismissAlpha(dragOffsetY = 100f, maxDrag = 300f, baseAlpha = 0.95f)
        assertTrue(alpha100 < alpha0)
        assertEquals(0.95f * (200f / 300f), alpha100, 0.01f)

        val alpha300 = StationPhotoViewerHelper.calculateDismissAlpha(dragOffsetY = 300f, maxDrag = 300f, baseAlpha = 0.95f)
        assertEquals(0.0f, alpha300, 0.001f)

        val alphaPastMax = StationPhotoViewerHelper.calculateDismissAlpha(dragOffsetY = 400f, maxDrag = 300f, baseAlpha = 0.95f)
        assertEquals(0.0f, alphaPastMax, 0.001f)
    }
}
