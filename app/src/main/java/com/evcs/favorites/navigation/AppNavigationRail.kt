package com.evcs.favorites.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary

import androidx.compose.foundation.layout.sizeIn
import com.evcs.favorites.ui.theme.AutomotiveDimens
import com.evcs.favorites.ui.theme.MIN_CAR_TOUCH_TARGET

/**
 * Material 3 NavigationRail providing automotive left-edge vertical navigation
 * in landscape mode, preserving vertical screen height and offering large automotive
 * touch targets (>= 56dp) with distinct Emerald indicators.
 */
@Composable
fun AppNavigationRail(
    currentTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier.width(72.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        header = {
            Spacer(modifier = Modifier.height(8.dp))
        }
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        AppTab.entries.forEach { tab ->
            val selected = tab == currentTab
            NavigationRailItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                },
                alwaysShowLabel = true,
                modifier = Modifier
                    .sizeIn(minWidth = AutomotiveDimens.MIN_CAR_TOUCH_TARGET, minHeight = AutomotiveDimens.MIN_CAR_TOUCH_TARGET)
                    .heightIn(min = AutomotiveDimens.MIN_CAR_TOUCH_TARGET),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = EmeraldPrimary,
                    selectedTextColor = EmeraldPrimary,
                    indicatorColor = EmeraldContainerDark.copy(alpha = 0.6f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
