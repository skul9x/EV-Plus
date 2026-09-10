# Phase 01: Navigation Core & Top-Level Tab Integration
Status: 🟩 Completed
Dependencies: None

## Objective
Promote Settings to a top-level application destination (`AppTab.SETTINGS`), wire it into both `AppNavigationBar` (Portrait) and `AppNavigationRail` (Landscape), configure standard back stack navigation returning to `AppTab.NEARBY`, and remove redundant Settings gear icons from `NearbyScreen` and `FavoritesScreen` TopAppBars.

## Requirements
### Functional
- [x] Add `SETTINGS` entry to `AppTab` enum with label `"Cài đặt"`, `selectedIcon = Icons.Filled.Settings`, and `unselectedIcon = AppIcons.Settings`.
- [x] Preserve strict `AppTab` sequence: `NEARBY`, `FAVORITES`, `SETTINGS`.
- [x] Automatically render all 3 tabs in `AppNavigationBar` (Portrait Bottom Bar) with Emerald styling and active pill indicators.
- [x] Update `AppNavigationRail` and `AppNavigationRailHelper.handleRailAction`: clicking `SETTINGS` dispatches `onTabSelected(AppTab.SETTINGS)`.
- [x] Update `RailIconButton` for `SETTINGS` in `AppNavigationRail` to reflect active selection state (`isSelected = isTabSelected(AppTab.SETTINGS, currentTab)`).
- [x] Keep `REFRESH` button functional on `AppNavigationRail` to maintain EVCS telemetry refresh capability.
- [x] In `MainActivity`, handle back press: when `currentTab == AppTab.SETTINGS`, back press returns to `AppTab.NEARBY`. When `currentTab == AppTab.NEARBY`, back press triggers normal system exit.
- [x] Remove `showRoutingSettingsModal` dialog overlay state and popup invocations from `MainActivity`.
- [x] Remove the Settings gear `IconButton` from `NearbyScreen` TopAppBar actions.
- [x] Remove the Settings gear `IconButton` from `FavoritesScreen` TopAppBar actions.
- [x] Update `CustomConfigPromptDialog` setup action to select `AppTab.SETTINGS`.

### Non-Functional
- [x] Maintain 100% backward compatibility for all existing rail actions (`HOME`, `NEARBY`, `FAVORITES`, `REFRESH`).
- [x] Ensure seamless state preservation across tab switches using `SaveableStateHolder`.

## Implementation Steps
1. [x] Update `AppTab.kt`: Add `SETTINGS` with label `"Cài đặt"`, `selectedIcon = Icons.Filled.Settings`, and `unselectedIcon = AppIcons.Settings`.
2. [x] Update `AppNavigationRail.kt`:
   - Refactor `handleRailAction` so `NavigationRailAction.SETTINGS` invokes `onTabSelected(AppTab.SETTINGS)`.
   - Update `RailIconButton` for `SETTINGS` to bind `isSelected = AppNavigationRailHelper.isTabSelected(AppTab.SETTINGS, currentTab)`.
   - Update `AppNavigationRailDefaults.ACTION_ORDER` and helper methods if needed.
3. [x] Update `MainActivity.kt`:
   - Remove `showRoutingSettingsModal` state.
   - Update `BackHandler`: if `currentTab != AppTab.NEARBY`, switch `currentTab = AppTab.NEARBY`.
   - Wire `AppTab.SETTINGS` placeholder in `when (currentTab)`.
4. [x] Clean up TopAppBars:
   - Remove settings `IconButton` from `NearbyScreen.kt`.
   - Remove settings `IconButton` from `FavoritesScreen.kt`.
   - Update `CustomConfigPromptDialog.kt` to trigger tab selection.
5. [x] Create single comprehensive test file in `app/src/test/java/com/evcs/favorites/navigation/SettingsNavigationTabIntegrationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/navigation/AppTab.kt` - Add `SETTINGS` entry to enum.
- `app/src/main/java/com/evcs/favorites/navigation/AppNavigationRail.kt` - Wire settings selection and active indicator pill.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Wire tab destination and back navigation.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - Remove TopAppBar settings icon.
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - Remove TopAppBar settings icon.
- `app/src/test/java/com/evcs/favorites/navigation/SettingsNavigationTabIntegrationTest.kt` - [NEW] Single comprehensive test for Phase 01.

## Test Criteria
- Exactly one comprehensive test file: `SettingsNavigationTabIntegrationTest.kt` verifying:
  1. `AppTab` enum contains exactly 3 entries (`NEARBY`, `FAVORITES`, `SETTINGS`) with correct labels and icons.
  2. `AppNavigationRailHelper.isTabSelected` returns true for `AppTab.SETTINGS` when active.
  3. `AppNavigationRailHelper.handleRailAction` for `SETTINGS` correctly triggers tab selection `AppTab.SETTINGS`.
  4. Back navigation logic contract: non-nearby tabs navigate back to `NEARBY`, while `NEARBY` does not intercept system exit.
  5. Absence of settings gear icon in `NearbyScreen` and `FavoritesScreen` TopAppBars.

---
Next Phase: [Phase 02: Commercial Settings Screen Components & Auto-Save](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260910-1332-standalone-commercial-settings-screen/phase-02-settings-screen-layout-and-components.md)
