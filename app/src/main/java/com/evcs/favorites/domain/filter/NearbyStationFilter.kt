package com.evcs.favorites.domain.filter

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.domain.model.WattageOption

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
     * 2. If [selectedWattages] is empty: station must have at least one available plug (totalAvailablePlugs > 0).
     * 3. If [selectedWattages] is not empty: station must have at least one connector matching any selected
     *    wattage tier with availablePlugs > 0.
     */
    fun filterStations(
        stations: List<Station>,
        selectedWattages: Set<WattageOption>
    ): List<Station> {
        return stations.filter { station ->
            val isOutOfService = station.depotStatus.equals("Maintaining", ignoreCase = true) ||
                    station.depotStatus.equals("OutOfService", ignoreCase = true)
            if (isOutOfService) {
                return@filter false
            }

            if (selectedWattages.isEmpty()) {
                station.totalAvailablePlugs > 0
            } else {
                station.powers.any { power ->
                    power.availablePlugs > 0 && selectedWattages.any { option ->
                        option.matchesWattage(power.typeWatts)
                    }
                }
            }
        }
    }

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
}
