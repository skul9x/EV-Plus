# Phase 02: EV Routing Settings & Vehicle Profile Configuration
Status: 🟢 Completed
Dependencies: [Phase 01: Offline Locations Dataset & Repository](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-01-offline-locations-dataset-and-repository.md)

## Objective
Establish the data structures, default values, validation logic, and encrypted/shared preferences persistence for EV vehicle specs and routing safety buffers, enabling the user to configure their safe range, starting SoC, reserve threshold, target charging SoC, and duration delay buffer.

## Requirements
### Functional
- [x] Define `EvRoutingSettings` data class with fields:
  - `vehicleSafeRangeKm: Int` (allowed range: 100 - 500, default: 200)
  - `startBatteryPercent: Int` (allowed range: 10 - 100, default: 100)
  - `arrivalBufferSocPercent: Int` (allowed range: 5 - 25, default: 10)
  - `targetChargingSocPercent: Int` (allowed range: 70 - 95, default: 85)
  - `safetyDurationBufferEnabled: Boolean` (default: true)
  - `safetyDurationBufferRatio: Float` (default: 0.25f, representing +25% buffer)
- [x] Provide helper formulas on `EvRoutingSettings`:
  - `calculateUsableRangeKm(socPercent: Int): Double` = `vehicleSafeRangeKm * (socPercent / 100.0)`
  - `calculateEffectiveRangeBeforeReserveKm(socPercent: Int): Double` = `vehicleSafeRangeKm * ((socPercent - arrivalBufferSocPercent).coerceAtLeast(0) / 100.0)`
  - `applyDurationBuffer(rawMinutes: Int): Int` = if (`safetyDurationBufferEnabled`) `(rawMinutes * (1f + safetyDurationBufferRatio)).roundToInt()` else `rawMinutes`
- [x] Extend `RoutingSettings` and `RoutingPreferencesManager` to serialize, read, update, and emit `EvRoutingSettings` as a reactive `StateFlow`.
- [x] Enforce boundary clamping and sanitization against invalid inputs.

### Non-Functional
- [x] Backward compatibility with existing user routing preferences (zero breaking schema changes).
- [x] Instant reactivity via `StateFlow`.

## Implementation Steps
1. [x] Create `app/src/main/java/com/evcs/favorites/data/routing/EvRoutingSettings.kt`.
2. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/RoutingSettings.kt` and `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt`.
3. [x] Implement comprehensive test in `app/src/test/java/com/evcs/favorites/data/preferences/EvRoutingSettingsPreferencesTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/EvRoutingSettings.kt` - EV vehicle and safety buffer model.
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingSettings.kt` - Integrated settings container.
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` - Preferences read/write/flow manager.
- `app/src/test/java/com/evcs/favorites/data/preferences/EvRoutingSettingsPreferencesTest.kt` - Phase 2 verification test.

## Verification Test (Exactly One File-Based Test)
- Test Class: `com.evcs.favorites.data.preferences.EvRoutingSettingsPreferencesTest`
- Execution Command:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.preferences.EvRoutingSettingsPreferencesTest"
  ```

---
Next Phase: [Phase 03: Smart EV Corridor Route Planner Engine](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-03-smart-ev-corridor-route-planner.md)
