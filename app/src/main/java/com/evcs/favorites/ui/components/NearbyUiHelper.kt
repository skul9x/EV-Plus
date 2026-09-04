package com.evcs.favorites.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.theme.AppIcons

/**
 * Model describing visual state for the interactive station card favorite button.
 */
data class FavoriteIconState(
    val isFavorite: Boolean,
    val icon: ImageVector,
    val tintColor: Color,
    val contentDescription: String
)

/**
 * Pure Kotlin/Compose UI helper for Nearby stations components:
 * - Wattage options descending sorting & label formatting
 * - Multi-selection toggle & clear filter state logic
 * - Heart icon state resolution (colors, icons, content descriptions)
 * - Accessibility & dialog string constants
 */
object NearbyUiHelper {

    val CoralRed: Color = Color(0xFFEF4444)
    val SlateGray: Color = Color(0xFF94A3B8)

    const val CLEAR_FILTERS_LABEL: String = "Xóa bộ lọc"
    const val FAVORITE_ACTION_ADD: String = "Lưu yêu thích"
    const val FAVORITE_ACTION_REMOVE: String = "Bỏ yêu thích"

    const val LOGIN_REQUIRED_TITLE: String = "Đăng nhập để lưu yêu thích"
    const val LOGIN_REQUIRED_DESCRIPTION: String =
        "Đăng nhập tài khoản EVCS để đồng bộ các trạm sạc yêu thích của bạn trên mọi thiết bị."
    const val LOGIN_REQUIRED_CONFIRM: String = "Đăng nhập ngay"
    const val LOGIN_REQUIRED_DISMISS: String = "Để sau"

    /**
     * All supported [WattageOption]s in descending order of power rating.
     * 360kW, 300kW, 250kW, 180kW, 150kW, 120kW, 80kW, 60kW, 40kW, 30kW, 22kW, 20kW, 11kW, 7kW, 3.5kW.
     */
    val SORTED_WATTAGE_OPTIONS: List<WattageOption> =
        WattageOption.entries.sortedByDescending { it.watts }

    /**
     * Returns the sorted list of wattage options.
     */
    fun getSortedWattageOptions(): List<WattageOption> = SORTED_WATTAGE_OPTIONS

    /**
     * Formats the chip display label for a given [WattageOption].
     */
    fun formatWattageChipLabel(option: WattageOption): String = option.label

    /**
     * Adds or removes [option] from [current] set.
     */
    fun toggleWattageSelection(
        current: Set<WattageOption>,
        option: WattageOption
    ): Set<WattageOption> {
        return if (current.contains(option)) {
            current - option
        } else {
            current + option
        }
    }

    /**
     * Returns true if the "Clear filters" chip should be displayed.
     */
    fun isClearFiltersVisible(selectedWattages: Set<WattageOption>): Boolean {
        return selectedWattages.isNotEmpty()
    }

    /**
     * Returns an empty set to reset all active wattage filters.
     */
    fun clearWattageSelection(): Set<WattageOption> {
        return emptySet()
    }

    /**
     * Resolves the heart button visual state based on whether the station is favorited.
     * - isFavorite = true -> Coral Red (#EF4444) + Icons.Filled.Favorite
     * - isFavorite = false -> Slate Gray (#94A3B8) + Icons.Outlined.FavoriteBorder
     */
    fun resolveFavoriteIconState(isFavorite: Boolean): FavoriteIconState {
        return if (isFavorite) {
            FavoriteIconState(
                isFavorite = true,
                icon = Icons.Filled.Favorite,
                tintColor = CoralRed,
                contentDescription = FAVORITE_ACTION_REMOVE
            )
        } else {
            FavoriteIconState(
                isFavorite = false,
                icon = AppIcons.FavoriteBorder,
                tintColor = SlateGray,
                contentDescription = FAVORITE_ACTION_ADD
            )
        }
    }
}
