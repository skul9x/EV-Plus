# Phase 01: Domain Models & AC Classification Refinement
Status: ✅ Completed
Dependencies: None

## Objective
Refine core domain models to restrict AC charging classification strictly to 11kW and 22kW tiers, exclude 3.5kW and 7kW/7.4kW from `isAc()`, reject unrated `type = 0L` ports from AC filtering, remove `KW_3_5` and `KW_7` from `WattageOption`, and update `toDisplaySummary()` to show `"Cổng AC (11kW, 22kW)"`.

## Requirements
### Functional
- [x] Modify `AC_STANDARD_WATTS` in `SmartFilterModels.kt` to only contain `setOf(11_000L, 22_000L)`.
- [x] Update `PowerPort.isAc()`:
  - Return `false` if `label.contains("DC", ignoreCase = true)`.
  - Return `true` ONLY if `typeWatts in AC_STANDARD_WATTS`.
  - Explicitly return `false` if `typeWatts <= 0L`, even if `label` contains `"AC"` or `"Type 2"`.
  - Explicitly return `false` for 3.5kW (3,500W), 7kW (7,000W), and 7.4kW (7,400W).
- [x] Ensure `PowerPort.matchesCustomRange(minKw, maxKw)` preserves raw numeric matching so custom queries (e.g. min 3kW to max 7kW) match 3.5kW and 7kW ports.
- [x] Update `CustomFilterConfig.toDisplaySummary()` for `QuickChipOption.AC` to return `"Cổng AC (11kW, 22kW)"`.
- [x] Remove `KW_3_5(3_500L, "3.5kW")` and `KW_7(7_000L, "7kW")` from `WattageOption.kt`.
- [x] Update `WattageOption.fromWatts()` and `matchesWattage()` to cleanly handle remaining tiers (11kW to 360kW).

### Non-Functional
- [x] Pure Kotlin domain models, zero Android framework dependencies.
- [x] Deterministic, zero-allocation matching for hot filtering paths.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/domain/model/SmartFilterModels.kt`:
   - Change `AC_STANDARD_WATTS` to `setOf(11_000L, 22_000L)`.
   - Update `PowerPort.isAc()` implementation to enforce strict 11kW/22kW rating requirement.
   - Update `CustomFilterConfig.toDisplaySummary()` for `QuickChipOption.AC`.
2. [x] Update `app/src/main/java/com/evcs/favorites/domain/model/WattageOption.kt`:
   - Remove `KW_7` and `KW_3_5`.
   - Remove 7.4kW variation matching branch from `matchesWattage()`.
3. [x] Implement single comprehensive test: `app/src/test/java/com/evcs/favorites/domain/filter/AcPortClassificationAndWattageTierTest.kt`.
4. [x] Run verification test: `./gradlew test --tests "com.evcs.favorites.domain.filter.AcPortClassificationAndWattageTierTest"`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/model/SmartFilterModels.kt` - Update AC definition, `isAc()` and display summary
- `app/src/main/java/com/evcs/favorites/domain/model/WattageOption.kt` - Remove 3.5kW and 7kW enum constants
- `app/src/test/java/com/evcs/favorites/domain/filter/AcPortClassificationAndWattageTierTest.kt` - Single comprehensive test for Phase 01

## Test Criteria
- [x] `PowerPort(11_000L, "11kW").isAc()` returns `true`.
- [x] `PowerPort(22_000L, "22kW").isAc()` returns `true`.
- [x] `PowerPort(3_500L, "3.5kW").isAc()` returns `false`.
- [x] `PowerPort(7_000L, "7kW").isAc()` returns `false`.
- [x] `PowerPort(7_400L, "7.4kW").isAc()` returns `false`.
- [x] `PowerPort(0L, "AC Type 2").isAc()` returns `false` (unrated port rejected).
- [x] `PowerPort(7_000L, "7kW").matchesCustomRange(3, 7)` returns `true` (numeric range preserved).
- [x] `QuickChipOption.AC` summary equals `"Cổng AC (11kW, 22kW)"`.
- [x] `WattageOption.entries` has exactly 13 options, ranging from 360kW down to 11kW.
- [x] `WattageOption.fromWatts(7_000L)` and `fromWatts(3_500L)` return `null`.

## Notes
- Single test verification only: Run `AcPortClassificationAndWattageTierTest` upon phase completion and stop for user review.

---
Next Phase: [Phase 02: Motorbike Station Exclusion & Search/Nearby Filtering](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0328-exclude-3_5kw-7kw-from-ac-filter/phase-02-motorbike-station-exclusion-and-filtering.md)
