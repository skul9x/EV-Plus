package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.filter.hasDcPorts
import com.evcs.favorites.domain.model.isDc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single comprehensive unit test verifying Phase 02:
 * UI Photo Loading State & Carousel Rendering for AC Stations.
 *
 * Core verification criteria:
 * 1. Helper rules correctly determine carousel visibility and placeholder states for AC stations with empty vs populated photos.
 * 2. Indicator string formatting (`NativeStationDetailSheetHelper.formatCarouselIndicator`) produces exact index and count badges.
 * 3. Lightbox modal activation contracts match identically between DC and AC stations.
 * 4. Photo layout specifications remain stable across portrait and landscape configurations.
 * 5. Automotive memory optimization (RGB_565 in Carousel vs full-resolution Lightbox) and composable contract stability.
 */
class AcStationPhotoCarouselUiTest {

    private fun createAcStation(
        id: String = "AC_STATION_001",
        name: String = "VinFast AC ChargePoint - Times City",
        images: List<String> = emptyList()
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Minh Khai, Hai Ba Trung, Hanoi",
            latitude = 20.9950,
            longitude = 105.8670,
            summary = "24/7",
            connectors = "Type 2 11kW, Type 2 7.4kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 11000L, label = "11kW", availablePlugs = 3, totalPlugs = 4),
                PowerPort(typeWatts = 7400L, label = "7.4kW", availablePlugs = 2, totalPlugs = 2)
            ),
            images = images,
            image = images.firstOrNull()
        )
    }

    private fun createDcStation(
        id: String = "DC_STATION_001",
        name: String = "VinFast DC Supercharge - Ocean Park",
        images: List<String> = emptyList()
    ): Station {
        return Station(
            id = id,
            name = name,
            address = "Gia Lam, Hanoi",
            latitude = 20.9900,
            longitude = 105.9400,
            summary = "24/7",
            connectors = "CCS2 250kW, CCS2 150kW",
            depotStatus = "Normal",
            powers = listOf(
                PowerPort(typeWatts = 250000L, label = "250kW", availablePlugs = 2, totalPlugs = 4),
                PowerPort(typeWatts = 150000L, label = "150kW", availablePlugs = 1, totalPlugs = 2)
            ),
            images = images,
            image = images.firstOrNull()
        )
    }

    private fun resolveSourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        val subProject = File("app", relativePath)
        if (subProject.exists()) return subProject
        return direct
    }

    // -------------------------------------------------------------------------
    // Test Criterion 1: Carousel Visibility and Placeholder State Helper Rules
    // -------------------------------------------------------------------------

    @Test
    fun testCarouselVisibilityAndPlaceholder_acStationLifecycle() {
        val sampleCloudFrontPhotos = listOf(
            "https://cpo-prod-s3.vinfastauto.com/stations/ac_times_city_01.jpg",
            "https://cpo-prod-s3.vinfastauto.com/stations/ac_times_city_02.jpg",
            "https://cpo-prod-s3.vinfastauto.com/stations/ac_times_city_03.jpg"
        )

        val acStationInitial = createAcStation(images = emptyList())
        assertFalse("AC station should not have DC ports", acStationInitial.hasDcPorts())
        assertFalse("AC station powers must not have DC plugs", acStationInitial.powers.any { it.isDc() })

        // State 1: Detail sheet opened, photos resolving/fetching (isLoading = true, images = empty)
        val initialCarouselVisible = NativeStationDetailSheetHelper.shouldShowCarousel(acStationInitial)
        val initialPlaceholderVisible = NativeStationDetailSheetHelper.shouldShowPhotoPlaceholder(acStationInitial, isLoading = true)
        val initialSpec = NativeStationDetailSheetHelper.resolvePhotoPlaceholderSpec(acStationInitial, isLoading = true)

        assertFalse("Carousel must not be shown while images are empty", initialCarouselVisible)
        assertTrue("Placeholder must be visible while AC station photos are resolving", initialPlaceholderVisible)
        assertTrue("Placeholder spec isVisible must be true", initialSpec.isVisible)
        assertEquals("Placeholder aspect ratio must be 16:9", 16f / 9f, initialSpec.aspectRatio)
        assertEquals("Placeholder corner radius must be 16dp", 16f, initialSpec.cornerRadiusDp)

        // Overload parity check (images vs station)
        assertEquals(
            initialPlaceholderVisible,
            NativeStationDetailSheetHelper.shouldShowPhotoPlaceholder(acStationInitial.images, isLoading = true)
        )
        assertEquals(
            initialCarouselVisible,
            NativeStationDetailSheetHelper.shouldShowCarousel(acStationInitial.images)
        )

        // State 2: Photo resolution finishes with authentic images (isLoading = true or false, images non-empty)
        val acStationEnriched = acStationInitial.copy(
            images = sampleCloudFrontPhotos,
            image = sampleCloudFrontPhotos.first()
        )

        val enrichedCarouselVisible = NativeStationDetailSheetHelper.shouldShowCarousel(acStationEnriched)
        val enrichedPlaceholderVisible = NativeStationDetailSheetHelper.shouldShowPhotoPlaceholder(acStationEnriched, isLoading = false)
        val enrichedSpec = NativeStationDetailSheetHelper.resolvePhotoPlaceholderSpec(acStationEnriched, isLoading = false)

        assertTrue("Carousel must be shown once images are populated", enrichedCarouselVisible)
        assertFalse("Placeholder must hide once carousel has images", enrichedPlaceholderVisible)
        assertFalse("Placeholder spec isVisible must be false once images exist", enrichedSpec.isVisible)

        // State 3: Photo resolution finishes with no images found (isLoading = false, images = empty)
        val acStationEmptyResult = acStationInitial.copy(images = emptyList())
        val terminalCarouselVisible = NativeStationDetailSheetHelper.shouldShowCarousel(acStationEmptyResult)
        val terminalPlaceholderVisible = NativeStationDetailSheetHelper.shouldShowPhotoPlaceholder(acStationEmptyResult, isLoading = false)
        val terminalSpec = NativeStationDetailSheetHelper.resolvePhotoPlaceholderSpec(acStationEmptyResult, isLoading = false)

        assertFalse("Carousel must remain hidden if no photos exist", terminalCarouselVisible)
        assertFalse("Placeholder must collapse to zero height when resolution concludes with empty result", terminalPlaceholderVisible)
        assertFalse("Terminal spec isVisible must be false", terminalSpec.isVisible)
    }

    @Test
    fun testCarouselVisibilityAndPlaceholder_dcStationParity() {
        val dcPhotos = listOf("https://cpo-prod-s3.vinfastauto.com/stations/dc_ocean_park.jpg")
        val dcStationInitial = createDcStation(images = emptyList())
        val dcStationEnriched = createDcStation(images = dcPhotos)

        assertTrue("DC station should have DC ports", dcStationInitial.hasDcPorts())
        assertTrue("DC station should contain DC power ports", dcStationInitial.powers.any { it.isDc() })

        // Verify identical behavior between AC and DC stations
        assertEquals(
            "AC and DC empty loading state must match",
            NativeStationDetailSheetHelper.shouldShowPhotoPlaceholder(createAcStation(images = emptyList()), isLoading = true),
            NativeStationDetailSheetHelper.shouldShowPhotoPlaceholder(dcStationInitial, isLoading = true)
        )
        assertEquals(
            "AC and DC enriched state must match",
            NativeStationDetailSheetHelper.shouldShowCarousel(createAcStation(images = dcPhotos)),
            NativeStationDetailSheetHelper.shouldShowCarousel(dcStationEnriched)
        )
    }

    // -------------------------------------------------------------------------
    // Test Criterion 2: Indicator String Formatting
    // -------------------------------------------------------------------------

    @Test
    fun testFormatCarouselIndicator_exactPillBadges() {
        // Zero or empty cases
        assertEquals("0 / 0", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 0, totalCount = 0))
        assertEquals("0 / 0", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = -1, totalCount = 0))

        // Single image
        assertEquals("1 / 1", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 0, totalCount = 1))

        // Standard multi-photo paging (1-based human-readable page / total)
        assertEquals("1 / 3", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 0, totalCount = 3))
        assertEquals("2 / 3", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 1, totalCount = 3))
        assertEquals("3 / 3", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 2, totalCount = 3))

        // Large photo set (e.g. 10 photos)
        assertEquals("1 / 10", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 0, totalCount = 10))
        assertEquals("5 / 10", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 4, totalCount = 10))
        assertEquals("10 / 10", NativeStationDetailSheetHelper.formatCarouselIndicator(currentPage = 9, totalCount = 10))
    }

    // -------------------------------------------------------------------------
    // Test Criterion 3: Lightbox Modal Activation Contracts
    // -------------------------------------------------------------------------

    @Test
    fun testLightboxActivationContract_acAndDcIdenticalParity() {
        val photos = listOf(
            "https://cpo-prod-s3.vinfastauto.com/photo1.jpg",
            "https://cpo-prod-s3.vinfastauto.com/photo2.jpg",
            "https://cpo-prod-s3.vinfastauto.com/photo3.jpg"
        )
        val acStation = createAcStation(images = photos)
        val dcStation = createDcStation(images = photos)

        // Case 1: No photo tapped (null selected index)
        assertFalse(NativeStationDetailSheetHelper.shouldActivateLightbox(null, acStation))
        assertFalse(NativeStationDetailSheetHelper.shouldActivateLightbox(null, dcStation))
        assertFalse(NativeStationDetailSheetHelper.shouldActivateLightbox(null, photos))

        // Case 2: Valid photo clicked (indices 0, 1, 2)
        for (index in photos.indices) {
            assertTrue("AC station lightbox must activate for valid index $index",
                NativeStationDetailSheetHelper.shouldActivateLightbox(index, acStation))
            assertTrue("DC station lightbox must activate for valid index $index",
                NativeStationDetailSheetHelper.shouldActivateLightbox(index, dcStation))
            assertEquals(
                "Contract parity between AC and DC at index $index",
                NativeStationDetailSheetHelper.shouldActivateLightbox(index, acStation),
                NativeStationDetailSheetHelper.shouldActivateLightbox(index, dcStation)
            )
        }

        // Case 3: Out of bound indices (-1, size, size + 10)
        val invalidIndices = listOf(-1, -5, photos.size, photos.size + 1, 99)
        for (badIndex in invalidIndices) {
            assertFalse("Out of bound index $badIndex must not activate AC lightbox",
                NativeStationDetailSheetHelper.shouldActivateLightbox(badIndex, acStation))
            assertFalse("Out of bound index $badIndex must not activate DC lightbox",
                NativeStationDetailSheetHelper.shouldActivateLightbox(badIndex, dcStation))
        }

        // Case 4: Empty images list
        val emptyAc = createAcStation(images = emptyList())
        val emptyDc = createDcStation(images = emptyList())
        assertFalse(NativeStationDetailSheetHelper.shouldActivateLightbox(0, emptyAc))
        assertFalse(NativeStationDetailSheetHelper.shouldActivateLightbox(0, emptyDc))
        assertFalse(NativeStationDetailSheetHelper.shouldActivateLightbox(0, emptyList()))
    }

    // -------------------------------------------------------------------------
    // Test Criterion 4: Layout Specifications Stability & Automotive Dimens
    // -------------------------------------------------------------------------

    @Test
    fun testPhotoLayoutSpec_stabilityAcrossPortraitAndLandscape() {
        val portraitSpec = NativeStationDetailSheetHelper.getPhotoLayoutSpec(isLandscape = false)
        val landscapeSpec = NativeStationDetailSheetHelper.getPhotoLayoutSpec(isLandscape = true)

        // 16:9 ratio preservation
        assertEquals("Portrait aspect ratio must be 16:9", 16f / 9f, portraitSpec.aspectRatio, 0.0001f)
        assertEquals("Landscape aspect ratio must be 16:9", 16f / 9f, landscapeSpec.aspectRatio, 0.0001f)

        // 16dp rounded corners
        assertEquals("Portrait corner radius must be 16dp", 16f, portraitSpec.cornerRadiusDp, 0.0001f)
        assertEquals("Landscape corner radius must be 16dp", 16f, landscapeSpec.cornerRadiusDp, 0.0001f)

        // Dot indicator bounds (2 to 5 photos)
        assertEquals(2, portraitSpec.minDotIndicatorCount)
        assertEquals(5, portraitSpec.maxDotIndicatorCount)
        assertEquals(portraitSpec.minDotIndicatorCount, landscapeSpec.minDotIndicatorCount)
        assertEquals(portraitSpec.maxDotIndicatorCount, landscapeSpec.maxDotIndicatorCount)

        // Automotive memory protection flag
        assertTrue("RGB_565 decoding must be preferred in carousel thumbnails", portraitSpec.isRgb565PreferredForThumbnails)
        assertTrue("RGB_565 decoding must be preferred in carousel thumbnails", landscapeSpec.isRgb565PreferredForThumbnails)

        // Full equality across orientations
        assertEquals("PhotoLayoutSpec must be identical across portrait and landscape", portraitSpec, landscapeSpec)
    }

    // -------------------------------------------------------------------------
    // Test Criterion 5: Architecture, Composable Contracts & Memory Guards
    // -------------------------------------------------------------------------

    @Test
    fun testComposableDeclarationsAndMemoryGuards() {
        // 1. Verify Composable functions exist on NativeStationDetailSheetKt
        val sheetClass = Class.forName("com.evcs.favorites.ui.components.NativeStationDetailSheetKt")
        val methods = sheetClass.declaredMethods.map { it.name }

        assertTrue(
            "StationPhotoCarousel composable must exist in NativeStationDetailSheetKt",
            methods.any { it.startsWith("StationPhotoCarousel") }
        )
        assertTrue(
            "StationPhotoPlaceholder composable must exist in NativeStationDetailSheetKt",
            methods.any { it.startsWith("StationPhotoPlaceholder") }
        )
        assertTrue(
            "NativeStationDetailContent composable must exist in NativeStationDetailSheetKt",
            methods.any { it.startsWith("NativeStationDetailContent") }
        )

        // 2. Verify Coil RGB_565 memory guard and memoization in StationPhotoCarousel
        val sheetFile = resolveSourceFile("src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt")
        assertTrue("NativeStationDetailSheet.kt must exist", sheetFile.exists())
        val sheetCode = sheetFile.readText()

        assertTrue(
            "StationPhotoCarousel must use allowRgb565(true) for JVM heap conservation on automotive TBox",
            sheetCode.contains("allowRgb565(true)")
        )
        assertTrue(
            "StationPhotoCarousel must memoize ImageRequest with remember(images[page])",
            sheetCode.contains("remember(images[page])")
        )
        assertTrue(
            "NativeStationDetailContent must bind StationPhotoPlaceholder for resolving photos",
            sheetCode.contains("StationPhotoPlaceholder()")
        )

        // 3. Verify Lightbox Modal preserves full-resolution rendering without RGB_565 downsampling
        val modalFile = resolveSourceFile("src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt")
        assertTrue("StationPhotoViewerModal.kt must exist", modalFile.exists())
        val modalCode = modalFile.readText()

        assertTrue(
            "StationPhotoViewerModal must memoize ImageRequest with remember(imageUrl)",
            modalCode.contains("remember(imageUrl)")
        )
        assertFalse(
            "StationPhotoViewerModal must NOT downsample with allowRgb565 to maintain full ARGB_8888 fidelity",
            modalCode.contains("allowRgb565(true)")
        )

        // 4. Verify Landscape screens reactively reflect station photo updates
        val favLandscapeFile = resolveSourceFile("src/main/java/com/evcs/favorites/ui/screens/landscape/FavoritesLandscapeScreen.kt")
        assertTrue("FavoritesLandscapeScreen.kt must exist", favLandscapeFile.exists())
        val favLandscapeCode = favLandscapeFile.readText()
        assertTrue(
            "FavoritesLandscapeScreen must pass reactive effectiveStation to NativeStationDetailContent",
            favLandscapeCode.contains("val effectiveStation = effectiveDetailState.station ?: activeStationForDetail")
        )
    }
}
