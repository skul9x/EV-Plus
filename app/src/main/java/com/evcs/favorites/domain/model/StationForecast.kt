package com.evcs.favorites.domain.model

import kotlinx.serialization.Serializable

/**
 * Detailed charging session for an individual vehicle at a station.
 */
@Serializable
data class ForecastSession(
    val kw: Double = 0.0,
    val min: Int = 1,
    val soc: Int = 0
)

/**
 * Grouped forecast summary for a specific power level.
 */
@Serializable
data class ForecastPowerGroup(
    val kw: Double,
    val vehicleCount: Int,
    val minMinutes: Int,
    val maxMinutes: Int
) {
    /**
     * Formats a single bullet point line matching the design spec in 1.md:
     * e.g.:
     * • 20kW:  ~7-14 phút (2 xe)
     * • 60kW:  ~13 phút (1 xe)
     * • 250kW: ~8 phút (1 xe)
     */
    fun formatBulletLine(): String {
        val kwFormatted = if (kw % 1.0 == 0.0) "${kw.toInt()}kW" else "${kw}kW"
        val timeRange = if (minMinutes == maxMinutes) "$minMinutes phút" else "$minMinutes-$maxMinutes phút"
        val spacing = if (kwFormatted.length < 5) "  " else " "
        return "• $kwFormatted:$spacing~$timeRange ($vehicleCount xe)"
    }
}

/**
 * Real-time forecast model for charging completions at a station.
 */
@Serializable
data class StationForecast(
    val rawText: String = "",
    val vehicleCount: Int = 1,
    val wattageKw: Double = 0.0,
    val minMinutes: Int = 0,
    val maxMinutes: Int = 0,
    val isTeaser: Boolean = false,
    val detailedSessions: List<ForecastSession> = emptyList()
) {
    /**
     * True if there are multiple distinct power groups.
     */
    val isMultiSession: Boolean
        get() = getGroupedPowerForecasts().size > 1

    /**
     * Display summary alias for single line representation.
     */
    val displaySummary: String
        get() = formatSingleSummary()

    /**
     * Groups detailedSessions by wattage kw, computing vehicle count and min/max minutes per power level.
     * By default sorted descending by kw (highest power first).
     */
    fun getGroupedPowerForecasts(descending: Boolean = true): List<ForecastPowerGroup> {
        if (detailedSessions.isNotEmpty()) {
            val grouped = detailedSessions.groupBy { it.kw }
                .map { (kw, sessions) ->
                    ForecastPowerGroup(
                        kw = kw,
                        vehicleCount = sessions.size,
                        minMinutes = sessions.minOf { it.min },
                        maxMinutes = sessions.maxOf { it.min }
                    )
                }
            return if (descending) {
                grouped.sortedByDescending { it.kw }
            } else {
                grouped.sortedBy { it.kw }
            }
        }
        if (wattageKw > 0.0 || vehicleCount > 0) {
            return listOf(
                ForecastPowerGroup(
                    kw = wattageKw,
                    vehicleCount = vehicleCount,
                    minMinutes = minMinutes,
                    maxMinutes = maxMinutes
                )
            )
        }
        return emptyList()
    }

    /**
     * Formats single session / 1 power line forecast string:
     * e.g. "⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa"
     */
    fun formatSingleSummary(): String {
        val group = getGroupedPowerForecasts().firstOrNull()
        val kw = if (wattageKw > 0.0) wattageKw else (group?.kw ?: 0.0)
        val count = if (vehicleCount > 0) vehicleCount else (group?.vehicleCount ?: 1)
        val minM = if (minMinutes > 0) minMinutes else (group?.minMinutes ?: 0)
        val maxM = if (maxMinutes > 0) maxMinutes else (group?.maxMinutes ?: minM)
        val kwFormatted = if (kw % 1.0 == 0.0) "${kw.toInt()}kW" else "${kw}kW"
        val timeRange = if (minM == maxM) "$minM phút" else "$minM-$maxM phút"
        return "⏱️ Dự kiến $count xe sạc trụ $kwFormatted sẽ xong trong $timeRange nữa"
    }
}
