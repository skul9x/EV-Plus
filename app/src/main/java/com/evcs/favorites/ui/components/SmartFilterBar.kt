package com.evcs.favorites.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.ui.theme.AutomotiveDimens
import com.evcs.favorites.ui.theme.CAR_CHIP_HEIGHT
import com.evcs.favorites.ui.theme.EmeraldPrimary

/**
 * Pure UI helper and constants for SmartFilterBar.
 */
object SmartFilterUiHelper {
    const val CUSTOM_LABEL: String = "Custom"
    const val DC_LABEL: String = "DC"
    const val AC_LABEL: String = "AC"
    const val BACK_LABEL: String = "Quay lại"

    const val EMOJI_CUSTOM: String = "🎯"
    const val EMOJI_DC: String = "⚡"
    const val EMOJI_AC: String = "🔌"

    /**
     * Formats the Custom button label with sub-label/badge when configured and active.
     * E.g. "Custom • ≥60kW" or "Custom".
     */
    fun formatCustomButtonLabel(config: CustomFilterConfig?, isActive: Boolean): String {
        if (!isActive || config == null) return CUSTOM_LABEL
        val badge = when (config.mode) {
            CustomFilterMode.QUICK_CHIP -> when (config.quickChip) {
                QuickChipOption.ALL -> "Tất cả"
                QuickChipOption.AC -> "AC"
                QuickChipOption.DC_LE_30KW -> "≤30kW"
                QuickChipOption.DC_BETWEEN_30_60KW -> "30-60kW"
                QuickChipOption.DC_GE_60KW -> "≥60kW"
                QuickChipOption.DC_GE_120KW -> "≥120kW"
            }
            CustomFilterMode.CUSTOM_RANGE -> {
                if (config.minKw != null && config.maxKw != null) {
                    "${config.minKw}-${config.maxKw}kW"
                } else if (config.minKw != null) {
                    "≥${config.minKw}kW"
                } else if (config.maxKw != null) {
                    "≤${config.maxKw}kW"
                } else {
                    ""
                }
            }
        }
        return if (badge.isNotBlank()) "$CUSTOM_LABEL • $badge" else CUSTOM_LABEL
    }

    /**
     * Returns true if cancel icon [✕] should be shown inside active button.
     */
    fun isCancelButtonVisible(mode: SmartFilterMode): Boolean {
        return mode == SmartFilterMode.AC || mode == SmartFilterMode.CUSTOM
    }

    /**
     * Determines whether the active filter configuration represents a DC filter.
     * Evaluates DC mode, DC sub-filter visibility, selected DC tier, and Custom DC configurations.
     */
    fun isDcFilterActive(
        activeFilterMode: SmartFilterMode,
        isDcSubFilterVisible: Boolean = false,
        selectedDcTier: DcWattageTier? = null,
        savedCustomConfig: CustomFilterConfig? = null
    ): Boolean {
        if (activeFilterMode == SmartFilterMode.DC || isDcSubFilterVisible || selectedDcTier != null) {
            return true
        }
        if (activeFilterMode == SmartFilterMode.CUSTOM && savedCustomConfig != null) {
            return when (savedCustomConfig.mode) {
                CustomFilterMode.QUICK_CHIP -> savedCustomConfig.quickChip in setOf(
                    QuickChipOption.DC_LE_30KW,
                    QuickChipOption.DC_BETWEEN_30_60KW,
                    QuickChipOption.DC_GE_60KW,
                    QuickChipOption.DC_GE_120KW
                )
                CustomFilterMode.CUSTOM_RANGE -> (savedCustomConfig.minKw ?: 0) >= 20
            }
        }
        return false
    }
}

/**
 * Animated Smart Filter Bar presenting:
 * - Mode 1: 3-button selector ([ 🎯 Custom ], [ ⚡ DC ], [ 🔌 AC ]) with integrated cancel buttons.
 * - Mode 2: DC Sub-Filter Row with thumb-zone [ ← Quay lại ] back button and 4 single-select power tier chips.
 */
@Composable
fun SmartFilterBar(
    activeFilterMode: SmartFilterMode,
    isDcSubFilterVisible: Boolean,
    selectedDcTier: DcWattageTier?,
    savedCustomConfig: CustomFilterConfig?,
    onCustomClick: () -> Unit,
    onDcClick: () -> Unit,
    onAcClick: () -> Unit,
    onSelectDcTier: (DcWattageTier) -> Unit,
    onBackFromDc: () -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = isDcSubFilterVisible,
        transitionSpec = {
            if (targetState) {
                // Sliding forward into DC sub-filter
                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> -width } + fadeOut()
                )
            } else {
                // Reverse-sliding back to 3-button row
                (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> width } + fadeOut()
                )
            }.using(SizeTransform(clip = false))
        },
        label = "SmartFilterBarModeTransition",
        modifier = modifier.fillMaxWidth()
    ) { dcSubFilterActive ->
        if (dcSubFilterActive) {
            DcSubFilterRow(
                selectedDcTier = selectedDcTier,
                onSelectDcTier = onSelectDcTier,
                onBackFromDc = onBackFromDc
            )
        } else {
            TopLevelFilterRow(
                activeFilterMode = activeFilterMode,
                savedCustomConfig = savedCustomConfig,
                onCustomClick = onCustomClick,
                onDcClick = onDcClick,
                onAcClick = onAcClick,
                onClearFilter = onClearFilter
            )
        }
    }
}

