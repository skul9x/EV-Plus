# Phase 02: Commercial Settings Screen Components & Auto-Save
Status: 🟩 Completed
Dependencies: Phase 01

## Objective
Build and modernize the functional cards for `SettingsScreen`, implementing instant Auto-Save persistence for toggles and orientation, providing +/- 10kW stepping controls and an inline Apply button for power filtering, eliminating obsolete HTTP debug logs, and adding an About & Copyright card.

## Requirements
### Functional
- [x] Implement instant auto-save when toggling Startup Screen Orientation (`orientationPrefs.setStartupOrientation(...)`) and updating activity orientation.
- [x] Implement instant auto-save when toggling Voice Announcements (`focusPrefs.setVoiceAlertEnabled(...)`) and updating `FocusModeForegroundService`.
- [x] Implement instant auto-save when tapping quick power filter chips (`ALL`, `AC`, `DC <= 30kW`, `DC 30-60kW`, `DC >= 60kW`, `DC >= 120kW`).
- [x] Emit a floating feedback pill / snackbar (`"Đã lưu cài đặt"`) with smooth enter/exit animation upon every auto-save event.
- [x] Add `+ / - 10kW` stepper buttons adjacent to Min and Max kW text fields to allow quick 1-tap increments/decrements while driving.
- [x] Clamp stepper values safely within bounds (`1` to `500` kW).
- [x] Add an inline `"Áp dụng"` (Apply) button adjacent to the Min/Max kW inputs to commit custom numbers.
- [x] Build `AboutAppCard` presenting EV+ branding, version `v4.1.0-commercial`, `Nguyễn Duy Trường Copyright 2026`, and `skul9x@gmail.com`.
- [x] Provide a "Đặt lại mặc định" (Reset Defaults) dialog resetting orientation to `SYSTEM`, voice alerts to enabled, and custom filter to `ALL`.
- [x] Completely purge `DebugLogViewerCard` and its raw HTTP network logs from the settings UI.

### Non-Functional
- [x] Automotive-grade touch targets ($\ge 48\text{dp}$ touch target padding).
- [x] Zero UI jank or keyboard flicker during custom kW input stepping.

## Implementation Steps
1. [x] Update `CustomFilterSettingsCard.kt`:
   - Add `+ / - 10kW` steppers for Min and Max kW with bounds clamping (`1..500`).
   - Add compact inline `"Áp dụng"` button enabling only when values are valid and dirty.
   - Support auto-save on quick chips selection.
2. [x] Create `AboutAppCard.kt`:
   - Design commercial branding card with app logo, version `v4.1.0-commercial`, copyright notice, and email.
3. [x] Create `SettingsComponents.kt`:
   - Implement `SettingsAutoSaveBadge` (floating pill displaying `"Đã lưu cài đặt"`).
   - Implement `ResetDefaultsConfirmationDialog` with clear warning and confirm/dismiss actions.
   - Refactor `StartupOrientationCard` and `FocusModeVoiceAlertCard` to support immediate auto-save callbacks.
4. [x] Purge `DebugLogViewerCard` embedding from the new settings components.
5. [x] Create single comprehensive test file in `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenComponentsAndAutoSaveTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt` - Add +/- 10kW steppers and inline Apply button.
- `app/src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt` - [NEW] Commercial info and copyright card.
- `app/src/main/java/com/evcs/favorites/ui/components/SettingsComponents.kt` - [NEW] Auto-save feedback pill and reset dialog.
- `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenComponentsAndAutoSaveTest.kt` - [NEW] Single comprehensive test for Phase 02.

## Test Criteria
- Exactly one comprehensive test file: `SettingsScreenComponentsAndAutoSaveTest.kt` verifying:
  1. Stepper helper logic (`stepKw(currentKw, delta, min, max)` correctly increments/decrements by 10 within bounds 1-500).
  2. Auto-save dispatch contract for orientation, voice alerts, and quick chips.
  3. Custom filter validation with inline Apply action.
  4. Reset to defaults logic restoring initial state cleanly.
  5. Absence of `DebugLogViewerCard` in `SettingsScreen`.
  6. About card content verification (author, copyright 2026, email).

---
Next Phase: [Phase 03: Adaptive Layouts (Landscape Master-Detail & Portrait Fullscreen) & Final Cleanup](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260910-1332-standalone-commercial-settings-screen/phase-03-master-detail-auto-save-and-cleanup.md)
