# Phase 02: On-Demand Lifecycle & System Regression Verification

Status: ✅ Completed  
Dependencies: [Phase 01](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2345-on-demand-station-detail-forecast/phase-01-webview-forecast-restoration-and-responsive-layout.md)

## Objective
Verify the end-to-end on-demand lifecycle of `StationDetailModal`: ensure that the WebView is only loaded when the modal is actively visible, all Chromium WebCore resources and DOM elements are cleanly torn down upon sheet dismissal without memory leaks, and that the main station list cards remain completely decoupled with zero background forecast network operations.

## Requirements

### Functional
- [x] Verify `StationDetailWebViewHelper.cleanUpWebView` correctly executes the full 6-step disposal sequence (`stopLoading`, `loadBlankUrl`, `clearHistory`, `removeAllViews`, `detachFromParent`, and `destroy`).
- [x] Verify that opening and closing `StationDetailModal` does not trigger any background forecast polling or requests in `NearbyViewModel` and `FavoritesViewModel`.
- [x] Verify that `StationCard` in the station list renders status badges deterministically ("Hết cổng" for full stations with `totalAvailablePlugs == 0`, "Hoạt động" for available stations) without any dependency on modal opening or forecast presence.

### Non-Functional
- [x] Memory Leak Prevention: Ensure no static references to Android `Context` or `WebView` instances persist after modal dismissal.
- [x] Battery & Data Conservation: No background timers, polling threads, or lingering network connections.

## Implementation Steps
1. Review `StationDetailModal.kt` and `StationDetailWebViewHelper.kt` to ensure seamless `DisposableEffect` and `onRelease` coordination.
2. Create single comprehensive test file `StationDetailOnDemandLifecycleRegressionTest.kt` in `app/src/test/java/com/evcs/favorites/ui/components/`.
3. Verify with `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationDetailOnDemandLifecycleRegressionTest`.

## Files to Create (Test)
- `app/src/test/java/com/evcs/favorites/ui/components/StationDetailOnDemandLifecycleRegressionTest.kt`

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/ui/components/StationDetailOnDemandLifecycleRegressionTest.kt`
- Verifies:
  1. `WebViewLifecycleTarget` mock receives the complete tear-down sequence (`stopLoading()`, `loadBlankUrl()`, `clearHistory()`, `removeAllViews()`, `detachFromParent()`, `destroy()`) when cleanUp is triggered.
  2. Idempotent cleanup: repeated cleanup calls on the same target handle disposal gracefully without crashing.
  3. Strict list decoupling: `StationCardKt` has zero references to forecast capsules or amber forecast badges. Full stations always resolve to `"Hết cổng"` (`StatusBusy`).
  4. Station list models (`Station`) maintain zero runtime coupling to background forecast polling loops.

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationDetailOnDemandLifecycleRegressionTest
```
