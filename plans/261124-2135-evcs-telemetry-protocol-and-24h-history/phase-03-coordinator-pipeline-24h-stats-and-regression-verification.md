# Phase 03: Coordinator Pipeline, 24h Stats Calculation & End-to-End Regression Verification
Status: ✅ Completed
Dependencies: Phase 01 (`StationTelemetryParser`, `SessionManager`), Phase 02 (`EvcsTelemetryDataSource`, `EvcsTelemetryRepository`)

## Objective
Connect the modernized telemetry data source and REST 24h history endpoint to `StationDetailCoordinator`, ensure `sendTelemetryUpdate` transmits the live `chargingKw` map and integrity token `uToken`, wire Stage 2 history retrieval into `calculate24hStats` to populate `stats24h` on `StationDetailUiState`, and verify end-to-end regression across both portrait and landscape UI architectures without regressions.

## Scope
1. **Station Detail Coordinator Orchestration (`StationDetailCoordinator.kt`)**:
   - In Stage 1: Capture `telemetry.uToken` and `telemetry.busyByKw`.
   - In telemetry sync ping: pass `chargingKw = telemetry.busyByKw` and `uToken = telemetry.uToken` to `telemetryRepository.sendTelemetryUpdate`.
   - In Stage 2: `telemetryRepository.fetch24hHistory(station.id, tokens.apiToken)` executes within `statsTimeoutMs` (3000ms). When non-empty points are returned, invoke `telemetryRepository.calculate24hStats(points, station.totalPlugs)` and update `_stationDetailState.update { it.copy(isLoadingStats = false, stats24h = stats) }`.
   - Ensure timeout or network error in Stage 2 degrades gracefully to `stats24h = null, isLoadingStats = false` without impacting live port availability or throwing unhandled exceptions.
2. **UI Layer Binding Verification (`NativeStationDetailContent.kt` & Sheet)**:
   - Ensure `Station24hStats` properties (`peakUsage`, `avgUsage`, `peakHour`, `fillRate`) cleanly bind to the historical occupancy metrics section in the station detail sheet.
   - Verify layout stability in both portrait bottom sheet and landscape split-pane orientations.
3. **Comprehensive End-to-End Regression Test**:
   - Author exactly one comprehensive integration test file: `app/src/test/java/com/evcs/favorites/ui/StationDetailTelemetryAnd24hHistoryIntegrationTest.kt`.
   - Verify Stage 1 live telemetry parsing, `/update` payload generation, Stage 2 24h stats calculation, error handling, timeout handling, and dismissal state reset.

## Requirements
### Functional
- [x] Complete Stage 1 Telemetry Binding: Station detail opens, parses live telemetry with `chargingKw` -> `busyByKw`, and updates `portStatuses`.
- [x] Telemetry Update Forwarding: `StationDetailCoordinator` dispatches `sendTelemetryUpdate` with the correct `stationId`, `tokens.apiToken`, `totalBusy`, `chargingKw = telemetry.busyByKw`, and `uToken = telemetry.uToken`.
- [x] Complete Stage 2 24h History Enrichment: On station selection, `fetch24hHistory` loads time-series points, calculates `Station24hStats` (peak, average, peak hour, fill rate), and populates `stats24h` on `StationDetailUiState`.
- [x] Graceful Timeout & Failure Degradation: When `fetch24hHistory` encounters a timeout or network error, `_stationDetailState` sets `isLoadingStats = false` and `stats24h = null` without crashing or clearing Stage 1 port data.
- [x] Station Dismissal State Reset: Closing the station detail sheet clears `stats24h`, `telemetry`, `portStatuses`, and resets loading flags.
- [x] Dual-Orientation Compatibility: UI models seamlessly support both Portrait BottomSheet and Landscape Split-Pane.

