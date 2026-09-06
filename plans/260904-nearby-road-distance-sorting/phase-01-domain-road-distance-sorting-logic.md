# Phase 01: Domain Road-Distance Sorting Logic & Comparator Engine
Status: ✅ Completed
Dependencies: None

## Objective
Implement pure domain business logic for sorting charging stations by actual driving road distance (`distanceMeters`), with tie-breaker by driving duration / ETA (`durationSeconds`) and robust fallback to Haversine distance (`distanceKm * 1000.0`) when routing metrics are absent.

## Requirements

### Functional
- [x] Add `sortByDrivingDistance(stations: List<Station>): List<Station>` method to `NearbyStationFilter` object (or companion comparator engine in domain layer).
- [x] **Primary Criterion:** Sort ascending by `drivingMetrics.distanceMeters`.
- [x] **Secondary Criterion (Tie-breaker):** If `distanceMeters` is identical or missing, sort ascending by `drivingMetrics.durationSeconds`.
- [x] **Graceful Fallback:** If `drivingMetrics` is null or `distanceMeters <= 0`, convert `distanceKm * 1000.0` to effective meters and use it for comparison without crashing.
- [x] **Deterministic Stability:** Preserve relative ordering or tie-break by station `id` if all distance and duration values are equivalent.
- [x] Ensure edge cases (empty list, single item list, all metrics null, mixed metrics) execute safely without exceptions.

### Non-Functional
- [x] Pure Kotlin function with zero Android framework dependencies (runs on local JVM in milliseconds).
- [x] Strict immutability: returns a new sorted list without mutating input elements.

## Implementation Steps
1. Open `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt`.
2. Implement `sortByDrivingDistance(stations: List<Station>): List<Station>`.
   - Calculate effective distance in meters: `station.drivingMetrics?.distanceMeters?.takeIf { it > 0L } ?: ((station.distanceKm ?: Double.MAX_VALUE) * 1000.0).toLong()`.
   - Apply comparator chain: `compareBy<Station> { effectiveDistance(it) }.thenBy { it.drivingMetrics?.durationSeconds ?: Long.MAX_VALUE }.thenBy { it.id }`.
3. Create test file `app/src/test/java/com/evcs/favorites/NearbyDrivingMetricsSortTest.kt`.
4. Run only the single phase test:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.NearbyDrivingMetricsSortTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - Add road-distance sorting engine
- `app/src/test/java/com/evcs/favorites/NearbyDrivingMetricsSortTest.kt` - Verification unit test

## Test Criteria (Single File-Based Test)
`NearbyDrivingMetricsSortTest.kt` must verify:
- [x] `testSortByDrivingDistance_whenMetricsPresent_ordersByRoadDistanceAscending`: Multiple stations with driving metrics are sorted in ascending road distance order.
- [x] `testSortByDrivingDistance_inversionCase_correctsHaversineDiscrepancy`: Station with smaller Haversine distance (2.0 km) but larger road distance (5.5 km) is ranked after station with larger Haversine (2.5 km) but shorter road distance (2.8 km).
- [x] `testSortByDrivingDistance_whenRoadDistancesEqual_tieBreaksByDuration`: Stations with identical road distance are sorted by shortest ETA.
- [x] `testSortByDrivingDistance_whenMetricsNull_fallsBackToHaversineKm`: Stations without metrics correctly fallback to Haversine straight-line distance.
- [x] `testSortByDrivingDistance_mixedMetrics_handlesGracefully`: Stations with a mix of driving metrics and null metrics sort properly without crashing.
- [x] `testSortByDrivingDistance_emptyAndSingleElement_returnsAsIs`: Boundary conditions (0 or 1 items) handle smoothly.

---
Next Phase: [Phase 02: ViewModel Pipeline Re-sorting Integration & State Verification](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-nearby-road-distance-sorting/phase-02-viewmodel-pipeline-resorting-integration.md)
