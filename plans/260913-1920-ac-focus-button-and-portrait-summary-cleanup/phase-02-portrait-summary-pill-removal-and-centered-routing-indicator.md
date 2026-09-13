# Phase 02: Portrait Summary Pill Removal & Centered Routing Indicator
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Remove the redundant summary pill container (`filterSummaryPillText`, e.g. "Tìm thấy 10 trạm có cổng AC khả dụng", "Top 10 trạm sạc VinFast...") from portrait mode (`NearbyScreen.kt`) across all filter modes to reclaim vertical screen estate. Re-center the routing progress spinner (`CircularProgressIndicator` during `isRoutingLoading == true`) within the station viewport for clear, modern automotive feedback.

## Requirements
### Functional
- [x] Remove the top summary pill `Row` (lines 816-846 in `NearbyScreen.kt`) displaying `uiState.filterSummaryPillText` in portrait mode.
- [x] Ensure the stations list container is positioned immediately below the filter bar with zero wasted vertical gap.
- [x] When `uiState.isRoutingLoading == true`:
  - Display a centered, lightweight progress indicator in the stations list viewport area.
  - When `isRoutingLoading == false`, the indicator occupies 0dp of space so no vertical shift or gap occurs.
- [x] Preserve `filterSummaryPillText` property in `NearbyUiState.kt` for background/state backwards-compatibility without breaking existing unit tests referencing the state model.
- [x] Ensure empty results state ("Không tìm thấy trạm sạc phù hợp"), initial loading state, and error states continue to display smoothly and without overlapping.

### Non-Functional
- [x] Ergonomics & UX: Maximizes visible station cards in portrait mode on mobile devices and vertical car displays.
- [x] Frame rate: Removing the decorative pill row decreases Compose node tree depth and layout measurement passes.

## Implementation Steps
1. [x] Remove the header info pill `Row` in `NearbyScreen.kt` above the stations list / empty state block.
2. [x] Integrate a centered routing indicator in `NearbyScreen.kt` when `uiState.isRoutingLoading` is active.
3. [x] Verify that existing unit tests referencing `NearbyUiState.filterSummaryPillText` remain green or adjust tests if they directly assert UI composition of the removed pill row.
4. [x] Create single comprehensive test file `app/src/test/java/com/evcs/favorites/ui/screens/NearbyPortraitSummaryPillRemovalTest.kt`.
5. [x] Run the single test file to verify the implementation.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [MODIFY] Remove summary pill row and add centered routing loading presentation.
- `app/src/test/java/com/evcs/favorites/ui/screens/NearbyPortraitSummaryPillRemovalTest.kt` - [NEW] Single comprehensive test validating the portrait layout structure, absence of the pill row, and centered routing loading state.

## Test Criteria
- [x] Portrait screen layout does not emit the summary pill box regardless of active filter (`NONE`, `AC`, `DC`, `CUSTOM`).
- [x] Stations list directly follows the filter bar without a permanent pill height offset.
- [x] Routing progress indicator is centered when `isRoutingLoading == true`.
- [x] `filterSummaryPillText` in `NearbyUiState` remains intact for non-breaking API compatibility.

---
Phase 02 concludes this feature implementation.
