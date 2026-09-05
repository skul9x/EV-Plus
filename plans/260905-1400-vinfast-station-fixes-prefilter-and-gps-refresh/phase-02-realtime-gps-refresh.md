# Phase 02: Real-Time GPS Refresh & Location Error Handling
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Update `NearbyViewModel.refresh()` to always re-fetch real-time GPS coordinates via `locationService.getFreshLocation()` instead of reusing stale coordinates from RAM. If GPS acquisition fails during refresh, report a clear error and request a retry per user-selected Option B.

## Requirements
### Functional
- [x] In `NearbyViewModel.kt`:
  - When `refresh()` is called:
    - Cancel active scan/routing jobs (`scanJob`, `routingJob`, `routingDebounceJob`).
    - Check runtime location permission via `locationService.hasLocationPermission()`:
      - If missing: emit `NearbyUiEvent.RequestLocationPermission` and return.
    - Set `_uiState.update { it.copy(isLocating = true, errorMessage = null) }`.
    - Always call `locationService.getFreshLocation()` (do NOT reuse stale coordinates from RAM).
    - If `getFreshLocation()` returns `null` (e.g. GPS disabled, timeout, permission revoked):
      - Update UI state: `isLocating = false`, `isSearching = false`.
      - Set `errorMessage = "Không thể lấy vị trí hiện tại. Vui lòng kiểm tra GPS và thử lại."`.
      - Do NOT silently fallback to stale in-memory coordinates (adhere strictly to user choice Option B).
    - If `getFreshLocation()` returns a valid `Location` with `(newLat, newLon)`:
      - Update UI state: `userLatitude = newLat`, `userLongitude = newLon`, `isLocating = false`, `isSearching = true`, `errorMessage = null`.
      - Fetch stations using `repository.searchNearbyVinFast(newLat, newLon)`.
      - If fetch fails: update `isSearching = false`, set `errorMessage`.
      - If fetch succeeds:
        - Update `rawStations = raw`, `hasSearched = true`, `isSearching = false`.
        - Execute filtering and routing pipeline via `executeFilterAndRoutingPipeline(raw, newLat, newLon, _uiState.value.selectedWattages, debounce = false)`.

### Non-Functional
- [x] Coroutine cancellation safety: prevent race conditions between rapid refresh clicks.
- [x] Thread-safe state emission via `StateFlow.update`.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Refactor `fun refresh(): Job` to always acquire fresh coordinates from `locationService.getFreshLocation()`.
   - Implement Option B error propagation on GPS location failure.
2. [x] Implement single comprehensive test: `app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyRealtimeGpsRefreshTest.kt`.
3. [x] Run verification test: `./gradlew test --tests "com.evcs.favorites.ui.viewmodel.NearbyRealtimeGpsRefreshTest"`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - Refactor `refresh()` method
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyRealtimeGpsRefreshTest.kt` - Single comprehensive test for Phase 02

## Test Criteria
- [x] `NearbyViewModel.refresh()` calls `locationService.getFreshLocation()` and does not reuse stale in-memory coordinates.
- [x] When `locationService.getFreshLocation()` succeeds with new coordinates `(21.05, 105.85)`, `userLatitude` and `userLongitude` in `uiState` are updated to `(21.05, 105.85)`.
- [x] When `locationService.getFreshLocation()` returns `null`, `uiState.errorMessage` contains `"Không thể lấy vị trí hiện tại. Vui lòng kiểm tra GPS và thử lại."`, `isSearching` is `false`, `isLocating` is `false`, and stale coordinates are not silently re-queried.
- [x] Calling `refresh()` while an existing scan job is running cancels the previous job cleanly.

## Notes
- Single test verification only: Run `NearbyRealtimeGpsRefreshTest` upon phase completion and stop for user review.

---
Next Phase: [Phase 03: Pre-Filter Selection on Initial Screen, Persistence & Immediate Execution](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1400-vinfast-station-fixes-prefilter-and-gps-refresh/phase-03-prefilter-and-initial-screen-ux.md)
