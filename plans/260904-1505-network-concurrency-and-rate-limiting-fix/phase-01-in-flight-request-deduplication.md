# Phase 01: In-Flight Request Deduplication Engine & Job Coalescing
Status: ✅ Completed
Dependencies: None

## Objective
Eliminate concurrent duplicate HTTP requests (e.g. twin `POST /favorite.html`, twin `POST /search`, twin `POST /charging`) triggered within milliseconds when multiple triggers (Activity lifecycle, permission callback, map events) fire simultaneously.

## Requirements
### Functional
- Create a generic, thread-safe, coroutine-aware `SingleFlight` concurrency coalescing engine under `com.evcs.favorites.util`:
  - Signature: `suspend fun <T> execute(key: String, block: suspend () -> T): T`.
  - Backed by an independent supervisor scope (`private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`) to ensure complete detachment from caller coroutine lifecycles.
  - When multiple callers request the same key simultaneously:
    - Atomically register and launch `scope.async { block() }` inside `ConcurrentHashMap<String, Deferred<Any?>>`.
    - All concurrent callers receive the same shared result via `deferred.await() as T`.
    - Guarantee cleanup in `finally { withContext(NonCancellable) { inFlight.remove(key) } }` so future sequential requests execute freshly.
  - **Cancellation Safety**: If Caller 1 is cancelled (e.g. Activity rotation, pull-to-refresh restart), Caller 1's cancellation does NOT cancel the underlying `Deferred` running in `scope`. Caller 2 and subsequent callers awaiting the operation continue to completion successfully.
- Integrate `SingleFlight` into `EvcsRepository`:
  - Deduplicate `getFavorites(userLat, userLon, autoResolve)` with single-flight key:
    `"favorites_${userLat ?: 0.0}_${userLon ?: 0.0}_${autoResolveUnknownCoordinates}"`.
  - Deduplicate `searchNearbyVinFast(lat, lon)` with single-flight key:
    `"search_${lat}_${lon}"`.
  - Deduplicate `fetchStationForecast(station, forceRefresh)` with single-flight key:
    `"forecast_${station.id}"`.
- Update `FavoritesViewModel`:
  - Consolidate `initialLoadJob` and `refresh()` under a single managed `favoritesLoadJob: Job?`.
  - Cancel any in-flight `favoritesLoadJob` before launching a new favorites request, avoiding dual-trigger overlaps between Activity `init` and location permission callbacks.
- Update `NearbyViewModel`:
  - In `scanNearbyStations()`: If `_uiState.value.isLocating` or `_uiState.value.isSearching` is already true, safely coalesce by returning the active `scanJob` without spinning up a duplicate scan.
  - Cancel superseded `routingJob` and `forecastJob` before starting a new scan.

### Non-Functional
- Zero overhead for sequential requests.
- Thread-safe atomicity without global blocking locks.
- Full compatibility with existing unit tests and mock environments via optional injected `CoroutineDispatcher` / `CoroutineScope`.

## Implementation Steps
1. Create `SingleFlight.kt` under `com.evcs.favorites.util` with `SupervisorJob` and `NonCancellable` map cleanup.
2. Integrate `SingleFlight` instance into `EvcsRepository` for `getFavorites`, `searchNearbyVinFast`, and `fetchStationForecast`.
3. Update `FavoritesViewModel` to coalesce `initialLoadJob` and `refresh()` under `favoritesLoadJob`.
4. Update `NearbyViewModel` to guard `scanNearbyStations()` against concurrent duplicate invocations.
5. Create and run the single comprehensive test `SingleFlightRequestDeduplicationTest.kt`.

## Files to Create/Modify
- [NEW] `app/src/main/java/com/evcs/favorites/util/SingleFlight.kt` - Single-flight request deduplication engine with supervisor scope.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Deduplicate in-flight repository calls.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - Coalesce favorites loading jobs.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - Guard against concurrent duplicate scans.
- [NEW] `app/src/test/java/com/evcs/favorites/data/repository/SingleFlightRequestDeduplicationTest.kt` - Single comprehensive test for Phase 01.

## Test Criteria
- [x] Concurrent calls to `getFavorites()` with identical parameters result in exactly 1 HTTP call to `/favorite.html`.
- [x] Concurrent calls to `searchNearbyVinFast()` with identical coordinates produce exactly 1 HTTP call to `/search`.
- [x] Concurrent calls to `fetchStationForecast()` for the same station ID produce exactly 1 forecast network execution.
- [x] Sequential calls executed after completion trigger a fresh network call.
- [x] Cancellation of one caller does not cancel or corrupt the underlying shared execution for remaining callers.
- [x] Calling `scanNearbyStations()` while already searching returns existing job without triggering duplicate network searches.

## Verification Command
```bash
./gradlew test --tests "com.evcs.favorites.data.repository.SingleFlightRequestDeduplicationTest"
```

---
Next Phase: [Phase 02: Charge Token Session Caching, Forecast Pacing & Instant 429 Abort](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1505-network-concurrency-and-rate-limiting-fix/phase-02-charge-token-caching-and-rate-limit-abort.md)