/**
 * Mode 1: Top-level 3 buttons row with icons and integrated [✕] cancel buttons.
 */
@Composable
private fun TopLevelFilterRow(
    activeFilterMode: SmartFilterMode,
    savedCustomConfig: CustomFilterConfig?,
    onCustomClick: () -> Unit,
    onDcClick: () -> Unit,
    onAcClick: () -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCustomActive = activeFilterMode == SmartFilterMode.CUSTOM
    val isDcActive = activeFilterMode == SmartFilterMode.DC
    val isAcActive = activeFilterMode == SmartFilterMode.AC

    val customLabel = SmartFilterUiHelper.formatCustomButtonLabel(savedCustomConfig, isCustomActive)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. [ 🎯 Custom ] Button
        Button(
            onClick = {
                if (isCustomActive) onClearFilter() else onCustomClick()
            },
            modifier = Modifier
                .weight(if (isCustomActive && customLabel.length > 8) 1.35f else 1f)
                .heightIn(min = AutomotiveDimens.CAR_CHIP_HEIGHT)
                .height(AutomotiveDimens.CAR_CHIP_HEIGHT),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isCustomActive) EmeraldPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = if (isCustomActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = if (isCustomActive) 2.dp else 0.dp
            ),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = SmartFilterUiHelper.EMOJI_CUSTOM, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = customLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isCustomActive) FontWeight.Bold else FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCustomActive) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onClearFilter() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Bỏ lọc",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // 2. [ ⚡ DC ] Button
        Button(
            onClick = onDcClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = AutomotiveDimens.CAR_CHIP_HEIGHT)
                .height(AutomotiveDimens.CAR_CHIP_HEIGHT),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDcActive) EmeraldPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = if (isDcActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = if (isDcActive) 2.dp else 0.dp
            ),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = SmartFilterUiHelper.EMOJI_DC, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = SmartFilterUiHelper.DC_LABEL,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isDcActive) FontWeight.Bold else FontWeight.Medium
                    )
                )
            }
        }

        // 3. [ 🔌 AC ] Button
        Button(
            onClick = {
                if (isAcActive) onClearFilter() else onAcClick()
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = AutomotiveDimens.CAR_CHIP_HEIGHT)
                .height(AutomotiveDimens.CAR_CHIP_HEIGHT),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isAcActive) EmeraldPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = if (isAcActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = if (isAcActive) 2.dp else 0.dp
            ),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = SmartFilterUiHelper.EMOJI_AC, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = SmartFilterUiHelper.AC_LABEL,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isAcActive) FontWeight.Bold else FontWeight.Medium
                    )
                )
                if (isAcActive) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onClearFilter() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Bỏ lọc",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mode 2: DC Sub-Filter Row with one-handed thumb back button and single-select tier chips.
 */
@Composable
private fun DcSubFilterRow(
    selectedDcTier: DcWattageTier?,
    onSelectDcTier: (DcWattageTier) -> Unit,
    onBackFromDc: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // One-Handed Thumb-Zone [ ← Quay lại ] button
        Button(
            onClick = onBackFromDc,
            modifier = Modifier
                .heightIn(min = AutomotiveDimens.CAR_CHIP_HEIGHT)
                .height(AutomotiveDimens.CAR_CHIP_HEIGHT)
                .defaultMinSize(minWidth = AutomotiveDimens.CAR_CHIP_HEIGHT),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = SmartFilterUiHelper.BACK_LABEL,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = SmartFilterUiHelper.BACK_LABEL,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        // Single-select DC power tier chips
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                items = DcWattageTier.entries,
                key = { it.name }
            ) { tier ->
                val isSelected = selectedDcTier == tier
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        // Once a chip is tapped, it remains selected; tapping the same preserves selection
                        if (!isSelected) {
                            onSelectDcTier(tier)
                        }
                    },
                    label = {
                        Text(
                            text = tier.label,
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
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = AutomotiveDimens.CAR_CHIP_HEIGHT)
                        .height(AutomotiveDimens.CAR_CHIP_HEIGHT)
                )
            }
        }
    }
}
