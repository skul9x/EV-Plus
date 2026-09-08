package com.evcs.favorites.car

import com.evcs.favorites.data.model.Station

/**
 * Visual indicator status for station port availability on the vehicle screen.
 */
enum class CarAvailabilityStatus(val emoji: String) {
    AVAILABLE("🟢"),
    LIMITED("🟡"),
    UNAVAILABLE("🔴"),
    UNKNOWN("⚪")
}

/**
 * Pure Kotlin presentation model representing an automotive station list entry.
 * Completely decoupled from Android platform framework for fast JVM unit testing.
 */
data class CarStationUiModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val availablePlugs: Int,
    val totalPlugs: Int,
    val status: CarAvailabilityStatus,
    val distanceKm: Double? = null,
    val isAvailable: Boolean = availablePlugs > 0,
    val station: Station
)

/**
 * Specification for a single row rendered in automotive templates (PlaceListMapTemplate or PaneTemplate).
 */
data class CarRowSpec(
    val title: String,
    val subtitle: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isAvailable: Boolean = true,
    val station: Station? = null
)

/**
 * Decoupled specification for automotive station detail PaneTemplate.
 * Strictly adheres to Car App Library driver safety constraints:
 * - Constraint 1: Maximum 4 content rows (host throws IllegalArgumentException if exceeded).
 * - Constraint 2: Maximum 2 pane actions.
 */
data class CarPaneSpec(
    val title: String,
    val rows: List<CarRowSpec>,
    val primaryActionTitle: String = ACTION_NAVIGATE_AND_MONITOR,
    val primaryActionEnabled: Boolean = true
) {
    companion object {
        const val MAX_LIST_ITEMS = 6
        const val MAX_PANE_ROWS = 4
        const val MAX_PANE_ACTIONS = 2
        const val ACTION_NAVIGATE_AND_MONITOR = "⚡ DẪN ĐƯỜNG & THEO DÕI"
        const val MAIN_SCREEN_TITLE = "EV-Plus - Trạm sạc"
        const val REFRESH_ACTION_TITLE = "Làm mới"
        const val EMPTY_STATIONS_MESSAGE = "Không tìm thấy trạm sạc khả dụng"
    }

    init {
        require(rows.size <= MAX_PANE_ROWS) {
            "CarPaneSpec cannot exceed $MAX_PANE_ROWS rows due to driver distraction regulations (current: ${rows.size})"
        }
    }
}
