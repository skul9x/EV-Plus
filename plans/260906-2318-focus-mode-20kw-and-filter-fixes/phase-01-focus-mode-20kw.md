# Phase 01: Focus Mode 20kW DC Support & Smart Reroute
Status: ✅ Completed
Dependencies: None

## Objective
Enable Focus Mode and Live DC Telemetry to recognize 20kW DC fast charging stations (commonly deployed by VinFast for smaller EVs like VF3 and VF5). Update slot calculation and the alternative station reroute algorithm when the target 20kW station becomes full to recommend the nearest station offering a matching or higher DC power tier (`typeWatts >= targetMaxDcWatts`).

## Requirements
### Functional
- [x] Update `FocusModeDcFilter.MIN_DC_POWER_WATTS = 20_000L` (20kW).
- [x] Align `FocusModeDcFilter.isDcPort(port)` with `port.isDc()` logic, ensuring 22kW AC is excluded while 20kW DC is accepted.
- [x] Update `HereConnector.isDcCharging` in `HereEvModels.kt` to classify ports >= 20kW as DC (excluding 22kW AC).
- [x] Adapt `FocusModeTelemetryEngine.findAlternativeStation`: when evaluating candidate stations, select stations that have available DC slots in a tier greater than or equal to the target station's maximum DC tier (`port.typeWatts >= targetMaxDcWatts`).
- [x] Retain existing TTS Vietnamese announcement policies without breaking utterance formatting.

### Non-Functional
- [x] Zero impact on memory allocation and GC churn during live 5s-15s polling cycles.
- [x] Backward compatible with existing 30kW, 60kW, 120kW, 150kW, 250kW tiers.

## Implementation Steps
1. [x] Modify `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt`:
   - Change `FocusModeDcFilter.MIN_DC_POWER_WATTS` from `30_000L` to `20_000L`.
   - Update `FocusModeDcFilter.isDcPort(port)` to delegate to `port.isDc()` or apply identical logic (`typeWatts >= 20_000L && typeWatts != 22_000L && !label.contains("AC")`).
   - Update `FocusModeTelemetryEngine.findAlternativeStation` line 147 from `port.typeWatts == targetMaxDcWatts` to `port.typeWatts >= targetMaxDcWatts`.
2. [x] Modify `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt`:
   - Verify and adjust `HereConnector.isDcCharging` property so `powerKw >= 20.0` (with `powerKw != 22.0`) is consistently recognized as DC charging.
3. [x] Create single test file: `app/src/test/java/com/evcs/favorites/focus/FocusMode20kWSupportTest.kt`:
   - Verify `FocusModeDcFilter.calculateDcSlots` correctly counts available and total slots for 20kW DC stations.
   - Verify 22kW AC ports are excluded from DC slots calculation.
   - Verify `findAlternativeStation` recommends nearest station with >= 20kW available slots when 20kW target station is saturated.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt` - Update DC threshold, DC classifier, and auto-reroute rule.
- `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt` - Update connector DC classifier for 20kW.
- `app/src/test/java/com/evcs/favorites/focus/FocusMode20kWSupportTest.kt` - Unit test for Phase 01 verification.

## Test Criteria
- [x] Run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusMode20kWSupportTest"`
- [x] 100% tests pass.

---
Next Phase: [phase-02-auto-scroll-filter-fix.md](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260906-2318-focus-mode-20kw-and-filter-fixes/phase-02-auto-scroll-filter-fix.md)
