# Phase 01: Focus Mode EVCS Fixed 10s Telemetry Polling
Status: ✅ Completed
Dependencies: None

## Objective
Migrate `FocusModeTelemetryEngine` and `FocusModeForegroundService` to query station telemetry directly from EVCS API (`EvcsApiClient` / `EvcsRepository`) instead of `HereEvApiClient`. Enforce a constant 10,000ms polling interval regardless of distance to the station, and retain the previous valid telemetry snapshot on network jitter, timeout, or delay without degrading the UI.

## Requirements
### Functional
- [x] Replace `fetchStationTelemetry` implementation in Focus Mode to fetch telemetry using EVCS API (`EvcsApiClient.searchStations` targeting the station coordinates and matching `locationId`).
- [x] Align search parameters with EVCS production protocol (`curl_capture_20260910_105448`), ensuring wattage filtering (`wattageTypes = ["FAST", "SUPER_FAST"]`) to focus on DC charging plugs and reduce payload overhead.
- [x] Replace dynamic distance-based polling interval (15s/10s/5s) with a fixed constant interval: `INTERVAL_FIXED_MS = 10_000L` (10s) from start to finish.
- [x] When EVCS network response fails or times out, retain the latest valid station telemetry (available DC slots, total DC slots, powers) without resetting to 0 slots or flashing offline.
- [x] Preserve `HereEvApiClient` as an intact backup component in the codebase (Direction A).

### Non-Functional
- [x] Thread safety and non-blocking IO via Kotlin Coroutines Dispatchers.IO.
- [x] No UI stuttering or thread locks in Android System Alert Window Overlay.
- [x] Zero payload waste: avoid parsing unneeded AC or motorcycle power tiers during DC telemetry calculation.

## Implementation Steps
1. Update `FocusModeTelemetryEngine`:
   - Add constant `INTERVAL_FIXED_MS = 10_000L` (10s).
   - Deprecate / replace dynamic interval calculation with fixed 10s interval in `start()` polling coroutine.
   - Update error-handling in `pollOnce()` to retain the existing `availableDcSlots` and `totalDcSlots` from `currentState` if `fetchStationTelemetry` returns failure or timeout.
2. Update `FocusModeForegroundService` and `AppContainer`:
   - Inject `EvcsApiClient` or `EvcsRepository` into `FocusModeForegroundService`.
   - Provide a telemetry fetcher lambda that performs an EVCS search around the station coordinates and maps the matching `SearchStationRaw` to domain `Station`.
3. Provide unit test in `app/src/test/java/com/evcs/favorites/focus/FocusModeEvcsPollingTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt` - Modify polling interval to fixed 10s and refine failure retention.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt` - Wire EVCS API client into `FocusModeTelemetryEngine` initialization.
- `app/src/main/java/com/evcs/favorites/di/AppContainer.kt` - Ensure `evcsApiClient` or `evcsRepository` is exposed for Focus Mode.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeEvcsPollingTest.kt` - [NEW] Single comprehensive unit test for Phase 01.

## Test Criteria
- Exactly one comprehensive test file: `FocusModeEvcsPollingTest.kt` verifying:
  1. Polling interval is constant at 10,000ms.
  2. Telemetry is parsed correctly from EVCS API format (`evsePowers`, `totalCharging`, `chargingKw`).
  3. When an EVCS API request times out or throws IOException, the existing available DC slots count and previous state are retained without zeroing out.

---
Next Phase: [Phase 02: Conditional OSRM Driving Matrix Reroute Engine](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260910-1044-focus-mode-evcs-telemetry-and-osrm-reroute/phase-02-conditional-osrm-reroute.md)
