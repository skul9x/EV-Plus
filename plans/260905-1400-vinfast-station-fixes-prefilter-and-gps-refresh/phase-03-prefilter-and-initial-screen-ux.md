# Phase 03: Pre-Filter Selection on Initial Screen, Persistence & Immediate Execution
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Enable users to view and select their desired charging power filters (`SmartFilterBar`) directly on the initial hero screen before tapping "Nhấn để tìm trạm quanh đây", restore their last used filter from `SmartFilterPreferences` on startup, and immediately apply the filter upon scanning.

## Requirements
### Functional
- [x] In `NearbyViewModel.kt`:
  - Ensure filter state (`activeFilterMode`, `selectedDcTier`, `savedCustomConfig`) is restored from `SmartFilterPreferences` during initialization so it is active even when `hasSearched == false`.
  - When filter actions are triggered on the initial screen (`applyCustomFilter`, `enterDcMode`, `toggleAcFilter`, `selectDcTier`, `exitDcMode`, `clearSmartFilter`):
    - Update `_uiState` filter properties (`activeFilterMode`, `selectedDcTier`, `isDcSubFilterVisible`, etc.).
    - Persist the updated configuration to `SmartFilterPreferences` immediately.
    - If `hasSearched == true`, re-filter existing stations; if `hasSearched == false`, keep pre-selected filter in state ready for the subsequent scan.
  - When `scanNearbyStations()` finishes fetching raw stations, immediately apply the pre-selected filter in `executeFilterAndRoutingPipeline()` to display the filtered Top 10.
- [x] In `NearbyScreen.kt`:
  - Update `NearbyInitialHeroContent` signature:
    ```kotlin
    private fun NearbyInitialHeroContent(
        uiState: NearbyUiState,
        onScanClick: () -> Unit,
        onCustomFilterClick: () -> Unit,
        onDcFilterClick: () -> Unit,
        onAcFilterClick: () -> Unit,
        onSelectDcTier: (DcWattageTier) -> Unit,
        onBackFromDc: () -> Unit,
        onClearFilters: () -> Unit,
        modifier: Modifier = Modifier
    )
    ```
  - In `NearbyInitialHeroContent`:
    - Display `SmartFilterBar` prominently at the top of the initial hero screen.
    - Below `SmartFilterBar`, display the Hero icon, headline, descriptive text, and "Nhấn để tìm trạm quanh đây" button.
    - Connect all filter interaction callbacks to `viewModel`.
    - Ensure smooth transitions and no visual layout jumps when entering/exiting DC sub-filters.

### Non-Functional
- [x] Material 3 design compliance with smooth enter/exit animations.
- [x] Immediate responsiveness: Filter chip taps must update UI state in <16ms (60fps).

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Verify initial state restores from `SmartFilterPreferences` when `hasSearched == false`.
   - Ensure filter changes when `hasSearched == false` correctly persist and remain active.
2. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt`:
   - Update `NearbyInitialHeroContent` to receive `uiState` and filter callbacks.
   - Render `SmartFilterBar` at the top of the initial view.
   - Wire all callbacks in `NearbyScreen` Composable.
3. [x] Implement single comprehensive test: `app/src/test/java/com/evcs/favorites/ui/screens/NearbyPreFilterAndInitialScanTest.kt`.
4. [x] Run verification test: `./gradlew test --tests "com.evcs.favorites.ui.screens.NearbyPreFilterAndInitialScanTest"`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - Support pre-search filter persistence & immediate execution
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - Render `SmartFilterBar` in initial hero view
- `app/src/test/java/com/evcs/favorites/ui/screens/NearbyPreFilterAndInitialScanTest.kt` - Single comprehensive test for Phase 03

## Test Criteria
- [x] Initial `uiState` has `activeFilterMode` and `selectedDcTier` matching values stored in `SmartFilterPreferences` prior to search.
- [x] Selecting `SmartFilterMode.AC` or a DC tier on the initial screen updates `uiState` and persists to `SmartFilterPreferences`.
- [x] Executing `scanNearbyStations()` applies the pre-selected filter to the returned raw stations immediately.
- [x] Calling `clearSmartFilter()` on initial screen resets `activeFilterMode` to `NONE` and updates preferences.

## Notes
- Single test verification only: Run `NearbyPreFilterAndInitialScanTest` upon phase completion and stop for user review.

---
All Phases Complete after Phase 03.
