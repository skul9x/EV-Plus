# Phase 01: AC Focus Button Suppression & Adaptive Button Layout
Status: ✅ Completed
Dependencies: None

## Objective
Implement station DC port capability detection and adjust station detail UI components so that:
1. Pure AC stations (stations with no DC ports) hide the Focus action button across all views (Nearby, Favorites, Search).
2. When the user is actively filtering by AC, the Focus button is also suppressed for stations opened under that filter.
3. In landscape mode, switching filter mode from AC to DC or All immediately restores the Focus button for DC-capable stations.
4. When the Focus button is hidden, the "Chỉ Đường" (Navigate) action button expands to full width (`fillMaxWidth()`).
5. Ensure synchronization between portrait bottom sheet (`NativeStationDetailSheet`) and landscape detail pane (`NativeStationDetailContent`).

## Requirements
### Functional
- [x] Add `Station.hasDcPorts(): Boolean` extension function in `NearbyStationFilter.kt` (or domain model):
  - Returns true if `powers.any { it.isDc() }`.
  - If `powers` is empty and `connectors` is not blank, parses connectors via `EvcsRepository.parseConnectorsToPowers(connectors)` and checks if any parsed port satisfies `it.isDc()`.
- [x] Add helper function `NativeStationDetailSheetHelper.shouldShowFocusButton(station: Station, isAcFilterActive: Boolean): Boolean`:
  - Returns `station.hasDcPorts() && !isAcFilterActive`.
  - Returns `false` for any station with only AC ports (11kW, 22kW, etc.) even when `isAcFilterActive == false`.
  - Returns `false` when `isAcFilterActive == true` regardless of station power mix.
  - Returns `true` when the station has at least one DC port and `isAcFilterActive == false`.
- [x] In `NativeStationDetailContent`:
  - Accept parameter `isAcFilterActive: Boolean = false`.
  - Determine `val showFocusButton = NativeStationDetailSheetHelper.shouldShowFocusButton(station, isAcFilterActive)`.
  - If `showFocusButton`:
    - Primary button row renders `[ Chỉ Đường ]` with `Modifier.weight(1f)`.
    - Primary button row renders `[ Focus ]` with `Modifier.weight(1f)`.
  - If `!showFocusButton`:
    - Primary button row renders `[ Chỉ Đường ]` with `Modifier.fillMaxWidth()`.
    - `[ Focus ]` button is completely omitted from the composition tree.
- [x] In `NativeStationDetailSheet`:
  - Accept parameter `isAcFilterActive: Boolean = false` and forward it to `NativeStationDetailContent`.
- [x] In `NearbyScreen.kt`:
  - Compute `val isAcActive = uiState.activeFilterMode == SmartFilterMode.AC || (uiState.activeFilterMode == SmartFilterMode.CUSTOM && uiState.savedCustomConfig?.mode == CustomFilterMode.QUICK_CHIP && uiState.savedCustomConfig?.quickChip == QuickChipOption.AC)`.
  - Pass `isAcFilterActive = isAcActive` to `NativeStationDetailSheet`.
- [x] In `FavoritesScreen.kt`:
  - Pass `isAcFilterActive = false` to `NativeStationDetailSheet` (ensures pure AC stations still hide Focus, while DC stations show Focus).
- [x] In `NearbyLandscapeScreen.kt`:
  - Compute `isAcActive` from `uiState` and forward `isAcFilterActive = isAcActive` to `NativeStationDetailContent`.
  - When the user selects another filter chip (e.g. DC or All), `uiState.activeFilterMode` changes and `NativeStationDetailContent` recomposes with the updated `isAcFilterActive` state.
- [x] In `FavoritesLandscapeScreen.kt`:
  - Forward `isAcFilterActive = false` to `NativeStationDetailContent`.

### Non-Functional
- [x] Performance: Zero extra heap allocations during recomposition; pure boolean checks and memoized connector parsing.
- [x] Accessibility & Automotive ergonomics: Expanding "Chỉ Đường" to full width when Focus is hidden gives drivers a large, easily tapable target (>56dp height, full screen/panel width).

## Implementation Steps
1. [x] Add `Station.hasDcPorts()` to `NearbyStationFilter.kt` with testable fallback handling.
2. [x] Add `NativeStationDetailSheetHelper.shouldShowFocusButton` in `NativeStationDetailSheet.kt`.
3. [x] Update `NativeStationDetailContent` and `NativeStationDetailSheet` signatures and action button row layout.
4. [x] Wire `isAcFilterActive` in `NearbyScreen.kt`, `FavoritesScreen.kt`, `NearbyLandscapeScreen.kt`, and `FavoritesLandscapeScreen.kt`.
5. [x] Create single comprehensive test file `app/src/test/java/com/evcs/favorites/ui/components/StationDetailAcFocusButtonSuppressionTest.kt`.
6. [x] Run the single test file to verify implementation.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - [MODIFY] Add `Station.hasDcPorts()`.
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - [MODIFY] Add helper decision logic, `isAcFilterActive` param, and dynamic `fillMaxWidth()` / `weight(1f)` button layout.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [MODIFY] Forward `isAcFilterActive` to `NativeStationDetailSheet`.
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - [MODIFY] Forward `isAcFilterActive = false` to `NativeStationDetailSheet`.
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt` - [MODIFY] Forward `isAcFilterActive` to `NativeStationDetailContent`.
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/FavoritesLandscapeScreen.kt` - [MODIFY] Forward `isAcFilterActive = false` to `NativeStationDetailContent`.
- `app/src/test/java/com/evcs/favorites/ui/components/StationDetailAcFocusButtonSuppressionTest.kt` - [NEW] Single comprehensive test verifying Focus button suppression across pure AC, DC, hybrid stations, filter toggle, and layout modifiers.

## Test Criteria
- [x] Pure AC station (powers contain only 11kW / 22kW AC ports) returns `hasDcPorts() == false`.
- [x] `shouldShowFocusButton` returns `false` for pure AC station when `isAcFilterActive == false`.
- [x] `shouldShowFocusButton` returns `false` for pure AC station when `isAcFilterActive == true`.
- [x] DC-only station returns `hasDcPorts() == true` and `shouldShowFocusButton == true` when `isAcFilterActive == false`.
- [x] Hybrid station (DC + AC ports) returns `hasDcPorts() == true` and `shouldShowFocusButton == true` when `isAcFilterActive == false`.
- [x] Hybrid station returns `shouldShowFocusButton == false` when `isAcFilterActive == true`.
- [x] Station with empty powers falls back to connector string parsing to accurately determine DC capability.
- [x] Landscape filter transition (switching from AC filter to NONE or DC) toggles `shouldShowFocusButton` from `false` to `true` for hybrid/DC stations.

---
Next Phase: [phase-02-portrait-summary-pill-removal-and-centered-routing-indicator.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-1920-ac-focus-button-and-portrait-summary-cleanup/phase-02-portrait-summary-pill-removal-and-centered-routing-indicator.md)
