# Phase 02: Persistence & ViewModel Filter Pipeline
Status: ✅ Completed
Dependencies: [Phase 01: Domain Smart Filter Models & Engine](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1138-smart-filter-ac-dc-custom/phase-01-domain-smart-filter-models-and-engine.md)

## Objective
Implement secure encrypted storage for smart filter states and integrate the new filter modes, DC tier selections, and Custom filter execution within `NearbyViewModel` and `NearbyUiState`.

## Requirements
### Functional
- [x] Create `SmartFilterPreferences`:
  - Persists active filter mode (`SmartFilterMode`).
  - Persists active DC tier (`DcWattageTier?`).
  - Persists user custom configuration (`CustomFilterConfig?`).
  - Provides `hasCustomConfig(): Boolean` to verify if the user has established custom preferences.
  - Backed by `SessionStorage` (`EncryptedSharedPrefsStorage` for production, `InMemorySessionStorage` for unit tests).
- [x] Extend `NearbyUiState`:
  - `activeFilterMode: SmartFilterMode` (default `SmartFilterMode.NONE`).
  - `selectedDcTier: DcWattageTier?` (default `null`).
  - `isDcSubFilterVisible: Boolean` (default `false`).
  - `savedCustomConfig: CustomFilterConfig?` (default `null`).
  - `showCustomConfigPrompt: Boolean` (default `false`).
  - Helper/Derived property `filterSummaryPillText: String`: Generates dynamic feedback for the info pill above stations list ("Top 10 trạm sạc VinFast gần nhất còn cổng trống", "Tìm thấy X trạm có cổng AC khả dụng", "Tìm thấy X trạm có cổng DC [Tier] khả dụng", "Tìm thấy X trạm theo bộ lọc tùy chỉnh").
- [x] Implement ViewModel intent handlers in `NearbyViewModel`:
  - `toggleAcFilter()`: If AC active, resets to `NONE`; if not active, sets to `AC` and triggers pipeline.
  - `enterDcMode()`: Sets `isDcSubFilterVisible = true`, `activeFilterMode = DC`, without filtering until a tier is selected.
  - `exitDcMode()`: Sets `isDcSubFilterVisible = false`, resets `selectedDcTier = null`, sets `activeFilterMode = NONE`, and triggers pipeline with unfiltered stations.
  - `selectDcTier(tier: DcWattageTier)`: Sets `selectedDcTier = tier`, updates preferences, and triggers filter pipeline.
  - `applyCustomFilter()`: Checks `prefs.hasCustomConfig()`. If configured, sets `activeFilterMode = CUSTOM` and executes pipeline. If not configured, sets `showCustomConfigPrompt = true`.
  - `saveAndApplyCustomFilter(config: CustomFilterConfig)`: Persists new config and directly applies `CUSTOM` mode (called when returning from Settings modal setup).
  - `dismissCustomPrompt()`: Clears `showCustomConfigPrompt = false`.
  - `clearSmartFilter()`: Resets filter to `NONE` and refreshes pipeline.
  - On app launch, initializes with persisted filter mode from previous session (restoring `isDcSubFilterVisible = true` if persisted mode was `DC` with a selected tier).

### Non-Functional
- [x] Coroutine dispatching on TestDispatcher / IO Dispatcher without thread blocking.
- [x] State updates are atomic via `_uiState.update { ... }`.

## Implementation Steps
1. [x] Create `SmartFilterPreferences.kt` in `com.evcs.favorites.data.preferences`.
2. [x] Update `NearbyUiState.kt` with smart filter properties.
3. [x] Update `NearbyViewModel.kt` with smart filter intent handlers and pipeline integration.
4. [x] Create single comprehensive unit test file `NearbyViewModelSmartFilterTest.kt` verifying:
   - Initial state loads persisted filter from preferences, restoring DC sub-filter when persisted mode was DC.
   - Toggling AC mode applies AC filter, toggling again resets to unfiltered.
   - Entering DC mode exposes DC sub-filter without filtering; selecting DC tier filters strictly by tier.
   - Exiting DC mode resets filter state and returns to 3-button mode.
   - Clicking Custom without saved configuration triggers `showCustomConfigPrompt`.
   - Clicking Custom with saved configuration executes custom filter pipeline.
   - `saveAndApplyCustomFilter` saves configuration and immediately activates custom filter pipeline.
   - `filterSummaryPillText` dynamically reflects station counts and active filter descriptions.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/preferences/SmartFilterPreferences.kt` - [NEW] Filter preferences storage.
- `app/src/main/java/com/evcs/favorites/ui/state/NearbyUiState.kt` - [MODIFY] Smart filter UI state.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - [MODIFY] Intent handlers & pipeline integration.
- `app/src/test/java/com/evcs/favorites/ui/viewmodel/NearbyViewModelSmartFilterTest.kt` - [NEW] Single comprehensive test file.

## Test Criteria
- [x] Run only `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.NearbyViewModelSmartFilterTest`
- [x] All test cases pass with zero failures.

---
Next Phase: [Phase 03: Settings Custom Filter Modal & Validation](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1138-smart-filter-ac-dc-custom/phase-03-settings-custom-filter-modal.md)
