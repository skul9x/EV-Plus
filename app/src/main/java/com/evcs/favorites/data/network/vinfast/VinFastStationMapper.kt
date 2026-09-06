package com.evcs.favorites.data.network.vinfast

import com.evcs.favorites.data.model.EvsePowerRaw
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.SearchStationRaw
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.domain.location.DistanceCalculator
import com.evcs.favorites.util.StationNameSanitizer
import com.evcs.favorites.util.VinFastCdnUrlDecoder

/**
 * Pure transformer mapping raw VinFast station DTOs (Tier-1 VinFast CAPP telemetry and Tier-2 fallback)
 * into clean domain [Station] and [PowerPort] models.
 *
 * Enforces strict automobile port hard-filtering (eliminating 2-wheeler / motorbike plugs where type <= 7000),
 * drops pure-motorbike charging locations, re-aggregates total/available plug counts strictly from car bays,
 * and resolves direct S3/CloudFront CDN image URLs.
 */
object VinFastStationMapper {

    const val VINFAST_CDN_BASE_URL = "https://cpo-prod-s3.vinfastauto.com/"

    /**
     * Formats integer watts (e.g. 11000, 60000, 120000, 250000) into human-readable kW label.
     * Examples: 11000L -> "11kW", 60000L -> "60kW", 7400L -> "7.4kW".
     */
    fun formatWattageLabel(typeWatts: Long): String {
        if (typeWatts <= 0L) return ""
        return if (typeWatts % 1000L == 0L) {
            "${typeWatts / 1000L}kW"
        } else {
            val kw = typeWatts / 1000.0
            val formatted = if (kw % 1.0 == 0.0) {
                "${kw.toLong()}"
            } else {
                kw.toString().removeSuffix(".0")
            }
            "${formatted}kW"
        }
    }

    /**
     * Resolves raw image string into direct VinFast CloudFront/S3 CDN URL.
     * Handles:
     * - Relative paths (e.g. "charging-station-car/depot/images/sample.jpg" -> "https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/sample.jpg")
     * - Direct absolute HTTPS URLs (retained unchanged)
     * - Tier-2 EVCS media token queries (decoded via [VinFastCdnUrlDecoder])
     */
    fun resolveImageUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()

        // 1. If it contains EVCS proxy file token, decode via VinFastCdnUrlDecoder
        if (trimmed.contains("file=", ignoreCase = true)) {
            val decoded = VinFastCdnUrlDecoder.decode(trimmed)
            if (decoded != null) return decoded
        }

