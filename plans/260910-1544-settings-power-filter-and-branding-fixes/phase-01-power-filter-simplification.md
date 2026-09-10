# Phase 01: Power Filter Simplification in Settings

**Status:** ✅ Completed  
**Target File:** `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt`

---

## Objective
Remove the "Chọn nhanh loại cổng / công suất" quick chip selector from `CustomFilterSettingsCard` when rendered in the Settings screen, leaving only the automotive stepper controls (`+/- 10kW`) for Min/Max custom power range. Update the card header subtitle to reflect custom kW range configuration.

---

## Requirements

### Functional
1. **Remove / Hide Quick Chips in Settings:**
   - In `CustomFilterSettingsCard`, add a parameter `showQuickChips: Boolean = false` (defaulting to `false` for Settings).
   - When `showQuickChips` is false, hide the text `"Chọn nhanh loại cổng / công suất:"` and the `FlowRow` containing `QuickChipOption` chips.
2. **Update Header Subtitle:**
   - Update header description subtitle from `"Lọc nhanh AC/DC hoặc tự đặt công suất kW mong muốn"` to `"Tùy chỉnh khoảng công suất kW mong muốn"`.
3. **Preserve Automotive Stepper Controls:**
   - Ensure the automotive steppers (`AutomotiveStepperBox`) for Min kW and Max kW with `+/- 10kW` buttons remain functional and prominently displayed.
4. **Compatibility:**
   - Retain full compatibility with `RoutingSettingsModal.kt` and `SettingsScreen.kt`.

### Non-Functional
- Large touch targets ($\ge 56\text{dp}$) maintained for automotive in-cabin usability.
- Zero regression on auto-save invocation when stepping kW values.

---

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt`:
   - Add parameter `showQuickChips: Boolean = false` to `CustomFilterSettingsCard`.
   - Update the card subtitle to `"Tùy chỉnh khoảng công suất kW mong muốn"`.
   - Wrap the quick chips header and `FlowRow` inside `if (showQuickChips) { ... }`.
2. In `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt`:
   - Ensure `CustomFilterSettingsCard` is invoked with `showQuickChips = false` (default) across both portrait and landscape layouts.
3. Create test file `app/src/test/java/com/evcs/favorites/ui/screens/SettingsPowerFilterSimplificationTest.kt` to verify:
   - `showQuickChips` parameter exists and defaults to `false`.
   - The subtitle text matches `"Tùy chỉnh khoảng công suất kW mong muốn"`.
   - `AutomotiveStepperBox` is present and utilized.

---

## Files to Create / Modify
- `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt` - [MODIFY]
- `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt` - [MODIFY]
- `app/src/test/java/com/evcs/favorites/ui/screens/SettingsPowerFilterSimplificationTest.kt` - [NEW] (Phase 01 Single Verification Test)

---

## Verification Test
- **Test File:** `app/src/test/java/com/evcs/favorites/ui/screens/SettingsPowerFilterSimplificationTest.kt`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.SettingsPowerFilterSimplificationTest"
  ```
