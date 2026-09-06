# Phase 01: Domain Models & Parser Implementation
Status: ✅ Completed
Dependencies: None

## Objective
Establish the core data models for station charging forecasts and implement a resilient, highly optimized regex & JSON parser to extract vehicle completion forecasts from server-side rendered (SSR) HTML pages, with full Kotlinx Serialization support for domain integration.

## Requirements
### Functional
- [x] Define `@Serializable` domain models in `com.evcs.favorites.domain.model.StationForecast.kt`:
  - `StationForecast`: holds `rawText: String`, `vehicleCount: Int`, `wattageKw: Double`, `minMinutes: Int`, `maxMinutes: Int`, `isTeaser: Boolean`, and `detailedSessions: List<ForecastSession>`.
  - `ForecastSession`: holds `kw: Double = 0.0`, `min: Int = 1`, `soc: Int = 0`.
  - `ForecastPowerGroup`: holds `kw: Double`, `vehicleCount: Int`, `minMinutes: Int`, `maxMinutes: Int`.
  - Helper methods on `StationForecast`:
    - `getGroupedPowerForecasts(): List<ForecastPowerGroup>`: groups `detailedSessions` by wattage `kw` descending, computing vehicle count and min/max minutes per power level.
    - `isMultiSession: Boolean`: returns true if `getGroupedPowerForecasts().size > 1`.
    - `formatSingleSummary(): String`: formats `"⏱️ Dự kiến $vehicleCount xe sạc trụ ${kwFormatted} sẽ xong trong $timeRange nữa"`.
    - `ForecastPowerGroup.formatBulletLine(): String`: formats `"• ${kwFormatted}:  ~$timeRange ($vehicleCount xe)"`.
- [x] Update `com.evcs.favorites.data.model.StationModels.kt`:
  - Add optional `val forecast: StationForecast? = null` property to `@Serializable data class Station`.
- [x] Implement `StationForecastParser` handling:
  - Extracting `#stationTicker` text block even when encapsulated with HTML tags.
  - Parsing vehicle count, wattage kW, min minutes, max minutes via Regex.
  - Parsing structured JSON sessions from `data-charging-sessions` (`<script>` or attribute).
  - Returning `null` gracefully if page contains `amd-locked` without forecast or invalid HTML.

### Non-Functional
- [x] Zero external heavy dependencies; pure Kotlin standard library and Kotlinx Serialization.
- [x] Regex execution speed < 5ms per HTML document.
- [x] Full serialization round-trip compatibility with `Station` data class.

## Implementation Steps
1. [x] Create `com.evcs.favorites.domain.model.StationForecast.kt` with `@Serializable` models and power-grouping helpers.
2. [x] Update `com.evcs.favorites.data.model.Station` to include `@Serializable val forecast: StationForecast? = null`.
3. [x] Create `com.evcs.favorites.data.parser.StationForecastParser.kt`.
4. [x] Create and run unit test `StationForecastParserTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/model/StationForecast.kt` - [NEW] Core domain models (`@Serializable`)
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - [MODIFY] Add forecast property to Station
- `app/src/main/java/com/evcs/favorites/data/parser/StationForecastParser.kt` - [NEW] HTML SSR forecast extractor
- `app/src/test/java/com/evcs/favorites/data/parser/StationForecastParserTest.kt` - [NEW] Single comprehensive unit test

## Test Criteria (Single File-Based Test)
Run single test:
`./gradlew testDebugUnitTest --tests com.evcs.favorites.data.parser.StationForecastParserTest`
- [x] Verified range parse: "Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa" -> count: 2, kw: 20.0, min: 7, max: 14.
- [x] Verified single minute parse: "Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 13 phút nữa" -> count: 1, kw: 60.0, min: 13, max: 13.
- [x] Verified multi-session JSON extraction and grouped power lines:
  - Sessions with 20kW (7m, 14m), 60kW (13m), 250kW (8m) -> groups correctly into 3 bullets matching `1.md`.
- [x] Verified locked/empty ticker returns null safely.
- [x] Verified Kotlinx Serialization round-trip for `Station` containing `StationForecast`.

---
Next Phase: [phase-02-repository-cache-and-network-retry.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-0830-live-station-forecast-cards/phase-02-repository-cache-and-network-retry.md)
