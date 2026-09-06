# Phase 03: Focus Mode Telemetry Engine & Polling

Status: ✅ Completed
Dependencies: Phase 02

## Objective
Build the core Focus Mode state coordinator and foreground engine that continuously tracks the user's targeted charging station, dynamically adjusts polling frequency based on distance to the station (15s -> 10s -> 5s), strictly filters DC fast charging ports, detects offline basement scenarios, and identifies the best alternative station when all target DC slots become occupied.

## Requirements
### Functional
- [x] Implement `FocusModeState` holding target station, available DC slots, total DC slots, distance remaining, connection status, and alternative station recommendation.
- [x] Implement `FocusModeTelemetryEngine`:
  - Enforce DC-only counting ($\ge 30$kW, excluding 7kW/11kW AC and motorcycle ports).
  - Dynamic Polling intervals: 15s when distance > 3km, 10s when 1.5km - 3km, 5s when < 1.5km.
  - Connection failure detection: when network drops (e.g. entering underground parking), retain last valid telemetry and flag offline state with timestamp string (`⚠️ Mất kết nối - Dữ liệu lúc HH:mm`).
  - Auto-Reroute Resolver: when target station DC availability drops to 0, search for the nearest alternative station having matching DC power tier with available slots based on the driver's current GPS position.
- [x] Implement `FocusModeForegroundService` maintaining an active Android Foreground Service to prevent background termination while Google Maps is in the foreground.

### Non-Functional
- [x] Coroutine cancellations cleanly handled when service stops.
- [x] Zero memory leaks upon manual stop.

## Implementation Steps
1. Create `FocusModeState.kt` representing immutable state and status transitions.
2. Implement `FocusModeTelemetryEngine.kt` with pure business logic decoupled from Android UI.
3. Implement `FocusModeForegroundService.kt` to bind the polling loop to Android's foreground service lifecycle.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeState.kt` - [State model & DC slot statistics]
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt` - [Dynamic polling loop, DC filter, offline fallback & reroute logic]
- `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt` - [Android foreground service lifecycle]

## Test Verification (Single Test per Phase)
- Exactly one comprehensive test file:
  - `app/src/test/java/com/evcs/favorites/focus/FocusModeTelemetryEngineTest.kt`
- Test cases covered:
  - Verify dynamic interval calculation (15s at 4.2km, 10s at 2.1km, 5s at 0.8km).
  - Verify strict exclusion of AC ports (11kW) from DC count calculations.
  - Verify offline state transition with formatted timestamp upon network failure.
  - Verify auto-reroute algorithm selecting the closest equivalent-power DC station with available ports.

---
Next Phase: [phase-04-voice-alert-and-audio-announcement.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260906-1930-focus-mode-and-live-telemetry/phase-04-voice-alert-and-audio-announcement.md)
