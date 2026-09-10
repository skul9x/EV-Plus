# Phase 03: Commercial Branding & Copyright Footer

Status: ✅ Completed
Dependencies: [Phase 02: Automotive Power Filter Steppers](phase-02-automotive-power-filter-stepper.md)

## Objective
Lock the commercial version in `AboutAppInfo` to `"1.0"` (replacing `"v4.1.0-commercial"`), display "Phiên bản thương mại: 1.0" in `AboutAppCard`, anchor a persistent "Copyright 2026" footer at the bottom of the settings screen across both landscape and portrait layouts, and update existing test suites to align with these requirements.

## Requirements

### Functional
- In `AboutAppInfo`: Update `APP_VERSION` from `"v4.1.0-commercial"` to `"1.0"`.
- In `AboutAppCard`: Display commercial version prominently as `1.0` and author as `Nguyễn Duy Trường`.
- Anchor a dedicated Copyright 2026 footer at the bottom of the Settings screen:
  - In Landscape mode: Anchored at the bottom of the Left Master Sidebar (below "Đặt lại mặc định") or sticky at the screen bottom:
    `© 2026 Nguyễn Duy Trường • Phiên bản 1.0`
  - In Portrait mode: Anchored or appended at the bottom of the Settings screen list:
    `© 2026 Nguyễn Duy Trường • Phiên bản 1.0`
- Update existing `SettingsScreenComponentsAndAutoSaveTest.kt` line 298: change expected version assertion from `"v4.1.0-commercial"` to `"1.0"`.

### Non-Functional
- Copyright text must be readable and understated (`labelSmall`, `onSurfaceVariant`).

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt`:
   - Update `AboutAppInfo.APP_VERSION = "1.0"`.
2. In `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt`:
   - Add persistent bottom copyright footer composable `SettingsCopyrightFooter`.
   - Embed footer in `SettingsScreenLandscape` sidebar footer area and `SettingsScreenPortrait` bottom container.
3. Update `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenComponentsAndAutoSaveTest.kt`:
   - Assert `assertEquals("1.0", AboutAppInfo.APP_VERSION)`.
4. Create exactly one comprehensive file-based verification test:
   `app/src/test/java/com/evcs/favorites/ui/screens/CommercialBrandingAndCopyrightContractTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt` - [MODIFY] Update version to "1.0".
- `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt` - [MODIFY] Add anchored copyright 2026 footer.
- `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenComponentsAndAutoSaveTest.kt` - [MODIFY] Align expected version string to "1.0".
- `app/src/test/java/com/evcs/favorites/ui/screens/CommercialBrandingAndCopyrightContractTest.kt` - [NEW] Phase 03 contract test.

## Test Criteria
- Verify `AboutAppInfo.APP_VERSION` is `"1.0"`.
- Verify `AboutAppInfo.COPYRIGHT` contains `"2026"` and `"Nguyễn Duy Trường"`.
- Verify `SettingsScreen.kt` contains the copyright footer in both landscape and portrait layouts.
- Verify all unit tests pass with `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.CommercialBrandingAndCopyrightContractTest"`.
