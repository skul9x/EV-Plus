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
 * Origin source triggering a station refresh or list reload.
 */
enum class RefreshTriggerType {
    /** Explicit user action such as tapping the refresh button or pull-to-refresh */
    USER_REFRESH,
    /** Passive periodic background refresh or telemetry update */
    PASSIVE_BACKGROUND,
    /** Infinite scroll pagination or lazy loading */
    PAGINATION,
    /** Filter configuration changes (AC/DC/Custom toggle) */
    FILTER_CHANGE
}

/**
 * Effect describing an automatic scroll-to-top action.
 *
 * @param triggerTimestamp Epoch timestamp of the refresh event.
 * @param triggerType The origin trigger for the reload.
 */
data class ScrollToTopEffect(
    val triggerTimestamp: Long = System.currentTimeMillis(),
    val triggerType: RefreshTriggerType = RefreshTriggerType.USER_REFRESH
)

/**
 * Pure Kotlin/Compose UI helper for Nearby stations components:
 * - Wattage options descending sorting & label formatting
 * - Multi-selection toggle & clear filter state logic
 * - Heart icon state resolution (colors, icons, content descriptions)
 * - Auto-scroll on refresh state resolution & scroll position preservation
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
     * 360kW, 300kW, 250kW, 180kW, 150kW, 120kW, 80kW, 60kW, 40kW, 30kW, 22kW, 20kW, 11kW.
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

    /**
     * Determines whether an incoming station reload should trigger an automatic smooth scroll
     * of the list back to the top (index = 0).
     *
     * Invariant Rules:
     * 1. Only fires for explicit user refresh actions ([RefreshTriggerType.USER_REFRESH]).
     * 2. Passive background updates, pagination, or filter changes must NOT trigger auto-scroll.
     * 3. The loaded list must be non-empty ([itemCount] > 0) to avoid index bounds or race conditions.
     * 4. The event timestamp must be strictly newer than the last handled timestamp.
     */
    fun shouldScrollToTop(
        triggerType: RefreshTriggerType,
        itemCount: Int,
        lastHandledTimestamp: Long = 0L,
        eventTimestamp: Long = 1L
    ): Boolean {
        return triggerType == RefreshTriggerType.USER_REFRESH &&
                itemCount > 0 &&
                eventTimestamp > lastHandledTimestamp
    }

    /**
     * Convenience boolean-based overload for [shouldScrollToTop].
     */
    fun shouldScrollToTop(
        isUserInitiated: Boolean,
        itemCount: Int,
        lastHandledTimestamp: Long = 0L,
        eventTimestamp: Long = 1L
    ): Boolean {
        val trigger = if (isUserInitiated) RefreshTriggerType.USER_REFRESH else RefreshTriggerType.PASSIVE_BACKGROUND
        return shouldScrollToTop(trigger, itemCount, lastHandledTimestamp, eventTimestamp)
    }

    /**
     * Resolves the target scroll position (firstVisibleItemIndex, firstVisibleItemScrollOffset)
     * during a list reload:
     * - For user-initiated refresh ([RefreshTriggerType.USER_REFRESH]), returns (0, 0) to reset viewport to top.
     * - For passive background updates, pagination, or filter reloads, preserves ([currentIndex], [currentOffset])
     *   so the driver's reading position is never hijacked.
     */
    fun resolveTargetScrollPosition(
        triggerType: RefreshTriggerType,
        currentIndex: Int,
        currentOffset: Int
    ): Pair<Int, Int> {
        return if (triggerType == RefreshTriggerType.USER_REFRESH) {
            0 to 0
        } else {
            currentIndex to currentOffset
        }
    }

    /**
     * Convenience boolean-based overload for [resolveTargetScrollPosition].
     */
    fun resolveTargetScrollPosition(
        isUserInitiated: Boolean,
        currentIndex: Int,
        currentOffset: Int
    ): Pair<Int, Int> {
        val trigger = if (isUserInitiated) RefreshTriggerType.USER_REFRESH else RefreshTriggerType.PASSIVE_BACKGROUND
        return resolveTargetScrollPosition(trigger, currentIndex, currentOffset)
    }
}
