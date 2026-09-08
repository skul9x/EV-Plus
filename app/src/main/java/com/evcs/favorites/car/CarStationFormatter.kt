package com.evcs.favorites.car

import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.util.StationNameSanitizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Automotive text and template formatting utility.
 * Pure Kotlin implementation decoupled from Android framework dependencies for fast JVM testing.
 */
object CarStationFormatter {

    private const val DC_POWER_THRESHOLD_WATTS = 30000L

    /**
     * Determines color-coded port availability status.
     * - 🟢 AVAILABLE: ports > 0 and healthy capacity
     * - 🟡 LIMITED: only 1 port left or <= 25% remaining
     * - 🔴 UNAVAILABLE: 0 ports left
     * - ⚪ UNKNOWN: no live telemetry available
     */
    fun getAvailabilityStatus(availablePlugs: Int, totalPlugs: Int): CarAvailabilityStatus {
        if (totalPlugs <= 0) return CarAvailabilityStatus.UNKNOWN
        return when {
            availablePlugs <= 0 -> CarAvailabilityStatus.UNAVAILABLE
            availablePlugs == 1 && totalPlugs >= 3 -> CarAvailabilityStatus.LIMITED
            (availablePlugs.toDouble() / totalPlugs) <= 0.25 -> CarAvailabilityStatus.LIMITED
            else -> CarAvailabilityStatus.AVAILABLE
        }
    }

    /**
     * Formats station title by stripping distance prefixes, sanitizing naming,
     * and attaching the EVSE provider badge.
     */
    fun formatTitle(station: Station): String {
        val sanitized = StationNameSanitizer.sanitize(station.name).ifBlank {
            station.name.ifBlank { "Trạm sạc" }
        }.trim()

        val provider = station.evse.trim()
        val badge = if (provider.isNotEmpty() && !sanitized.startsWith("[$provider]", ignoreCase = true)) {
            "[$provider] "
        } else {
            ""
        }
        return "$badge$sanitized".trim()
    }

