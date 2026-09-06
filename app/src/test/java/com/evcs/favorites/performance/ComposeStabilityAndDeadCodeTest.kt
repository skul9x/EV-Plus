package com.evcs.favorites.performance

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.components.NativeStationDetailSheetHelper
import com.evcs.favorites.ui.components.StatCardModel
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.state.NearbyUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Compose Stability & Dead Code Elimination (PERF-004, PERF-014).
 *
 * Verifies:
 * 1. Reflection check: NearbyUiState is annotated with @androidx.compose.runtime.Immutable.
 * 2. Reflection check: FavoritesUiState.Success is annotated with @androidx.compose.runtime.Immutable.
 * 3. NativeStationDetailSheet compiles cleanly and dead StatCard / StatCardShimmer composables are absent.
 * 4. Active sheet components and helpers (NativeStationDetailSheetHelper, StatCardModel) are preserved.
 * 5. State equality, copy semantics, and immutability invariants hold across NearbyUiState and FavoritesUiState.Success.
 */
class ComposeStabilityAndDeadCodeTest {

    private fun hasImmutableAnnotation(clazz: Class<*>): Boolean {
        if (clazz.isAnnotationPresent(Immutable::class.java) ||
            clazz.annotations.any { it.annotationClass.java.name == Immutable::class.java.name }
        ) {
            return true
        }

        // Compose @Immutable has AnnotationRetention.BINARY, which is stored in the
        // bytecode RuntimeInvisibleAnnotations attribute and constant pool.
        val resourcePath = clazz.name.replace('.', '/') + ".class"
        val stream = clazz.classLoader?.getResourceAsStream(resourcePath)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
        if (stream != null) {
            val bytes = stream.use { it.readBytes() }
            val bytecodeString = String(bytes, Charsets.ISO_8859_1)
            return bytecodeString.contains("Landroidx/compose/runtime/Immutable;")
        }
        return false
    }

    // =========================================================================
    // 1. Compose Stability Annotations Verification
    // =========================================================================

    @Test
    fun testNearbyUiStateCarriesImmutableAnnotation() {
        assertTrue(
            "NearbyUiState must carry @androidx.compose.runtime.Immutable annotation for smart recomposition skipping",
            hasImmutableAnnotation(NearbyUiState::class.java)
        )
    }

    @Test
    fun testFavoritesUiStateSuccessCarriesImmutableAnnotation() {
        assertTrue(
            "FavoritesUiState.Success must carry @androidx.compose.runtime.Immutable annotation for smart recomposition skipping",
            hasImmutableAnnotation(FavoritesUiState.Success::class.java)
        )
    }

    // =========================================================================
    // 2. Dead Code Elimination: StatCard Absence & Active Component Preservation
    // =========================================================================

    @Test
    fun testDeadStatCardComposableIsEliminated() {
        // Load the compiled NativeStationDetailSheetKt class
        val sheetClass = Class.forName("com.evcs.favorites.ui.components.NativeStationDetailSheetKt")
        val methods = sheetClass.declaredMethods

        // StatCard must NOT exist as a compiled method in NativeStationDetailSheetKt
        val statCardMethods = methods.filter { it.name == "StatCard" }
        assertTrue(
            "Unused StatCard composable must be eliminated from NativeStationDetailSheetKt",
            statCardMethods.isEmpty()
        )

        // StatCardShimmer must NOT exist as a compiled method in NativeStationDetailSheetKt
        val statCardShimmerMethods = methods.filter { it.name == "StatCardShimmer" }
        assertTrue(
            "Unused StatCardShimmer composable must be eliminated from NativeStationDetailSheetKt",
            statCardShimmerMethods.isEmpty()
        )

        // Verify active layout components and methods are retained
        val hasNativeStationDetailSheet = methods.any { it.name.startsWith("NativeStationDetailSheet") }
        assertTrue(
            "NativeStationDetailSheet must be preserved in NativeStationDetailSheetKt",
            hasNativeStationDetailSheet
        )

        val hasNativeStationDetailContent = methods.any { it.name.startsWith("NativeStationDetailContent") }
        assertTrue(
            "NativeStationDetailContent must be preserved in NativeStationDetailSheetKt",
            hasNativeStationDetailContent
        )

        val hasStationPhotoCarousel = methods.any { it.name.startsWith("StationPhotoCarousel") }
        assertTrue(
            "StationPhotoCarousel must be preserved in NativeStationDetailSheetKt",
            hasStationPhotoCarousel
        )

        val hasLiveForecastCapsule = methods.any { it.name.startsWith("LiveForecastCapsule") }
        assertTrue(
            "LiveForecastCapsule must be preserved in NativeStationDetailSheetKt",
            hasLiveForecastCapsule
        )

        // Verify StationPhotoViewerModal is preserved in its component file
        val viewerModalClass = Class.forName("com.evcs.favorites.ui.components.StationPhotoViewerModalKt")
        val hasViewerModal = viewerModalClass.declaredMethods.any { it.name.startsWith("StationPhotoViewerModal") }
        assertTrue(
            "StationPhotoViewerModal must be preserved",
            hasViewerModal
        )

        // Verify helper methods remain accessible and functional
        assertFalse(
            "NativeStationDetailSheetHelper.shouldAnimateShimmer must remain functional",
            NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = false, stats = null)
        )
        val sampleGrid = NativeStationDetailSheetHelper.resolveStatsGrid(stats = null, isLoadingStats = false)
        assertEquals(4, sampleGrid.size)

