# Phase 01: Low-Allocation Pre-Filtering & Mapping Optimization
Status: ✅ Complete
Dependencies: None

## Objective
Optimize the data processing layer in HERE Maps EV API models and client by eliminating intermediate collection allocations during AC connector availability evaluation, and computing Haversine distance on raw station coordinates to slice the top 10 stations *prior* to domain model mapping.

## Requirements
### Functional
- [x] Optimize `HereEvStation.hasAvailableAcConnector()` in `HereEvModels.kt` to inspect connectors directly across root containers and EVSE collections without invoking `allConnectors()` or `.filter { ... }`.
- [x] Add helper function to check connector availability (`availableCount > 0` and status `AVAILABLE`) without allocating temporary status lists.
- [x] In `HereEvApiClient.fetchNearbyAvailableAcStations()`:
  - Filter `qualifyingHereStations = hereResponse.allStations.filter { it.hasAvailableAcConnector() }`.
  - Calculate Haversine distance directly on raw station coordinates (`hereStation.position.latitude`, `hereStation.position.longitude`).
  - Sort raw qualifying stations ascending by distance and take up to `maxLimit` (default 10).
  - Only call `HereModelMapper.mapStationToDomain(it, dcOnly = false)` on the sliced top stations (maximum 10 stations instead of all 30-50 stations).
- [x] Ensure full data contract integrity: preserve power port breakdown, live slot counts, coordinates, and address formatting.

### Non-Functional
- [x] Memory Allocation: Reduce object allocation and GC churn by at least 70% during the mapping phase of AC discovery.
- [x] CPU Efficiency: Minimize redundant string formatting and tier aggregation on stations that are discarded outside the top 10.

## Implementation Steps
1. [x] Update `HereEvModels.kt` with allocation-free AC connector inspection.
2. [x] Update `HereEvApiClient.kt` to compute distances on raw positions and truncate candidate stations to `maxLimit` before calling `HereModelMapper.mapStationToDomain`.
3. [x] Create the single comprehensive test file:
   `app/src/test/java/com/evcs/favorites/performance/AcStationPreFilterAndMappingOptimizationTest.kt`
   Verifying:
   - Zero-allocation AC qualification accurately matches 11kW/22kW available connectors.
   - Raw coordinate distance calculation orders stations properly.
   - Slicing before mapping ensures only top N stations are converted to domain models while maintaining complete model fidelity.
4. [x] Run only this test to verify Phase 01:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.performance.AcStationPreFilterAndMappingOptimizationTest"`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt` - [MODIFY] Optimize `hasAvailableAcConnector` and connector status checks without intermediate list allocations.
- `app/src/main/java/com/evcs/favorites/data/network/here/HereEvApiClient.kt` - [MODIFY] Truncate raw qualifying stations to `maxLimit` before domain model mapping.
- `app/src/test/java/com/evcs/favorites/performance/AcStationPreFilterAndMappingOptimizationTest.kt` - [NEW] Single comprehensive unit test suite for Phase 01.

## Test Criteria
- [x] Only qualifying stations with available 11kW/22kW connectors are selected.
- [x] Distance calculation and ascending proximity sort order remain 100% accurate.
- [x] Exactly top N nearest stations are mapped to domain models.
- [x] All power port aggregations, connector counts, and IDs match original mapping logic.

---
Next Phase: [phase-02-streaming-html-resolution-and-caching.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260916-1925-ac-charging-flow-performance-optimization/phase-02-streaming-html-resolution-and-caching.md)
