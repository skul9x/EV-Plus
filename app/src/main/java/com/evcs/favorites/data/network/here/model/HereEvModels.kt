package com.evcs.favorites.data.network.here.model

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Token response returned by HERE OAuth 1.0a token endpoint `https://account.api.here.com/oauth2/token`.
 */
@Serializable
data class HereTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long = 86400L
)

/**
 * Top-level response container for HERE EV stations API `https://ev-v2.cc.api.here.com/ev/stations.json`.
 * Accommodates both standard `evStations` object wrapper and direct `stations` list.
 */
@Serializable
data class HereEvStationsResponse(
    val evStations: HereEvStationsList? = null,
    val stations: List<HereEvStation>? = null
) {
    val allStations: List<HereEvStation>
        get() = evStations?.evStation ?: stations ?: emptyList()
}

/**
 * Nested container for stations under `evStations`.
 */
@Serializable
data class HereEvStationsList(
    val evStation: List<HereEvStation> = emptyList(),
    val total: Int? = null
)

/**
 * Individual EV charging station from HERE Maps EV API.
 */
@Serializable
data class HereEvStation(
    val id: String = "",
    val cpoId: String? = null,
    val name: String = "",
    val address: HereAddress? = null,
    val position: HerePosition? = null,
    val connectors: HereConnectorsContainer? = null,
    val rawConnectors: List<HereConnector>? = null,
    val evse: List<HereEvse>? = null
) {
    fun allConnectors(): List<HereConnector> {
        val list = mutableListOf<HereConnector>()
        connectors?.connector?.let { list.addAll(it) }
        rawConnectors?.let { list.addAll(it) }
        evse?.forEach { e -> list.addAll(e.connectors) }
        return list
    }
}

/**
 * GPS coordinates of a charging station.
 */
