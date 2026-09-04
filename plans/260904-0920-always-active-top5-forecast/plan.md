# Plan: Unconditional Top 5 Nearest Stations Forecast Enrichment
Created: 2026-09-04 09:20:00 (GMT+7)
Updated: 2026-09-04 09:25:00 (GMT+7)
Status: ✅ Completed

## Overview
Enable real-time EV charging completion forecast requests for the Top 5 nearest stations across both "Quanh đây" (Nearby) and "Yêu thích" (Favorites) screens unconditionally, without filtering by `totalAvailablePlugs == 0`.
If a station has live forecast sessions from EVCS server-side rendered (SSR) HTML, display the Amber Forecast Capsule (`⏱️ Dự kiến...`). If no forecast data is available for a station (e.g. no cars currently charging or SSR page has no forecast sessions), skip it cleanly and display nothing extra for that station.

## User Decisions & Key Rules
1. **Always Query Top 5 Nearest Stations**:
   - `NearbyViewModel` and `FavoritesViewModel` unconditionally target `take(5)` from the nearest stations list, removing the `totalAvailablePlugs == 0` constraint.
2. **Clean Skip When No Data ("nếu không có thì bỏ qua không hiện gì hết")**:
   - No fake or synthetic forecasts. Only display the Forecast Capsule when genuine forecast data is returned by EVCS SSR.
   - If forecast data is null, the card renders normally with no capsule.
3. **UI Status Badge & Capsule Behavior**:
   - Full station (`totalPlugs > 0 && totalAvailablePlugs == 0`) with forecast: Amber `⏱️ Sắp trống` badge and Amber Forecast Capsule.
   - Full station without forecast: Red `Hết cổng` badge and no capsule.
   - Available station (`totalAvailablePlugs > 0`) with forecast: Green `Hoạt động` badge and Amber Forecast Capsule.
   - Available station without forecast: Green `Hoạt động` badge and no capsule.
4. **Testing Protocol**:
   - Exactly ONE comprehensive file-based test per phase.
   - Run only that single test for verification after completing each phase, then stop for user review.

## Phases

| Phase | Name | Status | Test File |
|-------|------|--------|-----------|
| 01 | ViewModel Top 5 Unconditional Forecast Pipeline | ✅ Completed | `Top5UnconditionalForecastViewModelTest.kt` |
| 02 | StationCard UI & Badge Presentation for Live Top 5 Forecasts | ✅ Completed | `StationCardTop5ForecastTest.kt` |

## Quick Commands
- Start Phase 1: Implement `phase-01-viewmodel-top5-unconditional-enrichment.md`
- Verify Phase 1: `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.Top5UnconditionalForecastViewModelTest`
- Start Phase 2: Implement `phase-02-ui-stationcard-forecast-presentation.md`
- Verify Phase 2: `./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationCardTop5ForecastTest`
