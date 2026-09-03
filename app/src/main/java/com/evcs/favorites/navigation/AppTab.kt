package com.evcs.favorites.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level application destinations for bottom navigation.
 */
enum class AppTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    NEARBY(
        label = "Quanh đây",
        selectedIcon = Icons.Filled.LocationOn,
        unselectedIcon = Icons.Outlined.LocationOn
    ),
    FAVORITES(
        label = "Yêu thích",
        selectedIcon = Icons.Filled.Favorite,
        unselectedIcon = Icons.Outlined.FavoriteBorder
    )
}
