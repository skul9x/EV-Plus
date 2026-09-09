# Phase 03: Dedicated Landscape UI Screens for Nearby & Favorites

Status: ✅ Completed
Dependencies: `phase-02-immersive-mode-and-compact-navigation-rail.md`

## Objective

Decouple horizontal automotive layouts completely from portrait layouts by creating dedicated composables (`NearbyLandscapeScreen.kt` and `FavoritesLandscapeScreen.kt`). Eliminate the top `TopAppBar` and the filter summary count pill (`"Tìm thấy 10 trạm có cổng DC <=30kW khả dụng"`) on both screens, reclaiming ~95dp of vertical space to display 3-4 visible station cards simultaneously next to the full-height detail pane.

## Requirements

### Functional
- [x] Create `com.evcs.favorites.ui.screens.landscape.NearbyLandscapeScreen.kt`:
  - **No `TopAppBar`:** The top header bar is completely eliminated.
  - **No Filter Summary Pill:** The text pill (`filterSummaryPillText`) is removed; cards begin directly below the filter chips.
  - **Master Column (Left):** Compact `SmartFilterBar` (padding 4dp top/bottom) + `LinearProgressIndicator` (visible during background search/routing) + `LazyColumn` of `StationCard` items with marquee titles.
  - **Detail Column (Right):** Full-height `Surface` embedding `NativeStationDetailContent` with charging port status breakdown, reviews, and the 1-tap "⚡ DẪN ĐƯỜNG & THEO DÕI" action button.
  - **Auto-Selection:** Maintain auto-selection of the nearest station when results are loaded.
- [x] Create `com.evcs.favorites.ui.screens.landscape.FavoritesLandscapeScreen.kt`:
  - **No `TopAppBar`:** Top header bar eliminated in landscape mode.
  - **Master Column (Left):** Saved favorites list with cloud sync status.
  - **Detail Column (Right):** Full-height embedded `NativeStationDetailContent`.
- [x] Update `NearbyScreen.kt` and `FavoritesScreen.kt`:
  - When `effectiveIsLandscape == true`, cleanly delegate to `NearbyLandscapeScreen` and `FavoritesLandscapeScreen` without rendering outer scaffolding or nested top bars.
- [x] Wire Settings & Refresh actions from `MainActivity.kt` and `AppNavigationRail`:
  - Clicking Settings triggers `showRoutingSettings = true`.
  - Clicking Refresh calls `nearbyViewModel.refresh()` or `favoritesViewModel.refresh()` depending on `currentTab`.

### Non-Functional
- [x] Clean architectural separation: 0 nested `if (!isLandscape)` branches inside landscape composables.
- [x] Flawless 60fps scrolling and instant tab switching.
- [x] Single file-based verification test.

## Implementation Steps
1. [x] Create `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt`:
   - Implement two-column Master-Detail layout with zero top bar and zero filter summary pill.
   - Embed `SmartFilterBar`, `LinearProgressIndicator`, `LazyColumn` with `StationCard`, and `NativeStationDetailContent`.
2. [x] Create `app/src/main/java/com/evcs/favorites/ui/screens/landscape/FavoritesLandscapeScreen.kt`:
   - Implement two-column Master-Detail layout for saved favorite stations with zero top bar.
3. [x] Refactor `NearbyScreen.kt` and `FavoritesScreen.kt`:
   - Route landscape rendering directly to the dedicated landscape screen components.
4. [x] Connect `MainActivity.kt`:
   - Provide Settings dialog state and refresh handler to `AppNavigationRail`.
5. [x] Create single verification test `app/src/test/java/com/evcs/favorites/ui/LandscapeDedicatedScreensContractTest.kt`:
   - Verify layout contract constants: top app bar suppressed in landscape mode.
   - Verify filter summary pill suppressed in landscape mode.
   - Verify master-detail width ratio calculation with the 58dp navigation rail.
   - Verify auto-selection resolution contract for nearest station in landscape mode.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt` - [NEW] Dedicated landscape screen for Nearby tab
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/FavoritesLandscapeScreen.kt` - [NEW] Dedicated landscape screen for Favorites tab
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [MODIFY] Delegate landscape mode to `NearbyLandscapeScreen`
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - [MODIFY] Delegate landscape mode to `FavoritesLandscapeScreen`
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Pass navigation rail action callbacks
- `app/src/test/java/com/evcs/favorites/ui/LandscapeDedicatedScreensContractTest.kt` - [NEW] Single comprehensive verification test

## Single Verification Test
- **Test Class:** `com.evcs.favorites.ui.LandscapeDedicatedScreensContractTest`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.LandscapeDedicatedScreensContractTest"
  ```

---
All Phases Complete!
