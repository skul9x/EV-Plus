# Phase 02: Auto-Scroll To Top on Refresh & Filter Changes
Status: ⬜ Pending
Dependencies: Phase 01

## Objective
Fix the issue where auto-scroll to the top nearest station only occurred during manual refresh in AC mode. Ensure any filter mode selection (All, AC, DC, DC wattage tier, Custom filter), clearing filters, or pulling/tapping refresh across all filter modes smoothly scrolls the list to index 0 so drivers immediately see the nearest valid station.

## Requirements
### Functional
- [ ] Update `NearbyUiHelper.shouldScrollToTop` to accept both `RefreshTriggerType.USER_REFRESH` and `RefreshTriggerType.FILTER_CHANGE`.
- [ ] Update `NearbyViewModel.triggerFilterPipeline` or its caller methods (`toggleAcFilter`, `enterDcMode`, `exitDcMode`, `selectDcTier`, `applyCustomFilter`, `saveAndApplyCustomFilter`, `clearFilters`, `toggleWattage`) to pass `triggerType = RefreshTriggerType.FILTER_CHANGE`.
- [ ] Ensure `lastRefreshTimestamp` is generated and assigned on `FILTER_CHANGE` as well as user refresh in `NearbyViewModel.executeFilterAndRoutingPipeline`.
- [ ] Preserve reading position on background passive updates (`PASSIVE_BACKGROUND`).

### Non-Functional
- [ ] Debounced and flicker-free animation in Compose `listState.animateScrollToItem(0)`.
- [ ] No race conditions when list is empty (`itemCount == 0`).

## Implementation Steps
1. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt`:
   - In `shouldScrollToTop(triggerType, itemCount, lastHandledTimestamp, eventTimestamp)`, update condition:
     ```kotlin
     val isScrollEligibleTrigger = triggerType == RefreshTriggerType.USER_REFRESH ||
             triggerType == RefreshTriggerType.FILTER_CHANGE
     return isScrollEligibleTrigger && itemCount > 0 && eventTimestamp > lastHandledTimestamp
     ```
2. [ ] Modify `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Update filter modifier functions (`toggleAcFilter`, `selectDcTier`, `applyCustomFilter`, `saveAndApplyCustomFilter`, `exitDcMode`, `clearFilters`) to pass `triggerType = RefreshTriggerType.FILTER_CHANGE` to `triggerFilterPipeline`.
   - In `executeFilterAndRoutingPipeline`, calculate timestamp:
     ```kotlin
     val isScrollTrigger = triggerType == RefreshTriggerType.USER_REFRESH || triggerType == RefreshTriggerType.FILTER_CHANGE
     val refreshTimestamp = if (isScrollTrigger) System.currentTimeMillis() else _uiState.value.lastRefreshTimestamp
     ```
   - Ensure user-initiated refresh in `refreshNearbyStations(isUserRefresh = true)` assigns `RefreshTriggerType.USER_REFRESH` uniformly regardless of current active filter mode.
3. [ ] Create single test file: `app/src/test/java/com/evcs/favorites/ui/NearbyAutoScrollFilterFixTest.kt`:
   - Test `shouldScrollToTop` returns true for `USER_REFRESH` and `FILTER_CHANGE` when items > 0 and timestamp is newer.
   - Test `shouldScrollToTop` returns false for `PASSIVE_BACKGROUND` and `PAGINATION`.
   - Test `shouldScrollToTop` returns false when list is empty.
   - Test that changing filters in ViewModel emits updated `lastRefreshTimestamp`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt` - Support `FILTER_CHANGE` trigger in auto-scroll resolution.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - Pass `FILTER_CHANGE` trigger and update refresh timestamp.
- `app/src/test/java/com/evcs/favorites/ui/NearbyAutoScrollFilterFixTest.kt` - Unit test for Phase 02 verification.

## Test Criteria
- [ ] Run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.NearbyAutoScrollFilterFixTest"`
- [ ] 100% tests pass.

---
Next Phase: [phase-03-ac-only-station-focus-button.md](file:///home/skul9x/Desktop/Test_Code/EV-Plus-main/plans/260906-2318-focus-mode-20kw-and-filter-fixes/phase-03-ac-only-station-focus-button.md)
