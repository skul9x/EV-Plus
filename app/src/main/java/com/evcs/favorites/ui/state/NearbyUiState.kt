package com.evcs.favorites.ui.state

import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.domain.model.WattageOption

/**
 * UI State for the Nearby Charging Stations screen.
 *
 * @param hasSearched False before user initiates GPS scan.
 * @param isLocating True during LocationService.getFreshLocation() execution.
 * @param isSearching True while fetching raw stations from EvcsRepository.searchNearbyVinFast.
 * @param isRoutingLoading True while MultiTierRoutingCoordinator computes Top 10 routes.
 * @param userLatitude User's current GPS latitude.
 * @param userLongitude User's current GPS longitude.
 * @param selectedWattages User-selected power filter chips.
 * @param rawStations Complete raw list of stations fetched from server.
 * @param top10DisplayStations Filtered, sorted, and routed Top 10 stations.
 * @param routingMetrics Station ID -> DrivingMetrics mapping.
 * @param favoriteStationIds Station IDs marked as favorite (reactive from repository).
 * @param errorMessage User-visible error notification.
 */
data class NearbyUiState(
    val hasSearched: Boolean = false,
    val isLocating: Boolean = false,
    val isSearching: Boolean = false,
    val isRoutingLoading: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val selectedWattages: Set<WattageOption> = emptySet(),
    val rawStations: List<Station> = emptyList(),
    val top10DisplayStations: List<Station> = emptyList(),
    val routingMetrics: Map<String, DrivingMetrics> = emptyMap(),
    val favoriteStationIds: Set<String> = emptySet(),
    val errorMessage: String? = null
)
