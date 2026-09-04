# Phase 03: Jetpack Compose Model Stability & Regex Hot-Path Caching

Status: ✅ Completed
Issue IDs: PERF-UI-01, PERF-UI-02
Dependencies: Phase 02

## Objective
Restore Compose compiler Smart Skipping for `StationCard` items and prevent unnecessary full-screen recompositions and micro-stutters by annotating domain models (`Station`, `PowerPort`, `DrivingMetrics`, `StationForecast`, `ForecastSession`, `ForecastPowerGroup`) with `@Immutable` and memoizing connector power resolution with `remember`.

---

## Requirements

### Functional
- [x] Annotate domain models with `@androidx.compose.runtime.Immutable`:
  - `Station` and `PowerPort` in `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt`.
  - `DrivingMetrics` in `app/src/main/java/com/evcs/favorites/data/routing/DrivingMetrics.kt`.
  - `StationForecast`, `ForecastSession`, and `ForecastPowerGroup` in `app/src/main/java/com/evcs/favorites/domain/model/StationForecast.kt`.
- [x] In `StationCard.kt`, wrap `displayPowers` resolution block in `remember(station.powers, station.connectors)` so regex parsing only executes when input data changes.
- [x] In `EvcsRepository.kt`, pre-compile connector parsing regex into a static constant (`KW_REGEX`) in `companion object` so that when `parseConnectorsToPowers` runs, it does not re-compile regex per connector token.
- [x] Ensure serialization and deserialization via `@kotlinx.serialization.Serializable` remain completely intact.

### Non-Functional
- [x] Compose Smart Skipping: When one station receives updated forecast or telemetry, other unmodified `StationCard` composables skip recomposition completely.
- [x] Hot-path CPU savings: Eliminate redundant regex evaluation on every scroll frame.

---

## Implementation Steps
1. **Update Domain Models**:
   - Add `@Immutable` annotation to `Station` and `PowerPort` in `StationModels.kt`.
   - Add `@Immutable` annotation to `DrivingMetrics` in `DrivingMetrics.kt`.
   - Add `@Immutable` annotation to `StationForecast`, `ForecastSession`, and `ForecastPowerGroup` in `StationForecast.kt`.
2. **Update `StationCard.kt`**:
   - Wrap `displayPowers` computation in `remember(station.powers, station.connectors)`.
3. **Update `EvcsRepository.kt`**:
   - Move `Regex("""(\d+(?:\.\d+)?)\s*kW""", RegexOption.IGNORE_CASE)` to a pre-compiled companion object constant.
4. **Verify Functionality**:
   - Verify station card rendering, wattage chip display, and forecast badge alignment remain 100% accurate.

---

## Files to Modify/Create
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - Add `@Immutable` to `Station` and `PowerPort`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/routing/DrivingMetrics.kt` - Add `@Immutable` to `DrivingMetrics`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/domain/model/StationForecast.kt` - Add `@Immutable` to `StationForecast`, `ForecastSession`, `ForecastPowerGroup`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Pre-compile connector parsing regex.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - Memoize `displayPowers` resolution with `remember`.
- [NEW] `app/src/test/java/com/evcs/favorites/ComposeStabilityAndHotPathRecompositionTest.kt` - Exactly one comprehensive test for Phase 03.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/ComposeStabilityAndHotPathRecompositionTest.kt`
- **Core Verifications**:
  1. `Station`, `PowerPort`, `DrivingMetrics`, and `StationForecast` carry `@Immutable` annotation at runtime/reflection.
  2. Data class equality and copy contracts are preserved when telemetry or forecast changes.
  3. Connector power parsing results are identical and idempotent across repeated calls.
  4. Station list update isolation: modifying one station in a collection leaves other station identities and hashes unchanged.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ComposeStabilityAndHotPathRecompositionTest
```
