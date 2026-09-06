# Phase 05: Thread-Safe EncryptedSharedPreferences Singleton
Status: ✅ Completed
Dependencies: Phase 04

## Objective
Resolve ANDROID-LOGIC-008: Eliminate the construction of multiple concurrent `EncryptedSharedPrefsStorage` instances referencing `"evcs_secure_session"`. Multiple separate instances across `SessionManager`, `EvcsRepository`, `RoutingPreferencesManager`, and `NearbyFilterPreferences` lead to Keystore master key access contention, cache desynchronization, and silent data overwrites. Implement a thread-safe singleton pattern and ensure a single shared storage instance is used across the application.

## Requirements
### Functional
- Update `EncryptedSharedPrefsStorage` in `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt`:
  - Introduce a companion object with a thread-safe singleton accessor:
    `fun getInstance(context: Context): EncryptedSharedPrefsStorage`
  - Implement double-checked locking using `context.applicationContext` to prevent context leaks and duplicate instance creation.
- Update factory / create methods:
  - `SessionManager.create(context: Context)` -> use `EncryptedSharedPrefsStorage.getInstance(context)`.
  - `RoutingPreferencesManager.create(context: Context)` -> use `EncryptedSharedPrefsStorage.getInstance(context)`.
  - `NearbyFilterPreferences.create(context: Context)` -> use `EncryptedSharedPrefsStorage.getInstance(context)`.
- Update `MainActivity.kt`:
  - Pass the shared singleton storage or instantiate components using their updated factory methods, ensuring only one `EncryptedSharedPreferences` handle exists on `"evcs_secure_session"`.

### Non-Functional
- Thread Safety: Double-checked locking guarantees safe access across all dispatchers (`Dispatchers.Main` and `Dispatchers.IO`).
- Memory Safety: Binds to `applicationContext` to eliminate Activity context leaks.
- Zero Keystore Contention: Single MasterKey and crypto envelope per process.

## Implementation Steps
1. Add thread-safe singleton accessor `getInstance` to `EncryptedSharedPrefsStorage` in `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt`.
2. Update `RoutingPreferencesManager.create` in `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` to use `getInstance`.
3. Update `NearbyFilterPreferences.create` in `app/src/main/java/com/evcs/favorites/data/preferences/NearbyFilterPreferences.kt` to use `getInstance`.
4. Update `MainActivity.kt` in `app/src/main/java/com/evcs/favorites/MainActivity.kt` to supply the singleton instance to `repository`.
5. Create single comprehensive test file `app/src/test/java/com/evcs/favorites/data/auth/EncryptedSharedPrefsSingletonTest.kt`.
6. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.auth.EncryptedSharedPrefsSingletonTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt` - [MODIFY] Add `getInstance` singleton to `EncryptedSharedPrefsStorage`
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` - [MODIFY] Use singleton storage
- `app/src/main/java/com/evcs/favorites/data/preferences/NearbyFilterPreferences.kt` - [MODIFY] Use singleton storage
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Share singleton storage across components
- `app/src/test/java/com/evcs/favorites/data/auth/EncryptedSharedPrefsSingletonTest.kt` - [NEW] Single comprehensive test for Phase 05

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.auth.EncryptedSharedPrefsSingletonTest`
- [x] Verifies that `EncryptedSharedPrefsStorage.getInstance(context)` returns the exact same instance across repeated calls.
- [x] Verifies that writes performed via `SessionManager` are immediately visible in `RoutingPreferencesManager` and `NearbyFilterPreferences` when backed by the shared storage.
- [x] Verifies concurrent read and write operations across background coroutines do not throw concurrency or Keystore exceptions.
- [x] Verifies clear and key removal operations correctly update the unified underlying storage.

---
Phase Complete: All High Severity issues resolved!
