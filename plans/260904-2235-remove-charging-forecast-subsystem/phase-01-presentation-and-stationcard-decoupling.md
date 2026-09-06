# Phase 01: Presentation and StationCard Decoupling

Status: ✅ Completed  
Dependencies: None

## Objective
Decouple the charging forecast UI components from `StationCard.kt`, ensure the station detail WebView modal cleanly hides the web forecast banner, and retire legacy UI forecast unit tests to guarantee clean test compilation.

## Requirements

### Functional
- [x] Remove `ForecastCapsule` from the `StationCard` layout so the amber text (e.g. `⏱️ Dự kiến 2 xe sạc trụ 120kW sẽ xong trong 13-17 phút`) is completely eliminated from the native station list.
- [x] Update `resolveStatusBadge` in `StationCard.kt`: when a station has `totalPlugs > 0 && totalAvailablePlugs == 0`, it must unconditionally return `"Hết cổng"` with `StatusBusy` (`#EF4444`). The `"⏱️ Sắp trống"` amber badge branch must be removed. Keep `forecast: StationForecast? = null` parameter optional/deprecated for signature compatibility until Phase 04.
- [x] Remove unused visual models and helper functions (`ForecastCapsuleData`, `resolveForecastCapsuleData`, `ForecastCapsule`) or cleanly clean them up.
- [x] In `StationDetailModal.kt`, ensure CSS injected into the WebView hides `.amd-ticker` and `.amd-more` (`.amd-ticker, .amd-more { display: none !important; }`), ensuring no forecast text is visible when inspecting station details.
- [x] Retire legacy UI forecast test files that assert legacy capsule & amber badge behaviors (`StationCardForecastBadgeTest.kt`, `StationCardTop5ForecastTest.kt`, and `StationDetailModalForecastAlignmentTest.kt`) to ensure `compileDebugUnitTestKotlin` succeeds atomically.

### Non-Functional
- [x] Clean Compose recomposition: StationCard height is reduced and consistent across all station states.
- [x] Zero layout jitter or animation overhead previously caused by `AnimatedVisibility` on `ForecastCapsule`.
- [x] Zero compilation errors across test source set during Gradle build.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`:
   - Remove `ForecastCapsule(forecast = station.forecast)` call between address text and connector chips.
   - In `resolveStatusBadge`, simplify `totalPlugs > 0 && totalAvailablePlugs == 0` to directly return `Triple("Hết cổng", StatusBusy, StatusBusyContainer)`.
   - Remove unused forecast color constants (`StatusForecastAmber`, `StatusForecastAmberContainer`, `ForecastCapsuleBg`, `ForecastCapsuleBorder`).
   - Remove `ForecastCapsuleData`, `resolveForecastCapsuleData`, and `ForecastCapsule` composable.
2. In `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt`:
   - Update `FORECAST_OVERLAP_FIX_CSS` to `.amd-ticker, .amd-more { display: none !important; }`.
   - Keep script injection intact so the WebView cleanly suppresses the server-rendered forecast ticker.
3. Retire legacy UI forecast test files:
   - Remove `app/src/test/java/com/evcs/favorites/ui/components/StationCardForecastBadgeTest.kt`.
   - Remove `app/src/test/java/com/evcs/favorites/ui/components/StationCardTop5ForecastTest.kt`.
   - Remove `app/src/test/java/com/evcs/favorites/ui/components/StationDetailModalForecastAlignmentTest.kt`.

## Files to Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`
- `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt`

## Files to Delete (Legacy Tests)
- `app/src/test/java/com/evcs/favorites/ui/components/StationCardForecastBadgeTest.kt`
- `app/src/test/java/com/evcs/favorites/ui/components/StationCardTop5ForecastTest.kt`
- `app/src/test/java/com/evcs/favorites/ui/components/StationDetailModalForecastAlignmentTest.kt`

## Files to Create (Test)
- `app/src/test/java/com/evcs/favorites/ui/components/StationCardForecastRemovalTest.kt`

## Test Criteria (Single Comprehensive Test)
- Exactly one test file: `app/src/test/java/com/evcs/favorites/ui/components/StationCardForecastRemovalTest.kt`
- Verifies:
  1. `resolveStatusBadge` always returns `"Hết cổng"` for full stations with `totalPlugs > 0 && totalAvailablePlugs == 0`, regardless of forecast values.
  2. Available stations return `"Còn cổng"` or `"Hoạt động"` with green `StatusAvailable`.
  3. WebView CSS snippet contains `.amd-ticker` and `.amd-more` hiding rules (`display: none !important`).
  4. Non-null station state renders cleanly without forecast capsule artifacts.

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationCardForecastRemovalTest
```

---
Next Phase: [Phase 02: ViewModel and Background Job Decommissioning](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260904-2235-remove-charging-forecast-subsystem/phase-02-viewmodel-and-background-job-decommissioning.md)