        // Verify StatCardModel data class integrity
        val sampleModel = StatCardModel(header = "H", value = "V", footer = "F")
        assertFalse(sampleModel.isLoading)
    }

    // =========================================================================
    // 3. NearbyUiState Equality and Immutability Invariants
    // =========================================================================

    @Test
    fun testNearbyUiStateEqualityAndImmutabilityInvariants() {
        val s1 = Station(
            id = "st_01",
            name = "Trạm Times City",
            address = "458 Minh Khai, Hà Nội",
            latitude = 20.9950,
            longitude = 105.8675,
            summary = "Trụ sạc nhanh",
            connectors = "60kW, 250kW",
            depotStatus = "Normal"
        )
        val metrics = DrivingMetrics(
            distanceMeters = 1500,
            durationSeconds = 300,
            trafficCondition = TrafficCondition.FREE_FLOW,
            engineUsed = RoutingEngineType.OSRM
        )

        val initialState = NearbyUiState(
            hasSearched = true,
            isLocating = false,
            isSearching = false,
            isRoutingLoading = false,
            userLatitude = 20.9950,
            userLongitude = 105.8675,
            selectedWattages = setOf(WattageOption.KW_60, WattageOption.KW_250),
            rawStations = listOf(s1),
            top10DisplayStations = listOf(s1),
            routingMetrics = mapOf("st_01" to metrics),
            favoriteStationIds = setOf("st_01"),
            activeFilterMode = SmartFilterMode.DC,
            selectedDcTier = DcWattageTier.GE_120KW
        )

        // 1. Exact copy equality
        val identicalState = initialState.copy()
        assertEquals("Identical copy must equal original state", initialState, identicalState)
        assertEquals("Identical copy must have same hashcode", initialState.hashCode(), identicalState.hashCode())

        // 2. Immutability & smart recomposition stability: collection reference reuse
        val stateWithPillUpdate = initialState.copy(isRoutingLoading = true)
        assertNotEquals(initialState, stateWithPillUpdate)
        // Collections remain strictly the same instance to allow smart Compose skipping
        assertTrue(
            "Collections must preserve referential identity across non-collection state changes",
            initialState.top10DisplayStations === stateWithPillUpdate.top10DisplayStations
        )
        assertTrue(
            "Routing metrics map must preserve referential identity",
            initialState.routingMetrics === stateWithPillUpdate.routingMetrics
        )
        assertTrue(
            "Favorite station IDs set must preserve referential identity",
            initialState.favoriteStationIds === stateWithPillUpdate.favoriteStationIds
        )

        // 3. Filter summary pill text dynamic behavior
        assertEquals(
            "Tìm thấy 1 trạm có cổng DC ≥ 120kW khả dụng",
            initialState.filterSummaryPillText
        )

        val acState = initialState.copy(
            activeFilterMode = SmartFilterMode.AC,
            selectedDcTier = null
        )
        assertEquals("Tìm thấy 1 trạm có cổng AC khả dụng", acState.filterSummaryPillText)

        val noneState = initialState.copy(
            activeFilterMode = SmartFilterMode.NONE,
            selectedDcTier = null
        )
        assertEquals("Top 10 trạm sạc VinFast gần nhất còn cổng trống", noneState.filterSummaryPillText)
    }

    // =========================================================================
    // 4. FavoritesUiState.Success Equality and Immutability Invariants
    // =========================================================================

    @Test
    fun testFavoritesUiStateSuccessEqualityAndImmutabilityInvariants() {
        val s1 = Station(
            id = "fav_01",
            name = "Trạm Royal City",
            address = "72A Nguyễn Trãi, Thanh Xuân, Hà Nội",
            latitude = 21.0028,
            longitude = 105.8158,
            summary = "Trụ sạc 30kW - 250kW",
            connectors = "30kW, 60kW, 250kW",
            depotStatus = "Normal"
        )
        val s2 = Station(
            id = "fav_02",
            name = "Trạm Ocean Park",
            address = "Gia Lâm, Hà Nội",
            latitude = 20.9902,
            longitude = 105.9405,
            summary = "Trụ sạc siêu nhanh",
            connectors = "250kW",
            depotStatus = "Normal"
        )

        val stationList = listOf(s1, s2)
        val successState = FavoritesUiState.Success(
            stations = stationList,
            isRefreshing = false,
            selectedStationForDetail = null
        )

        // 1. Exact copy equality
        val identicalCopy = successState.copy()
        assertEquals(successState, identicalCopy)
        assertEquals(successState.hashCode(), identicalCopy.hashCode())

        // 2. Modifying isRefreshing retains stable stations list reference
        val refreshingState = successState.copy(isRefreshing = true)
        assertNotEquals(successState, refreshingState)
        assertTrue(
            "Stations list reference must remain identical across refresh updates",
            successState.stations === refreshingState.stations
        )
        assertEquals(2, refreshingState.stations.size)

        // 3. Detail sheet selection retains stations list reference
        val selectedState = successState.copy(selectedStationForDetail = s1)
        assertNotEquals(successState, selectedState)
        assertTrue(
            "Stations list reference must remain identical when detail modal opens",
            successState.stations === selectedState.stations
        )
        assertEquals("fav_01", selectedState.selectedStationForDetail?.id)

        // 4. Updating station list produces distinct state
        val updatedList = listOf(s1)
        val reducedState = successState.copy(stations = updatedList)
        assertNotEquals(successState, reducedState)
        assertEquals(1, reducedState.stations.size)
    }
}
