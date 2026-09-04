# Phase 05: GPS Location Jitter Filtering & Async Compute Dispatching
Status: 🟢 Complete  
Dependencies: `phase-04-bounded-lru-memory-cache.md`  
Issue IDs: `PERF-LOC-01`, `PERF-ASYNC-02`

## Objective
Conserve battery, eliminate redundant routing recalculations on stationary GPS drift, and keep the Main thread smooth:
1. **PERF-LOC-01**: In `FavoritesViewModel.kt:updateUserLocation`, add a GPS jitter threshold (20 meters). If user displacement <= 20m, return immediately without launching background jobs. If displacement <= 200m and cache is valid, skip recalculating routes.
2. **PERF-ASYNC-02**: In `FavoritesViewModel.kt` and `NearbyViewModel.kt`, offload heavy CPU-bound distance sorting (`DistanceCalculator.sortByDistance`), filtering (`NearbyStationFilter.filterSmartStations`), and top-N extraction to `Dispatchers.Default` (via an injectable `defaultDispatcher: CoroutineDispatcher = Dispatchers.Default`).

## Requirements
### Functional
- When GPS updates with displacement > 200m, the list is re-sorted and routes are fetched anew.
- When GPS updates with displacement > 20m and <= 200m with expired cache, routes are re-fetched.
- When GPS updates with displacement <= 20m (GPS jitter / drift while standing still), redundant work is skipped entirely.
- All station list sorting and smart filtering outputs remain identical in order and content.

### Non-Functional
- Main thread never executes heavy sorting or list filtering loops for large station collections (> 100 stations).
- Unnecessary coroutines are not dispatched for minor stationary GPS drift.

## Implementation Steps
1. [x] In `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`:
   - Introduce `const val MIN_DISPLACEMENT_METERS = 20.0`.
   - Track `private var lastProcessedCoordinates: Pair<Double, Double>? = null` (only updated when displacement > 20m).
   - Add `defaultDispatcher: CoroutineDispatcher = Dispatchers.Default` parameter to ViewModel constructor and `provideFactory`.
   - In `updateUserLocation(latitude, longitude)`:
     - If `lastProcessedCoordinates != null`:
       - Calculate `val displacement = DistanceCalculator.calculateDistanceMeters(lastProcessedCoordinates!!.first, lastProcessedCoordinates!!.second, latitude, longitude)`.
       - If `displacement <= MIN_DISPLACEMENT_METERS`, complete and return immediately (no coroutine launched, no state mutation).
       - If `displacement <= MAX_DISPLACEMENT_METERS` and `isCacheValid(latitude, longitude)`, update coordinates and return immediately without running `executeRoutingPipeline`.
     - Update `lastProcessedCoordinates = Pair(latitude, longitude)` and `currentCoordinates = Pair(latitude, longitude)`.
     - When sorting candidates (e.g. `DistanceCalculator.sortByDistance`), offload computation via `withContext(defaultDispatcher) { ... }`.
2. [x] In `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Add `defaultDispatcher: CoroutineDispatcher = Dispatchers.Default` parameter to ViewModel constructor and `provideFactory`.
   - In `executeFilterAndRoutingPipeline`:
     - Wrap heavy CPU-bound operations:
       ```kotlin
       val top10 = withContext(defaultDispatcher) {
           val f = if (currentMode != SmartFilterMode.NONE) {
               NearbyStationFilter.filterSmartStations(...)
           } else if (selectedWattages.isNotEmpty()) {
               NearbyStationFilter.filterStations(...)
           } else {
               NearbyStationFilter.filterSmartStations(...)
           }
           NearbyStationFilter.extractTopNearest(userLat, userLon, f, limit = 10)
       }
       ```
     - And wrap post-routing sorting:
       ```kotlin
       val sortedRoutedTop10 = withContext(defaultDispatcher) {
           val routed = top10.map { station ->
               val m = metrics[station.id]
               if (m != null) station.copy(drivingMetrics = m) else station
           }
           NearbyStationFilter.sortByDrivingDistance(routed)
       }
       ```
3. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/ui/viewmodel/LocationJitterAndAsyncComputeTest.kt`:
   - Verifies stationary GPS drift <= 20m early-returns with completed no-op job and does not alter UI state or reference position.
   - Verifies displacement > 200m invalidates routing cache and re-sorts stations on `defaultDispatcher`.
   - Verifies station sorting and smart filtering correctly computes on `defaultDispatcher` without blocking Main.
4. [x] Run single test:
   ```bash
   ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.LocationJitterAndAsyncComputeTest
   ```

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Add lastProcessedCoordinates jitter filtering and defaultDispatcher offloading
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Offload filtering and sorting to defaultDispatcher
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/LocationJitterAndAsyncComputeTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria
- [x] Minor GPS drift <= 20m early-returns with no-op job.
- [x] Stationary sub-threshold movements do not drift the reference position.
- [x] Major displacement triggers expected refresh and cache invalidation.
- [x] List filtering and sorting produces identical results on `defaultDispatcher`.
- [x] Exactly one test file is executed and passes cleanly.

---
Next Phase: `phase-06-filter-routing-debounce.md`
