# Phase 02: P1 UI Recomposition & Startup Cache Optimization

Status: ✅ Completed
Dependencies: `phase-01-p0-startup-marquee-di.md`

## Objective

Remediate Priority 1 (P1) performance issues related to UI recomposition overhead and synchronous cold-start disk deserialization:
1. **[COMPOSE-01]:** Eliminate redundant synchronous encrypted disk reads (`viewModel.getCookieHeader()`) executed during every Compose recomposition pass in `MainActivity`, and remove the unused `cookieHeader` parameter from `FavoritesScreen`, `NearbyScreen`, and `FavoritesApp`.
2. **[STARTUP-02]:** Change `eagerLoadCache` to `false` by default in `EvcsRepository`, ensuring disk reading and JSON deserialization of station coordinates occur strictly asynchronously on `Dispatchers.IO` via `initializeAsync()`.

## Requirements

### Functional
- [x] In `FavoritesScreen.kt`:
  - Delete `@Suppress("UNUSED_PARAMETER") cookieHeader: String? = null` parameter from `FavoritesScreen` Composable signature.
- [x] In `NearbyScreen.kt`:
  - Delete `@Suppress("UNUSED_PARAMETER") cookieHeader: String? = null` parameter from `NearbyScreen` Composable signature.
- [x] In `MainActivity.kt`:
  - Remove `cookieHeader = viewModel.getCookieHeader()` parameter calls on lines 338 and 368.
  - Remove `cookieHeader: String? = null` parameter from `FavoritesApp` composable.
- [x] In `EvcsRepository.kt`:
  - Change constructor parameter default from `eagerLoadCache: Boolean = true` to `eagerLoadCache: Boolean = false`.
  - In constructor `init`: when `eagerLoadCache == false`, skip `loadCacheInternal()`.
  - In `initializeAsync()`: load coordinates off the main thread via `loadCacheInternal()` before setting `_isInitialized.value = true`.

### Non-Functional
- [x] Zero disk reads on Main Thread during UI tab switching or recomposition.
- [x] Compose compiler skipping enabled on `FavoritesScreen` and `NearbyScreen`.
- [x] Single file-based verification test executed in pure JVM environment.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt`:
   - Delete `cookieHeader: String? = null` parameter from `FavoritesScreen` Composable signature.
2. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt`:
   - Delete `cookieHeader: String? = null` parameter from `NearbyScreen` Composable signature.
3. [x] Update `app/src/main/java/com/evcs/favorites/MainActivity.kt`:
   - Delete `cookieHeader` arguments passed to `FavoritesScreen` and `NearbyScreen`.
   - Remove `viewModel.getCookieHeader()` calls from Composable hierarchy.
4. [x] Update `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   - Change `eagerLoadCache: Boolean = false` in constructor parameters.
   - Retain `loadCacheInternal()` inside `initializeAsync(dispatcher)` to run strictly on `Dispatchers.IO`.
5. [x] Create single verification test `app/src/test/java/com/evcs/favorites/performance/Phase02P1RecompositionAndStartupCacheTest.kt`:
   - Verify `EvcsRepository` does not load coordinates synchronously when `eagerLoadCache = false`.
   - Verify `initializeAsync()` properly populates `coordinateCache` asynchronously on `Dispatchers.IO`.
   - Verify absence of `cookieHeader` in Composable parameter bindings and that no disk reads occur during UI state updates.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - [MODIFY] Remove unused `cookieHeader` parameter
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [MODIFY] Remove unused `cookieHeader` parameter
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Remove `getCookieHeader()` recomposition calls
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Default `eagerLoadCache = false`
- `app/src/test/java/com/evcs/favorites/performance/Phase02P1RecompositionAndStartupCacheTest.kt` - [NEW] Single comprehensive verification test

## Single Verification Test
- **Test Class:** `com.evcs.favorites.performance.Phase02P1RecompositionAndStartupCacheTest`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.performance.Phase02P1RecompositionAndStartupCacheTest"
  ```

---
Next Phase: `phase-03-p1-routing-coordinator-cache.md`