        // 2. Direct absolute HTTP/HTTPS URL
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }

        // 3. Encoded token without file= query param (Tier-2 fallback token)
        val decoded = VinFastCdnUrlDecoder.decode(trimmed)
        if (decoded != null) {
            return decoded
        }

        // 4. Relative path on VinFast S3 CDN (Tier-1)
        val cleanPath = trimmed.removePrefix("/")
        return "$VINFAST_CDN_BASE_URL$cleanPath"
    }

    /**
     * Maps raw connector DTO to domain [PowerPort].
     * Returns `null` for 2-wheeler / motorbike connectors where `type <= 7000` (e.g. 3.5kW and 7kW AC).
     */
    fun toDomainPowerPort(connector: VinFastConnectorDto): PowerPort? {
        val type = connector.type ?: return null
        if (type <= 7000) return null

        val typeWatts = type.toLong()
        val label = formatWattageLabel(typeWatts)
        val available = connector.count ?: 0
        val total = connector.total ?: 0
        val displayString = "$label: trống $available/$total cổng"

        return PowerPort(
            typeWatts = typeWatts,
            label = label,
            availablePlugs = available,
            totalPlugs = total,
            displayString = displayString
        )
    }

    /**
     * Maps Tier-1 [VinFastStationStatusDto] to domain [Station].
     * Returns `null` if the station has no car-compatible ports remaining after filtering out motorbike plugs.
     */
    fun toDomainStation(
        dto: VinFastStationStatusDto,
        userLat: Double? = null,
        userLon: Double? = null
    ): Station? {
        val carPowers = dto.connectors?.mapNotNull { toDomainPowerPort(it) } ?: emptyList()
        if (carPowers.isEmpty()) {
            return null
        }

        val totalAvailablePlugs = carPowers.sumOf { it.availablePlugs }
        val totalPlugs = carPowers.sumOf { it.totalPlugs }
        val connectorsStr = carPowers.joinToString(", ") { it.label }

        val lat = dto.latitude ?: 0.0
        val lon = dto.longitude ?: 0.0

        val dist = if (userLat != null && userLon != null && (lat != 0.0 || lon != 0.0)) {
            DistanceCalculator.calculateDistanceKm(userLat, userLon, lat, lon)
        } else {
            null
        }

        val resolvedImages = dto.images?.mapNotNull { img ->
            resolveImageUrl(img.url?.takeIf { it.isNotBlank() } ?: img.thumbnail)
        } ?: emptyList()

        val workingTime = dto.workingTimeDescription?.takeIf { it.isNotBlank() } ?: "24/7"

        return Station(
            id = dto.locationId.orEmpty(),
            name = StationNameSanitizer.sanitize(dto.stationName),
            address = dto.stationAddress.orEmpty(),
            latitude = lat,
            longitude = lon,
            summary = workingTime,
            connectors = connectorsStr,
            depotStatus = dto.depotStatus ?: "Normal",
            powers = carPowers,
            totalAvailablePlugs = totalAvailablePlugs,
            totalPlugs = totalPlugs,
            images = resolvedImages,
            image = resolvedImages.firstOrNull(),
            addedAt = 0L,
            isPublic = dto.isPublic ?: true,
            isFreeParking = dto.isFreeParking ?: true,
            workingTimeDescription = workingTime,
            distanceKm = dist,
            drivingMetrics = null,
            evse = "VinFast",
            sourceTier = "VINFAST_DIRECT"
        )
    }

    /**
     * Maps a list of Tier-1 [VinFastStationStatusDto] to domain [Station] models,
     * automatically discarding pure-motorbike stations.
     */
    fun toDomainStations(
        dtos: List<VinFastStationStatusDto>,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<Station> {
        return dtos.mapNotNull { toDomainStation(it, userLat, userLon) }
    }

    /**
     * Maps Tier-2 fallback [EvsePowerRaw] to domain [PowerPort].
     * Returns `null` for 2-wheeler / motorbike connectors where `type <= 7000`.
     */
    fun toDomainPowerPort(rawPower: EvsePowerRaw): PowerPort? {
        if (rawPower.type <= 7000L) return null

        val typeWatts = rawPower.type
        val label = formatWattageLabel(typeWatts).ifEmpty {
            if (rawPower.powerType?.contains("DC", ignoreCase = true) == true) "DC" else "AC"
        }
        val available = rawPower.numberOfAvailableEvse
        val total = rawPower.totalEvse
        val displayString = if (total > 0) {
            "$label: trống $available/$total cổng"
        } else {
            label
        }

        return PowerPort(
            typeWatts = typeWatts,
            label = label,
            availablePlugs = available,
            totalPlugs = total,
            displayString = displayString
        )
    }

    /**
     * Maps Tier-2 fallback [SearchStationRaw] to domain [Station].
     * Returns `null` if the station has no car-compatible ports remaining after filtering.
     */
    fun toDomainStation(
        raw: SearchStationRaw,
        userLat: Double? = null,
        userLon: Double? = null
    ): Station? {
        val carPowers = raw.evsePowers.mapNotNull { toDomainPowerPort(it) }
        if (carPowers.isEmpty()) {
            return null
        }

        val totalAvailablePlugs = carPowers.sumOf { it.availablePlugs }
        val totalPlugs = carPowers.sumOf { it.totalPlugs }
        val connectorsStr = carPowers.joinToString(", ") { it.label }

        val dist = if (userLat != null && userLon != null && (raw.latitude != 0.0 || raw.longitude != 0.0)) {
            DistanceCalculator.calculateDistanceKm(userLat, userLon, raw.latitude, raw.longitude)
        } else {
            raw.distance
        }

        val resolvedImages = VinFastCdnUrlDecoder.decodeList(raw.media)
        val workingTime = raw.workingTimeDescription?.takeIf { it.isNotBlank() } ?: "24/7"

        return Station(
            id = raw.effectiveLocationId,
            name = StationNameSanitizer.sanitize(raw.stationName),
            address = raw.stationAddress.orEmpty(),
            latitude = raw.latitude,
            longitude = raw.longitude,
            summary = workingTime,
            connectors = connectorsStr,
            depotStatus = raw.depotStatus ?: "Normal",
            powers = carPowers,
            totalAvailablePlugs = totalAvailablePlugs,
            totalPlugs = totalPlugs,
            images = resolvedImages,
            image = resolvedImages.firstOrNull(),
            addedAt = 0L,
            isPublic = raw.isPublic ?: true,
            isFreeParking = raw.isFreeParking ?: true,
            workingTimeDescription = workingTime,
            distanceKm = dist,
            drivingMetrics = null,
            evse = raw.evse ?: "VinFast",
            sourceTier = "EVCS_FALLBACK"
        )
    }

    /**
     * Maps a list of Tier-2 fallback [SearchStationRaw] to domain [Station] models,
     * automatically discarding pure-motorbike stations.
     */
    fun toDomainStationsFromRaw(
        rawList: List<SearchStationRaw>,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<Station> {
        return rawList.mapNotNull { toDomainStation(it, userLat, userLon) }
    }

    /**
     * Checks if a station DTO possesses at least one automobile-compatible port (wattage > 7000W).
     */
    fun hasCarCompatiblePorts(dto: VinFastStationStatusDto): Boolean {
        return dto.connectors?.any { (it.type ?: 0) > 7000 } == true
    }
}
