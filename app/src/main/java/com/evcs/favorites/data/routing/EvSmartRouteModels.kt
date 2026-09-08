package com.evcs.favorites.data.routing

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.Station
import kotlinx.serialization.Serializable

/**
 * Geographic GPS coordinate along a route path.
 */
@Immutable
@Serializable
data class RouteCoordinate(
    val latitude: Double,
    val longitude: Double
)

/**
 * Result of a path routing calculation between origin and destination.
 */
@Immutable
@Serializable
data class RoutePathResult(
    val coordinates: List<RouteCoordinate> = emptyList(),
    val distanceMeters: Long = 0L,
    val durationSeconds: Long = 0L,
    val engineUsed: RoutingEngineType = RoutingEngineType.OSRM
)

/**
 * Charger classification hierarchy.
 * Priority: ULTRA_FAST_DC (>= 60kW) > STANDARD_DC (>= 30kW) > SLOW_AC (<= 11kW, strictly excluded from stops).
 */
@Serializable
enum class ChargerTier(val priority: Int, val minKw: Double) {
    ULTRA_FAST_DC(priority = 3, minKw = 60.0),
    STANDARD_DC(priority = 2, minKw = 30.0),
    SLOW_AC(priority = 1, minKw = 0.0);

    companion object {
        fun fromKw(kw: Double): ChargerTier {
            return when {
                kw >= 60.0 -> ULTRA_FAST_DC
                kw >= 30.0 -> STANDARD_DC
                else -> SLOW_AC
            }
        }
    }
}

/**
 * Live station availability status based on telemetry plugs.
 */
@Serializable
enum class StopAvailabilityStatus {
    AVAILABLE,      // Live plugs > 0 (🟢 Trống x/y)
    STATION_BUSY,   // Live plugs == 0 (🟠 Đang kín)
    UNKNOWN         // No live telemetry (⚪ Chưa có dữ liệu)
}

/**
 * Visual battery depletion waypoint along the trip corridor.
 *
 * @property distanceKm Cumulative distance from trip origin in kilometers.
 * @property batteryPercent Vehicle state of charge (%) at this waypoint.
 * @property isChargingStop True if this point represents an energy replenishment event at a charging station.
 */
@Immutable
@Serializable
data class EnergyWaypoint(
    val distanceKm: Double,
    val batteryPercent: Int,
    val isChargingStop: Boolean = false
)

/**
 * Warning raised when a highway corridor gap exceeds the vehicle's safe operating range.
 *
 * @property gapStartKm Distance from origin where the safe range runs out.
 * @property gapEndKm Distance from origin to the next reachable station or final destination.
 * @property missingRangeKm Distance gap that cannot be safely covered without battery exhaustion.
 * @property safeRangeKm The vehicle's configured safe range used during computation.
 * @property message Human-readable explanation in Vietnamese for UI warning banners.
 */
@Immutable
@Serializable
data class DeadZoneWarning(
    val gapStartKm: Double,
    val gapEndKm: Double,
    val missingRangeKm: Double,
    val safeRangeKm: Int,
    val message: String
)

/**
 * An individual charging stop scheduled along the route corridor.
 *
 * @property stopIndex 1-based order index of this charging stop (1, 2, ...).
 * @property station The selected VinFast charging station.
 * @property distanceFromOriginKm Cumulative driving distance from origin to this station.
 * @property distanceFromPreviousStopKm Leg driving distance from previous stop (or origin).
 * @property arrivalBatteryPercent Estimated battery SoC (%) upon arrival.
 * @property targetBatteryPercent Target battery SoC (%) upon departure (e.g. 85%).
 * @property estimatedChargingMinutes Estimated charging time in minutes to reach target SoC (including duration buffer if enabled).
 * @property rawChargingMinutes Raw estimated charging duration before buffer.
 * @property maxPowerKw Peak DC charging power available at this station in kW.
 * @property chargerTier The classification tier of the charger.
 * @property availabilityStatus Live plug occupancy status (AVAILABLE, STATION_BUSY, UNKNOWN).
 * @property estimatedQueueMinutes Estimated wait/queue time in minutes if station is currently busy (0 if available).
 * @property alternativeStations Other viable candidate VinFast stations within reach for swap ("Đổi trạm khác").
 */
@Immutable
@Serializable
data class EvRouteStop(
    val stopIndex: Int,
    val station: Station,
    val distanceFromOriginKm: Double,
    val distanceFromPreviousStopKm: Double,
    val arrivalBatteryPercent: Int,
    val targetBatteryPercent: Int,
    val estimatedChargingMinutes: Int,
    val rawChargingMinutes: Int = estimatedChargingMinutes,
    val maxPowerKw: Double = 0.0,
    val chargerTier: ChargerTier = ChargerTier.ULTRA_FAST_DC,
    val availabilityStatus: StopAvailabilityStatus = StopAvailabilityStatus.AVAILABLE,
    val estimatedQueueMinutes: Int = 0,
    val alternativeStations: List<Station> = emptyList()
) {
    /**
     * Live plug vacancy badge text for UI presentation.
     */
    val liveStatusBadge: String
        get() = when (availabilityStatus) {
            StopAvailabilityStatus.AVAILABLE -> "🟢 Trống ${station.totalAvailablePlugs}/${station.totalPlugs}"
            StopAvailabilityStatus.STATION_BUSY -> "🟠 Đang kín - Dự kiến rảnh sau ${estimatedQueueMinutes}p"
            StopAvailabilityStatus.UNKNOWN -> "⚪ Chưa có dữ liệu thời gian thực"
        }

    /**
     * Power pill label for UI presentation (e.g. "⚡ 180 kW", "⚡ 60 kW").
     */
    val powerDisplayLabel: String
        get() = if (maxPowerKw > 0) "⚡ ${maxPowerKw.toInt()} kW" else "⚡ DC"
}

/**
 * Complete smart EV route plan result generated by [EvSmartRoutePlanner].
 */
@Immutable
@Serializable
data class EvSmartRoutePlan(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val totalDistanceKm: Double,
    val totalDrivingDurationSeconds: Long,
    val totalChargingDurationMinutes: Int,
    val stops: List<EvRouteStop>,
    val energyProfile: List<EnergyWaypoint>,
    val polylineCoordinates: List<RouteCoordinate> = emptyList(),
    val deadZoneWarning: DeadZoneWarning? = null,
    val finalBatteryPercent: Int = 0
) {
    val isSuccess: Boolean
        get() = deadZoneWarning == null

    val totalTripDurationSeconds: Long
        get() = totalDrivingDurationSeconds + (totalChargingDurationMinutes * 60L)

    val chargingStopsCount: Int
        get() = stops.size
}
