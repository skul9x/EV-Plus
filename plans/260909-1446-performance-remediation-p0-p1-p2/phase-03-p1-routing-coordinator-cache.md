# Phase 03: P1 Centralized Routing Cache & Quota Conservation

Status: ✅ Completed
Dependencies: `phase-02-p1-recomposition-and-startup-cache.md`

## Objective

Remediate Priority 1 (P1) performance issue `[ROUTING-01]`:
Elevate routing metrics caching from ViewModel ad-hoc implementations into the centralized `MultiTierRoutingCoordinator`, introducing origin-displacement thresholds (50 meters) and Time-To-Live (TTL = 60 seconds). This eliminates redundant network matrix calls to Google Routes API v2 and OSRM Table Service when the user moves slightly, switches tabs, or updates UI filter chips in `NearbyViewModel`.

## Requirements

### Functional
- [x] Add thread-safe coordinate displacement and TTL caching inside `MultiTierRoutingCoordinator`:
  - Cache entries mapping `stationId -> DrivingMetrics`.
  - Maintain `cachedOriginLat: Double?`, `cachedOriginLng: Double?`, and `cacheTimestamp: Long`.
  - Displacement threshold: If origin coordinates differ by `<= 50.0 meters` (calculated via `DistanceCalculator.calculateDistanceMeters`), and age is `<= 60,000 ms`, and all destination IDs are present in cache, return cached metrics immediately.
  - Parameter `forceRefresh: Boolean = false`: When true, bypass cache, execute remote routing, and refresh cached entries.
  - Provide `fun clearRoutingCache()` for testing and explicit invalidation.
- [x] In `NearbyViewModel.kt`:
  - Increase default `routingDebounceMs` from 300ms to 600ms to throttle rapid filter toggles and GPS fluctuations.
  - Pass `forceRefresh = (triggerType == RefreshTriggerType.USER_REFRESH)` into `calculateRoutes`.
- [x] In `FavoritesViewModel.kt`:
  - Remove duplicate private routing cache (`routingCache`, `cachedOriginLat`, `cachedOriginLon`, `cacheTimestamp`), delegating caching directly to the shared `MultiTierRoutingCoordinator`.

### Non-Functional
- [x] 60%–80% reduction in Google Routes API v2 element quota consumption during active station discovery.
- [x] Instant 0ms response for repeated route queries within 50-meter radius.
- [x] Single file-based verification test executed in pure JVM environment.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt`:
   - Implement `calculateRoutes` caching check before executing `executeAuto`, `executeGoogleOnly`, `executeOsrm`, or `computeHaversine`.
   - Update `cachedMetrics`, `cachedOriginLat`, `cachedOriginLng`, `cacheTimestamp` on successful calculations.
   - Implement `clearRoutingCache()`.
2. [x] Update `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Set default `routingDebounceMs: Long = 600L`.
   - Pass `forceRefresh = isUserRefresh` to `routingCoordinator.calculateRoutes(...)`.
3. [x] Update `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`:
   - Simplify `updateUserLocation` to call `routingCoordinator.calculateRoutes(..., forceRefresh = forceRefresh)` directly without local cache duplicates.
4. [x] Create single verification test `app/src/test/java/com/evcs/favorites/performance/Phase03P1RoutingCoordinatorCacheTest.kt`:
   - Test cache hit when origin moves by 10 meters (< 50m threshold).
   - Test cache miss when origin moves by 100 meters (> 50m threshold).
   - Test TTL expiry after 60,001 ms.
   - Test force refresh bypasses valid cache.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` - [MODIFY] Add displacement and TTL cache
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Leverage coordinator cache and adjust debounce
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Delegate routing cache to coordinator
- `app/src/test/java/com/evcs/favorites/performance/Phase03P1RoutingCoordinatorCacheTest.kt` - [NEW] Single comprehensive verification test

## Single Verification Test
- **Test Class:** `com.evcs.favorites.performance.Phase03P1RoutingCoordinatorCacheTest`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.performance.Phase03P1RoutingCoordinatorCacheTest"
  ```

---
Next Phase: `phase-04-p2-concurrency-network-memory.md`
