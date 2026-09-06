# Phase 01: Presentation & WebView On-Demand Forecast Restoration

Status: 🟢 Completed  
Dependencies: None

## Objective
Restore the real-time charging forecast ticker within the station detail WebView modal (`StationDetailModal.kt`) on-demand when the user opens a station info modal. Eliminate the blanket `display: none !important` suppression rule, re-apply the mobile-optimized padding override, provide a 1-tap manual reload capability in the header, and ensure clean test compilation.

## Requirements

### Functional
- [x] In `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt`:
  - Update `FORECAST_OVERLAP_FIX_CSS` from `.amd-ticker, .amd-more { display: none !important; }` to:
    ```css
    .amd-hasmore .amd-item { padding-right: 115px !important; }
    ```
    This unsuppresses the `.amd-ticker` ("Dự kiến X xe sạc trụ YkW sẽ xong trong Z phút nữa") and `.amd-more` buttons while maintaining padding protection against layout clipping on narrow mobile viewports.
  - Retain `FORECAST_OVERLAP_FIX_SCRIPT` and `isEvcsDomain(url)` domain boundary check to guarantee script injection only targets verified EVCS domains.
  - Add an on-demand reload `IconButton` (with `Icons.Default.Refresh`) to the `StationDetailModal` header row next to the close button, enabling users to re-fetch the latest live forecast and connector state on-demand (`webViewInstance?.reload()`).
- [x] In `app/src/test/java/com/evcs/favorites/ui/components/StationCardForecastRemovalTest.kt`:
  - Update test 3 (`stationDetailModalWebViewCssHidesForecastTickerAndMoreButton`) to assert that `StationCard` remains decoupled from forecast badges, while modal CSS assertions are migrated and validated in the new dedicated Phase 01 test.

### Non-Functional
- [x] Mobile Layout Integrity: On viewports between 360dp and 465dp width, the forecast text ("Dự kiến X xe sạc trụ YkW sẽ xong trong Z phút nữa") must not be occluded by the `[Xem thêm ↗]` button.
- [x] Zero List Interference: No changes made to `StationCard.kt`, `NearbyViewModel.kt`, or `FavoritesViewModel.kt`. The main station list remains completely free of background forecast requests.

## Implementation Steps
1. Update `StationDetailModal.kt`:
   - Change `FORECAST_OVERLAP_FIX_CSS = ".amd-hasmore .amd-item { padding-right: 115px !important; }"`
   - Add reload `IconButton` in modal header row before the dismiss button.
2. Adapt `StationCardForecastRemovalTest.kt` to avoid asserting `display: none !important`.
3. Create single comprehensive test file `StationDetailModalOnDemandForecastTest.kt` in `app/src/test/java/com/evcs/favorites/ui/components/`.

## Files to Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt`
- `app/src/test/java/com/evcs/favorites/ui/components/StationCardForecastRemovalTest.kt`

## Files to Create (Test)
- `app/src/test/java/com/evcs/favorites/ui/components/StationDetailModalOnDemandForecastTest.kt`

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/ui/components/StationDetailModalOnDemandForecastTest.kt`
- Verifies:
  1. `FORECAST_OVERLAP_FIX_CSS` contains `.amd-hasmore .amd-item` and `padding-right: 115px !important`.
  2. `FORECAST_OVERLAP_FIX_CSS` does NOT contain `display: none !important`.
  3. `FORECAST_OVERLAP_FIX_SCRIPT` contains valid JavaScript syntax that appends `<style>` to `document.head`.
  4. `isEvcsDomain` strictly filters and permits only genuine EVCS domains (`https://evcs.vn`, `https://api.evcs.vn/v1/stations`, etc.) and rejects untrusted or phishing URLs (`https://evil-evcs.vn.attacker.com`).

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationDetailModalOnDemandForecastTest
```

---
Next Phase: [Phase 02: On-Demand Lifecycle & System Regression Verification](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2345-on-demand-station-detail-forecast/phase-02-on-demand-lifecycle-and-system-regression.md)
