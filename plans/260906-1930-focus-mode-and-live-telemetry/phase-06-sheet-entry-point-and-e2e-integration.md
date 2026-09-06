# Phase 06: Sheet Entry Point & E2E Integration

Status: ✅ Completed
Dependencies: Phase 01, Phase 02, Phase 03, Phase 04, Phase 05

## Objective
Wire the entire Focus Mode workflow into the main application UI by adding the `[⚡ Focus Mode]` button to `NativeStationDetailSheet`, introducing an informative overlay permission onboarding dialog, binding Google Maps navigation launch with `FocusModeForegroundService` startup, and ensuring full end-to-end integration and dependency injection across `AppContainer`.

## Requirements
### Functional
- [x] Add `[⚡ Focus Mode]` button into `NativeStationDetailSheet.kt` (positioned adjacent to "Chỉ đường" or inside the quick action row).
- [x] Show `FocusModePermissionDialog` on first click if `Settings.canDrawOverlays` is not granted, giving users the choice to "Cấp quyền (Cửa sổ nổi)" or "Dùng thông báo (Không cần quyền)".
- [x] On activation:
  1. Start `FocusModeForegroundService` with the selected station ID and coordinates.
  2. Launch Google Maps navigation intent pointing to the station's latitude/longitude.
- [x] Register `FocusModeForegroundService` in `AndroidManifest.xml` with appropriate foreground service types (`dataSync` / `specialUse` / `location`).
- [x] Update `AppContainer.kt` to provide dependencies (`HereOAuthManager`, `HereEvApiClient`, `FocusModeTelemetryEngine`).

### Non-Functional
- [x] Clean back-stack transition: tapping back or switching apps keeps Focus Mode alive until closed with `[X]`.
- [x] No regression on existing station detail sheet operations (refresh, favorite, share, photo carousel).

## Implementation Steps
1. Add `FocusModePermissionDialog.kt` composable.
2. Update `NativeStationDetailSheet.kt` quick action row to include the Focus Mode trigger.
3. Update `AndroidManifest.xml` to declare `FocusModeForegroundService` and `SYSTEM_ALERT_WINDOW` permission.
4. Wire DI in `AppContainer.kt`.
5. Execute end-to-end integration test.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/FocusModePermissionDialog.kt` - [Onboarding dialog for overlay permission]
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - [Add Focus Mode action button]
- `app/src/main/AndroidManifest.xml` - [Service and permission declarations]
- `app/src/main/java/com/evcs/favorites/di/AppContainer.kt` - [Service & client singletons]

## Test Verification (Single Test per Phase)
- Exactly one comprehensive test file:
  - `app/src/test/java/com/evcs/favorites/focus/FocusModeEndToEndIntegrationTest.kt`
- Test cases covered:
  - Validates full end-to-end pipeline: Sheet trigger -> Intent creation -> Service start intent building -> State pipeline setup.
  - Validates permission dialog action routing (accept opens system settings; decline initiates notification fallback).
  - Validates coexistence and preservation of all station detail sheet actions.

---
Plan Complete. Next: Ready for execution via `/code phase-01`.
