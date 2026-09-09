package com.evcs.favorites.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.ui.theme.AutomotiveDimens

/**
 * Helper object providing touch-target sizes, typography specifications,
 * Hero Metric string formatting, and power distribution summary formatting
 * for automotive car mode.
 */
object StationCardHelper {
    val HERO_METRIC_FONT_SIZE: TextUnit = AutomotiveDimens.CAR_HERO_METRIC_TEXT_SIZE
    val HERO_METRIC_FONT_WEIGHT: FontWeight = FontWeight.Bold
    val CAR_BUTTON_HEIGHT: Dp = AutomotiveDimens.CAR_BUTTON_HEIGHT
    val PORTRAIT_BUTTON_HEIGHT: Dp = 44.dp
    val CAR_CARD_MIN_HEIGHT: Dp = AutomotiveDimens.CAR_CARD_MIN_HEIGHT

    const val HERO_METRIC_FONT_SIZE_SP: Float = 24f
    const val CAR_BUTTON_HEIGHT_DP: Float = 56f
    const val PORTRAIT_BUTTON_HEIGHT_DP: Float = 44f
    const val CAR_CARD_MIN_HEIGHT_DP: Float = 76f

    fun resolveButtonHeight(isCarMode: Boolean): Dp {
        return if (isCarMode) CAR_BUTTON_HEIGHT else PORTRAIT_BUTTON_HEIGHT
    }

    fun resolveButtonHeightDp(isCarMode: Boolean): Float {
        return if (isCarMode) CAR_BUTTON_HEIGHT_DP else PORTRAIT_BUTTON_HEIGHT_DP
    }

    fun formatHeroMetric(
        totalAvailablePlugs: Int,
        totalPlugs: Int,
        depotStatus: String = "Normal"
    ): String {
        return when {
            depotStatus.equals("Maintaining", ignoreCase = true) -> "🟡 BẢO TRÌ"
            depotStatus.equals("OutOfService", ignoreCase = true) -> "🔴 TẠM DỪNG"
            totalPlugs > 0 && totalAvailablePlugs == 0 -> "🔴 0/$totalPlugs HẾT CỔNG"
            totalAvailablePlugs > 0 -> "🟢 $totalAvailablePlugs/$totalPlugs TRỐNG"
            totalPlugs == 0 && depotStatus.equals("Normal", ignoreCase = true) -> "🟢 SẴN SÀNG"
            else -> "⚡ ĐÃ LƯU"
        }
    }

    /**
     * Formats power distribution summary by grouping duplicate power tiers,
     * aggregating total plugs, and sorting descending by power tier (wattage).
     * Falls back gracefully to parsed connector definitions if [powers] is empty.
     */
    fun formatPowerDistributionSummary(
        powers: List<PowerPort>,
        connectors: String = ""
    ): List<Pair<String, Int>> {
        val effectivePorts = if (powers.isNotEmpty()) {
            powers
        } else {
            EvcsRepository.parseConnectorsToPowers(connectors)
        }

        if (effectivePorts.isEmpty()) {
            return emptyList()
        }

        // Group by typeWatts if > 0, otherwise by label
        val grouped = effectivePorts.groupBy { port ->
            if (port.typeWatts > 0L) port.typeWatts else port.label.trim()
        }

        return grouped.values
            .map { group ->
                val first = group.first()
                val total = group.sumOf { if (it.totalPlugs > 0) it.totalPlugs else 1 }
                val label = when {
                    first.label.isNotBlank() && (first.typeWatts == 0L || first.label.contains("kW", ignoreCase = true)) -> first.label
                    first.typeWatts > 0L -> "${first.typeWatts / 1000}kW"
                    else -> first.label.ifBlank { "Charger" }
                }
                Triple(label, total, first.typeWatts)
            }
            .sortedByDescending { it.third }
            .map { Pair(it.first, it.second) }
    }

    /**
     * Builds styled AnnotatedString for power distribution summary marquee line.
     * Power ratings use neutral color, charger counts use vibrant accent color,
     * and separators use muted divider color.
     */
    fun buildPowerDistributionAnnotatedString(
        distribution: List<Pair<String, Int>>,
        powerColor: Color,
        countColor: Color,
        separatorColor: Color
    ): AnnotatedString {
        return buildAnnotatedString {
            distribution.forEachIndexed { index, (power, count) ->
                if (index > 0) {
                    withStyle(SpanStyle(color = separatorColor)) {
                        append(" | ")
                    }
                }
                withStyle(SpanStyle(color = powerColor)) {
                    append(power)
                }
                append(" ")
                withStyle(SpanStyle(color = countColor)) {
                    append("x $count")
                }
            }
        }
    }

    /**
     * Overload formatting power distribution summary and building AnnotatedString directly.
     */
    fun buildPowerDistributionAnnotatedString(
        powers: List<PowerPort>,
        connectors: String,
        powerColor: Color,
        countColor: Color,
        separatorColor: Color
    ): AnnotatedString = buildPowerDistributionAnnotatedString(
        distribution = formatPowerDistributionSummary(powers, connectors),
        powerColor = powerColor,
        countColor = countColor,
        separatorColor = separatorColor
    )
}
