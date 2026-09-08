package com.evcs.favorites.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================================
// Automotive Dimension & Touch Target Tokens
// Enforces Google Automotive Safety Standards (touch target >= 56dp, cards >= 76dp)
// =============================================================================

val MIN_CAR_TOUCH_TARGET: Dp = 56.dp
val CAR_CARD_MIN_HEIGHT: Dp = 76.dp
val CAR_BUTTON_HEIGHT: Dp = 56.dp
val CAR_ICON_SIZE: Dp = 28.dp
val CAR_HERO_METRIC_TEXT_SIZE: TextUnit = 24.sp
val CAR_PADDING_SPACER: Dp = 12.dp
val CAR_CHIP_HEIGHT: Dp = 48.dp

object AutomotiveDimens {
    val MIN_CAR_TOUCH_TARGET: Dp = 56.dp
    val CAR_CARD_MIN_HEIGHT: Dp = 76.dp
    val CAR_BUTTON_HEIGHT: Dp = 56.dp
    val CAR_ICON_SIZE: Dp = 28.dp
    val CAR_HERO_METRIC_TEXT_SIZE: TextUnit = 24.sp
    val CAR_PADDING_SPACER: Dp = 12.dp
    val CAR_CHIP_HEIGHT: Dp = 48.dp

    // Raw float constants for pure JVM testing and mathematical calculations
    const val MIN_CAR_TOUCH_TARGET_DP: Float = 56f
    const val CAR_CARD_MIN_HEIGHT_DP: Float = 76f
    const val CAR_BUTTON_HEIGHT_DP: Float = 56f
    const val CAR_ICON_SIZE_DP: Float = 28f
    const val CAR_HERO_METRIC_TEXT_SIZE_SP: Float = 24f
    const val CAR_PADDING_SPACER_DP: Float = 12f
    const val CAR_CHIP_HEIGHT_DP: Float = 48f

    fun isTouchTargetCompliant(dpValue: Float): Boolean {
        return dpValue >= MIN_CAR_TOUCH_TARGET_DP
    }

    fun isCardHeightCompliant(dpValue: Float): Boolean {
        return dpValue >= CAR_CARD_MIN_HEIGHT_DP
    }

    fun isChipHeightCompliant(dpValue: Float): Boolean {
        return dpValue >= CAR_CHIP_HEIGHT_DP
    }
}
