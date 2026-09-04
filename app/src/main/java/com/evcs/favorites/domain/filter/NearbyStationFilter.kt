package com.evcs.favorites.domain.filter

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.domain.model.SmartFilterMode
import com.evcs.favorites.domain.model.WattageOption
import com.evcs.favorites.domain.model.isAc
import com.evcs.favorites.domain.model.isDc
import com.evcs.favorites.domain.model.matchesCustomRange
import com.evcs.favorites.domain.model.matchesQuickChip

/**
 * Extension function on [Station] to check if it has any car-compatible charging ports
 * (DC ports, or AC ports with rating >= 11kW).
 *
 * Rules:
 * - Returns true if any port satisfies `it.isDc()`, `it.isAc()`, or `it.typeWatts >= 11_000L`.
 * - If `powers` is empty, falls back to connector string parsing via [EvcsRepository.parseConnectorsToPowers].
 * - Returns false if the station only contains low-power motorbike ports (3.5kW, 7kW, 7.4kW) and no DC or AC >= 11kW ports.
 */
fun Station.hasCarCompatiblePorts(): Boolean {
    val portList = if (powers.isNotEmpty()) {
        powers
    } else {
        EvcsRepository.parseConnectorsToPowers(connectors)
    }
    return portList.any { it.isDc() || it.isAc() || it.typeWatts >= 11_000L }
}

/**
 * Pure business logic engine for filtering charging stations by wattage tiers,
 * active port availability, depot service status, and selecting Top N nearest stations.
 */
object NearbyStationFilter {

    /**
     * Filters stations by active status and wattage requirements.
     *
     * Rules:
     * 1. Depot status must not be "Maintaining" or "OutOfService" (case-insensitive).
     * 2. Pure motorbike/low-power stations (!station.hasCarCompatiblePorts()) are excluded.
     * 3. If [selectedWattages] is empty: station must have at least one available plug (totalAvailablePlugs > 0).
     * 4. If [selectedWattages] is not empty: station must have at least one connector matching any selected
     *    wattage tier with availablePlugs > 0.
     */
    fun filterStations(
        stations: List<Station>,
        selectedWattages: Set<WattageOption>,
        includeFullStations: Boolean = false
    ): List<Station> {
        return stations.filter { station ->
            val isOutOfService = station.depotStatus.equals("Maintaining", ignoreCase = true) ||
                    station.depotStatus.equals("OutOfService", ignoreCase = true)
            if (isOutOfService) {
                return@filter false
            }

            if (!station.hasCarCompatiblePorts()) {
                return@filter false
            }

            if (selectedWattages.isEmpty()) {
                station.totalAvailablePlugs > 0 || (includeFullStations && station.totalPlugs > 0)
            } else {
                station.powers.any { power ->
                    (power.availablePlugs > 0 || (includeFullStations && power.totalPlugs > 0)) &&
                            selectedWattages.any { option -> option.matchesWattage(power.typeWatts) }
                }
            }
        }
    }

