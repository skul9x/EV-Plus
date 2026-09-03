package com.evcs.favorites.data.routing

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Traffic conditions evaluated from duration delay ratio or speed fallback heuristic.
 */
@Serializable
enum class TrafficCondition(val displayName: String) {
    FREE_FLOW("Thông thoáng"),
    MODERATE_CONGESTION("Đông đúc"),
    HEAVY_CONGESTION("Ùn tắc"),
    UNKNOWN("Không rõ");

    companion object {
        /**
         * Calculates traffic condition:
         * - If staticDuration is present, uses traffic delay ratio R = durationSeconds / staticDurationSeconds:
         *   - R >= 1.35: HEAVY_CONGESTION
         *   - 1.15 <= R < 1.35: MODERATE_CONGESTION
         *   - R < 1.15: FREE_FLOW
         * - If staticDuration is absent/null, uses speed heuristic v = distance / duration (km/h):
         *   - v >= 30 km/h: FREE_FLOW
         *   - 15 <= v < 30 km/h: MODERATE_CONGESTION
         *   - v < 15 km/h: HEAVY_CONGESTION
         *   - fallback to UNKNOWN if duration or distance are non-positive
         */
        fun computeCondition(
            durationSeconds: Long,
            staticDurationSeconds: Long?,
            distanceMeters: Long
        ): TrafficCondition {
            if (durationSeconds <= 0L) return UNKNOWN

            if (staticDurationSeconds != null && staticDurationSeconds > 0L) {
                val ratio = durationSeconds.toDouble() / staticDurationSeconds.toDouble()
                return when {
                    ratio >= 1.35 -> HEAVY_CONGESTION
                    ratio >= 1.15 -> MODERATE_CONGESTION
                    else -> FREE_FLOW
                }
            }

            // Fallback heuristic based on driving speed v (km/h)
            if (distanceMeters <= 0L) return UNKNOWN
            val speedKmH = (distanceMeters.toDouble() / 1000.0) / (durationSeconds.toDouble() / 3600.0)
            return when {
                speedKmH >= 30.0 -> FREE_FLOW
                speedKmH >= 15.0 -> MODERATE_CONGESTION
                speedKmH > 0.0 -> HEAVY_CONGESTION
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Routing engine types used to compute driving metrics.
 */
@Serializable
enum class RoutingEngineType {
    GOOGLE,
    OSRM,
    HAVERSINE
}

/**
 * Domain representation of driving metrics for navigation and station ordering.
 */
@Serializable
data class DrivingMetrics(
    val distanceMeters: Long,
    val durationSeconds: Long,
    val staticDurationSeconds: Long? = null,
    val trafficCondition: TrafficCondition = TrafficCondition.UNKNOWN,
    val engineUsed: RoutingEngineType = RoutingEngineType.OSRM
) {
    /**
     * Formats duration into a concise human-readable Vietnamese string:
     * - E.g. "15 phút", "1 giờ 10 phút", "1 giờ", "0 phút"
     */
    val formattedDuration: String
        get() {
            if (durationSeconds <= 0L) return "0 phút"
            val totalMinutes = (durationSeconds + 30) / 60
            val effectiveMinutes = if (totalMinutes == 0L && durationSeconds > 0L) 1L else totalMinutes
            val hours = effectiveMinutes / 60
            val minutes = effectiveMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> "$hours giờ $minutes phút"
                hours > 0 -> "$hours giờ"
                else -> "$minutes phút"
            }
        }

    /**
     * Formats distance into a human-readable string:
     * - Under 1000 m: e.g. "850 m"
     * - 1000 m and above: e.g. "4.2 km"
     */
    val formattedDistance: String
        get() {
            if (distanceMeters <= 0L) return "0 m"
            return if (distanceMeters < 1000L) {
                "$distanceMeters m"
            } else {
                val km = distanceMeters.toDouble() / 1000.0
                String.format(Locale.US, "%.1f km", km)
            }
        }
}
