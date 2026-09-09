# Phase 02: Fullscreen Immersive Sticky Mode & Compact Centered Navigation Rail

Status: ✅ Completed
Dependencies: `phase-01-station-name-sanitizer-and-marquee.md`

## Objective

Configure automatic Fullscreen Immersive Sticky Mode in `MainActivity` to hide the Carlinkit Tbox Ambient system navigation dock bar by default while allowing drivers to access system controls via a transient edge swipe. Re-engineer `AppNavigationRail` into a compact 58dp automotive navigation column with vertically centered, icon-only touch targets ordered as: `Nearby` ➔ `Favorites` ➔ `Settings` ➔ `Refresh`.

## Requirements

### Functional
- [x] In `MainActivity.kt`, implement edge-to-edge immersive sticky mode:
  - `WindowCompat.setDecorFitsSystemWindows(window, false)`
  - `WindowInsetsControllerCompat.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
  - `WindowInsetsControllerCompat.hide(WindowInsetsCompat.Type.systemBars())`
  - Re-apply automatically inside `onWindowFocusChanged(hasFocus: Boolean)` when `hasFocus == true`.
- [x] In `AppNavigationRail.kt`, revamp the navigation column:
  - Fix width to `58.dp` (reduced from 72.dp to give 14dp more horizontal screen estate to station lists).
  - Arrange items vertically centered: `Arrangement.Center` inside `fillMaxHeight()`.
  - Display icon-only large touch targets (26-28dp icon within $\ge 48-52dp$ touch container) with subtle active indicators.
  - Provide 4 actions in strict sequence:
    1. **Nearby:** Selects `AppTab.NEARBY`
    2. **Favorites:** Selects `AppTab.FAVORITES`
    3. **Settings:** Invokes `onSettingsClick` (opens `RoutingSettingsModal`)
    4. **Refresh:** Invokes `onRefreshClick` (refreshes data for current tab), with animated rotation when `isRefreshing == true`.

### Non-Functional
- [x] Safe fallback for non-automotive or standard devices without breaking portrait behavior.
- [x] Meets automotive glanceability and touch ergonomics standards.
- [x] Single file-based verification test.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/MainActivity.kt`:
   - Add helper method `applyImmersiveMode()` utilizing `WindowCompat` and `WindowInsetsControllerCompat`.
   - Invoke `applyImmersiveMode()` during `onCreate` and inside `onWindowFocusChanged(hasFocus = true)`.
2. [x] Redesign `app/src/main/java/com/evcs/favorites/navigation/AppNavigationRail.kt`:
   - Accept parameters:
     - `currentTab: AppTab`
     - `onTabSelected: (AppTab) -> Unit`
     - `onSettingsClick: () -> Unit`
     - `onRefreshClick: () -> Unit`
     - `isRefreshing: Boolean = false`
     - `modifier: Modifier = Modifier`
   - Implement `Modifier.width(58.dp)` and vertically centered `Column`.
   - Render 4 items: `Nearby` tab, `Favorites` tab, `Settings` button, `Refresh` button with rotation animation.
3. [x] Create single verification test `app/src/test/java/com/evcs/favorites/navigation/LandscapeNavigationRailTest.kt`:
   - Test rail contract constants (`RAIL_WIDTH_DP = 58.dp`).
   - Test action order sequence (`NEARBY` -> `FAVORITES` -> `SETTINGS` -> `REFRESH`).
   - Test click handlers dispatching appropriate events (`onTabSelected`, `onSettingsClick`, `onRefreshClick`).
   - Test refresh loading animation state mapping.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [MODIFY] Configure Immersive Sticky Mode & pass navigation rail callbacks
- `app/src/main/java/com/evcs/favorites/navigation/AppNavigationRail.kt` - [MODIFY] Overhaul to 58dp centered icon-only rail
- `app/src/test/java/com/evcs/favorites/navigation/LandscapeNavigationRailTest.kt` - [NEW] Single comprehensive verification test

## Single Verification Test
- **Test Class:** `com.evcs.favorites.navigation.LandscapeNavigationRailTest`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.navigation.LandscapeNavigationRailTest"
  ```

---
Next Phase: `phase-03-dedicated-landscape-screens.md`
