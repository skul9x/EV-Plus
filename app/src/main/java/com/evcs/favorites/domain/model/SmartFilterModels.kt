package com.evcs.favorites.domain.model

import com.evcs.favorites.data.model.PowerPort
import kotlinx.serialization.Serializable

/**
 * Top-level smart filter modes for EV charging stations.
 */
@Serializable
enum class SmartFilterMode {
    NONE,
    AC,
    DC,
    CUSTOM
}

/**
 * DC charging wattage tiers for quick selection.
 */
@Serializable
enum class DcWattageTier(
    val label: String,
    val minWatts: Long,
    val maxWatts: Long
) {
    LE_30KW("≤ 30kW", 0L, 30_000L),
    BETWEEN_30_60KW("30 - 60kW", 30_000L, 60_000L),
    GE_60KW("≥ 60kW", 60_000L, Long.MAX_VALUE),
    GE_120KW("≥ 120kW", 120_000L, Long.MAX_VALUE);

    fun matchesWatts(watts: Long): Boolean {
        return watts in minWatts..maxWatts
    }
}

/**
 * Quick chip filter options available in custom filter setup.
 */
@Serializable
enum class QuickChipOption(val label: String) {
    ALL("Tất cả"),
    AC("🔌 AC"),
    DC_LE_30KW("⚡ DC ≤ 30kW"),
    DC_BETWEEN_30_60KW("⚡ DC 30-60kW"),
    DC_GE_60KW("⚡ DC ≥ 60kW"),
    DC_GE_120KW("⚡ DC ≥ 120kW")
}

/**
 * Custom filter configuration input mode.
 */
@Serializable
enum class CustomFilterMode {
    QUICK_CHIP,
    CUSTOM_RANGE
}

/**
 * User-configurable custom filter settings supporting quick chips or manual minKw..maxKw range.
 */
@Serializable
data class CustomFilterConfig(
    val mode: CustomFilterMode = CustomFilterMode.QUICK_CHIP,
    val quickChip: QuickChipOption = QuickChipOption.ALL,
    val minKw: Int? = null,
    val maxKw: Int? = null
) {
    /**
     * Validates that range values are positive and minKw <= maxKw when both are present.
     */
    fun isValid(): Boolean {
        return when (mode) {
            CustomFilterMode.QUICK_CHIP -> true
            CustomFilterMode.CUSTOM_RANGE -> {
                if (minKw == null && maxKw == null) return false
                if (minKw != null && minKw !in 1..500) return false
                if (maxKw != null && maxKw !in 1..500) return false
                if (minKw != null && maxKw != null && minKw > maxKw) return false
                true
            }
        }
    }

    /**
     * Generates a human-readable localized Vietnamese summary string for UI live preview and info pills.
     */
    fun toDisplaySummary(): String {
        return when (mode) {
            CustomFilterMode.QUICK_CHIP -> when (quickChip) {
                QuickChipOption.ALL -> "Tất cả các trạm có cổng trống"
                QuickChipOption.AC -> "Cổng AC (11kW, 22kW)"
                QuickChipOption.DC_LE_30KW -> "Cổng DC công suất ≤ 30kW"
                QuickChipOption.DC_BETWEEN_30_60KW -> "Cổng DC từ 30kW - 60kW"
                QuickChipOption.DC_GE_60KW -> "Cổng DC công suất ≥ 60kW"
                QuickChipOption.DC_GE_120KW -> "Cổng DC công suất ≥ 120kW"
            }
            CustomFilterMode.CUSTOM_RANGE -> {
                if (minKw != null && maxKw != null) {
                    "Cổng từ $minKw kW đến $maxKw kW"
                } else if (minKw != null) {
                    "Cổng công suất ≥ $minKw kW"
                } else if (maxKw != null) {
                    "Cổng công suất ≤ $maxKw kW"
                } else {
                    "Tất cả các trạm có cổng trống"
                }
            }
        }
    }
}

private val AC_STANDARD_WATTS = setOf(11_000L, 22_000L)

/**
 * Ports with [typeWatts] in [11_000L, 22_000L] (strictly car-compatible AC tiers) are classified as AC.
 * Excludes 3.5kW, 7kW/7.4kW, unrated (typeWatts <= 0L), and ports explicitly labeled DC.
 */
fun PowerPort.isAc(): Boolean {
    if (typeWatts <= 0L) return false
    if (label.contains("DC", ignoreCase = true)) return false
    return typeWatts in AC_STANDARD_WATTS
}

/**
 * Ports with [typeWatts] >= 20_000L (excluding 22kW AC) or explicitly labeled DC are classified as DC.
 */
fun PowerPort.isDc(): Boolean {
    if (label.contains("DC", ignoreCase = true)) return true
    if (label.contains("AC", ignoreCase = true)) return false
    return typeWatts >= 20_000L && typeWatts != 22_000L
}

/**
 * Checks if this port matches a given [QuickChipOption].
 */
fun PowerPort.matchesQuickChip(chip: QuickChipOption): Boolean {
    return when (chip) {
        QuickChipOption.ALL -> true
        QuickChipOption.AC -> isAc()
        QuickChipOption.DC_LE_30KW -> isDc() && DcWattageTier.LE_30KW.matchesWatts(typeWatts)
        QuickChipOption.DC_BETWEEN_30_60KW -> isDc() && DcWattageTier.BETWEEN_30_60KW.matchesWatts(typeWatts)
        QuickChipOption.DC_GE_60KW -> isDc() && DcWattageTier.GE_60KW.matchesWatts(typeWatts)
        QuickChipOption.DC_GE_120KW -> isDc() && DcWattageTier.GE_120KW.matchesWatts(typeWatts)
    }
}

/**
 * Checks if this port matches a custom numeric kW range minKw..maxKw.
 */
fun PowerPort.matchesCustomRange(minKw: Int?, maxKw: Int?): Boolean {
    if (minKw == null && maxKw == null) return true
    if (typeWatts <= 0L) return false
    val minWatts = minKw?.let { it * 1000L }
    val maxWatts = maxKw?.let { it * 1000L }
    if (minWatts != null && typeWatts < minWatts) return false
    if (maxWatts != null && typeWatts > maxWatts) return false
    return true
}

/**
 * Checks if this port matches a [CustomFilterConfig].
 */
fun PowerPort.matchesCustomConfig(config: CustomFilterConfig): Boolean {
    return when (config.mode) {
        CustomFilterMode.QUICK_CHIP -> matchesQuickChip(config.quickChip)
        CustomFilterMode.CUSTOM_RANGE -> {
            if (!config.isValid()) true
            else matchesCustomRange(config.minKw, config.maxKw)
        }
    }
}
