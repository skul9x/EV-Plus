# Phase 03: AC-Only Station Focus Button Visibility Refinement
Status: ⬜ Pending
Dependencies: Phase 01, Phase 02

## Objective
Refine `NativeStationDetailSheet` so that when a driver inspects a station with only AC charging connectors (no DC ports available, e.g. 7kW / 11kW / 22kW), the "⚡ Focus Mode" button is completely hidden, and the "Chỉ đường" (Navigate) button seamlessly expands to full width (`fillMaxWidth()`) on row 1 of the action section.

## Requirements
### Functional
- [ ] Determine whether a station has DC charging capability: `val hasDc = station.powers.any { it.isDc() }`.
- [ ] In `NativeStationDetailSheetContent`:
  - When `hasDc == true`: Show Row 1 with two buttons: `[Chỉ đường]` (weight 1f) and `[⚡ Focus Mode]` (weight 1f).
  - When `hasDc == false`: Render only `[Chỉ đường]` with `Modifier.fillMaxWidth()`.
- [ ] Retain Row 2 with `[Yêu thích]` and `[Chia sẻ]` unchanged in both scenarios.
- [ ] Add helper method or spec evaluator in `NativeStationDetailSheetHelper` to allow pure unit testing without Compose runtime dependencies:
  - `fun shouldShowFocusModeButton(station: Station): Boolean`
  - `fun resolvePrimaryActionLayout(station: Station): PrimaryActionLayoutSpec`

### Non-Functional
- [ ] No UI jank or visual breakage when sheet expands.
- [ ] Maintain consistent Material 3 button heights and rounded corner shapes (24.dp).

## Implementation Steps
1. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt`:
   - In `NativeStationDetailSheetHelper`, add:
     ```kotlin
     fun hasDcCharging(station: Station): Boolean {
         return station.powers.any { it.isDc() }
     }
     ```
   - In `NativeStationDetailSheetContent` (row 1 quick actions):
     ```kotlin
     val hasDcCharging = remember(station.powers) {
         NativeStationDetailSheetHelper.hasDcCharging(station)
     }
     ```
   - Conditionally display the buttons:
     - If `hasDcCharging`: Render Row with `[Chỉ đường]` (weight 1f) + `[⚡ Focus Mode]` (weight 1f).
     - If `!hasDcCharging`: Render Row with single `[Chỉ đường]` (fillMaxWidth).
2. [ ] Create single test file: `app/src/test/java/com/evcs/favorites/ui/components/StationDetailFocusButtonVisibilityTest.kt`:
   - Test station with only AC ports (e.g. 11kW, 22kW AC) returns `hasDcCharging == false`.
   - Test station with 20kW DC returns `hasDcCharging == true`.
   - Test station with mixed 11kW AC and 60kW DC returns `hasDcCharging == true`.
   - Test station with 0 power ports returns `hasDcCharging == false`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Add DC port inspection and conditional Focus Mode button rendering.
- `app/src/test/java/com/evcs/favorites/ui/components/StationDetailFocusButtonVisibilityTest.kt` - Unit test for Phase 03 verification.

## Test Criteria
- [ ] Run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.components.StationDetailFocusButtonVisibilityTest"`
- [ ] 100% tests pass.

---
Implementation Complete after Phase 03.
