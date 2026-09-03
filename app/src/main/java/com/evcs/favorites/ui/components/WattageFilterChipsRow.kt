package com.evcs.favorites.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.ui.theme.EmeraldPrimary

/**
 * Horizontally scrollable row displaying power rating filter chips.
 *
 * - Renders all 14+ wattage tiers in descending order (360kW down to 3.5kW).
 * - Shows an action chip "Xóa bộ lọc" when any filter is active.
 * - Highlights selected chips with EV Emerald green (#10B981) and white checkmark icon.
 */
@Composable
fun WattageFilterChipsRow(
    selectedWattages: Set<WattageOption>,
    onToggleWattage: (WattageOption) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    options: List<WattageOption> = NearbyUiHelper.SORTED_WATTAGE_OPTIONS
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // "Xóa bộ lọc" Action Chip - visible whenever selectedWattages is not empty
        if (NearbyUiHelper.isClearFiltersVisible(selectedWattages)) {
            item(key = "clear_filters_action_chip") {
                FilterChip(
                    selected = true,
                    onClick = onClearFilters,
                    label = {
                        Text(
                            text = NearbyUiHelper.CLEAR_FILTERS_LABEL,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = NearbyUiHelper.CLEAR_FILTERS_LABEL,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        // Power rating filter chips
        items(
            items = options,
            key = { it.name }
        ) { option ->
            val isSelected = selectedWattages.contains(option)
            FilterChip(
                selected = isSelected,
                onClick = { onToggleWattage(option) },
                label = {
                    Text(
                        text = NearbyUiHelper.formatWattageChipLabel(option),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = EmeraldPrimary,
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