    /**
     * Extracts and sorts power ratings descending (e.g. "250kW, 60kW, 11kW").
     */
    fun formatPowerSummary(station: Station): String {
        val effectivePowers = getEffectivePowers(station)
        if (effectivePowers.isEmpty()) return ""

        val sortedPowers = effectivePowers
            .sortedByDescending { it.typeWatts }
            .map { it.label.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return sortedPowers.joinToString(", ")
    }

    /**
     * Formats live driving/haversine distance (e.g. "1.8 km" or "850m").
     */
    fun formatDistance(station: Station): String? {
        val km = station.effectiveDistanceKm ?: station.distanceKm ?: return null
        if (km < 0.0) return null
        return if (km < 1.0) {
            val meters = (km * 1000.0).roundToInt()
            "${meters}m"
        } else {
            String.format(Locale.US, "%.1f km", km)
        }
    }

    /**
     * Formats list row subtitle with hero metric: `🟢 X/Y TRỐNG • 250kW, 60kW • 1.8 km`.
     */
    fun formatSubtitle(station: Station): String {
        val status = getAvailabilityStatus(station.totalAvailablePlugs, station.totalPlugs)
        val heroText = if (station.totalPlugs > 0) {
            "${status.emoji} ${station.totalAvailablePlugs}/${station.totalPlugs} TRỐNG"
        } else {
            "⚡ Trạng thái: ${station.depotStatus.ifBlank { "Bình thường" }}"
        }

        val powerSummary = formatPowerSummary(station)
        val distanceText = formatDistance(station)

        val parts = mutableListOf<String>()
        parts.add(heroText)
        if (powerSummary.isNotEmpty()) {
            parts.add(powerSummary)
        }
        if (!distanceText.isNullOrBlank()) {
            parts.add(distanceText)
        }

        return parts.joinToString(" • ")
    }

    /**
     * Strictly truncates station list to Android Auto safety limit (max 6 items).
     */
    fun truncateStations(stations: List<Station>, limit: Int = CarPaneSpec.MAX_LIST_ITEMS): List<Station> {
        return stations.take(limit)
    }

    /**
     * Converts a domain [Station] into a decoupled [CarStationUiModel].
     */
    fun toUiModel(station: Station): CarStationUiModel {
        return CarStationUiModel(
            id = station.id,
            title = formatTitle(station),
            subtitle = formatSubtitle(station),
            latitude = station.latitude,
            longitude = station.longitude,
            availablePlugs = station.totalAvailablePlugs,
            totalPlugs = station.totalPlugs,
            status = getAvailabilityStatus(station.totalAvailablePlugs, station.totalPlugs),
            distanceKm = station.effectiveDistanceKm ?: station.distanceKm,
            station = station
        )
    }

    /**
     * Formats DC fast charging breakdown (e.g. "⚡ DC: 250kW (2 trụ), 60kW (2 trụ)").
     */
    fun formatDcBreakdown(station: Station): String {
        val powers = getEffectivePowers(station)
        val dcPorts = powers.filter { isDcPort(it) }.sortedByDescending { it.typeWatts }
        if (dcPorts.isEmpty()) {
            return "⚡ DC: Không khả dụng"
        }

        val items = dcPorts.map { port ->
            if (port.totalPlugs > 0) "${port.label} (${port.totalPlugs} trụ)" else port.label
        }.distinct()

        return "⚡ DC: ${items.joinToString(", ")}"
    }

    /**
     * Formats AC charging breakdown (e.g. "🔌 AC: 11kW (4 trụ)").
     */
    fun formatAcBreakdown(station: Station): String {
        val powers = getEffectivePowers(station)
        val acPorts = powers.filter { !isDcPort(it) }.sortedByDescending { it.typeWatts }
        if (acPorts.isEmpty()) {
            return "🔌 AC: Không khả dụng"
        }

        val items = acPorts.map { port ->
            if (port.totalPlugs > 0) "${port.label} (${port.totalPlugs} trụ)" else port.label
        }.distinct()

        return "🔌 AC: ${items.joinToString(", ")}"
    }

    /**
     * Builds the complete 4-row [CarPaneSpec] for [StationDetailCarScreen].
     * Strictly limits rows to 4 to comply with Car App Library safety restrictions.
     */
    fun buildPaneSpec(station: Station): CarPaneSpec {
        val title = formatTitle(station)

        // Row 1: Location & live distance
        val distance = formatDistance(station)
        val address = station.address.ifBlank { "Chưa có địa chỉ chi tiết" }.trim()
        val locationSubtitle = if (!distance.isNullOrBlank()) {
            "$distance • $address"
        } else {
            address
        }
        val rowLocation = CarRowSpec(
            title = "Vị trí & Khoảng cách",
            subtitle = locationSubtitle,
            latitude = station.latitude,
            longitude = station.longitude,
            station = station
        )

        // Row 2: Hero availability
        val status = getAvailabilityStatus(station.totalAvailablePlugs, station.totalPlugs)
        val availabilitySubtitle = if (station.totalPlugs > 0) {
            "${status.emoji} ${station.totalAvailablePlugs}/${station.totalPlugs} Trụ trống (Tổng ${station.totalPlugs} trụ sạc)"
        } else {
            "⚡ Trạng thái: ${station.depotStatus.ifBlank { "Sẵn sàng hoạt động" }}"
        }
        val rowAvailability = CarRowSpec(
            title = "Tình trạng trụ",
            subtitle = availabilitySubtitle,
            isAvailable = station.totalAvailablePlugs > 0,
            station = station
        )

        // Row 3: DC fast charging breakdown
        val rowDc = CarRowSpec(
            title = "Sạc nhanh DC",
            subtitle = formatDcBreakdown(station),
            station = station
        )

        // Row 4: AC charging breakdown
        val rowAc = CarRowSpec(
            title = "Sạc chuẩn AC",
            subtitle = formatAcBreakdown(station),
            station = station
        )

        return CarPaneSpec(
            title = title,
            rows = listOf(rowLocation, rowAvailability, rowDc, rowAc),
            primaryActionTitle = CarPaneSpec.ACTION_NAVIGATE_AND_MONITOR,
            primaryActionEnabled = true
        )
    }

    private fun isDcPort(port: PowerPort): Boolean {
        if (port.typeWatts >= DC_POWER_THRESHOLD_WATTS) return true
        if (port.label.contains("DC", ignoreCase = true)) return true
        if (port.displayString.contains("DC", ignoreCase = true)) return true
        return false
    }

    private fun getEffectivePowers(station: Station): List<PowerPort> {
        if (station.powers.isNotEmpty()) return station.powers
        return EvcsRepository.parseConnectorsToPowers(station.connectors)
    }
}
