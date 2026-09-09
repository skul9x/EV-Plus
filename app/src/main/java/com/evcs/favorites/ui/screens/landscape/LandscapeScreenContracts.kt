package com.evcs.favorites.ui.screens.landscape

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Architectural contract constants and layout guidelines for dedicated
 * automotive landscape screens ([NearbyLandscapeScreen] & [FavoritesLandscapeScreen]).
 */
object LandscapeScreenContracts {
    const val TOP_APP_BAR_VISIBLE: Boolean = false
    const val FILTER_SUMMARY_PILL_VISIBLE: Boolean = false
    const val AUTO_SELECTION_ENABLED: Boolean = true

    val COMPACT_NAV_RAIL_WIDTH_DP: Dp = 58.dp
    val FILTER_BAR_VERTICAL_PADDING_DP: Dp = 4.dp
    val SCREEN_HORIZONTAL_PADDING_DP: Dp = 8.dp
    val SCREEN_VERTICAL_PADDING_DP: Dp = 6.dp
    val MASTER_DETAIL_SPACING_DP: Dp = 8.dp
    val CARD_SPACING_DP: Dp = 8.dp
    val DETAIL_SURFACE_CORNER_RADIUS_DP: Dp = 16.dp
    val DETAIL_SURFACE_ELEVATION_DP: Dp = 2.dp
}
