# Phase 02: Interactive Guide Dialog UI Component & Presenter
Status: ✅ Completed
Dependencies: [Phase 01: Guide Data Models, Content & Troubleshooting Provider](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-google-maps-api-key-guide-and-settings-ux/phase-01-guide-data-models-and-content.md)

## Objective
Build a rich, accessible, interactive Jetpack Compose Guide Dialog (`GoogleApiKeyGuideModal.kt`) presenting the 5-step Google Cloud guide. Using a customized `Dialog` with high z-index and rounded surface card styling avoids nested `ModalBottomSheet` scrim and gesture conflicts when launched from `RoutingSettingsModal`. The UI features a step indicator / pager, one-tap "Open Console" action links, one-tap clipboard copy for package name/links, an expandable troubleshooting FAQ accordion, and an informative Free Tier safety banner.

## Requirements
### Functional
- **Modal Dialog Component (`GoogleApiKeyGuideModal.kt`)**:
  - Implemented via `androidx.compose.ui.window.Dialog` with `DialogProperties(usePlatformDefaultWidth = false)` and a rounded top/surface card (mimicking a clean bottom sheet / full modal) to cleanly overlay `RoutingSettingsModal` without dimming artifacts.
  - **Header & Free Tier Callout**:
    - Title: "Hướng dẫn lấy Google Maps API Key".
    - Badge: "Miễn phí 10.000 lượt/tháng" with safety note explaining Google's per-SKU free usage allowance on Routes API Essentials and how $0 budget alerts prevent charges.
  - **Step Stepper / Navigation**:
    - Visual step progress indicator (e.g. 5 dots or numbered step bar showing Step 1 of 5).
    - Previous / Next navigation buttons with smooth transitions.
    - Ability to directly jump to any step via step pills or tabs.
  - **Step Content View**:
    - Clear typography, numbered action instructions, and highlighted important notes (`Lưu ý:`).
    - Contextual Action Buttons:
      - "Mở Google Cloud Console" button with external link icon, invoking `onOpenUrl(step.actionUrl)`.
      - "Sao chép Package Name" (`com.evcs.favorites`) button when on Step 4, with instant visual copied feedback, alongside explicit note explaining why Application restriction should be set to "None" for BYOK.
  - **Troubleshooting & FAQ Section**:
    - Expandable accordion at the bottom: "Câu hỏi thường gặp & Khắc phục lỗi (HTTP 400, 403, 429)".
    - Displays all troubleshooting items with clear causes and direct fix buttons.
- **State Management & Testability (`ApiKeyGuideUiState.kt` / `ApiKeyGuidePresenter.kt`)**:
  - Pure Kotlin UI state holder and presenter:
    - Current step index (0..4).
    - Total step count.
    - `canGoBack` / `canGoForward`.
    - Expanded FAQ items set.
    - Clipboard copy status message / feedback.
    - Navigation actions: `nextStep()`, `previousStep()`, `selectStep(index)`, `toggleFaq(item)`, `initialStepForError(errorCode)`.

### Non-Functional
- Adheres to the app's dark mode palette (`DarkCardBackground`, `EmeraldPrimary`, `DarkOutline`, `EmeraldContainerDark`).
- Accessibility friendly with clear content descriptions and touch target sizes (minimum 48dp).

## Implementation Steps
1. Create `ApiKeyGuideUiState.kt` and `ApiKeyGuidePresenter.kt` to manage step pagination, clipboard copy events, error mapping, and troubleshooting visibility.
2. Build Composable `GoogleApiKeyGuideModal.kt` incorporating step indicators, console action buttons, copy helpers, and expandable FAQ cards.
3. Write `ApiKeyGuideUiStateTest.kt` verifying step bounds, state transitions, navigation boundaries, URL triggers, and error-to-initial-step resolution.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/ApiKeyGuideUiState.kt` - [New] State holder and presenter logic for guide navigation
- `app/src/main/java/com/evcs/favorites/ui/components/GoogleApiKeyGuideModal.kt` - [New] Compose Material 3 interactive guide dialog
- `app/src/test/java/com/evcs/favorites/ApiKeyGuideUiStateTest.kt` - [New] Single comprehensive file-based test for Phase 02

## Test Criteria (Single Comprehensive Test: `ApiKeyGuideUiStateTest.kt`)
- Verify initial UI state starts at Step 1 (index 0) with `canGoBack == false` and `canGoForward == true`.
- Verify calling `nextStep()` increments step index up to max step (index 4) where `canGoForward == false`.
- Verify calling `previousStep()` decrements step index down to 0.
- Verify `selectStep(index)` clamps to valid bounds (0..4) and updates current step content.
- Verify Step 4 exposes the copyable package name and clear instructions.
- Verify FAQ accordion expansion state toggling and troubleshooting item selection.
- Verify `initialStepForError` maps errors to the appropriate step (e.g. Billing error -> Step 1, Disabled API -> Step 2, Restrictions -> Step 4).

---
Next Phase: [Phase 03: Settings Modal UX Optimization & Contextual Deep-Links](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-google-maps-api-key-guide-and-settings-ux/phase-03-routing-settings-modal-ux-enhancement.md)
