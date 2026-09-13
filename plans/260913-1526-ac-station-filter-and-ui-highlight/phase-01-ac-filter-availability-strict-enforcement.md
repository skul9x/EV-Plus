# Phase 01: AC Filter Availability Strict Enforcement
Status: ✅ Completed
Dependencies: None

## Objective
Update the station filtering logic in `NearbyStationFilter.kt` so that when `SmartFilterMode.AC` (or `QuickChipOption.AC`) is selected, stations without any available AC plugs (`availablePlugs == 0`) are strictly hidden, even if `includeFullStations = true`. Stations with at least one car-compatible AC port (11kW or 22kW) having `availablePlugs > 0` are returned, including both company-owned and private / franchise stations ("Tư nhân"), as well as hybrid stations having both high-power DC and AC.

## Requirements
### Functional
- [x] In `NearbyStationFilter.filterSmartStations`, when `mode == SmartFilterMode.AC`, require `station.powers.any { power -> power.isAc() && power.availablePlugs > 0 }`.
- [x] Ignore the `includeFullStations` flag when evaluating `SmartFilterMode.AC` so that full / occupied AC stations (`availablePlugs == 0`) are completely excluded from the result list.
- [x] In `QuickChipOption.AC` handling within `CustomFilterMode.QUICK_CHIP`, enforce the same strict availability rule (`power.isAc() && power.availablePlugs > 0`).
- [x] Retain all stations satisfying the AC availability rule regardless of station name: both stations with "Tư nhân" (e.g. "VinFast - Nhượng Quyền Tư Nhân Vũ Thị Hợi") and standard stations (e.g. "VinFast - Vincom Plaza") must be included if their 11kW or 22kW port has `availablePlugs > 0`.
- [x] Exclude stations that only have DC ports (e.g. 20kW, 30kW, 60kW, 120kW) or sub-11kW ports (3.5kW, 7kW), even if they have "Tư nhân" in their name.
- [x] Maintain consistent Top 10 sorting and routing candidate selection in `NearbyViewModel`.

### Non-Functional
- [x] Performance: Zero extra heap allocations; reuse existing `PowerPort.isAc()` extension function in $O(P)$ time per station.
- [x] Regression Safety: Ensure `SmartFilterMode.NONE`, `SmartFilterMode.DC`, and custom range modes retain their respective availability behaviors without regression.

## Implementation Steps
1. [x] Inspect `NearbyStationFilter.kt` around line 151 where `SmartFilterMode.AC` is evaluated.
2. [x] Modify the `SmartFilterMode.AC` filter branch to require `power.isAc() && power.availablePlugs > 0`.
3. [x] Modify the `QuickChipOption.AC` branch in `CustomFilterMode.QUICK_CHIP` to also require `power.isAc() && power.availablePlugs > 0`.
4. [x] Create the single comprehensive unit test file `app/src/test/java/com/evcs/favorites/domain/filter/AcFilterAvailabilityStrictEnforcementTest.kt` verifying all functional and edge-case requirements.
5. [x] Execute the single test via Gradle to confirm verification passes.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - [MODIFY] Enforce strict `availablePlugs > 0` on AC filter branches.
- `app/src/test/java/com/evcs/favorites/domain/filter/AcFilterAvailabilityStrictEnforcementTest.kt` - [NEW] Single comprehensive test covering AC availability and mixed/private station retention.

## Test Criteria
- [x] Station with 11kW (1/1 available) is included.
- [x] Station with 11kW (0/2 available) is excluded under `SmartFilterMode.AC`.
- [x] Private station ("Tư nhân") with 11kW (1/1 available) and 20kW DC is included.
- [x] Private station ("Tư nhân") with only 20kW DC and 3.5kW is excluded under `SmartFilterMode.AC`.
- [x] Hybrid station with 120kW DC (3/4 available) and 11kW AC (1/2 available) is included.
- [x] Hybrid station with 120kW DC (3/4 available) and 11kW AC (0/2 available) is excluded under `SmartFilterMode.AC`.
- [x] Non-AC modes (`NONE`, `DC`) continue to respect `includeFullStations = true`.

---
Next Phase: [phase-02-station-card-ac-chip-highlight.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-1526-ac-station-filter-and-ui-highlight/phase-02-station-card-ac-chip-highlight.md)