    /**
     * Filters stations by smart filter mode:
     * - [SmartFilterMode.NONE]: Station must have available plugs (totalAvailablePlugs > 0) and car-compatible ports.
     * - [SmartFilterMode.AC]: Station must have at least one car-compatible AC connector (11kW, 22kW) with availablePlugs > 0.
     * - [SmartFilterMode.DC]: Station must have at least one DC connector matching the active [dcTier] with availablePlugs > 0.
     *   If [dcTier] is null, station must have available plugs (unfiltered until tier is selected) and car-compatible ports.
     * - [SmartFilterMode.CUSTOM]: Evaluates either matching quick chip (excluding motorbike stations) or manual minKw..maxKw range with availablePlugs > 0.
     *
     * Motorbike Station Exclusion Rule:
     * Stations lacking car-compatible ports (!station.hasCarCompatiblePorts()) are excluded across NONE, AC, DC,
     * and QUICK_CHIP modes. In CUSTOM_RANGE mode, stations are matched directly against user's numeric bounds.
     *
     * Mixed Station Exclusion Rule:
     * If a station has both AC and DC, availability is evaluated strictly on the connectors matching the filter.
     * If matching connectors have 0 vacant plugs, the station is filtered out even if non-matching connectors are free.
     *
     * Maintains depot maintenance exclusion (depotStatus != "Maintaining" / "OutOfService").
     */
    fun filterSmartStations(
        stations: List<Station>,
        mode: SmartFilterMode,
        dcTier: DcWattageTier? = null,
        customConfig: CustomFilterConfig? = null,
        includeFullStations: Boolean = false
    ): List<Station> {
        return stations.filter { station ->
            val isOutOfService = station.depotStatus.equals("Maintaining", ignoreCase = true) ||
                    station.depotStatus.equals("OutOfService", ignoreCase = true)
            if (isOutOfService) {
                return@filter false
            }

            val isCustomRange = mode == SmartFilterMode.CUSTOM &&
                    customConfig != null &&
                    customConfig.isValid() &&
                    customConfig.mode == CustomFilterMode.CUSTOM_RANGE

            if (!isCustomRange && !station.hasCarCompatiblePorts()) {
                return@filter false
            }

            when (mode) {
                SmartFilterMode.NONE -> {
                    station.totalAvailablePlugs > 0 || (includeFullStations && station.totalPlugs > 0)
                }
                SmartFilterMode.AC -> {
                    station.powers.any { power ->
                        power.isAc() && (power.availablePlugs > 0 || (includeFullStations && power.totalPlugs > 0))
                    }
                }
                SmartFilterMode.DC -> {
                    if (dcTier == null) {
                        station.totalAvailablePlugs > 0 || (includeFullStations && station.totalPlugs > 0)
                    } else {
                        station.powers.any { power ->
                            power.isDc() && dcTier.matchesWatts(power.typeWatts) &&
                                    (power.availablePlugs > 0 || (includeFullStations && power.totalPlugs > 0))
                        }
                    }
                }
                SmartFilterMode.CUSTOM -> {
                    if (customConfig == null || !customConfig.isValid()) {
                        station.totalAvailablePlugs > 0 || (includeFullStations && station.totalPlugs > 0)
                    } else when (customConfig.mode) {
                        CustomFilterMode.QUICK_CHIP -> {
                            if (customConfig.quickChip == QuickChipOption.ALL) {
                                station.totalAvailablePlugs > 0 || (includeFullStations && station.totalPlugs > 0)
                            } else {
                                station.powers.any { power ->
                                    power.matchesQuickChip(customConfig.quickChip) &&
                                            (power.availablePlugs > 0 || (includeFullStations && power.totalPlugs > 0))
                                }
                            }
                        }
                        CustomFilterMode.CUSTOM_RANGE -> {
                            station.powers.any { power ->
                                power.matchesCustomRange(customConfig.minKw, customConfig.maxKw) &&
                                        (power.availablePlugs > 0 || (includeFullStations && power.totalPlugs > 0))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Overloaded helper delegating to [filterSmartStations].
     */
    fun filterStations(
        stations: List<Station>,
        mode: SmartFilterMode,
        dcTier: DcWattageTier? = null,
        customConfig: CustomFilterConfig? = null,
        includeFullStations: Boolean = false
    ): List<Station> = filterSmartStations(stations, mode, dcTier, customConfig, includeFullStations)

    /**
     * Computes great-circle Haversine distance from user's coordinates to each station,
     * sorts stations nearest-first, and extracts strictly the top [limit] elements.
     *
     * @param userLat User's current latitude
     * @param userLon User's current longitude
     * @param stations List of candidate stations
     * @param limit Maximum number of stations to return (default 10)
     * @return List of at most [limit] nearest stations with updated [Station.distanceKm]
     */
    fun extractTopNearest(
        userLat: Double,
        userLon: Double,
        stations: List<Station>,
        limit: Int = 10
    ): List<Station> {
        return stations
            .distinctBy { it.id }
            .map { station ->
                val distance = DistanceCalculator.calculateDistanceKm(
                    lat1 = userLat,
                    lon1 = userLon,
                    lat2 = station.latitude,
                    lon2 = station.longitude
                )
                station.copy(distanceKm = distance)
            }
            .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            .take(limit)
    }

    /**
     * Sorts stations ascending by actual driving road distance (`drivingMetrics.distanceMeters`).
     *
     * Tie-breaking & Fallback rules:
     * 1. Primary: ascending by driving road distance in meters.
     * 2. Fallback distance: If `drivingMetrics` is null or `distanceMeters <= 0`, falls back to
     *    Haversine distance (`distanceKm * 1000.0`).
     * 3. Secondary (Tie-breaker): If distance in meters is identical, sort ascending by `drivingMetrics.durationSeconds`.
     * 4. Stability (Tie-breaker): If duration is also identical, sort ascending by station `id`.
     *
     * @param stations List of stations to sort
     * @return New list of stations sorted by driving distance
     */
    fun sortByDrivingDistance(stations: List<Station>): List<Station> {
        val comparator = compareBy<Station> { station ->
            station.drivingMetrics?.distanceMeters?.takeIf { it > 0L }
                ?: station.distanceKm?.let { (it * 1000.0).toLong() }
                ?: Long.MAX_VALUE
        }.thenBy { station ->
            station.drivingMetrics?.durationSeconds ?: Long.MAX_VALUE
        }.thenBy { station ->
            station.distanceKm ?: Double.MAX_VALUE
        }.thenBy { station ->
            station.id
        }

        return stations.sortedWith(comparator)
    }
}
