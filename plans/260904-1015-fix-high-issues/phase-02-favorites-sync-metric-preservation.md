# Phase 02: Favorites Two-Way Sync Enrichment Preservation
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Resolve ANDROID-LOGIC-005: Prevent two-way synchronization in `FavoritesViewModel` from wiping transient UI enrichments (`drivingMetrics` and `forecast`) when `repository.favoritesState` emits an updated list (e.g., when a favorite is toggled from the Nearby tab or background cloud refresh). Ensure existing stations retain their metrics and new stations trigger enrichment.

## Requirements
### Functional
- Update `FavoritesViewModel.init` repository collector:
  - When merging `repoStations` into `currentState.stations`:
    - Build an ID map of currently displayed stations: `val existingMap = currentState.stations.associateBy { it.id }`.
    - For every station in `repoStations`, preserve existing `drivingMetrics` and `forecast` if that station was previously present.
    - If a station is newly added to favorites (`repoIds - currentIds`), retain the station and trigger multi-tier routing and forecast enrichment if user GPS coordinates are available.
  - Replace direct assignment `_uiState.value = currentState.copy(...)` with atomic `_uiState.update { ... }` to eliminate concurrent read-modify-write race conditions.
  - Maintain correct sorting order (ETA sort or distance sort) after merging.

### Non-Functional
- Smooth UI: Eliminates UI flicker where ETA pills and forecast capsules temporarily vanish.
- Concurrency Safety: Thread-safe state emission on `Dispatchers.Main`.

## Implementation Steps
1. Refactor repository collector in `FavoritesViewModel.kt` (`app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`) to merge stations using `existingMap` lookup and `_uiState.update { }`.
2. Ensure routing and forecast pipelines are invoked for newly added stations without overwriting existing station enrichments.
3. Create single comprehensive test file `app/src/test/java/com/evcs/favorites/ui/viewmodel/FavoritesTwoWaySyncEnrichmentPreservationTest.kt`.
4. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.FavoritesTwoWaySyncEnrichmentPreservationTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Refactor two-way sync state collection
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/FavoritesTwoWaySyncEnrichmentPreservationTest.kt` - [NEW] Single comprehensive test for Phase 02

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.FavoritesTwoWaySyncEnrichmentPreservationTest`
- [x] Verifies that when `repository.favoritesState` emits an updated station list, existing stations retain their `drivingMetrics` (duration, distance, traffic condition).
- [x] Verifies that existing stations retain their `StationForecast` data during repository state emissions.
- [x] Verifies that removing a favorite via repository emission removes only the targeted station while preserving metrics on remaining stations.
- [x] Verifies that adding a new favorite preserves metrics on existing stations and adds the new station to the UI state.
- [x] Verifies that atomic `_uiState.update` prevents state reversion when concurrent emissions occur.

---
Next Phase: [Phase 03: Cloud Sync Failure Rollback & Consistency Safety](phase-03-cloud-sync-failure-rollback-safety.md)
