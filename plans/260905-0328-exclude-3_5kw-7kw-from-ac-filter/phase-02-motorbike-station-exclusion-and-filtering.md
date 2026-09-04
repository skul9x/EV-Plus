# Phase 02: Motorbike Station Exclusion & Search/Nearby Filtering
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Implement station-level filtering rules in `NearbyStationFilter.kt` to hide pure motorbike/low-power charging stations (stations having only 3.5kW / 7kW / 7.4kW ports and zero car-compatible ports) from Nearby and Search lists, while preserving mixed stations (e.g. DC + 7kW) and honoring custom numerical ranges (e.g. Min 3kW to Max 7kW) in Settings.

## Requirements
### Functional
- [x] Add `Station.hasCarCompatiblePorts(): Boolean` extension function:
  - Returns `true` if any port satisfies `it.isDc()`, `it.isAc()`, or `it.typeWatts >= 11_000L`.
  - If `powers` is empty, fallback to connector string parsing via `EvcsRepository.parseConnectorsToPowers(connectors)`.
  - Returns `false` if the station only contains low-power motorbike ports (3.5kW, 7kW, 7.4kW) and no DC or AC >= 11kW ports.
- [x] Update `NearbyStationFilter.filterStations()`:
  - In default mode (`SmartFilterMode.NONE`), AC mode (`SmartFilterMode.AC`), DC mode (`SmartFilterMode.DC`), and QuickChip mode (`CustomFilterMode.QUICK_CHIP`):
    - Exclude stations where `!station.hasCarCompatiblePorts()`.
  - Under `CustomFilterMode.CUSTOM_RANGE`:
    - Do not exclude motorbike stations globally if the user explicitly queried a range matching those ports (e.g. `minKw = 3, maxKw = 7`).
  - In `SmartFilterMode.AC`:
    - Ensure stations with only 3.5kW / 7kW ports are excluded even if those ports are free.
    - Mixed stations (e.g. 60kW DC + 7kW AC) are NOT matched by AC filter unless they have an active 11kW or 22kW AC port.

### Non-Functional
- [x] Clean separation of concerns in domain filter logic.
- [x] High-performance list filtering without unnecessary allocations.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt`:
   - Implement `Station.hasCarCompatiblePorts()`.
   - Update filtering pipeline to filter out non-car-compatible stations for standard modes while allowing explicit custom range matches.
2. [x] Implement single comprehensive test: `app/src/test/java/com/evcs/favorites/domain/filter/MotorbikeStationExclusionAndFilteringTest.kt`.
3. [x] Run verification test: `./gradlew test --tests "com.evcs.favorites.domain.filter.MotorbikeStationExclusionAndFilteringTest"`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - Motorbike station exclusion and filtering logic
- `app/src/test/java/com/evcs/favorites/domain/filter/MotorbikeStationExclusionAndFilteringTest.kt` - Single comprehensive test for Phase 02

## Test Criteria
- [x] Pure 7kW station (only 7kW ports) is filtered out in `SmartFilterMode.NONE`.
- [x] Pure 3.5kW station (only 3.5kW ports) is filtered out in `SmartFilterMode.NONE`.
- [x] Pure 7kW station is filtered out in `SmartFilterMode.AC`.
- [x] Mixed station (60kW DC + 7kW AC) is retained in `SmartFilterMode.NONE`.
- [x] Mixed station (60kW DC + 7kW AC) is filtered out in `SmartFilterMode.AC` (no 11kW/22kW port).
- [x] Station with 11kW AC port is retained in `SmartFilterMode.AC`.
- [x] In `CustomFilterMode.CUSTOM_RANGE` with `minKw = 3, maxKw = 7`, pure 7kW station is successfully matched and returned.

## Notes
- Single test verification only: Run `MotorbikeStationExclusionAndFilteringTest` upon phase completion and stop for user review.

---
Next Phase: [Phase 03: UI Strings, Settings Live Preview & Regression Alignment](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0328-exclude-3_5kw-7kw-from-ac-filter/phase-03-ui-strings-settings-and-regression-alignment.md)
