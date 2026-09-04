package com.evcs.favorites.data.model

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.routing.DrivingMetrics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Raw power plug configuration for an EVCS charging station.
 */
@Serializable
data class EvsePowerRaw(
    val type: Long = 0L, // e.g. 60000 = 60kW, 7000 = 7kW
    val numberOfAvailableEvse: Int = 0,
    val totalEvse: Int = 0,
    val status: String? = null,
    val powerType: String? = null
) {
    fun toDomainPowerPort(): PowerPort {
        val label = if (type > 0) {
            val kw = type / 1000.0
            if (kw % 1.0 == 0.0) "${kw.toInt()}kW" else "${kw}kW"
        } else {
            if (powerType?.contains("DC", ignoreCase = true) == true) "DC" else "AC"
        }

        val display = if (totalEvse > 0) {
            "$label: trống $numberOfAvailableEvse/$totalEvse cổng"
        } else {
            label
        }

        return PowerPort(
            typeWatts = type,
            label = label,
            availablePlugs = numberOfAvailableEvse,
            totalPlugs = totalEvse,
            displayString = display
        )
    }
}

/**
 * Raw favorite station returned by `POST /favorite.html`.
 */
@Serializable
data class FavoriteStationRaw(
    val locationId: String,
    val name: String,
    val address: String,
    val summary: String? = null,
    val connectors: String? = null,
    val image: String? = null,
    val notifyFree: Int? = null,
    val notifyError: Int? = null,
    val notifyFreeMin: Int? = null
)

/**
 * Quota and notification limits in `FavoritesResponse`.
 */
@Serializable
data class FavoritesLimitsRaw(
    val fav: Int? = null,
    val notifyFree: Int? = null,
    val tier: String? = null,
    val plan: String? = null,
    val notifyNext: Int? = null
)

/**
 * Partial response returned by `POST /favorite.html` with `X-Partial: fav`.
 */
@Serializable
data class FavoritesResponse(
    val sync: Boolean = false,
    val csrf: String = "",
    val server: List<FavoriteStationRaw>? = null,
    val limits: FavoritesLimitsRaw? = null
)

/**
 * Request payload sent to `POST /favorite.html` to sync favorites to cloud.
 */
@Serializable
data class SaveFavoritesRequest(
    val action: String = "save",
    val csrf: String,
    val stations: List<FavoriteStationRaw>
)

/**
 * Raw station object returned by `POST /search?t=...`.
 */
@Serializable
data class SearchStationRaw(
    val locationId: String? = null,
    val id: String? = null,
    val stationName: String? = null,
    val stationAddress: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val depotStatus: String? = null,
    val evsePowers: List<EvsePowerRaw> = emptyList(),
    val isPublic: Boolean? = null,
    val isFreeParking: Boolean? = null,
    val workingTimeDescription: String? = null,
    val media: List<String>? = null,
    val verified: Boolean? = null,
    val distance: Double? = null,
    val evse: String? = null
) {
    /**
     * Fallback to support both `locationId` and `id`.
     */
    val effectiveLocationId: String
        get() = locationId ?: id ?: ""
}

/**
 * Envelope response returned by EVCS background search API.
 */
@Serializable
data class SearchResponse(
    val code: Int = 0,
    val data: List<SearchStationRaw>? = null,
    val error: String? = null
)

/**
 * Search payload sent to `POST /search?t=...`.
 */
@Serializable
data class SearchRequest(
    val latitude: Double,
    val longitude: Double,
    val wattageTypes: List<String> = listOf("FAST", "SUPER_FAST")
)

/**
 * Domain representation of a power port configuration.
 */
@Immutable
@Serializable
data class PowerPort(
    val typeWatts: Long = 0L,
    val label: String = "", // e.g. "60kW", "DC"
    val availablePlugs: Int = 0,
    val totalPlugs: Int = 0,
    val displayString: String = "" // e.g. "60kW: trống 1/2 cổng"
) {
    /**
     * True if verified real-time plug count telemetry is present.
     */
    val hasLiveTelemetry: Boolean
        get() = totalPlugs > 0

    /**
     * Chip text formatted for UI display without synthetic "trống 0/0" when unverified.
     */
    val chipDisplayString: String
        get() = if (hasLiveTelemetry) "$label: trống $availablePlugs/$totalPlugs" else label
}

/**
 * Domain model representing an enriched EVCS station.
 */
@Immutable
@Serializable
data class Station(
    val id: String, // locationId
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val summary: String,
    val connectors: String,
    val depotStatus: String, // "Normal", "Maintaining", "OutOfService", "Unknown"
    val powers: List<PowerPort> = emptyList(),
    val totalAvailablePlugs: Int = 0,
    val totalPlugs: Int = 0,
    val image: String? = null,
    val isPublic: Boolean = true,
    val isFreeParking: Boolean = true,
    val workingTimeDescription: String = "24/7",
    val distanceKm: Double? = null,
    val drivingMetrics: DrivingMetrics? = null
) {
    /**
     * True if verified real-time telemetry metrics are available for this station.
     */
    val hasLiveTelemetry: Boolean
        get() = totalPlugs > 0

    /**
     * Returns driving distance in km if drivingMetrics is available; otherwise falls back to Haversine distanceKm.
     */
    val effectiveDistanceKm: Double?
        get() = drivingMetrics?.let { it.distanceMeters / 1000.0 } ?: distanceKm

    /**
     * Returns driving duration in seconds if available; otherwise null.
     */
    val effectiveDurationSeconds: Long?
        get() = drivingMetrics?.durationSeconds
}