@Serializable
data class HerePosition(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

/**
 * Address details of a charging station.
 */
@Serializable
data class HereAddress(
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null
) {
    fun toFormattedAddress(): String {
        return listOfNotNull(street, city, state, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }
}

/**
 * Container holding a list of connectors.
 */
@Serializable
data class HereConnectorsContainer(
    val connector: List<HereConnector> = emptyList()
)

/**
 * EV Supply Equipment (EVSE) container holding connectors.
 */
@Serializable
data class HereEvse(
    val id: String? = null,
    val connectors: List<HereConnector> = emptyList()
)

@Serializable
data class HereConnectorType(
    val id: String? = null,
    val name: String? = null
)

/**
 * Detailed connector and charging power rating specification.
 */
@Serializable
data class HereConnector(
    val id: String? = null,
    val connectorType: HereConnectorType? = null,
    val maxPowerLevel: Double = 0.0, // in kW (e.g. 60.0) or Watts (e.g. 60000.0)
    val powerType: String? = null, // "DC", "AC_3_PHASE", "AC_1_PHASE"
    val connectorStatuses: HereConnectorStatusesContainer? = null,
    val rawStatuses: List<HereConnectorStatus>? = null
) {
    fun allStatuses(): List<HereConnectorStatus> {
        return connectorStatuses?.connectorStatus ?: rawStatuses ?: emptyList()
    }

    /**
     * Normalized power rating in kilowatts (kW).
     */
    val powerKw: Double
        get() = if (maxPowerLevel > 1000.0) maxPowerLevel / 1000.0 else maxPowerLevel

    /**
     * Identifies whether this connector represents a DC fast charger (>= 30kW).
     */
    val isDcCharging: Boolean
        get() {
            if (powerType != null && !powerType.contains("DC", ignoreCase = true) && powerKw < 30.0) {
                return false
            }
            return powerKw >= 30.0 || (powerType?.contains("DC", ignoreCase = true) == true && powerKw >= 20.0)
        }
}

/**
 * Container holding dynamic connector status slots.
 */
@Serializable
data class HereConnectorStatusesContainer(
    val connectorStatus: List<HereConnectorStatus> = emptyList()
)

/**
 * Dynamic charging gun / slot status.
 */
@Serializable
data class HereConnectorStatus(
    val cpoEvseId: String? = null,
    val cpoEvseEMI3Id: String? = null,
    val state: String = "UNKNOWN", // "AVAILABLE", "OCCUPIED", "OUT_OF_SERVICE", etc.
    val physicalReference: String? = null
) {
    val isAvailable: Boolean
        get() = state.equals("AVAILABLE", ignoreCase = true)

    val isOccupied: Boolean
        get() = state.equals("OCCUPIED", ignoreCase = true)
}

/**
 * Aggregated live slot metrics for a specific power tier.
 */
data class HerePowerTierAggregation(
    val powerWatts: Long,
    val powerKw: Int,
    val label: String,
    val availableCount: Int,
    val totalCount: Int,
    val gunIds: List<String> = emptyList()
) {
    fun toDomainPowerPort(): PowerPort {
        val display = if (totalCount > 0) {
            "$label: trống $availableCount/$totalCount cổng"
        } else {
            label
        }
        return PowerPort(
            typeWatts = powerWatts,
            label = label,
            availablePlugs = availableCount,
            totalPlugs = totalCount,
            displayString = display
        )
    }
}

/**
 * Utilities for normalizing power levels and mapping HERE EV stations to domain models.
 */
object HereModelMapper {

    /**
     * Quantizes power rating into standard VinFast / EV power tiers (30kW, 60kW, 150kW, 250kW, 360kW).
     */
    fun normalizeToPowerWatts(powerKw: Double): Long {
        val rounded = powerKw.roundToInt()
        return when (rounded) {
            in 25..35 -> 30_000L
            in 50..70 -> 60_000L
            in 110..130 -> 120_000L
            in 140..170 -> 150_000L
            in 230..270 -> 250_000L
            in 340..380 -> 360_000L
            else -> (rounded * 1000L).coerceAtLeast(0L)
        }
    }

    /**
     * Returns standard display label like "30kW", "60kW", "150kW".
     */
    fun getPowerLabel(powerWatts: Long): String {
        val kw = powerWatts / 1000L
        return "${kw}kW"
    }

    /**
     * Aggregates connectors by power tier, computing available and total slots.
     */
    fun aggregatePowerTiers(
        connectors: List<HereConnector>,
        dcOnly: Boolean = true
    ): List<HerePowerTierAggregation> {
        val filtered = if (dcOnly) {
            connectors.filter { it.isDcCharging }
        } else {
            connectors
        }

        // Group connectors by normalized power tier watts
        val grouped = filtered.groupBy { normalizeToPowerWatts(it.powerKw) }

        return grouped.map { (powerWatts, tierConnectors) ->
            var availableSum = 0
            var totalSum = 0
            val guns = mutableListOf<String>()

            for (conn in tierConnectors) {
                val statuses = conn.allStatuses()
                if (statuses.isNotEmpty()) {
                    for (status in statuses) {
                        totalSum++
                        if (status.isAvailable) {
                            availableSum++
                        }
                        status.cpoEvseEMI3Id?.takeIf { it.isNotBlank() }?.let { guns.add(it) }
                            ?: status.physicalReference?.takeIf { it.isNotBlank() }?.let { guns.add(it) }
                    }
                } else {
                    // If no explicit connectorStatus array provided, treat connector as 1 slot
                    totalSum++
                }
            }

            val kw = (powerWatts / 1000L).toInt()
            val label = getPowerLabel(powerWatts)

            HerePowerTierAggregation(
                powerWatts = powerWatts,
                powerKw = kw,
                label = label,
                availableCount = availableSum,
                totalCount = totalSum,
                gunIds = guns
            )
        }.sortedByDescending { it.powerWatts }
    }

    /**
     * Maps a HERE station DTO to the app's [Station] domain model.
     */
    fun mapStationToDomain(
        hereStation: HereEvStation,
        dcOnly: Boolean = true
    ): Station {
        val aggregations = aggregatePowerTiers(hereStation.allConnectors(), dcOnly = dcOnly)
        val powerPorts = aggregations.map { it.toDomainPowerPort() }

        val totalAvailable = powerPorts.sumOf { it.availablePlugs }
        val totalPlugs = powerPorts.sumOf { it.totalPlugs }

        val summary = if (totalPlugs > 0) {
            "Trống $totalAvailable/$totalPlugs cổng sạc${if (dcOnly) " DC" else ""}"
        } else {
            "Không có súng sạc${if (dcOnly) " DC" else ""}"
        }

        val connectorsString = powerPorts.joinToString(", ") { it.label }
        val depotStatus = if (totalPlugs > 0 && totalAvailable == 0) "Occupied" else "Normal"

        val domainId = hereStation.cpoId?.takeIf { it.isNotBlank() }
            ?: hereStation.id.ifBlank { "here_${hereStation.name.hashCode()}" }

        return Station(
            id = domainId,
            name = hereStation.name,
            address = hereStation.address?.toFormattedAddress().orEmpty(),
            latitude = hereStation.position?.latitude ?: 0.0,
            longitude = hereStation.position?.longitude ?: 0.0,
            summary = summary,
            connectors = connectorsString,
            depotStatus = depotStatus,
            powers = powerPorts,
            totalAvailablePlugs = totalAvailable,
            totalPlugs = totalPlugs,
            images = emptyList(),
            image = null,
            addedAt = System.currentTimeMillis(),
            isPublic = true,
            isFreeParking = true,
            workingTimeDescription = "24/7",
            distanceKm = null,
            drivingMetrics = null,
            evse = "VinFast"
        )
    }
}
