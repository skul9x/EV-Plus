# Phase 02: ViewModel Pipeline Re-sorting Integration & State Verification
Status: ✅ Completed
Dependencies: [Phase 01: Domain Road-Distance Sorting Logic & Comparator Engine](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-nearby-road-distance-sorting/phase-01-domain-road-distance-sorting-logic.md)

## Objective
Integrate the road-distance sorting engine into `NearbyViewModel.executeFilterAndRoutingPipeline`. When `MultiTierRoutingCoordinator.calculateRoutes` returns road distance and duration metrics on the IO dispatcher, the Top 10 candidate stations must be dynamically re-sorted into ascending road distance order before updating `NearbyUiState.top10DisplayStations`.

## Requirements

### Functional
- [x] In `NearbyViewModel.kt`, after merging `metrics` into `top10` stations:
  ```kotlin
  val routedTop10 = top10.map { station ->
      val m = metrics[station.id]
      if (m != null) station.copy(drivingMetrics = m) else station
  }
  val sortedRoutedTop10 = NearbyStationFilter.sortByDrivingDistance(routedTop10)
  ```
- [x] Update `_uiState` with `top10DisplayStations = sortedRoutedTop10`.
- [x] Preserve the initial 0ms Haversine rendering during initial search while routing calculation is in flight (`isRoutingLoading = true`).
- [x] Ensure all trigger flows correctly execute road-distance re-sorting:
  1. `scanNearbyStations()` (GPS acquisition -> raw search -> Haversine top 10 -> route re-sort)
  2. `refresh()` (re-scan -> route re-sort)
  3. `toggleWattageFilter()` (chip toggling -> fresh top 10 -> route re-sort)
  4. `clearWattageFilters()` (filter reset -> fresh top 10 -> route re-sort)
- [x] Ensure failure or timeout in `MultiTierRoutingCoordinator` (fallback to Haversine) does not discard stations or crash UI.

### Non-Functional
- [x] No regression in routing API quota or performance: still strictly queries <= 10 destinations.
- [x] Re-sorting takes < 1 ms for 10 items on the main thread / dispatcher.

## Implementation Steps
1. Open `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`.
2. In `executeFilterAndRoutingPipeline`:
   - Replace direct assignment of unsorted `routedTop10` with `NearbyStationFilter.sortByDrivingDistance(routedTop10)`.
3. Create test file `app/src/test/java/com/evcs/favorites/NearbyRoadDistanceRoutingIntegrationTest.kt`.
4. Run only the single phase test:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.NearbyRoadDistanceRoutingIntegrationTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - Apply road-distance re-sorting in pipeline
- `app/src/test/java/com/evcs/favorites/NearbyRoadDistanceRoutingIntegrationTest.kt` - Verification integration test

## Test Criteria (Single File-Based Test)
`NearbyRoadDistanceRoutingIntegrationTest.kt` must verify:
- [x] `testScanNearbyStations_initiallyShowsHaversine_thenResortsByRoadDistance`: Initial UI state before routing completion emits Haversine order; upon route completion, `top10DisplayStations` is dynamically reordered strictly by ascending road distance.
- [x] `testInversionResolution_stationWithShorterRoadDistanceBecomesFirst`: Station with 2.5 km Haversine / 2.8 km road distance jumps ahead of station with 2.0 km Haversine / 5.5 km road distance upon route arrival.
- [x] `testWattageFilterToggle_recomputesAndSortsByRoadDistance`: Toggling wattage filters triggers fresh pipeline and applies road-distance re-sorting on the filtered Top 10 subset.
- [x] `testRefresh_preservesOrUpdatesRoadDistanceSorting`: Calling `refresh()` maintains proper road-distance sorting with updated coordinates.
- [x] `testRoutingCoordinatorFailure_retainsHaversineFallbackGracefully`: When routing coordinator encounters an error and falls back to Haversine metrics, the station list remains valid and sorted without crashes.

---
Next Phase: None (Plan Complete)
