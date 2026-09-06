package com.evcs.favorites.ui.state

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.DcWattageTier
import com.evcs.favorites.domain.model.SmartFilterMode
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
 * @param selectedWattages User-selected power filter chips (legacy).
 * @param rawStations Complete raw list of stations fetched from server.
 * @param top10DisplayStations Filtered, sorted, and routed Top 10 stations.
 * @param routingMetrics Station ID -> DrivingMetrics mapping.
 * @param favoriteStationIds Station IDs marked as favorite (reactive from repository).
 * @param errorMessage User-visible error notification.
 * @param activeFilterMode Active smart filter mode (NONE, AC, DC, CUSTOM).
 * @param selectedDcTier Selected DC power wattage tier, or null if unselected.
 * @param isDcSubFilterVisible True if the DC sub-filter row is expanded.
 * @param savedCustomConfig User's saved custom filter configuration.
 * @param showCustomConfigPrompt True to display prompt dialog when tapping Custom without config.
 */
@Immutable
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
    val errorMessage: String? = null,
    val activeFilterMode: SmartFilterMode = SmartFilterMode.NONE,
    val selectedDcTier: DcWattageTier? = null,
    val isDcSubFilterVisible: Boolean = false,
    val savedCustomConfig: CustomFilterConfig? = null,
    val showCustomConfigPrompt: Boolean = false
) {
    /**
     * Dynamic feedback string for the info pill above stations list.
     */
    val filterSummaryPillText: String
        get() {
            val count = top10DisplayStations.size
            return when (activeFilterMode) {
                SmartFilterMode.NONE -> "Top 10 trạm sạc VinFast gần nhất còn cổng trống"
                SmartFilterMode.AC -> "Tìm thấy $count trạm có cổng AC khả dụng"
                SmartFilterMode.DC -> {
                    if (selectedDcTier != null) {
                        "Tìm thấy $count trạm có cổng DC ${selectedDcTier.label} khả dụng"
                    } else {
                        "Top 10 trạm sạc VinFast gần nhất còn cổng trống"
                    }
                }
                SmartFilterMode.CUSTOM -> "Tìm thấy $count trạm theo bộ lọc tùy chỉnh"
            }
        }
}

