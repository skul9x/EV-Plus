# Phase 02: Automotive Power Filter Steppers

Status: ✅ Completed
Dependencies: [Phase 01: Landscape Scrolling & Visual Hierarchy](phase-01-landscape-scrolling-and-visual-remediation.md)

## Objective
Eliminate numeric on-screen keyboard text fields (`OutlinedTextField`) in landscape mode within `CustomFilterSettingsCard`. Replace them with large-touch automotive stepper buttons (`-10` and `+10`) and a dedicated value display box to prevent virtual keyboards from occluding the automotive screen while driving.

## Requirements

### Functional
- Pass `isLandscape: Boolean = false` (or adaptively branch layout) into `CustomFilterSettingsCard`.
- In landscape mode (`isLandscape == true`):
  - Do NOT render `OutlinedTextField` or trigger soft keyboard (IME).
  - Provide a dedicated, distraction-free automotive display row for Min kW and Max kW:
    - Decrement button: Large `[-]` / `[-10]` button (minimum 56dp height and touch target).
    - Value display: High-contrast card showing current kW (e.g., `30 kW` or `-- kW` if unbounded).
    - Increment button: Large `[+]` / `[+10]` button (minimum 56dp height and touch target).
    - Quick clear icon / button to remove constraint (reset to unbounded).
  - Retain the compact inline "Áp dụng" (Apply) button or automatic commit when stepper changes.
- In portrait mode (`isLandscape == false`): Maintain existing behavior with both numeric text field and steppers.

### Non-Functional
- Adhere to Android Automotive OS design guidelines: minimum touch target of 56dp (ideally 76dp for primary actions) and clear visual distinction between active and disabled states.
- Bounds clamping: values remain strictly clamped within 1..500 kW via `stepKw`.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt`:
   - Add parameter `isLandscape: Boolean = false` to `CustomFilterSettingsCard`.
   - When `isLandscape` is true, render `AutomotiveStepperRow` without `OutlinedTextField` instead of the keyboard text inputs.
   - Provide clear numeric indicators and 56dp touch targets.
2. In `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt`:
   - Pass `isLandscape = true` to `CustomFilterSettingsCard` inside `SettingsScreenLandscape`.
   - Pass `isLandscape = false` to `CustomFilterSettingsCard` inside `SettingsScreenPortrait`.
3. Create exactly one comprehensive file-based verification test:
   `app/src/test/java/com/evcs/favorites/ui/screens/LandscapeCustomFilterStepperContractTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt` - [MODIFY] Add automotive landscape stepper UI without virtual keyboard.
- `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt` - [MODIFY] Pass `isLandscape` to `CustomFilterSettingsCard`.
- `app/src/test/java/com/evcs/favorites/ui/screens/LandscapeCustomFilterStepperContractTest.kt` - [NEW] Phase 02 contract test.

## Test Criteria
- Verify `CustomFilterSettingsCard` accepts `isLandscape` parameter.
- Verify when `isLandscape` is true, no `OutlinedTextField` is instantiated for automotive steppers.
- Verify steppers increment and decrement properly by 10 kW in `CustomFilterFormState`.
- Verify all unit tests pass with `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.LandscapeCustomFilterStepperContractTest"`.

---
Next Phase: [Phase 03: Commercial Branding & Copyright Footer](phase-03-commercial-branding-and-copyright-footer.md)
