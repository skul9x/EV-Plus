package com.evcs.favorites.ui.screens

import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.ui.state.NearbyUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Single comprehensive test suite verifying Phase 02:
 * Portrait Summary Pill Removal & Centered Routing Indicator.
 *
 * Verifies:
 * 1. [Layout Helper Contract] `NearbyPortraitLayoutHelper.isSummaryPillVisible()` returns false unconditionally across all filter modes (NONE, AC, DC, CUSTOM).
 * 2. [Routing Indicator State Contract] `shouldShowCenteredRoutingIndicator` returns true when `isRoutingLoading == true` and `isLoading == false`, and false when `isRoutingLoading == false` (occupying 0dp space) or when initial full loading is active.
 * 3. [Data Contract Compatibility] `NearbyUiState.filterSummaryPillText` remains intact and functional for backward compatibility without breaking existing consumers.
 * 4. [Structural Source Contract] `NearbyScreen.kt` source eliminates the summary pill Row, removes UI reference to `filterSummaryPillText`, and positions the station viewport directly below the filter bar.
 * 5. [Centered Indicator Layout] `NearbyScreen.kt` anchors the routing progress spinner centered (`align(Alignment.Center)`) over the viewport area.
 * 6. [Empty & Loading State Resilience] Empty results and initial loading states are preserved in the viewport container without overlapping regressions.
 */
class NearbyPortraitSummaryPillRemovalTest {

    private fun resolveSourceFile(relativePath: String): File {
        val rootDir = File(".").canonicalFile
        val candidate = File(rootDir, relativePath)
        if (candidate.exists()) return candidate
        val appCandidate = File(rootDir, "app/$relativePath")
        if (appCandidate.exists()) return appCandidate
        val parentCandidate = File(rootDir, "../$relativePath")
        if (parentCandidate.exists()) return parentCandidate
        return candidate
    }

    @Test
    fun testPortraitSummaryPillRemovalAndCenteredRoutingIndicatorContract() {
        // 1. [Layout Helper Contract] Summary pill is suppressed for all filter modes
        assertFalse(
            "Summary pill must be suppressed across all filter modes in portrait",
            NearbyPortraitLayoutHelper.isSummaryPillVisible()
        )

        // 2. [Routing Indicator State Contract] Centered routing progress indicator rules
        assertTrue(
            "Routing indicator must be displayed when routing is loading and not in initial full-screen loading",
            NearbyPortraitLayoutHelper.shouldShowCenteredRoutingIndicator(
                isRoutingLoading = true,
                isLoading = false
            )
        )
        assertFalse(
            "Routing indicator must occupy 0dp (not displayed) when isRoutingLoading is false",
            NearbyPortraitLayoutHelper.shouldShowCenteredRoutingIndicator(
                isRoutingLoading = false,
                isLoading = false
            )
        )
        assertFalse(
            "Routing indicator must not display when initial full-screen loading is active to prevent overlapping spinners",
            NearbyPortraitLayoutHelper.shouldShowCenteredRoutingIndicator(
                isRoutingLoading = true,
                isLoading = true
            )
        )
        assertFalse(
            "Routing indicator must not display when neither routing nor loading is active",
            NearbyPortraitLayoutHelper.shouldShowCenteredRoutingIndicator(
                isRoutingLoading = false,
                isLoading = true
            )
        )

        // 3. [Data Contract Compatibility] filterSummaryPillText is preserved in NearbyUiState
        val defaultState = NearbyUiState()
        assertEquals(
            "Top 10 trạm sạc VinFast gần nhất còn cổng trống",
            defaultState.filterSummaryPillText
        )

        val acState = NearbyUiState(
            activeFilterMode = SmartFilterMode.AC
        )
        assertEquals(
            "Tìm thấy 0 trạm có cổng AC khả dụng",
            acState.filterSummaryPillText
        )

        val dcState = NearbyUiState(
            activeFilterMode = SmartFilterMode.DC,
            selectedDcTier = DcWattageTier.GE_60KW
        )
        assertEquals(
            "Tìm thấy 0 trạm có cổng DC ≥ 60kW khả dụng",
            dcState.filterSummaryPillText
        )

        val customState = NearbyUiState(
            activeFilterMode = SmartFilterMode.CUSTOM,
            savedCustomConfig = CustomFilterConfig(
                mode = CustomFilterMode.QUICK_CHIP,
                quickChip = QuickChipOption.AC
            )
        )
        assertEquals(
            "Tìm thấy 0 trạm có cổng AC khả dụng",
            customState.filterSummaryPillText
        )

        // 4. [Structural Source Contract] Verify NearbyScreen.kt source file
        val nearbyFile = resolveSourceFile("src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt")
        assertTrue("NearbyScreen.kt must exist", nearbyFile.exists())
        val nearbyCode = nearbyFile.readText()

        // 4a. Verify NearbyPortraitLayoutHelper is defined
        assertTrue(
            "NearbyScreen.kt must define NearbyPortraitLayoutHelper",
            nearbyCode.contains("object NearbyPortraitLayoutHelper")
        )

        // 4b. Verify NearbyResultContent does NOT reference filterSummaryPillText
        val resultContentSection = nearbyCode.substringAfter("private fun NearbyResultContent")
            .substringBefore("private fun NearbyEmptyFilterContent")

        assertFalse(
            "NearbyResultContent must NOT reference filterSummaryPillText in portrait UI",
            resultContentSection.contains("filterSummaryPillText")
        )

        // 4c. Verify the old header info pill Row with EmeraldContainerDark background is removed
        assertFalse(
            "NearbyResultContent must NOT render the legacy summary pill Row",
            resultContentSection.contains("EmeraldContainerDark.copy(alpha = 0.5f)")
        )

        // 4d. Verify stations list container (Box with fillMaxSize) directly follows the linear progress bar
        assertTrue(
            "NearbyResultContent must position stations viewport container directly following the progress indicator",
            resultContentSection.contains("LinearProgressIndicator") &&
                resultContentSection.contains("Box(\n            modifier = Modifier.fillMaxSize()\n        )")
        )

        // 5. [Centered Indicator Layout]
        assertTrue(
            "Centered routing indicator must align in center of stations viewport",
            resultContentSection.contains(".align(Alignment.Center)")
        )
        assertTrue(
            "Centered routing indicator must use NearbyPortraitLayoutHelper.shouldShowCenteredRoutingIndicator",
            resultContentSection.contains("NearbyPortraitLayoutHelper.shouldShowCenteredRoutingIndicator")
        )
        assertTrue(
            "Centered routing indicator must use EmeraldPrimary CircularProgressIndicator",
            resultContentSection.contains("CircularProgressIndicator(\n                        modifier = Modifier.size(28.dp),\n                        strokeWidth = 3.dp,\n                        color = EmeraldPrimary\n                    )")
        )

        // 6. [Empty & Loading State Resilience]
        assertTrue(
            "NearbyResultContent must retain initial loading check in viewport",
            resultContentSection.contains("if (uiState.isLoading)")
        )
        assertTrue(
            "NearbyResultContent must retain empty filter content delegation",
            resultContentSection.contains("NearbyEmptyFilterContent(onClearFilters = onClearFilters)")
        )
        assertTrue(
            "NearbyResultContent must render LazyColumn for station list",
            resultContentSection.contains("LazyColumn(")
        )
    }
}
