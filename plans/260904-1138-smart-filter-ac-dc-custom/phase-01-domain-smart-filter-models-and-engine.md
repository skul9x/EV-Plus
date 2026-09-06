# Phase 01: Domain Smart Filter Models & Engine
Status: ✅ Completed
Dependencies: None

## Objective
Define the domain representation of smart filters (`SmartFilterMode`, `DcWattageTier`, `CustomFilterConfig`, `QuickChipOption`) and update `NearbyStationFilter` to execute AC filtering, DC tiered power filtering, and custom range filtering with strict availability enforcement on matching connectors.

## Requirements
### Functional
- [x] Define `SmartFilterMode`: `NONE`, `AC`, `DC`, `CUSTOM`.
- [x] Define `DcWattageTier`:
  - `LE_30KW` ("≤ 30kW", minWatts = 0L, maxWatts = 30_000L)
  - `BETWEEN_30_60KW` ("30 - 60kW", minWatts = 30_000L, maxWatts = 60_000L)
  - `GE_60KW` ("≥ 60kW", minWatts = 60_000L, maxWatts = Long.MAX_VALUE)
  - `GE_120KW` ("≥ 120kW", minWatts = 120_000L, maxWatts = Long.MAX_VALUE)
- [x] Define `CustomFilterConfig`:
  - Mode: `QUICK_CHIP` or `CUSTOM_RANGE`.
  - `quickChip`: `ALL`, `AC`, `DC_LE_30KW`, `DC_BETWEEN_30_60KW`, `DC_GE_60KW`, `DC_GE_120KW`.
  - `minKw`: nullable positive integer (`1`..`500`).
  - `maxKw`: nullable positive integer (`1`..`500`).
  - Validation: `isValid()` ensuring positive values and `minKw <= maxKw` when both are provided.
  - Helper: `toDisplaySummary(): String` generating localized summary (e.g. "Cổng từ 60 kW đến 150 kW", "Cổng công suất ≥ 60 kW", "Tất cả các trạm có cổng trống") for live UI preview and info pills.
- [x] Implement AC power classification:
  - Ports with `typeWatts` in `[3_500L, 7_000L, 7_400L, 11_000L, 22_000L]` or labels indicating AC are classified as AC.
  - Ports with `typeWatts` >= 20_000L (excluding 22kW AC) or explicitly labeled DC are classified as DC.
- [x] Extend `NearbyStationFilter.filterStations`:
  - **AC Mode**: Station must have at least one AC connector with `availablePlugs > 0`.
  - **DC Mode**: Station must have at least one DC connector matching the active `DcWattageTier` with `availablePlugs > 0`.
  - **Custom Mode**: Evaluates either the matching quick chip or manual `[minKw..maxKw]` range with `availablePlugs > 0`.
  - **Mixed Station Exclusion Rule**: If a station has both AC and DC, availability is evaluated strictly on the connectors matching the filter. If matching connectors have 0 vacant plugs, the station is filtered out even if non-matching connectors are free.
  - Maintains existing depot maintenance exclusion (`depotStatus` != "Maintaining" / "OutOfService").

### Non-Functional
- [x] Pure Kotlin domain logic with zero Android framework dependencies.
- [x] O(N * M) performance where N = stations, M = connectors per station (typically <= 6).

## Implementation Steps
1. [x] Create `SmartFilterModels.kt` in `com.evcs.favorites.domain.model`.
2. [x] Update `NearbyStationFilter.kt` in `com.evcs.favorites.domain.filter` with `filterSmartStations(...)`.
3. [x] Create single comprehensive unit test file `NearbyStationSmartFilterTest.kt` verifying:
   - AC filter includes stations with free AC plugs and excludes stations with occupied/missing AC plugs.
   - DC tier filter includes stations matching specific tiers (`≤ 30kW`, `30 - 60kW`, `≥ 60kW`, `≥ 120kW`).
   - Mixed station rule: Station with 0/2 on 60kW DC and 2/2 on 11kW AC is excluded when filtering DC >= 60kW.
   - Custom filter with Quick Chips and Min/Max ranges (Min only, Max only, both Min and Max).
   - CustomFilterConfig.toDisplaySummary() formats expected strings for all quick chips and custom ranges.
   - Maintenance and out-of-service stations are excluded across all modes.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/model/SmartFilterModels.kt` - [NEW] Domain filter models.
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - [MODIFY] Smart filtering logic.
- `app/src/test/java/com/evcs/favorites/domain/filter/NearbyStationSmartFilterTest.kt` - [NEW] Single comprehensive test file.

## Test Criteria
- [x] Run only `./gradlew testDebugUnitTest --tests com.evcs.favorites.domain.filter.NearbyStationSmartFilterTest`
- [x] All test cases pass with zero failures.

---
Next Phase: [Phase 02: Persistence & ViewModel Filter Pipeline](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1138-smart-filter-ac-dc-custom/phase-02-persistence-and-viewmodel-filter-pipeline.md)
