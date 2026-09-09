package com.evcs.favorites.navigation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.filled.Home

/**
 * Contract constant: Navigation rail width fixed at 58.dp to maximize
 * horizontal screen estate in landscape / automotive infotainment screens.
 */
val RAIL_WIDTH_DP: Dp = 58.dp

/**
 * Supported navigation rail actions in strict automotive glanceability sequence:
 * Home -> Nearby -> Favorites -> Settings -> Refresh.
 */
enum class NavigationRailAction(val title: String) {
    HOME("Trang chủ xe"),
    NEARBY("Quanh đây"),
    FAVORITES("Yêu thích"),
    SETTINGS("Cài đặt"),
    REFRESH("Làm mới")
}

/**
 * Intent descriptor for dispatching automotive system home launcher intents.
 * Decouples intent specification from Android runtime framework dependencies for JVM testing.
 */
data class SystemHomeIntentSpec(
    val action: String = Intent.ACTION_MAIN,
    val category: String = Intent.CATEGORY_HOME,
    val flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
) {
    fun toIntent(): Intent {
        return Intent(action).apply {
            addCategory(category)
            flags = this@SystemHomeIntentSpec.flags
        }
    }
}

/**
 * Design constants and contract values for the compact centered navigation rail.
 */
object AppNavigationRailDefaults {
    val RAIL_WIDTH_DP: Dp = 58.dp
    val ICON_SIZE: Dp = 26.dp
    val TOUCH_TARGET_SIZE: Dp = 50.dp
    val ITEM_SPACING: Dp = 16.dp

    val ACTION_ORDER: List<NavigationRailAction> = listOf(
        NavigationRailAction.HOME,
        NavigationRailAction.NEARBY,
        NavigationRailAction.FAVORITES,
        NavigationRailAction.SETTINGS,
        NavigationRailAction.REFRESH
    )
}

/**
 * Pure helper functions for navigation rail action dispatch and animation state mapping.
 */
object AppNavigationRailHelper {
    fun buildHomeIntentSpec(): SystemHomeIntentSpec = SystemHomeIntentSpec()

    fun createHomeIntent(): Intent = buildHomeIntentSpec().toIntent()

    fun dispatchSystemHome(context: Context) {
        try {
            val homeIntent = createHomeIntent()
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            (context as? Activity)?.moveTaskToBack(true)
        }
    }

    fun shouldAllowRefresh(isRefreshing: Boolean): Boolean = !isRefreshing

    fun resolveRefreshRotationAngle(isRefreshing: Boolean, animatedAngle: Float): Float {
        return if (isRefreshing) animatedAngle else 0f
    }

    fun resolveRefreshContentDescription(isRefreshing: Boolean): String {
        return if (isRefreshing) "Đang làm mới" else "Làm mới dữ liệu"
    }

    fun isTabSelected(tab: AppTab, currentTab: AppTab): Boolean = tab == currentTab

    fun handleRailAction(
        action: NavigationRailAction,
        onHomeClick: () -> Unit = {},
        onTabSelected: (AppTab) -> Unit,
        onSettingsClick: () -> Unit,
        onRefreshClick: () -> Unit,
        isRefreshing: Boolean = false
    ) {
        when (action) {
            NavigationRailAction.HOME -> onHomeClick()
            NavigationRailAction.NEARBY -> onTabSelected(AppTab.NEARBY)
            NavigationRailAction.FAVORITES -> onTabSelected(AppTab.FAVORITES)
            NavigationRailAction.SETTINGS -> onSettingsClick()
            NavigationRailAction.REFRESH -> {
                if (shouldAllowRefresh(isRefreshing)) {
                    onRefreshClick()
                }
            }
        }
    }
}

/**
 * Compact 58dp automotive navigation column providing vertically centered,
 * icon-only touch targets ordered as:
 * 0. System Home (Return directly to Android launcher / Carlinkit home)
 * 1. Nearby (AppTab.NEARBY)
 * 2. Favorites (AppTab.FAVORITES)
 * 3. Settings (Routing & BYOK modal)
 * 4. Refresh (Data refresh for active tab with animated rotation)
 */
@Composable
fun AppNavigationRail(
    currentTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    onSettingsClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onHomeClick: () -> Unit = {},
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(RAIL_WIDTH_DP)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 0. Top-anchored System Home Action for Carlinkit / Android Box
            RailIconButton(
                icon = Icons.Default.Home,
                contentDescription = NavigationRailAction.HOME.title,
                isSelected = false,
                onClick = onHomeClick
            )

            Spacer(modifier = Modifier.weight(1f))

            // Centered functional group
            // 1. Nearby
            val nearbySelected = AppNavigationRailHelper.isTabSelected(AppTab.NEARBY, currentTab)
            RailIconButton(
                icon = if (nearbySelected) AppTab.NEARBY.selectedIcon else AppTab.NEARBY.unselectedIcon,
                contentDescription = AppTab.NEARBY.label,
                isSelected = nearbySelected,
                onClick = { onTabSelected(AppTab.NEARBY) }
            )

            Spacer(modifier = Modifier.height(AppNavigationRailDefaults.ITEM_SPACING))

            // 2. Favorites
            val favoritesSelected = AppNavigationRailHelper.isTabSelected(AppTab.FAVORITES, currentTab)
            RailIconButton(
                icon = if (favoritesSelected) AppTab.FAVORITES.selectedIcon else AppTab.FAVORITES.unselectedIcon,
                contentDescription = AppTab.FAVORITES.label,
                isSelected = favoritesSelected,
                onClick = { onTabSelected(AppTab.FAVORITES) }
            )

            Spacer(modifier = Modifier.height(AppNavigationRailDefaults.ITEM_SPACING))

            // 3. Settings
            RailIconButton(
                icon = Icons.Default.Settings,
                contentDescription = NavigationRailAction.SETTINGS.title,
                isSelected = false,
                onClick = onSettingsClick
            )

            Spacer(modifier = Modifier.height(AppNavigationRailDefaults.ITEM_SPACING))

            // 4. Refresh (with animated rotation when isRefreshing == true)
            val animatedAngle = if (isRefreshing) {
                val infiniteTransition = rememberInfiniteTransition(label = "RailRefreshRotationTransition")
                val angle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "RailRefreshRotationAngle"
                )
                angle
            } else {
                0f
            }
            val rotationAngle = AppNavigationRailHelper.resolveRefreshRotationAngle(isRefreshing, animatedAngle)

            RailIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = AppNavigationRailHelper.resolveRefreshContentDescription(isRefreshing),
                isSelected = false,
                iconModifier = Modifier.rotate(rotationAngle),
                enabled = AppNavigationRailHelper.shouldAllowRefresh(isRefreshing),
                onClick = {
                    if (AppNavigationRailHelper.shouldAllowRefresh(isRefreshing)) {
                        onRefreshClick()
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Large touch target icon button optimized for automotive ergonomics.
 */
@Composable
private fun RailIconButton(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val activeColor = EmeraldPrimary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = EmeraldContainerDark.copy(alpha = 0.6f)

    Box(
        modifier = modifier
            .size(AppNavigationRailDefaults.TOUCH_TARGET_SIZE)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) indicatorColor else Color.Transparent)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isSelected) activeColor else inactiveColor,
            modifier = iconModifier.size(AppNavigationRailDefaults.ICON_SIZE)
        )
    }
}
