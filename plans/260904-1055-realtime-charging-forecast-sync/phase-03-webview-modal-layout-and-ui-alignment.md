# Phase 03: WebView Modal Layout & UI Alignment
Status: ✅ Completed
Dependencies: [Phase 02](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1055-realtime-charging-forecast-sync/phase-02-repository-realtime-forecast-pipeline.md)

## Objective
Fix the mobile layout overlap bug in `StationDetailModal` where the green `[Xem thêm ↗]` button covers the minute duration text in `.amd-ticker`, and verify end-to-end alignment between StationCard forecast capsules and StationDetailModal real-time states.

## Requirements
### Functional
- [x] In `StationDetailModal.kt`:
  - Enhance `WebViewClient.onPageFinished` to inject a targeted CSS stylesheet into the EVCS webview DOM:
    ```javascript
    (function() {
        var style = document.createElement('style');
        style.type = 'text/css';
        style.innerHTML = '.amd-hasmore .amd-item { padding-right: 115px !important; }';
        document.head.appendChild(style);
    })();
    ```
  - This ensures that on narrow smartphone viewports (e.g. 360-465px), the text "Dự kiến 1 xe sạc trụ 150kW sẽ xong trong 13 phút nữa" is never clipped or occluded by the absolutely positioned `[Xem thêm ↗]` action button.
- [x] Verify that the forecast displayed on `StationCard` (from the repository real-time pipeline) and the forecast inside `StationDetailModal` (from the webview) render identical vehicle counts, wattage levels, and consistent real-time completion expectations.

### Non-Functional
- [x] UX Consistency: Zero layout flickering or overlap on any device screen size or density.
- [x] Security: Script injection strictly targets CSS styling within the EVCS domain.

## Implementation Steps
1. [x] Update `StationDetailModal.kt` in `com.evcs.favorites.ui.components` to inject the CSS override in `onPageFinished`.
2. [x] Create single comprehensive test file `StationDetailModalForecastAlignmentTest.kt` verifying:
   - Script injection payload formatting and styling rule correctness.
   - End-to-end data alignment between `StationForecast` domain model and EVCS real-time ticker expectations.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt` - Inject layout fix script in `onPageFinished`.
- `app/src/test/java/com/evcs/favorites/ui/components/StationDetailModalForecastAlignmentTest.kt` - [NEW] Single comprehensive unit test.

## Test Criteria
- [x] Run only `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationDetailModalForecastAlignmentTest`
- [x] All test cases pass with zero failures.
