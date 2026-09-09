# Phase 01: P0 Critical Performance & Architecture Remediation

Status: ✅ Completed
Dependencies: None

## Objective

Remediate the three critical (P0) bottlenecks that severely harm user startup experience, cause battery drain on vehicle dashboards, and duplicate in-memory data structures:
1. **[STARTUP-01]:** Eliminate synchronous Keystore MasterKey generation, AES-GCM cipher initialization, and disk `.commit()` calls on the Android Main Thread during cold start.
2. **[COMPOSE-02]:** Halt the infinite `basicMarquee(iterations = Int.MAX_VALUE)` animation loop in `StationCard`, eliminating continuous 60/90/120Hz frame invalidation loops while idling.
3. **[ARCH-01]:** Unify `MainActivity` dependency resolution on `DefaultAppContainer` singletons and expose `authService` & `firestoreFavoritesRepository` on `AppContainer`, preventing state divergence and duplicate network calls between Phone and Android Auto sessions.

## Requirements

### Functional
- [x] In `SessionManager.kt` (inside `EncryptedSharedPrefsStorage`):
  - Eliminate the synchronous write probe `.commit()` calls (`esp.edit().putString("__esp_probe__", "1").commit()` and `remove(...).commit()`), replacing with `.apply()` or safe read verification to avoid blocking the caller thread.
- [x] In `AuthEngine.kt`:
  - Make auth session checking non-blocking on startup by defaulting `_isLoggedIn` to `MutableStateFlow(false)`.
  - Allow `checkLoggedInAsync(Dispatchers.IO)` (already invoked in `FavoritesViewModel.init` background coroutine) to asynchronously verify cookie existence and update `_isLoggedIn` off the Main Thread.
- [x] In `StationCard.kt`:
  - Cap `basicMarquee` iterations to `iterations = 2` with `delayMillis = 2000` and `velocity = 30.dp` across both portrait (lines 206-210) and landscape (lines 458-462) card variants.
- [x] In `AppContainer.kt` & `DefaultAppContainer`:
  - Expose `authService: AuthService` and `firestoreFavoritesRepository: FirestoreFavoritesRepository` in `AppContainer` interface.
  - In `DefaultAppContainer`, inject `firestoreFavoritesRepository` into `evcsRepository` so that both Phone and Android Auto share full Cloud Firestore synchronization.
- [x] In `MainActivity.kt`:
  - Wire `sessionManager`, `apiClient`, `authService`, `firestoreFavoritesRepository`, and `repository` to `(application as EvPlusApplication).appContainer` singletons, eliminating detached repository instances.

### Non-Functional
- [x] Zero blocking Keystore or SharedPreferences disk `.commit()` operations on the Main Thread during `MainActivity.onCreate` / `setContent`.
- [x] Frame invalidations drop to 0 FPS when resting on a stationary list of stations.
- [x] Identical singleton repository instance shared between Phone UI and Android Auto `MainCarScreen`.
- [x] Single file-based verification test executed in pure JVM environment.

## Implementation Steps
1. [x] Modify `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt`:
   - In `EncryptedSharedPrefsStorage`, remove synchronous `.commit()` probe calls to avoid Main-Thread disk I/O.
2. [x] Modify `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt`:
   - Default `_isLoggedIn = MutableStateFlow(false)`. Do not evaluate `sessionManager.hasAuthCookie()` in property initializer.
3. [x] Modify `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`:
   - Set `basicMarquee(iterations = 2, delayMillis = 2000, velocity = 30.dp)` on station title Text in portrait and landscape layouts.
4. [x] Modify `app/src/main/java/com/evcs/favorites/di/AppContainer.kt`:
   - Add `authService: AuthService` and `firestoreFavoritesRepository: FirestoreFavoritesRepository` to `AppContainer`.
   - In `DefaultAppContainer`, instantiate `authService` and `firestoreFavoritesRepository`, and pass `firestoreFavoritesRepository` into `EvcsRepository`.
5. [x] Modify `app/src/main/java/com/evcs/favorites/MainActivity.kt`:
   - Replace private lazy initializers of `sessionManager`, `apiClient`, `authService`, `firestoreFavoritesRepository`, and `repository` with references to `(application as EvPlusApplication).appContainer`.
6. [x] Create single verification test `app/src/test/java/com/evcs/favorites/performance/Phase01P0PerformanceAndArchitectureTest.kt`:
   - Verify `AuthEngine` initializes non-blocking without calling disk storage.
   - Verify `StationCard` marquee iterations are bounded to `<= 2`.
   - Verify `DefaultAppContainer` singletons are reused by `MainActivity` and include `firestoreFavoritesRepository`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt` - [MODIFY] Remove synchronous `.commit()` probe in `EncryptedSharedPrefsStorage`
- `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt` - [MODIFY] Make auth state non-blocking on startup
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [MODIFY] Cap `basicMarquee` iterations to 2
- `app/src/main/java/com/evcs/favorites/di/AppContainer.kt` - [MODIFY] Expose auth and firestore repositories, wire into singleton
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Bind dependencies to `AppContainer` singletons
- `app/src/test/java/com/evcs/favorites/performance/Phase01P0PerformanceAndArchitectureTest.kt` - [NEW] Single comprehensive verification test

## Single Verification Test
- **Test Class:** `com.evcs.favorites.performance.Phase01P0PerformanceAndArchitectureTest`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.performance.Phase01P0PerformanceAndArchitectureTest"
  ```

---
Next Phase: `phase-02-p1-recomposition-and-startup-cache.md`
