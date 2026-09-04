# Phase 02: Repository Real-Time Forecast Pipeline
Status: ✅ Completed
Dependencies: [Phase 01](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1055-realtime-charging-forecast-sync/phase-01-api-client-charging-forecast-handshake.md)

## Objective
Integrate real-time charging forecast API responses into `EvcsRepository` and refine `StationForecastParser` to parse live ticker snippets, ensuring cached in-memory forecasts and background enrichments reflect real-time station metrics without SSR stale cache drift.

## Requirements
### Functional
- [x] Update `StationForecastParser.parseForecastFromHtml`:
  - Ensure ticker snippets returned by `POST /charging` (e.g. `<div class="amd-ticker amd-hasmore" role="status" ...><span class="amd-item">Dự kiến <b>1</b> xe sạc trụ <b>150kW</b> sẽ xong trong <b>13</b> phút nữa</span></div><button ... class="amd-more amd-locked">...`) are parsed accurately.
  - Preserve `vehicleCount`, `wattageKw`, `minMinutes`, `maxMinutes`, and `isTeaser = true`.
- [x] Update `EvcsRepository.fetchStationForecast`:
  - Replace `apiClient.fetchStationHtml` with `apiClient.fetchChargingForecast(station.name, station.id, isVinFast = true)`.
  - Pass the received `response.ticker` to `StationForecastParser.parseForecastFromHtml(ticker)`.
  - If dynamic call fails, fall back silently with `forecastCache.recordFailure(station.id)` and return `Result.success(null)`.
  - Cache valid forecasts in `ForecastCache` (3-minute TTL).
  - Retain exponential backoff retry for transient network errors and `Semaphore(3)` throttling.

### Non-Functional
- [x] Zero UI crash / silent degradation guarantee: never throw network exceptions to UI.
- [x] Concurrency: Preserve `CoroutineCancellationException` propagation for clean job cancellation.

## Implementation Steps
1. [x] Verify `StationForecastParser.parseForecastFromHtml` handles real EVCS ticker HTML fragments extracted from `curl_capture_20260904_075005`.
2. [x] Update `EvcsRepository.fetchStationForecast` in `com.evcs.favorites.data.repository.EvcsRepository.kt` to use `fetchChargingForecast`.
3. [x] Create single comprehensive test file `ChargingForecastRepositoryPipelineTest.kt` verifying:
   - Repository queries dynamic charging forecast via `apiClient.fetchChargingForecast`.
   - Live ticker snippet is parsed into `StationForecast` matching EVCS real-time values.
   - Successful in-memory caching with 3-minute TTL and failure cooldown.
   - Concurrency throttling with `Semaphore(3)` and cancellation safety.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/parser/StationForecastParser.kt` - Parser tuning for ticker snippets.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Switch to `fetchChargingForecast`.
- `app/src/test/java/com/evcs/favorites/data/repository/ChargingForecastRepositoryPipelineTest.kt` - [NEW] Single comprehensive unit test.

## Test Criteria
- [x] Run only `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.ChargingForecastRepositoryPipelineTest`
- [x] All test cases pass with zero failures.

---
Next Phase: [Phase 03: WebView Modal Layout & UI Alignment](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1055-realtime-charging-forecast-sync/phase-03-webview-modal-layout-and-ui-alignment.md)
