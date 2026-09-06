# Phase 06: Filter Chip Routing Debounce & Quota Protection
Status: ✅ Completed  
Dependencies: `phase-05-location-jitter-and-async-compute.md`  
Issue IDs: `PERF-ROUT-01`

## Objective
Prevent burst requests to Google Routes Matrix API and OSRM when users rapidly tap or toggle filter chips in the Nearby screen:
1. Instantly update UI selection states and apply local in-memory station filtering and Top-10 display.
2. Debounce the remote driving route calculation (`MultiTierRoutingCoordinator`) by 300ms using a cancellable debounce job, collapsing rapid consecutive taps into a single route request.

## Requirements
### Functional
- When a user taps a filter chip, the chip highlights/unhighlights immediately without lag.
- The filtered station list updates immediately to reflect matching stations.
- If the user taps multiple chips in rapid succession (< 300ms between taps), only one batch routing API request is dispatched for the final selected filter state.
- If the user stops tapping, route calculations start 300ms after the last interaction.

### Non-Functional
- Prevent Google Routes Matrix API quota waste and unneeded cellular data transmission during rapid filtering.

## Implementation Steps
1. [x] In `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Introduce `private var routingDebounceJob: Job? = null` and a configurable `private val routingDebounceMs: Long = 300L` constructor parameter with default value `300L`.
   - Restructure `executeFilterAndRoutingPipeline`:
     - **Step 1 (Immediate UI & Local Computation)**:
       - Run local filtering (`filterSmartStations` / `filterStations`) and Top 10 extraction (`extractTopNearest`).
       - If `top10` is empty: update `_uiState` with empty list, clear `routingMetrics`, set `isRoutingLoading = false`, cancel `routingDebounceJob`, and return immediately.
       - If `top10` is non-empty: update `_uiState` immediately with the new `top10DisplayStations` (showing initial Haversine distances) and set `isRoutingLoading = true`.
     - **Step 2 (Debounced Remote Route Calculation)**:
       - Cancel any pending `routingDebounceJob`.
       - If `debounce == false` or `routingDebounceMs <= 0L`, execute `routingCoordinator.calculateRoutes(...)` immediately.
       - Otherwise, launch `routingDebounceJob = viewModelScope.launch(dispatcher)`:
         ```kotlin
         delay(routingDebounceMs)
         val metrics = withContext(ioDispatcher) {
             routingCoordinator.calculateRoutes(
                 originLat = userLat,
                 originLng = userLon,
                 destinations = destinations,
                 settings = prefsManager.settings.value
             )
         }
         val routedTop10 = top10.map { station ->
             val m = metrics[station.id]
             if (m != null) station.copy(drivingMetrics = m) else station
         }
         val sorted = withContext(defaultDispatcher) {
             NearbyStationFilter.sortByDrivingDistance(routedTop10)
         }
         _uiState.update {
             it.copy(
                 top10DisplayStations = sorted,
                 routingMetrics = metrics,
                 isRoutingLoading = false
             )
         }
         ```
   - In `scanNearbyStations()`, pass `debounce = false` to guarantee immediate routing upon manual pull-to-refresh or fresh scan.
2. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/ui/viewmodel/FilterRoutingDebounceTest.kt`:
   - Simulates rapid consecutive filter chip toggles within 100ms in `NearbyViewModel`.
   - Verifies UI state (selected chip and station list) updates immediately on every tap.
   - Verifies the mock routing coordinator receives exactly 1 `calculateRoutes` call after the 300ms debounce delay expires.
   - Verifies manual refresh triggers immediate route calculation without debounce delay.
3. [x] Run single test:
   ```bash
   ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.FilterRoutingDebounceTest
   ```

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Add 300ms debounce to routing coordinator network dispatch on filter toggles
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/FilterRoutingDebounceTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria
- [x] UI reflects filter selection changes immediately without input lag.
- [x] Rapid consecutive filter toggles result in only 1 routing network dispatch.
- [x] Exactly one test file is executed and passes cleanly.

---
Next Phase: `phase-07-apk-bloat-and-baseline-profiles.md`
