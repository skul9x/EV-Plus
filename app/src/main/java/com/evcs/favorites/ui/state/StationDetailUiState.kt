package com.evcs.favorites.ui.state

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.Station24hStats
import com.evcs.favorites.domain.StationPortStatus
import com.evcs.favorites.domain.StationRating
import com.evcs.favorites.domain.StationTelemetry

/**
 * UI State for the native EVCS Station Detail bottom sheet.
 *
 * @param station Selected domain station model, or null if sheet is dismissed.
 * @param isLoadingTelemetry True while fetching ephemeral session tokens and live charging telemetry.
 * @param isLoadingStats True while fetching Socket.io 24h history and computing usage statistics.
 * @param isRefreshing True during manual refresh / reload.
 * @param rating User star rating and review count from EVCS token handshake.
 * @param telemetry Live charging telemetry payload and busy kW distribution.
 * @param cleanForecast Sanitized live forecast ticker string (null if locked or unavailable).
 * @param portStatuses Real-time available/total ports per kW tier.
 * @param stats24h Computed 24h metrics (peak, average, rush hour, fill rate).
 * @param error User-visible error message if a critical failure occurred, or null.
 */
@Immutable
data class StationDetailUiState(
    val station: Station? = null,
    val isLoadingTelemetry: Boolean = false,
    val isLoadingStats: Boolean = false,
    val isRefreshing: Boolean = false,
    val rating: StationRating? = null,
    val telemetry: StationTelemetry? = null,
    val cleanForecast: String? = null,
    val portStatuses: List<StationPortStatus> = emptyList(),
    val stats24h: Station24hStats? = null,
    val error: String? = null
)
