# Phase 02: Deterministic WebView Lifecycle & Native Memory Leak Prevention

Status: ✅ Completed
Issue ID: PERF-MEM-02
Dependencies: Phase 01

## Objective
Prevent native Chromium rendering engine and C++ WebCore memory leaks (15MB–45MB RAM per modal open/close) in `StationDetailModal` by implementing deterministic lifecycle disposal, view hierarchy detachment, and renderer termination handling.

---

## Requirements

### Functional
- [x] Implement deterministic cleanup when `StationDetailModal` is dismissed (via drag, backdrop tap, close button, or back navigation).
- [x] Provide explicit `onRelease` callback in `AndroidView` for Compose 1.6+ runtime recycling and disposal.
- [x] In the cleanup pipeline, execute:
  1. `webView.stopLoading()`
  2. `webView.loadUrl("about:blank")`
  3. `webView.clearHistory()`
  4. `webView.removeAllViews()`
  5. Detach from parent view hierarchy: `(webView.parent as? ViewGroup)?.removeView(webView)`
  6. `webView.destroy()`
- [x] Handle Chromium renderer crash gracefully by overriding `onRenderProcessGone` in `WebViewClient`, destroying the view and returning `true` to avoid process termination.
- [x] Ensure `FavoritesViewModel.dismissStationDetail()` and logout cleanly clear the modal state and trigger disposal.

### Non-Functional
- [x] Zero native memory retention after modal dismissal.
- [x] Safety: Idempotent cleanup logic that never throws `NullPointerException` or illegal state crashes on duplicate release events.

---

## Implementation Steps

1. **Create `StationDetailWebViewHelper.kt`**:
   - Encapsulate the full `cleanUpWebView(webView: WebView)` sequence.
   - Implement `createSafeWebViewClient(...)` with robust `onRenderProcessGone` handling.
   - Support test hooks for lifecycle validation on JVM unit test environments.

2. **Update `StationDetailModal.kt`**:
   - Attach `onRelease = { webView -> StationDetailWebViewHelper.cleanUpWebView(webView) }` on the `AndroidView` composable.
   - Add a `DisposableEffect(station.id)` block to ensure cleanup triggers if composition leaves before `onRelease`.
   - Update `webViewClient` to override `onRenderProcessGone` using `StationDetailWebViewHelper`.

3. **Verify State Coordination in `FavoritesViewModel.kt`**:
   - Ensure `selectedStationForDetail` transitions deterministically update and trigger the UI modal teardown.

---

## Files to Modify/Create
- [NEW] `app/src/main/java/com/evcs/favorites/ui/components/StationDetailWebViewHelper.kt` - Centralized, testable WebView cleanup & lifecycle controller.
- [MODIFY] `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt` - Integrate `onRelease`, `DisposableEffect`, and safe `WebViewClient`.
- [NEW] `app/src/test/java/com/evcs/favorites/StationDetailWebViewLifecycleTest.kt` - Exactly one comprehensive test for Phase 2.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/StationDetailWebViewLifecycleTest.kt`
- **Core Verifications**:
  1. `StationDetailWebViewHelper.cleanUpWebView` invokes all steps in order: stopLoading -> loadUrl(about:blank) -> clearHistory -> removeAllViews -> detach from parent -> destroy.
  2. Idempotency: Invoking cleanup multiple times does not throw exceptions or crash.
  3. `onRenderProcessGone` intercepts renderer crashes, executes view detachment/cleanup, and returns `true`.
  4. Integration with `FavoritesViewModel`: Selecting a station, dismissing, or logging out correctly clears `selectedStationForDetail` and signals disposal.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.StationDetailWebViewLifecycleTest
```
