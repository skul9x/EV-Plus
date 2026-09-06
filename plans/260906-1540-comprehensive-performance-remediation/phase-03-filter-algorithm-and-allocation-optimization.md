# Phase 03: Filter Algorithm & Allocation Optimization (PERF-003, PERF-008, PERF-012)
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Optimize the hot path filtering and sorting pipeline by replacing full-list $O(N \log N)$ sorting and 1000-object allocations in `extractTopNearest` with a bounded PriorityQueue Min-Heap/Max-Heap $O(N \log 10)$ algorithm, memoizing connector string parsing in `hasCarCompatiblePorts()`, eliminating `LinkedHashMap` defensive cloning in `BoundedLruMap`, and dispatching CPU-heavy filtering and distance calculations to `Dispatchers.Default`.

## Requirements
### Functional
- [x] `DistanceCalculator.extractTopNearest` (or `NearbyStationFilter.extractTopNearest`) finds the top $k$ nearest stations (default $k = 10$) using a bounded heap in $O(N \log k)$ time and $O(k)$ extra space.
- [x] Avoid calling `station.copy(distanceKm = distance)` on all 500–1000 input stations; only calculate distance primitives during candidate evaluation and only instantiate copies for the $k$ selected stations.
- [x] `hasCarCompatiblePorts()` caches or checks pre-parsed powers first, avoiding repetitive string splitting and Regex matching when `powers` is populated or previously evaluated.
- [x] `BoundedLruMap` provides `forEachThreadSafe` and `mapValuesThreadSafe` (or snapshot without extra intermediate map creation), eliminating unnecessary 500-element `LinkedHashMap` allocation during `saveCachedCoordinates`.
- [x] In `NearbyViewModel` and `FavoritesViewModel`, route pure CPU workloads (Haversine calculations, list filtering, sorting) to `Dispatchers.Default` (configurable for testing) instead of running on the I/O thread pool.

### Non-Functional
- [x] Big-O Time Complexity: Reduced from $O(N \log N)$ to $O(N \log 10)$.
- [x] Memory Allocation: Reduce short-lived heap allocations by >80% on filter chips toggle.
- [x] Concurrency: Prevent IO thread starvation caused by CPU-bound mathematical operations.

## Implementation Steps
1. [x] Update `NearbyStationFilter.kt`:
   - Refactor `extractTopNearest` to use a bounded `PriorityQueue` (Max-Heap) of size `limit`.
   - Iterate stations, compute primitive distance `calculateDistanceKm`, and only keep the top `limit` minimum distances.
   - Only call `.copy(distanceKm = dist)` for the final $k$ elements extracted from the heap.
   - In `hasCarCompatiblePorts()`, fast-path check `powers` or memoize parsing result on the station model.
2. [x] Update `BoundedLruMap.kt`:
   - Implement `forEachThreadSafe(action: (K, V) -> Unit)` with internal synchronization.
   - Implement `mapValuesThreadSafe<R>(transform: (Map.Entry<K, V>) -> R): Map<K, R>`.
   - Update `EvcsRepository.saveCachedCoordinates` to use `coordinateCache.mapValuesThreadSafe { CoordinatePair(it.value.first, it.value.second) }`.
3. [x] Update `NearbyViewModel.kt` and `FavoritesViewModel.kt`:
   - Ensure `defaultDispatcher` defaults to `Dispatchers.Default` (while preserving constructor parameter for test injection).
   - Ensure filter pipelines and Haversine sorting invoke `withContext(defaultDispatcher)`.
4. [x] Create verification test `FilterAlgorithmAndAllocationOptimizationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - Bounded Heap Top-K & port parsing optimization.
- `app/src/main/java/com/evcs/favorites/data/cache/BoundedLruMap.kt` - Zero-copy thread-safe iteration/mapping.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Use `mapValuesThreadSafe` for cache saving.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - Dispatcher routing for CPU work.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - Dispatcher routing for CPU work.
- `app/src/test/java/com/evcs/favorites/performance/FilterAlgorithmAndAllocationOptimizationTest.kt` - Dedicated verification test.

## Test Criteria (Single Comprehensive Test)
- `FilterAlgorithmAndAllocationOptimizationTest.kt` must verify:
  1. Top-K correctness: `extractTopNearest` with bounded heap produces identical sorted ordering and distance values as classic full-sort across various dataset sizes (0, 5, 10, 100, 1000 stations).
  2. Heap efficiency: Input list of 1000 stations only results in at most `limit` (10) instances of `Station.copy` being generated.
  3. `BoundedLruMap.mapValuesThreadSafe` produces exact transformed map results under concurrent modifications without throwing `ConcurrentModificationException` and without allocating intermediate `LinkedHashMap` copies.
  4. `NearbyViewModel` and `FavoritesViewModel` execute filter pipelines on the provided `defaultDispatcher`.

---
Next Phase: [phase-04-network-routing-and-coalescing-resilience.md](file:///d:/skul9x/EV-Plus-main/plans/260906-1540-comprehensive-performance-remediation/phase-04-network-routing-and-coalescing-resilience.md)
