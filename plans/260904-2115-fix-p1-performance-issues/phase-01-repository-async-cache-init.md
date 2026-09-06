# Phase 01: Repository Non-Blocking Async Cache Initialization

Status: ✅ Completed
Issue ID: PERF-ANR-02
Dependencies: Phase 00 (P0 Fixes)

## Objective
Eliminate 50ms–180ms Main Thread disk I/O and AES-GCM JSON decoding bottlenecks during cold start by removing synchronous cache restoration from `EvcsRepository.init`, replacing `mutableMapOf()` with thread-safe `ConcurrentHashMap` for `coordinateCache`, and introducing non-blocking asynchronous cache initialization on `Dispatchers.IO`.

---

## Requirements

### Functional
- [x] Remove synchronous calls to `loadCachedCoordinates()` and `getCachedFavorites()` from the `init` block of `EvcsRepository`.
- [x] Replace `coordinateCache: MutableMap<String, Pair<Double, Double>> = mutableMapOf()` with `ConcurrentHashMap<String, Pair<Double, Double>>` to guarantee thread safety between `Dispatchers.IO` background loading and UI/ViewModel reads.
- [x] Implement `suspend fun initializeAsync(dispatcher: CoroutineDispatcher = Dispatchers.IO)` in `EvcsRepository` to populate `coordinateCache`, `_favoritesState`, and `_favoriteIdsState` off the Main Thread.
- [x] Provide helper `fun initialize(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO): Job` for non-suspending callers.
- [x] Expose `isInitialized: StateFlow<Boolean>` in `EvcsRepository` to allow UI/ViewModels to reactively track initialization completion.
- [x] Ensure backward compatibility for existing unit tests by providing an optional constructor flag `eagerLoadCache: Boolean = false` (or executing `loadCachedCoordinates()` when explicitly invoked).

### Non-Functional
- [x] Startup latency: 0ms disk read or AES decryption on `Dispatchers.Main` during `EvcsRepository` construction.
- [x] Thread safety: Zero `ConcurrentModificationException` when `coordinateCache` is accessed concurrently from background workers and Main Thread.

---

## Implementation Steps
1. **Refactor `EvcsRepository.kt`**:
   - Convert `coordinateCache` to `ConcurrentHashMap`.
   - Remove blocking disk reads and JSON decoding from `init`.
   - Implement `suspend fun initializeAsync(dispatcher: CoroutineDispatcher = Dispatchers.IO)` and `isInitialized: StateFlow<Boolean>`.
   - Support `eagerLoadCache: Boolean = false` constructor parameter for backward-compatible unit tests.
2. **Update `MainActivity.kt` & ViewModel Startup**:
   - Trigger `repository.initializeAsync()` within `MainActivity.onCreate`'s background IO coroutine alongside `warmUp()`.
   - Ensure `FavoritesViewModel` observes `repository.favoritesState` and reacts seamlessly when async cache loading completes.
3. **Verify Compatibility**:
   - Ensure all repository cache access functions (`getCachedFavorites()`, `loadCachedCoordinates()`, `saveCachedCoordinates()`) maintain consistent behavior.

---

## Files to Modify/Create
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Remove disk I/O from `init`, switch to `ConcurrentHashMap`, add `initializeAsync()` and `isInitialized`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Dispatch `repository.initializeAsync()` on IO during cold start.
- [NEW] `app/src/test/java/com/evcs/favorites/RepositoryAsyncInitPerformanceTest.kt` - Exactly one comprehensive test for Phase 01.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/RepositoryAsyncInitPerformanceTest.kt`
- **Core Verifications**:
  1. Instantiating `EvcsRepository` with `eagerLoadCache = false` performs 0ms synchronous disk reads and leaves main thread unblocked.
  2. Calling `initializeAsync` restores coordinates and favorites accurately from storage onto `Dispatchers.IO`.
  3. Reactive StateFlows (`favoritesState`, `favoriteIdsState`, `isInitialized`) reflect loaded cache items upon completion.
  4. Concurrent multi-threaded reads/writes to `coordinateCache` succeed without `ConcurrentModificationException`.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.RepositoryAsyncInitPerformanceTest
```
