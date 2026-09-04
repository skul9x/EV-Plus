# Phase 03: Cloud Sync Failure Rollback & Consistency Safety
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Resolve ANDROID-LOGIC-006: Fix unhandled cloud sync failures in `EvcsRepository` where optimistic local mutations to `_favoritesState`, `_favoriteIdsState`, and persistent cache `saveCachedFavorites` are performed without rollback logic when remote cloud synchronization (`apiClient.saveFavorites`) fails. Ensure local state rolls back to the exact pre-operation state upon network failure or server rejection.

## Requirements
### Functional
- Update `EvcsRepository.addFavoriteStation`:
  - Capture snapshot of current in-memory favorites: `val previousFavorites = _favoritesState.value` and `val previousIds = _favoriteIdsState.value`.
  - Perform cloud sync via `apiClient.saveFavorites(rawFavorites)`.
  - If `syncResult.isFailure`:
    - Roll back `_favoritesState.value = previousFavorites`.
    - Roll back `_favoriteIdsState.value = previousIds`.
    - Roll back persistent storage: `saveCachedFavorites(previousFavorites)`.
    - Return `Result.failure` containing the sync failure error.
- Update `EvcsRepository.removeFavoriteStation`:
  - Capture snapshot of current in-memory favorites: `val previousFavorites = _favoritesState.value` and `val previousIds = _favoriteIdsState.value`.
  - Perform cloud sync via `apiClient.saveFavorites(rawFavorites)`.
  - If `syncResult.isFailure`:
    - Roll back `_favoritesState.value = previousFavorites`.
    - Roll back `_favoriteIdsState.value = previousIds`.
    - Roll back persistent storage: `saveCachedFavorites(previousFavorites)`.
    - Return `Result.failure` containing the sync failure error.
- Update `FavoritesViewModel.removeFavorite`:
  - Verify that when `repository.removeFavoriteStation` rolls back on network failure, `FavoritesViewModel` retains/restores the station in UI state rather than permanently dropping it.

### Non-Functional
- Transactional Consistency: Guarantees ACID-like atomic consistency between in-memory state, disk cache, and remote server state.
- Offline Resilience: Prevents silent data divergence when device is intermittently offline.

## Implementation Steps
1. Refactor `addFavoriteStation` in `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` to snapshot previous state and roll back on failure.
2. Refactor `removeFavoriteStation` in `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` to snapshot previous state and roll back on failure.
3. Review `FavoritesViewModel.removeFavorite` in `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` to handle repository rollback cleanly.
4. Create single comprehensive test file `app/src/test/java/com/evcs/favorites/data/repository/CloudSyncRollbackSafetyTest.kt`.
5. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.CloudSyncRollbackSafetyTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Add transactional rollback logic to `addFavoriteStation` and `removeFavoriteStation`
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Coordinate rollback handling in `removeFavorite`
- `app/src/test/java/com/evcs/favorites/data/repository/CloudSyncRollbackSafetyTest.kt` - [NEW] Single comprehensive test for Phase 03

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.CloudSyncRollbackSafetyTest`
- [x] Verifies that when `apiClient.saveFavorites` fails during `addFavoriteStation`, `_favoritesState.value` is restored to pre-add state.
- [x] Verifies that when `apiClient.saveFavorites` fails during `addFavoriteStation`, persistent storage `cacheStorage` is rolled back.
- [x] Verifies that when `apiClient.saveFavorites` fails during `removeFavoriteStation`, `_favoritesState.value` retains the removed station.
- [x] Verifies that persistent storage is restored when `removeFavoriteStation` encounters network errors.
- [x] Verifies that successful cloud sync updates both in-memory and persistent cache without triggering rollback.

---
Next Phase: [Phase 04: Nearby Routing Settings Reactivity & Observation](phase-04-nearby-settings-routing-reactivity.md)
