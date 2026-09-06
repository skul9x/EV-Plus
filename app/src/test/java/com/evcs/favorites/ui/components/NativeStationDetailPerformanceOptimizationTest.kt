package com.evcs.favorites.ui.components

import com.evcs.favorites.domain.Station24hStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single Comprehensive Verification Test for Phase 02:
 * Infinite Shimmer Gating & Recomposition Performance Optimization.
 *
 * Verifies:
 * 1. Shimmer gating predicate logic (`shouldAnimateShimmer`):
 *    - Strictly active (`true`) during initial loading (`isLoadingStats = true && stats == null`).
 *    - Inactive (`false`) when valid stats exist (idle or refresh) or during fallback/error states.
 * 2. `StatCardModel` lifecycle and transition from `isLoading = true` to `isLoading = false`
 *    upon receiving valid `Station24hStats`.
 * 3. Exact formatting of static 24h usage statistics (Peak, Average, Peak Hour, Fill Rate).
 * 4. Zero shimmer leakage when errors occur or fallback values are rendered (`stats = null && isLoadingStats = false`).
 * 5. Data integrity and default values of `StatCardModel`.
 */
class NativeStationDetailPerformanceOptimizationTest {

    private val sampleStats = Station24hStats(
        peakUsage = 12,
        avgUsage = 5,
        peakHour = "17-19h",
        fillRate = 75
    )

    // =========================================================================
    // 1. Shimmer Gating Logic Verification
    // =========================================================================

    @Test
    fun testShimmerGatingPredicate() {
        // Initial loading state: loading is true and stats are null -> shimmer MUST be active
        assertTrue(
            "Shimmer should be active during initial loading",
            NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = true, stats = null)
        )

        // Loaded state: loading is false and stats are populated -> shimmer MUST be inactive
        assertFalse(
            "Shimmer must not be active once stats are populated",
            NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = false, stats = sampleStats)
        )

        // Refresh state with existing data: loading is true and stats are already present ->
        // previous stats remain visible, shimmer MUST be inactive to prevent layout jitter and unnecessary ticking
        assertFalse(
            "Shimmer must not restart during refresh if cached stats exist",
            NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = true, stats = sampleStats)
        )

        // Error / Fallback state: loading is false and stats are null -> shimmer MUST be inactive (zero leakage)
        assertFalse(
            "Shimmer must never run when stats failed to load",
            NativeStationDetailSheetHelper.shouldAnimateShimmer(isLoadingStats = false, stats = null)
        )
    }

    // =========================================================================
    // 2. StatCardModel Loading-to-Loaded Transition & Data Integrity
    // =========================================================================

    @Test
    fun testStatCardModelLoadingToLoadedTransition() {
        // Step 1: Initial loading state
        val loadingCards = NativeStationDetailSheetHelper.resolveStatsGrid(
            stats = null,
            isLoadingStats = true
        )
        assertEquals("Grid must contain 4 cards", 4, loadingCards.size)
        loadingCards.forEach { card ->
            assertTrue("Card [${card.header}] must have isLoading = true during load", card.isLoading)
            assertEquals("Placeholder value during loading must be '...'", "...", card.value)
        }

        // Step 2: Valid data arrives -> transition to static values with zero shimmer
        val loadedCards = NativeStationDetailSheetHelper.resolveStatsGrid(
            stats = sampleStats,
            isLoadingStats = false
        )
        assertEquals("Grid must contain 4 cards", 4, loadedCards.size)

        // Card 1: CAO ĐIỂM
        val peakCard = loadedCards[0]
        assertEquals("CAO ĐIỂM", peakCard.header)
        assertEquals("12", peakCard.value)
        assertEquals("ô tô sạc", peakCard.footer)
        assertFalse("Peak card must not be loading", peakCard.isLoading)

        // Card 2: TRUNG BÌNH
        val avgCard = loadedCards[1]
        assertEquals("TRUNG BÌNH", avgCard.header)
        assertEquals("5", avgCard.value)
        assertEquals("ô tô sạc", avgCard.footer)
        assertFalse("Average card must not be loading", avgCard.isLoading)

        // Card 3: GIỜ CAO ĐIỂM
        val peakHourCard = loadedCards[2]
        assertEquals("GIỜ CAO ĐIỂM", peakHourCard.header)
        assertEquals("17-19h", peakHourCard.value)
        assertEquals("đông xe nhất", peakHourCard.footer)
        assertFalse("Peak hour card must not be loading", peakHourCard.isLoading)

        // Card 4: TỈ LỆ LẤP ĐẦY
        val fillRateCard = loadedCards[3]
        assertEquals("TỈ LỆ LẤP ĐẦY", fillRateCard.header)
        assertEquals("75%", fillRateCard.value)
        assertEquals("theo số cổng", fillRateCard.footer)
        assertFalse("Fill rate card must not be loading", fillRateCard.isLoading)
    }

    // =========================================================================
    // 3. Fallback and Zero Shimmer State Leakage Verification
    // =========================================================================

    @Test
    fun testZeroShimmerStateLeakageOnFallbackAndError() {
        // When telemetry or socket query times out / fails, stats remain null and loading ends
        val fallbackCards = NativeStationDetailSheetHelper.resolveStatsGrid(
            stats = null,
            isLoadingStats = false
        )

        assertEquals(4, fallbackCards.size)
        fallbackCards.forEach { card ->
            assertFalse(
                "Fallback card [${card.header}] must strictly have isLoading = false (zero shimmer)",
                card.isLoading
            )
            assertEquals(
                "Fallback card [${card.header}] must display '-' instead of placeholder",
                "-",
                card.value
            )
        }
    }

    // =========================================================================
    // 4. Edge Value Formatting & Model Defaults
    // =========================================================================

    @Test
    fun testEdgeValueFormattingAndModelDefaults() {
        // Zero usage metrics
        val zeroStats = Station24hStats(
            peakUsage = 0,
            avgUsage = 0,
            peakHour = "Không có",
            fillRate = 0
        )
        val zeroCards = NativeStationDetailSheetHelper.resolveStatsGrid(
            stats = zeroStats,
            isLoadingStats = false
        )

        assertEquals("0", zeroCards[0].value)
        assertEquals("0", zeroCards[1].value)
        assertEquals("Không có", zeroCards[2].value)
        assertEquals("0%", zeroCards[3].value)
        zeroCards.forEach { assertFalse(it.isLoading) }

        // StatCardModel default value
        val defaultModel = StatCardModel(
            header = "TEST",
            value = "100",
            footer = "test"
        )
        assertFalse("StatCardModel.isLoading must default to false", defaultModel.isLoading)
    }
}
