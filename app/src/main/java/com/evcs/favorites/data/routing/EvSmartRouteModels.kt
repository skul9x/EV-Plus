package com.evcs.favorites.data.routing

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import kotlinx.serialization.Serializable

/**
 * Calculates straight-line distance in kilometers between primary and backup stations.
 */
fun distanceFromPrimaryStationKm(primary: Station, backup: Station): Double {
    return DistanceCalculator.calculateDistanceKm(
        primary.latitude,
        primary.longitude,
        backup.latitude,
        backup.longitude
    )
}

/**
 * Extracts peak charging power in kW from station powers, labels, and text descriptions.
 */
fun extractStationMaxPowerKw(station: Station): Double {
    val maxWatts = station.powers.maxOfOrNull { it.typeWatts } ?: 0L
    if (maxWatts > 0) {
        return maxWatts / 1000.0
    }

    val allText = buildString {
        station.powers.forEach { append("${it.label} ${it.displayString} ") }
        append("${station.connectors} ${station.summary} ${station.name}")
    }

    val kwMatches = Regex("""(\d+(?:\.\d+)?)\s*k[wW]""").findAll(allText)
    val maxFromText = kwMatches.mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
    if (maxFromText != null) return maxFromText

    if (allText.contains("DC", ignoreCase = true) || allText.contains("Super", ignoreCase = true)) {
        return 60.0
    }

    return 11.0
}

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
 * Priority: ULTRA_FAST_DC (>= 60kW) > STANDARD_DC (>= 20kW) > SLOW_AC (< 20kW, strictly excluded from stops).
 */
@Serializable
enum class ChargerTier(val priority: Int, val minKw: Double) {
    ULTRA_FAST_DC(priority = 3, minKw = 60.0),
    STANDARD_DC(priority = 2, minKw = 20.0),
    SLOW_AC(priority = 1, minKw = 0.0);

    companion object {
        fun fromKw(kw: Double): ChargerTier {
            return when {
                kw >= 60.0 -> ULTRA_FAST_DC
                kw >= 20.0 -> STANDARD_DC
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
 * Warning emitted when routing selects a fallback charger below the preferred minimum power.
 *
 * @property requiredPowerKw The user's requested minimum charger power threshold (e.g. 60.0 kW).
 * @property fallbackStation The station selected as a fallback stop.
 * @property fallbackPowerKw The peak power available at the fallback station (e.g. 30.0 kW).
 * @property stopIndex 1-based stop index of the fallback stop along the route.
 * @property legDistanceKm The driving distance of the leg leading to this fallback stop.
 * @property message Human-readable explanation in Vietnamese for UI dialog and warning banners.
 */
@Immutable
@Serializable
data class InsufficientPowerWarning(
    val requiredPowerKw: Double,
    val fallbackStation: Station,
    val fallbackPowerKw: Double,
    val stopIndex: Int,
    val legDistanceKm: Double,
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
    val alternativeStations: List<Station> = emptyList(),
    val backupStation: Station? = null
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

    /**
     * Straight-line distance from primary station to designated backup station in kilometers.
     */
    val backupDistanceKm: Double?
        get() = backupStation?.let { distanceFromPrimaryStationKm(station, it) }

    /**
     * Maximum power in kW for designated backup station.
     */
    val backupPowerKw: Double?
        get() = backupStation?.let { extractStationMaxPowerKw(it) }

    /**
     * Power badge label for designated backup station.
     */
    val backupPowerDisplayLabel: String?
        get() = backupPowerKw?.let { if (it > 0) "⚡ ${it.toInt()} kW" else "⚡ DC" }

    /**
     * Live plug vacancy badge text for designated backup station.
     */
    val backupLiveStatusBadge: String?
        get() = backupStation?.let {
            when {
                it.totalPlugs == 0 -> "⚪ Chưa có dữ liệu thời gian thực"
                it.totalAvailablePlugs > 0 -> "🟢 Trống ${it.totalAvailablePlugs}/${it.totalPlugs}"
                else -> "🟠 Đang kín"
            }
        }
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
    val insufficientPowerWarning: InsufficientPowerWarning? = null,
    val finalBatteryPercent: Int = 0
) {
    val isSuccess: Boolean
        get() = deadZoneWarning == null

    val hasInsufficientPowerWarning: Boolean
        get() = insufficientPowerWarning != null

    val totalTripDurationSeconds: Long
        get() = totalDrivingDurationSeconds + (totalChargingDurationMinutes * 60L)

    val chargingStopsCount: Int
        get() = stops.size
}
