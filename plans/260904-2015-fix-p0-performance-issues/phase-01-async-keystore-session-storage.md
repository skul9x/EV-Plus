# Phase 01: Async Keystore Warmup & Non-Blocking ViewModel Startup

Status: ✅ Completed
Issue ID: PERF-ANR-01
Dependencies: None

## Objective
Eliminate Main Thread blockage (250ms–900ms) during cold start caused by synchronous Android Keystore IPC, hardware key generation, and AES-GCM EncryptedSharedPreferences initialization, preventing frozen startup frames and ANR crashes.

---

## Requirements

### Functional
- [x] Ensure `MainActivity` and `FavoritesViewModel` instantiate immediately without blocking the Main Thread on `EncryptedSharedPreferences`.
- [x] Initialize `FavoritesViewModel._uiState` with `FavoritesUiState.Loading` by default.
- [x] Perform auth verification and cookie probing asynchronously off the Main Thread on `Dispatchers.IO`.
- [x] Transition `_uiState` to `FavoritesUiState.LoggedOut` if unauthenticated, or trigger `fetchFavorites()` if authenticated.
- [x] Support background warmup of `EncryptedSharedPrefsStorage` so Keystore IPC occurs concurrently with app startup.

### Non-Functional
- [x] Cold start Main Thread freeze reduced by >90% (zero synchronous Keystore IPC or `commit()` probe on Main Thread).
- [x] Thread safety: Concurrent reads and writes during background warmup must not cause deadlocks or race conditions.

---

## Implementation Steps

1. **Update `SessionStorage` and `EncryptedSharedPrefsStorage` (`SessionManager.kt`)**:
   - Add a non-blocking `warmUp()` suspend function to `SessionStorage` and implement it in `EncryptedSharedPrefsStorage` using `withContext(Dispatchers.IO)`.
   - Ensure initialization of `prefs` can be executed asynchronously off the Main Thread.
   - Add `checkAuthCookieAsync(dispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean` in `SessionManager`.

2. **Update `AuthEngine.kt`**:
   - Add `suspend fun checkLoggedInAsync(dispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean` to verify authentication state without blocking the caller.
   - Initialize the `_isLoggedIn` StateFlow safely without requiring synchronous disk I/O on ViewModel construction.

3. **Update `FavoritesViewModel.kt`**:
   - Change initial state of `_uiState` to `FavoritesUiState.Loading`.
   - In `init`, launch a coroutine on `ioDispatcher` to invoke `authEngine.checkLoggedInAsync()`.
   - Switch back to `Dispatchers.Main` to either trigger `fetchFavorites()` if authenticated or emit `FavoritesUiState.LoggedOut`.

4. **Update `MainActivity.kt`**:
   - Launch background warmup of `EncryptedSharedPrefsStorage` inside `onCreate()` on `Dispatchers.IO`.

---

## Files to Modify/Create
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt` - Add async warmup and non-blocking auth check.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt` - Add `checkLoggedInAsync()`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - Decouple init from synchronous auth check.
- [MODIFY] `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Trigger background warmup on IO dispatcher.
- [NEW] `app/src/test/java/com/evcs/favorites/AsyncSessionStorageColdStartTest.kt` - Exactly one comprehensive test for Phase 1.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/AsyncSessionStorageColdStartTest.kt`
- **Core Verifications**:
  1. `EncryptedSharedPrefsStorage` / `SessionStorage` warms up and initializes asynchronously without deadlocks.
  2. `AuthEngine.checkLoggedInAsync()` correctly reflects authenticated state and updates `isLoggedIn` StateFlow asynchronously.
  3. `FavoritesViewModel` initializes immediately with `FavoritesUiState.Loading` on the Main Thread without blocking on session storage.
  4. Once `checkLoggedInAsync()` completes in background, `FavoritesViewModel` properly transitions to `LoggedOut` (when no cookie exists) or starts `fetchFavorites()` (when authenticated).
  5. Concurrency: Concurrent requests during warmup return correct session values once resolved.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.AsyncSessionStorageColdStartTest
```

---
Next Phase: [Phase 02: Deterministic WebView Lifecycle & Native Memory Leak Prevention](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2015-fix-p0-performance-issues/phase-02-webview-lifecycle-leak-prevention.md)
