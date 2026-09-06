# Phase 03: Settings Modal UX Optimization, MainActivity Wiring & Contextual Deep-Links
Status: ✅ Completed
Dependencies: [Phase 02: Interactive Guide Dialog UI Component & Presenter](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-google-maps-api-key-guide-and-settings-ux/phase-02-interactive-guide-ui-component.md)

## Objective
Integrate the interactive Google Maps API key guide directly into `RoutingSettingsModal.kt`, wire full end-to-end persistent BYOK storage and validation in `MainActivity.kt`, and optimize the Settings UI/UX. This includes adding an actionable Guide Entry Card, a one-tap clipboard paste & clear button, contextual Cloud Console deep links on validation errors, and `RoutingSettingsHelper.kt` for pure Kotlin testability on JVM.

## Requirements
### Functional
- **Settings Modal Guide Integration (`RoutingSettingsModal.kt`)**:
  - Replace the static text card with an actionable Guide Entry Banner:
    - Displays a clickable Card with icon `MenuBook` / `Lightbulb`, title "Hướng dẫn từng bước lấy API Key", subtitle "Miễn phí 10.000 lượt/tháng • 5 bước đơn giản", and a chevron forward icon.
    - Tapping opens the `GoogleApiKeyGuideModal` dialog.
- **API Key Input Field UX Enhancements**:
  - Add a dedicated "Dán" (Paste from Clipboard) action button in or adjacent to the API Key `OutlinedTextField` using `LocalClipboardManager.current`.
  - Automatically sanitize input (trim whitespace, line breaks, carriage returns) via `RoutingSettingsHelper.sanitizeApiKey`.
  - Provide a clear button (`Clear` icon) when the field is non-empty.
- **Smart Validation Error Remediation**:
  - When connection test fails (`KeyValidationState.Invalid`), display:
    - The specific human-friendly error message.
    - Direct Cloud Console action buttons:
      - "Kích hoạt Billing ngay" -> `https://console.cloud.google.com/billing`
      - "Bật Routes API" -> `https://console.cloud.google.com/apis/library/routes.googleapis.com`
      - "Kiểm tra giới hạn khóa" -> `https://console.cloud.google.com/apis/credentials`
    - A "Xem hướng dẫn khắc phục" button opening `GoogleApiKeyGuideModal` with the corresponding step pre-selected.
- **Production Wiring in `MainActivity.kt`**:
  - Pass `RoutingPreferencesManager.create(applicationContext)` to `FavoritesViewModel.provideFactory`.
  - Collect `routingSettings by viewModel.routingSettings.collectAsState()` in `FavoritesApp`.
  - Pass `routingSettings`, `onSaveRoutingSettings = { viewModel.updateRoutingSettings(it) }`, and `onValidateGoogleApiKey = { viewModel.validateGoogleApiKey(it) }` into `FavoritesScreen`.
- **Pure Kotlin Logic Helper (`RoutingSettingsHelper.kt`)**:
  - `sanitizeApiKey(rawKey: String): String`
  - `resolveRemediationForError(errorMessage: String): RemediationAction?`
  - `buildUpdatedSettings(current: RoutingSettings, apiKey: String, engine: RoutingEngineMode, autoFallback: Boolean): RoutingSettings`

### Non-Functional
- Safe URI intent opening (`Intent.ACTION_VIEW`) with fallback if no browser is installed.
- 100% JVM unit-testable business logic via `RoutingSettingsHelper`.

## Implementation Steps
1. Create `RoutingSettingsHelper.kt` handling API key sanitization, error remediation URL mapping, and settings building.
2. Update `RoutingSettingsModal.kt` with the guide launch banner, clipboard paste/clear buttons, and smart remediation error cards.
3. Wire `RoutingPreferencesManager` and `FavoritesViewModel` callbacks into `MainActivity.kt`.
4. Implement `RoutingSettingsModalUxTest.kt` verifying key trimming, remediation link generation, and settings update persistence logic on JVM.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsHelper.kt` - [New] Pure Kotlin helper for settings sanitization and error link resolution
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` - [Modify] UX improvements, guide dialog trigger, paste/clear buttons, error action cards
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [Modify] Wire RoutingPreferencesManager into ViewModel factory and pass callbacks to FavoritesScreen
- `app/src/test/java/com/evcs/favorites/RoutingSettingsModalUxTest.kt` - [New] Single comprehensive file-based test for Phase 03

## Test Criteria (Single Comprehensive Test: `RoutingSettingsModalUxTest.kt`)
- Verify `sanitizeApiKey` correctly strips leading/trailing spaces, newlines, tabs, and carriage returns.
- Verify validation failure with HTTP 403 Billing generates the Billing console URI (`https://console.cloud.google.com/billing`).
- Verify validation failure with HTTP 403 Routes API generates the Routes API Library URI (`https://console.cloud.google.com/apis/library/routes.googleapis.com`).
- Verify validation failure with HTTP 403 Restrictions generates the Credentials URI (`https://console.cloud.google.com/apis/credentials`).
- Verify validation failure with HTTP 429 Quota generates the Quotas URI (`https://console.cloud.google.com/apis/api/routes.googleapis.com/quotas`).
- Verify `buildUpdatedSettings` properly trims API key and updates `preferredEngine` and `autoFallbackEnabled` while preserving custom OSRM URLs.

---
Next Phase: Execution complete after Phase 03 verification.
