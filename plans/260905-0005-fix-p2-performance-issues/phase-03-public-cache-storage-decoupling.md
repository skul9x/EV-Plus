# Phase 03: Public Station Snapshot Storage Decoupling
Status: ✅ Completed  
Dependencies: `phase-02-compose-ui-memoization.md`  
Issue IDs: `PERF-STOR-01`

## Objective
Decouple public non-sensitive station data (offline station snapshots and geographic coordinate caches) from `EncryptedSharedPreferences`. Reserve hardware Keystore AES-GCM encryption strictly for sensitive authentication sessions, while storing public offline station caches in standard unencrypted `SharedPreferences`.

## Requirements
### Functional
- Public station coordinates and offline favorites snapshots continue to persist reliably across application sessions.
- Backward compatibility: If an offline snapshot already exists in the legacy encrypted storage, migrate or read gracefully without data loss.
- Authentication tokens and session secrets remain strictly stored inside `EncryptedSharedPrefsStorage`.

### Non-Functional
- Eliminate CPU-heavy AES-GCM encryption and decryption overhead when saving and loading large offline station JSON payloads and coordinate maps.

## Implementation Steps
1. [x] In `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt`:
   - Introduce `PlainSharedPrefsStorage(context: Context, prefsName: String = "evcs_public_cache") : SessionStorage`:
     ```kotlin
     class PlainSharedPrefsStorage(
         context: Context,
         prefsName: String = "evcs_public_cache"
     ) : SessionStorage {
         private val appContext = context.applicationContext ?: context
         private val lock = Any()
         private val prefs by lazy { appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }
         override suspend fun warmUp() { withContext(Dispatchers.IO) { prefs } }
         override fun getString(key: String): String? = synchronized(lock) { prefs.getString(key, null) }
         override fun putString(key: String, value: String?) {
             synchronized(lock) {
                 val editor = prefs.edit()
                 if (value == null) editor.remove(key) else editor.putString(key, value)
                 editor.apply()
             }
         }
         override fun remove(key: String) { synchronized(lock) { prefs.edit().remove(key).apply() } }
         override fun clear() { synchronized(lock) { prefs.edit().clear().apply() } }

         companion object {
             @Volatile
             private var instance: PlainSharedPrefsStorage? = null
             fun getInstance(context: Context): PlainSharedPrefsStorage {
                 return instance ?: synchronized(this) {
                     instance ?: PlainSharedPrefsStorage(context.applicationContext ?: context).also { instance = it }
                 }
             }
         }
     }
     ```
2. [x] In `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   - Add optional constructor parameter `private val legacyStorage: SessionStorage? = null`.
   - In `loadCachedCoordinates()`: if `cacheStorage?.getString(KEY_COORDINATE_CACHE)` is null, check `legacyStorage?.getString(KEY_COORDINATE_CACHE)`. If found in `legacyStorage`, migrate it to `cacheStorage?.putString(...)` and remove from `legacyStorage`.
   - In `getCachedFavorites()`: if `cacheStorage?.getString(KEY_OFFLINE_FAVORITES)` is null, check `legacyStorage?.getString(KEY_OFFLINE_FAVORITES)`. If found, migrate to `cacheStorage` and remove from `legacyStorage`.
3. [x] In `app/src/main/java/com/evcs/favorites/MainActivity.kt`:
   - Instantiate `publicCacheStorage = PlainSharedPrefsStorage.getInstance(applicationContext)`.
   - Inject `publicCacheStorage` as `cacheStorage` and `EncryptedSharedPrefsStorage.getInstance(applicationContext)` as `legacyStorage` into `EvcsRepository`:
     ```kotlin
     private val repository by lazy {
         EvcsRepository(
             apiClient = apiClient,
             cacheStorage = PlainSharedPrefsStorage.getInstance(applicationContext),
             legacyStorage = EncryptedSharedPrefsStorage.getInstance(applicationContext),
             autoResolveCoordinates = true
         )
     }
     ```
   - Ensure `sessionManager` continues using `EncryptedSharedPrefsStorage.getInstance(applicationContext)`.
4. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/data/repository/PublicStationStorageDecouplingTest.kt`:
   - Verifies `PlainSharedPrefsStorage` saves and loads coordinates and station lists without Keystore crypto calls.
   - Verifies `EvcsRepository` reads/writes offline snapshots and coordinate cache from plain storage.
   - Verifies automatic seamless migration from `legacyStorage` to `cacheStorage` when cache is missing.
   - Verifies user authentication tokens are not stored in the public cache storage.
5. [x] Run single test:
   ```bash
   ./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.PublicStationStorageDecouplingTest
   ```

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt` - [MODIFY] Add `PlainSharedPrefsStorage` with thread-safe access and singleton
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Support `legacyStorage` migration and decouple public cache
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Pass plain cache storage and legacy storage to repository
- `app/src/test/java/com/evcs/favorites/data/repository/PublicStationStorageDecouplingTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria
- [x] Offline favorites and coordinate caches persist and restore accurately in plain storage.
- [x] Legacy encrypted snapshots are seamlessly migrated to plain storage and purged from encrypted prefs.
- [x] Session storage remains encrypted and isolated from public station caches.
- [x] Exactly one test file is executed and passes cleanly.

---
Next Phase: `phase-04-bounded-lru-memory-cache.md`
