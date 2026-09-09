# Phase 04: P2 Concurrency, Network Disk Cache & Memory Resilience

Status: ✅ Completed
Dependencies: `phase-03-p1-routing-coordinator-cache.md`

## Objective

Remediate Priority 2 (P2) performance issues across coroutine concurrency, network disk cache initialization, static memory growth, and build configuration:
1. **[COROUTINE-01]:** Fix lazy deferred coroutine leak in `SingleFlight.execute` by explicitly cancelling abandoned deferred jobs when a collision occurs in `inFlight.putIfAbsent`.
2. **[NETWORK-01]:** Resolve the race condition in `AppOkHttpClientProvider` by ensuring the HTTP disk cache is either initialized synchronously or safely bound before the first HTTP client builder resolves.
3. **[MEMORY-01]:** Bound static in-memory caches (`EvcsRepository.parsedConnectorsCache`, `NearbyStationFilter.connectorCompatibilityCache`, `SessionManager.COOKIE_REGEX_CACHE`) using bounded LRU caches (maximum 256 entries) to prevent monotonic memory expansion.
4. **[BUILD-01]:** Purge stale, non-existent class references from `app/src/main/baseline-prof.txt` and fix incorrect package qualifiers for ViewModels/UI states.

## Requirements

### Functional
- [x] In `SingleFlight.kt`:
  - When `inFlight.putIfAbsent(key, newDeferred)` returns an existing job, call `newDeferred.cancel()` to unregister it from the parent `SupervisorJob`.
- [x] In `AppOkHttpClientProvider.kt`:
  - Ensure `getSharedClient()` initializes or retrieves the disk cache in a thread-safe manner, eliminating the race where early clients are instantiated with a null cache.
- [x] Create `BoundedLruCache.kt`:
  - A thread-safe, pure Kotlin LRU cache utility based on `LinkedHashMap(accessOrder = true)` with a maximum capacity (default 256) and eviction policy, running seamlessly in both JVM unit tests and Android runtime without Android framework dependencies.
- [x] In `EvcsRepository.kt`, `NearbyStationFilter.kt`, and `SessionManager.kt`:
  - Replace unbounded `ConcurrentHashMap` caches with `BoundedLruCache` (capacity = 256).
- [x] In `app/src/main/baseline-prof.txt`:
  - Remove references to non-existent classes: `VerticalScrollbarKt`, `MemoryStationCache`, `DiskStationCache`, `RateLimitRetryInterceptor`, `PerformanceLogger`, `HSPLcom/evcs/favorites/domain/model/Station`, `HSPLcom/evcs/favorites/domain/model/PowerPort`.
  - Fix package names for `NearbyUiState` and `FavoritesUiState` (`com.evcs.favorites.ui.viewmodel.*`).
  - Add missing production classes: `DefaultAppContainer`, `FirebaseAuthManager`, `FocusModeForegroundService`, `MainCarScreen`, `BoundedLruCache`.

### Non-Functional
- [x] Zero coroutine job leaks in `SingleFlight` under high concurrency.
- [x] Bounded memory footprint for connector string parsing and regex caches.
- [x] Baseline Profile passes DEX validation during release compilation.
- [x] Single file-based verification test executed in pure JVM environment.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/util/SingleFlight.kt`:
   - Call `newDeferred.cancel()` if `existing != null`.
2. [x] Update `app/src/main/java/com/evcs/favorites/data/network/AppOkHttpClientProvider.kt`:
   - Ensure `getSharedClient()` incorporates the installed disk cache in a thread-safe manner.
3. [x] Create `app/src/main/java/com/evcs/favorites/util/BoundedLruCache.kt`:
   - Implement thread-safe LRU cache with synchronized `LinkedHashMap` and `removeEldestEntry`.
4. [x] Update `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   - Replace `ConcurrentHashMap` with `BoundedLruCache<String, List<PowerPort>>(256)` for `parsedConnectorsCache`.
5. [x] Update `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt`:
   - Replace `ConcurrentHashMap` with `BoundedLruCache<String, Boolean>(256)` for `connectorCompatibilityCache`.
6. [x] Update `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt`:
   - Replace `ConcurrentHashMap` with `BoundedLruCache<String, Regex>(256)` for `COOKIE_REGEX_CACHE`.
7. [x] Update `app/src/main/baseline-prof.txt`:
   - Prune obsolete lines and align packages with actual codebase.
8. [x] Create single verification test `app/src/test/java/com/evcs/favorites/performance/Phase04P2ConcurrencyAndMemoryTest.kt`:
   - Verify `SingleFlight` cancels discarded lazy deferreds on collision.
   - Verify `BoundedLruCache` evicts eldest entries beyond 256 items.
   - Verify `baseline-prof.txt` contains only verifiable class names.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/util/SingleFlight.kt` - [MODIFY] Cancel discarded deferreds
- `app/src/main/java/com/evcs/favorites/data/network/AppOkHttpClientProvider.kt` - [MODIFY] Thread-safe disk cache initialization
- `app/src/main/java/com/evcs/favorites/util/BoundedLruCache.kt` - [NEW] Pure Kotlin thread-safe LRU cache
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Bounded LRU cache
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - [MODIFY] Bounded LRU cache
- `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt` - [MODIFY] Bounded LRU cache
- `app/src/main/baseline-prof.txt` - [MODIFY] Prune stale classes and fix packages
- `app/src/test/java/com/evcs/favorites/performance/Phase04P2ConcurrencyAndMemoryTest.kt` - [NEW] Single comprehensive verification test

## Single Verification Test
- **Test Class:** `com.evcs.favorites.performance.Phase04P2ConcurrencyAndMemoryTest`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.performance.Phase04P2ConcurrencyAndMemoryTest"
  ```
