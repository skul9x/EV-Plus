# Phase 01: Cold Start & Main Thread Unblocking (PERF-001, PERF-009)
Status: ✅ Completed
Dependencies: None

## Objective
Eliminate Main Thread I/O freezes during application cold start caused by synchronous Android Keystore IPC initialization in UI configuration preferences (`SmartFilterPreferences`, `NearbyFilterPreferences`, `RoutingPreferencesManager`), synchronous JSON deserialization in `FirestoreFavoritesRepository.init`, and synchronous OkHttp disk cache initialization in `EvPlusApplication.onCreate()`.

## Requirements
### Functional
- [x] `PlainSharedPrefsStorage` in `SessionManager.kt` supports named preferences via thread-safe multi-instance registry `getInstance(context: Context, prefsName: String = "evcs_public_cache")`.
- [x] `SmartFilterPreferences.create(context)` uses `PlainSharedPrefsStorage.getInstance(context, "evcs_smart_filter_prefs")`, preserving all preference reading/writing behaviors with zero Keystore overhead.
- [x] `NearbyFilterPreferences.create(context)` uses `PlainSharedPrefsStorage.getInstance(context, "evcs_nearby_filter_prefs")`, maintaining comma-separated wattage persistence with zero Keystore overhead.
- [x] `RoutingPreferencesManager.create(context)` uses `PlainSharedPrefsStorage.getInstance(context, "evcs_routing_prefs")`, preserving Google API key, preferred engine, and custom OSRM URL storage with zero Keystore overhead.
- [x] `FirestoreFavoritesRepository` eliminates synchronous disk reading & JSON decoding in `init`; local cached favorites are loaded asynchronously via `scope.launch(ioDispatcher)` while maintaining immediate StateFlow updates upon completion.
- [x] `EvPlusApplication.onCreate()` initiates `AppOkHttpClientProvider.installDiskCache` asynchronously on `Dispatchers.IO` (or lazily) without blocking the main application startup thread.

### Non-Functional
- [x] Performance: Eliminate 150ms–300ms Main Thread freeze and StrictMode DiskRead/DiskWrite violations on app launch.
- [x] Security: Keep sensitive user authentication tokens securely inside `EncryptedSharedPrefsStorage` via `SessionManager`; non-sensitive UI filters and routing configurations operate with zero-overhead plain SharedPreferences.

## Implementation Steps
1. [x] Update `SessionManager.kt`:
   - Extend `PlainSharedPrefsStorage.Companion` to maintain a thread-safe registry (`ConcurrentHashMap<String, PlainSharedPrefsStorage>`) in `getInstance(context: Context, prefsName: String = "evcs_public_cache")`.
2. [x] Update `SmartFilterPreferences.kt`:
   - Change `create(context)` factory to use `PlainSharedPrefsStorage.getInstance(context, "evcs_smart_filter_prefs")`.
3. [x] Update `NearbyFilterPreferences.kt`:
   - Change `create(context)` factory to use `PlainSharedPrefsStorage.getInstance(context, "evcs_nearby_filter_prefs")`.
4. [x] Update `RoutingPreferencesManager.kt`:
   - Change `create(context)` factory to use `PlainSharedPrefsStorage.getInstance(context, "evcs_routing_prefs")`.
5. [x] Update `FirestoreFavoritesRepository.kt`:
   - Refactor `init` block so cache reading (`getCachedFavorites()`) is launched on `ioDispatcher`, updating `_favoritesState` and `_favoriteIdsState` as soon as decoded without blocking constructor execution.
6. [x] Update `EvPlusApplication.kt` and `AppOkHttpClientProvider.kt`:
   - Dispatch `installDiskCache` execution asynchronously on `Dispatchers.IO` via `CoroutineScope(Dispatchers.IO).launch { ... }` and ensure thread-safe client instantiation.
7. [x] Create verification test `ColdStartAndMainThreadUnblockingTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt` - Multi-instance registry for `PlainSharedPrefsStorage`.
- `app/src/main/java/com/evcs/favorites/data/preferences/SmartFilterPreferences.kt` - Switch factory to `PlainSharedPrefsStorage`.
- `app/src/main/java/com/evcs/favorites/data/preferences/NearbyFilterPreferences.kt` - Switch factory to `PlainSharedPrefsStorage`.
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` - Switch factory to `PlainSharedPrefsStorage`.
- `app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt` - Asynchronous local cache deserialization.
- `app/src/main/java/com/evcs/favorites/EvPlusApplication.kt` - Non-blocking OkHttp disk cache initialization.
- `app/src/main/java/com/evcs/favorites/data/network/AppOkHttpClientProvider.kt` - Support async/lazy disk cache installation.
- `app/src/test/java/com/evcs/favorites/performance/ColdStartAndMainThreadUnblockingTest.kt` - Dedicated verification test.

## Test Criteria (Single Comprehensive Test)
- `ColdStartAndMainThreadUnblockingTest.kt` must verify:
  1. `SmartFilterPreferences`, `NearbyFilterPreferences`, and `RoutingPreferencesManager` factories produce instances using `PlainSharedPrefsStorage` and successfully read/write data without invoking `EncryptedSharedPrefsStorage`.
  2. `PlainSharedPrefsStorage.getInstance` properly segregates independent storage files by name.
  3. `FirestoreFavoritesRepository` loads cached offline favorites asynchronously via coroutines without synchronous execution blocking the caller thread.
  4. `AppOkHttpClientProvider.installDiskCache` can be safely invoked in background dispatchers without thread contention or crashing `getSharedClient()`.

---
Next Phase: [phase-02-compose-stability-and-dead-code-elimination.md](file:///d:/skul9x/EV-Plus-main/plans/260906-1540-comprehensive-performance-remediation/phase-02-compose-stability-and-dead-code-elimination.md)