### Non-Functional
- [x] Main-Thread Responsiveness: 24h stats calculation and JSON decoding run strictly off the main thread (`Dispatchers.IO`).
- [x] Concurrency Safety: Stage 2 uses `withTimeoutOrNull(statsTimeoutMs)` to prevent indefinite background coroutine execution if network stalls.
- [x] Structured Concurrency: Closing station detail or selecting another station cancels any pending Stage 2 job.

## How to Implement
1. **Update `StationDetailCoordinator.kt`**:
   In `selectStation(station: Station)` or internal telemetry enrichment pipeline:
   ```kotlin
   // In Stage 1 after obtaining tokens and live telemetry:
   val telemetry = chargingResult.getOrThrow()
   val totalBusy = telemetry.busyByKw.values.sum()

   // Dispatch modernized telemetry update with chargingKw and uToken
   scope.launch(ioDispatcher) {
       telemetryRepository.sendTelemetryUpdate(
           stationId = station.id,
           apiToken = tokens.apiToken,
           totalBusy = totalBusy,
           chargingKw = telemetry.busyByKw,
           uToken = telemetry.uToken
       )
   }

   // Stage 2: 24h usage statistics calculation with bounded timeout
   try {
       withTimeoutOrNull(statsTimeoutMs) {
           val historyResult = withContext(ioDispatcher) {
               telemetryRepository.fetch24hHistory(station.id, tokens.apiToken)
           }
           if (historyResult.isSuccess) {
               val points = historyResult.getOrThrow()
               if (points.isNotEmpty()) {
                   val stats = telemetryRepository.calculate24hStats(points, station.totalPlugs)
                   _stationDetailState.update {
                       it.copy(
                           isLoadingStats = false,
                           stats24h = stats
                       )
                   }
               } else {
                   _stationDetailState.update { it.copy(isLoadingStats = false) }
               }
           } else {
               _stationDetailState.update { it.copy(isLoadingStats = false) }
           }
       } ?: run {
           _stationDetailState.update { it.copy(isLoadingStats = false, stats24h = null) }
       }
   } catch (e: CancellationException) {
       throw e
   } catch (_: Exception) {
       _stationDetailState.update { it.copy(isLoadingStats = false, stats24h = null) }
   }
   ```
2. **Review UI Components in `NativeStationDetailContent.kt`**:
   Verify that `stats24h` displays the rush hour ticker, peak usage, and fill rate badge if `stats24h != null`, and shows skeleton shimmer or hides cleanly when `isLoadingStats = false && stats24h == null`.

## Files to Create/Modify
- Modify `app/src/main/java/com/evcs/favorites/ui/viewmodel/StationDetailCoordinator.kt` - Update telemetry update invocation with `chargingKw` and `uToken`.
- Modify `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailContent.kt` (if needed for stats presentation verification).
- Create `app/src/test/java/com/evcs/favorites/ui/StationDetailTelemetryAnd24hHistoryIntegrationTest.kt` - Comprehensive single integration test file.

## Dependencies
- Phase 01 & Phase 02 implementations.
- Kotlinx Coroutines Test framework.

## Constraints
- Must not alter public contract of `StationDetailUiState` other than utilizing existing `stats24h` property.
- Preserve cooperative cancellation safety with `CancellationException` rethrowing.

## Acceptance Criteria
- [x] Station detail selection completes Stage 1 and populates port statuses derived from `chargingKw`.
- [x] Telemetry sync request to `/update` contains `t`, `a`, `b`, `p`, `u`.
- [x] Stage 2 completes and enriches `stats24h` with computed metrics (`peakUsage`, `avgUsage`, `peakHour`, `fillRate`).
- [x] Timeout or network error in Stage 2 cleanly sets `isLoadingStats = false` without crashing.
- [x] Exactly one test file `StationDetailTelemetryAnd24hHistoryIntegrationTest.kt` passes with 100% assertions green.

## Single Comprehensive Test File
- `app/src/test/java/com/evcs/favorites/ui/StationDetailTelemetryAnd24hHistoryIntegrationTest.kt`
