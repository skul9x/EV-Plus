# Phase 02: Thread-Safe Favorites Synchronization & Rapid-Click Guard

Status: ✅ Completed
Dependencies: [Phase 01: Action Debounce & Throttling Engine](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260907-0025-click-spam-and-edge-case-hardening/phase-01-action-debounce-and-throttling.md)

## Objective
Eliminate multithreaded race conditions and lost updates in `FirestoreFavoritesRepository`, guard against rapid favorite toggling per station, and prevent toast/snackbar queuing storms when the user repeatedly clicks heart icons.

## Requirements
### Functional
- [x] Guarantee thread-safe atomic mutations in `FirestoreFavoritesRepository` using coroutine `Mutex` across `addFavoriteStation`, `removeFavoriteStation`, and sync routines to prevent lost updates under high concurrency.
- [x] Track in-flight operations per station ID (`togglingStationIds: Set<String>`) in `NearbyViewModel` and `FavoritesViewModel` so additional rapid clicks while an operation is in-flight are ignored.
- [x] Suppress spam toast/snackbar events: ensure rapid toggles only emit a single notification when the state actually changes.
- [x] Ensure `FavoritesViewModel.removeFavorite` does not lose rollback metadata on duplicate rapid delete clicks.
- [x] Disable interactive click on the favorite icon in `StationCard` and `NativeStationDetailSheet` while an operation is actively in-flight for that station.

### Non-Functional
- [x] 100% thread-safe under concurrent coroutine dispatchers (`Dispatchers.IO` and `Dispatchers.Default`).
- [x] Zero blocking of the Android Main UI thread during Mutex acquisition.

## Implementation Steps
1. [x] Modify `app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt`:
   - Introduce `private val stateMutex = Mutex()`.
   - Wrap state modification logic in `addFavoriteStation` and `removeFavoriteStation` within `stateMutex.withLock { ... }`.
2. [x] Modify `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Add `private val _togglingStationIds = MutableStateFlow<Set<String>>(emptySet())`.
   - In `toggleFavorite(station)`: check if `station.id` is in `_togglingStationIds.value`; if so, immediately return active job.
   - Add `station.id` to in-flight set, execute toggle, and remove from set in a `finally` block.
   - Debounce toast emission to prevent queueing duplicate messages.
3. [x] Modify `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`:
   - Guard `removeFavorite(stationId)` so duplicate calls while `stationId` is in-flight do not clear rollback cache or corrupt UI state.
4. [x] Modify `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`:
   - Accept optional `isToggleInProgress: Boolean = false` or check ViewModel in-flight set; disable IconButton interaction when in-flight.
5. [x] Modify `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt`:
   - Disable favorite button interaction while favorite toggle is in-flight.
6. [x] Create single comprehensive test file:
   - `app/src/test/java/com/evcs/favorites/hardening/FavoriteConcurrencyAndThreadSafetyTest.kt`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt` [MODIFY] - Add Mutex synchronization to `addFavoriteStation`, `removeFavoriteStation`, and sync routines.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` [MODIFY] - Track in-flight station toggling and debounce toast notifications.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` [MODIFY] - Add in-flight guard to `removeFavorite`.
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` [MODIFY] - Disable favorite click when station is in-flight.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` [MODIFY] - Disable favorite click when in-flight.
- `app/src/test/java/com/evcs/favorites/hardening/FavoriteConcurrencyAndThreadSafetyTest.kt` [NEW] - Comprehensive test file for Phase 02.

## Test Criteria
- [x] Run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.hardening.FavoriteConcurrencyAndThreadSafetyTest"`
- [x] 100% tests pass.
- [x] Strictly only this single test is executed for Phase 02 verification.

---
Next Phase: [Phase 03: GPS Timeout, Scan Guard & Android 13+ Permissions](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260907-0025-click-spam-and-edge-case-hardening/phase-03-gps-timeout-and-notification-permission.md)
