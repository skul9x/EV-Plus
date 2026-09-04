# Phase 04: Nearby Screen Smart Filter UI & Animation
Status: ✅ Completed
Dependencies: [Phase 03: Settings Custom Filter Modal & Validation](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1138-smart-filter-ac-dc-custom/phase-03-settings-custom-filter-modal.md)

## Objective
Implement the animated `SmartFilterBar` replacing the legacy 15-chip wattage row in `NearbyScreen.kt` featuring:
- A 3-button selector with distinct icons (`[ 🎯 Custom ]`, `[ ⚡ DC ]`, `[ 🔌 AC ]`) and integrated `[ ✕ ]` cancel buttons.
- A dedicated thumb-zone `[ ← Quay lại ]` back button with 48dp minimum touch target and smooth slide/fade `AnimatedContent` transition to DC power tier chips.
- A styled Material 3 `CustomConfigPromptDialog` with icon header prompting users to configure settings when tapping Custom without a saved filter.
- Dynamic result pill summary reflecting the active filter and station counts in real time.

## Requirements
### Functional
- [x] Create `SmartFilterBar` composable:
  - Mode 1: Top-level 3 buttons with distinctive visual icons:
    - Left: `[ 🎯 Custom ]` (shows sub-label/badge e.g. `Custom • ≥60kW` when configured and active).
    - Center: `[ ⚡ DC ]` (electric lightning icon).
    - Right: `[ 🔌 AC ]` (plug icon).
  - Active state visual styling: Emerald green (`#10B981`) background, high-contrast white text/icons, subtle elevation.
  - Visually integrated `[ ✕ ]` cancel button:
    - When `AC` or `Custom` is active, displays an integrated trailing `[ ✕ ]` icon inside the active button.
    - Tapping either the active button or the `[ ✕ ]` icon resets the filter to show all stations.
  - Mode 2: DC Sub-Filter Row:
    - Animated slide and fade transition (`AnimatedContent` with `togetherWith`) replacing the 3 buttons.
    - One-Handed Thumb-Zone `[ ← Quay lại ]` button:
      - Positioned at leading left with minimum 48dp touch target for effortless one-handed thumb interaction.
      - Displays `Icons.AutoMirrored.Filled.ArrowBack` with "Quay lại" label.
      - Tapping smoothly reverse-slides back to the 3-button row and resets DC mode.
    - Single-select DC power tier chips: `[ ≤ 30kW ]`, `[ 30 - 60kW ]`, `[ ≥ 60kW ]`, `[ ≥ 120kW ]`.
    - Once a chip is tapped, it remains selected; tapping the same chip preserves selection (exit via `[ ← Quay lại ]`).
- [x] Polished `CustomConfigPromptDialog`:
  - When `Custom` is tapped and no custom configuration is saved:
    - Display Material 3 `AlertDialog`:
      - Header icon: `Icons.Default.Tune` centered within a circular `EmeraldContainerDark` badge.
      - Title: "Chưa thiết lập bộ lọc".
      - Message: "Thiết lập công suất sạc ưa thích (kW) để tìm nhanh trạm sạc phù hợp nhất với xe của bạn."
      - Confirm button: "Thiết lập ngay" (`EmeraldPrimary` filled button, dismisses dialog and opens `RoutingSettingsModal`).
      - Dismiss button: "Để sau" (neutral text button).
- [x] Dynamic Result Info Pill:
  - Wire `uiState.filterSummaryPillText` to the header pill above the stations list to display dynamic feedback based on active filter.
- [x] Replace `WattageFilterChipsRow` in `NearbyScreen.kt` with `SmartFilterBar`.

### Non-Functional
- [x] Smooth 60fps animations without recomposition lag or layout jitter.
- [x] Accessible touch targets (minimum 48dp height/width per Material Design guidelines across all buttons).

## Implementation Steps
1. [x] Create `SmartFilterBar.kt` in `com.evcs.favorites.ui.components` featuring icons, integrated `[ ✕ ]` buttons, and thumb-friendly `[ ← Quay lại ]` transition.
2. [x] Create `CustomConfigPromptDialog.kt` in `com.evcs.favorites.ui.components` with Material 3 styled icon badge and dual action buttons.
3. [x] Integrate `SmartFilterBar` and `CustomConfigPromptDialog` into `NearbyScreen.kt`, wiring callbacks to `NearbyViewModel`.
4. [x] Create single comprehensive test file `NearbySmartFilterUiStateTest.kt` verifying:
   - Initial render displays 3 buttons: `[ 🎯 Custom ]`, `[ ⚡ DC ]`, `[ 🔌 AC ]`.
   - Selecting `AC` marks AC button active with integrated `[ ✕ ]` cancel capability.
   - Selecting `DC` triggers animated DC sub-filter with 4 power tier chips and 48dp thumb-zone back button.
   - Selecting a DC tier locks selection on that tier.
   - Tapping Back button exits DC mode back to 3 buttons.
   - Tapping `Custom` with no saved configuration triggers `CustomConfigPromptDialog` state.
   - Dynamic info pill text correctly reflects station count and active filter description.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/SmartFilterBar.kt` - [NEW] Smart filter bar with icons and AnimatedContent.
- `app/src/main/java/com/evcs/favorites/ui/components/CustomConfigPromptDialog.kt` - [NEW] Polished prompt dialog.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [MODIFY] Replace filter row and wire prompt dialog.
- `app/src/test/java/com/evcs/favorites/ui/screens/NearbySmartFilterUiStateTest.kt` - [NEW] Single comprehensive test file.

## Test Criteria
- [x] Run only `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.screens.NearbySmartFilterUiStateTest`
- [x] All test cases pass with zero failures.

---
Next Steps: Phase 04 completed successfully.
