# Plan: On-Demand Charging Forecast in Station Detail Modal

Created: 2026-09-04 23:45:00 (GMT+7)  
Status: 🟡 In Progress  
Target: Restore and optimize real-time charging forecast exclusively within the on-demand Station Detail Modal

---

## 1. Executive Summary

### Problem
In the previous performance optimization milestone (commit `9cf5af7`), the charging forecast subsystem was decommissioned across the entire application to eliminate heavy background polling, prevent rate limiting, and stop battery/data drain. As part of that cleanup, the CSS rule `.amd-ticker, .amd-more { display: none !important; }` was injected into `StationDetailModal`, completely hiding the forecast ticker (`"Dự kiến 3 xe sạc trụ 60kW sẽ xong trong 11-17 phút nữa"`) even when a user explicitly taps on a station to view its details.

### Solution
Implement an **On-Demand** charging forecast architecture:
1. **Zero Background Overhead**: Keep the station list cards (`StationCard`) on "Quanh đây" (Nearby) and "Yêu thích" (Favorites) completely clean and decoupled from background forecast requests. Full stations remain cleanly marked as `"Hết cổng"` without polling overhead.
2. **On-Demand Modal Display**: When the user deliberately taps a station to view its info modal (`StationDetailModal`), restore the visibility of the real-time forecast ticker (`.amd-ticker`) and breakdown button (`.amd-more`) rendered by the official EVCS page.
3. **Responsive Mobile Layout Protection**: Re-apply the responsive CSS rule (`.amd-hasmore .amd-item { padding-right: 115px !important; }`) ensuring the `[Xem thêm ↗]` action button never occludes the forecast duration text on narrow smartphone displays (360-465px).
4. **On-Demand Manual Refresh**: Provide a 1-tap reload button in the modal header so users can refresh live telemetry on-demand without needing to close and re-open the bottom sheet.
5. **Deterministic Lifecycle Cleanup**: Enforce complete WebView resource disposal (`stopLoading`, `about:blank`, view removal, and `destroy()`) upon modal dismissal via `StationDetailWebViewHelper`.

---

## 2. Architecture & Design Decisions

```
+-------------------------------------------------------------------------+
|                              STATION LIST                               |
|   (NearbyScreen / FavoritesScreen)                                      |
|                                                                         |
|   - NO background HTTP requests for forecast (0% battery/data impact)   |
|   - Status badges remain clean ("Hết cổng" / "Hoạt động")               |
|   - Tap station -> triggers onStationClick(station)                     |
+-------------------------------------------------------------------------+
                                    |
                        User taps station card
                                    v
+-------------------------------------------------------------------------+
|                  ON-DEMAND MODAL (StationDetailModal)                   |
|                                                                         |
|   1. ModalBottomSheet mounts on-demand only                             |
|   2. Injects authenticated session cookies into CookieManager           |
|   3. Loads canonical detail URL in WebView                              |
|   4. On page finish: Injects responsive CSS:                            |
|      .amd-hasmore .amd-item { padding-right: 115px !important; }        |
|      (Displays "Dự kiến X xe sạc trụ YkW sẽ xong trong Z phút nữa")    |
|   5. Header provides 1-tap reload action to re-fetch on-demand          |
|   6. On dismiss: Cleans up WebView completely (0 memory leaks)          |
+-------------------------------------------------------------------------+
```

---

## 3. Strict Execution Principles

1. **Single Comprehensive Test Per Phase**:
   - Each phase defines exactly **ONE** file-based test.
   - Do NOT create or run more than one test per phase.
   - Run ONLY that single test for verification before pausing for user review.
2. **Deterministic English Phase Files**:
   - All phase files are written in English in `.md` format.
3. **Zero Regressions**:
   - Test suite and Android compilation (`:app:compileDebugUnitTestKotlin`) must remain 100% green.

---

## 4. Phase Overview

| Phase | Name | Scope | Production Files | Single Phase Test File | Status |
|---|---|---|---|---|---|
| 01 | Presentation & WebView On-Demand Forecast Restoration | `StationDetailModal.kt`, `StationCardForecastRemovalTest.kt` | Update `FORECAST_OVERLAP_FIX_CSS`, add header reload action, adapt legacy test assertions | `StationDetailModalOnDemandForecastTest.kt` | 🟡 Pending Review |
| 02 | On-Demand Lifecycle & System Regression Verification | `StationDetailModal.kt`, `StationDetailWebViewHelper.kt`, Screens | Lifecycle disposal, zero background leaks, list decoupling integrity | `StationDetailOnDemandLifecycleRegressionTest.kt` | ⬜ Pending |

---

## 5. Verification Commands
- **Phase 01**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationDetailModalOnDemandForecastTest
  ```
- **Phase 02**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationDetailOnDemandLifecycleRegressionTest
  ```
