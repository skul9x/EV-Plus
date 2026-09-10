package com.evcs.favorites.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.evcs.favorites.ui.theme.AppIcons

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
        unselectedIcon = AppIcons.LocationOn
    ),
    FAVORITES(
        label = "Yêu thích",
        selectedIcon = Icons.Filled.Favorite,
        unselectedIcon = AppIcons.FavoriteBorder
    ),
    SETTINGS(
        label = "Cài đặt",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = AppIcons.Settings
    )
}
