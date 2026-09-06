# Phase 03: Settings Custom Filter Modal & Validation
Status: ✅ Completed
Dependencies: [Phase 02: Persistence & ViewModel Filter Pipeline](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1138-smart-filter-ac-dc-custom/phase-02-persistence-and-viewmodel-filter-pipeline.md)

## Objective
Integrate a dedicated "Bộ lọc tùy chỉnh (Custom Filter)" section into the Settings modal (`RoutingSettingsModal.kt`) featuring:
- A clear, visible scrollbar indicator (`Modifier.verticalScrollbar` via `drawWithContent`) ensuring intuitive scroll awareness.
- A dedicated numeric keypad with `kW` suffix, 1-tap clear buttons (`[ ✕ ]`), and seamless next/done focus navigation.
- An instant human-readable "Live Preview" line (*"👉 Đang lọc: Trạm có cổng từ 60 kW đến 150 kW còn trống"*) giving continuous real-time clarity.
- Strict mutual exclusion between Quick Chips and manual Min/Max text inputs, real-time numeric validation with inline red warnings, and an active Save button persisting to encrypted preferences.

## Requirements
### Functional
- [x] Add Custom Filter section to `RoutingSettingsModal.kt`:
  - Clearly sectioned with header "Bộ lọc tùy chỉnh (Custom Filter)".
  - Scrollable container equipped with a clear visible scrollbar indicator (`Modifier.verticalScrollbar(scrollState)` using `drawWithContent` with alpha fade during scroll).
- [x] Quick Chip selection:
  - Renders options: `[Tất cả]`, `[🔌 AC]`, `[⚡ DC ≤ 30kW]`, `[⚡ DC 30-60kW]`, `[⚡ DC ≥ 60kW]`, `[⚡ DC ≥ 120kW]`.
  - Selecting a quick chip clears/deselects manual Min/Max text input fields and immediately updates the Live Preview.
- [x] Number Keyboard & Min/Max input utilities:
  - Two text fields: `Min kW` and `Max kW`.
  - `KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)` on `Min kW` (auto-focuses `Max kW`).
  - `KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)` on `Max kW`.
  - Fixed suffix text `"kW"` inside the input field for instant unit clarity.
  - Inline clear button `[ ✕ ]` (`IconButton`) visible whenever field is non-empty, clearing value in 1 tap.
  - Digits-only keyboard filtering; limits values to positive integers in range `1`..`500` kW.
  - Typing in Min or Max clears/deselects quick chip mode.
  - Interpretations:
    - Min provided, Max empty -> `X ≥ Min kW`.
    - Max provided, Min empty -> `X ≤ Max kW`.
    - Both provided -> `Min kW ≤ X ≤ Max kW`.
- [x] User-Friendly "Live Preview" row:
  - Dynamic preview pill/card rendered beneath the selection controls displaying plain-language explanation:
    - Quick chips: e.g. *"👉 Đang lọc: Cổng AC từ 3.5kW - 22kW còn trống"* or *"👉 Đang lọc: Cổng DC ≥ 60kW còn trống"*.
    - Manual range: *"👉 Đang lọc: Cổng từ 60 kW đến 150 kW còn trống"*, *"👉 Đang lọc: Cổng công suất ≥ 60 kW còn trống"*.
    - Updates in real-time as user types or switches chips.
- [x] Validation and error presentation:
  - If `minKw > maxKw` (e.g. Min = 120, Max = 60), display inline red warning text: *"Công suất tối thiểu không được lớn hơn công suất tối đa"*.
  - Disable the "Lưu cài đặt / Save" button whenever validation fails, input is invalid, or both fields are empty in custom range mode.
- [x] Save interaction:
  - Dedicated Save button in card (or modal save action) persists valid `CustomFilterConfig` into `SmartFilterPreferences` and triggers `onSaveCustomFilter`.

### Non-Functional
- [x] Clean Material 3 design with emerald accent (#10B981) matching app design language.
- [x] Proper keyboard actions (`ImeAction.Next`, `ImeAction.Done`) and numeric input filtering.

## Implementation Steps
1. [x] Create `VerticalScrollbar.kt` in `com.evcs.favorites.ui.components` implementing `Modifier.verticalScrollbar(scrollState: ScrollState)`.
2. [x] Create `CustomFilterSettingsCard.kt` in `com.evcs.favorites.ui.components` with Quick Chips, numeric inputs with `kW` suffix and clear button, and real-time Live Preview pill.
3. [x] Integrate `CustomFilterSettingsCard` and `Modifier.verticalScrollbar` into `RoutingSettingsModal.kt`.
4. [x] Pass `customFilterConfig` and `onSaveCustomFilter` callbacks from `MainActivity` / `NearbyScreen` to `RoutingSettingsModal`.
5. [x] Create single comprehensive test file `CustomFilterSettingsValidationTest.kt` verifying:
   - Quick chip selection mode toggles correctly and clears manual text fields.
   - Typing numbers in Min/Max clears quick chip selection.
   - Clear icon clears text and resets state to empty.
   - Live preview dynamic description updates accurately for quick chips and manual numbers.
   - Validation fails and error is displayed when Min > Max.
   - Save action creates valid `CustomFilterConfig` for Min only, Max only, and Min+Max.
   - Values <= 0 or > 500 are rejected by the validator.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/VerticalScrollbar.kt` - [NEW] DrawWithContent custom scrollbar modifier.
- `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt` - [NEW] Custom filter settings component with live preview.
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` - [MODIFY] Add custom filter section with scrollbar.
- `app/src/test/java/com/evcs/favorites/ui/components/CustomFilterSettingsValidationTest.kt` - [NEW] Single comprehensive test file.

## Test Criteria
- [x] Run only `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.CustomFilterSettingsValidationTest`
- [x] All test cases pass with zero failures.

---
Next Phase: [Phase 04: Nearby Screen Smart Filter UI & Animation](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1138-smart-filter-ac-dc-custom/phase-04-nearby-screen-smart-filter-ui-and-animation.md)
