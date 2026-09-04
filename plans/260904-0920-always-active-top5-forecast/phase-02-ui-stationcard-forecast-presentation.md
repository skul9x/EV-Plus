# Phase 02: StationCard UI & Badge Presentation for Live Top 5 Forecasts
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Verify and refine `StationCard.kt` to ensure the Amber Forecast Capsule renders cleanly whenever a station in the top 5 has live forecast data, regardless of whether it is partially occupied (`totalAvailablePlugs > 0`) or completely full (`totalAvailablePlugs == 0`). If a station has no forecast data (`station.forecast == null`), cleanly skip rendering the capsule and leave no extra whitespace. Ensure status badges correctly reflect the station's state.

## Requirements
### Functional
- [x] Forecast Capsule Display Rule in `StationCard.kt`:
  - When `station.forecast != null`:
    - Renders the Amber Forecast Capsule between Address row and Wattage chips row.
    - Smooth entrance animation: `AnimatedVisibility(visible = station.forecast != null, enter = fadeIn() + expandVertically())`.
    - Case 1 (Single session / 1 power line): `⏱️ Dự kiến {count} xe sạc trụ {kw} sẽ xong trong {range} nữa`.
    - Case 2 (Multi-sessions / multi-power levels): Bullet list with header `⚡ DỰ KIẾN CỔNG SẮP TRỐNG:`.
  - When `station.forecast == null`:
    - Cleanly skip: `ForecastCapsule` renders nothing (`AnimatedVisibility` invisible / takes 0 height).
- [x] Status Badge Resolution Rule in `StationCard.kt`:
  - Full station (`totalPlugs > 0 && totalAvailablePlugs == 0`) with forecast: Amber `⏱️ Sắp trống` badge (`#F59E0B`).
  - Full station (`totalPlugs > 0 && totalAvailablePlugs == 0`) without forecast: Red `Hết cổng` badge (`#EF4444`).
  - Available station (`totalAvailablePlugs > 0`): Green `Hoạt động` badge (`#10B981`) (with Forecast Capsule visible if `forecast != null`).
  - Maintaining / Out of Service: Preserves "Bảo trì" / "Tạm dừng".

### Non-Functional
- [x] Visual polish: High contrast, dark/light theme compatible, no layout jank or text clipping when capsule is present or skipped.
- [x] Strict typography: Monospace / tabular numbers for time ranges, clean alignment.

## Implementation Steps
1. [x] Review and ensure `ForecastCapsule` in `StationCard.kt` handles non-null forecast seamlessly for any station (whether full or partially occupied).
2. [x] Ensure when `forecast == null`, `ForecastCapsule` adds zero margin/padding to the layout.
3. [x] Create single test file `StationCardTop5ForecastTest.kt`.
4. [x] Run unit test to verify UI model resolution, badge rules, and capsule formatting.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [MODIFY] Verify and polish ForecastCapsule and StatusBadge rules
- `app/src/test/java/com/evcs/favorites/ui/components/StationCardTop5ForecastTest.kt` - [NEW] Single comprehensive test

## Test Criteria (Single File-Based Test)
Run single test:
`./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationCardTop5ForecastTest`
- [x] Verifies station with available plugs (`totalAvailablePlugs > 0`) and valid forecast displays `ForecastCapsule` content while retaining green `Hoạt động` badge.
- [x] Verifies full station (`totalAvailablePlugs == 0`) and valid forecast displays `ForecastCapsule` content with Amber `⏱️ Sắp trống` badge.
- [x] Verifies station without forecast (`forecast == null`) completely skips `ForecastCapsule`.
- [x] Verifies single session and multi-session text formatting accurately matching 1.md specifications.
- [x] Verifies animated crossfade model transitions between badge states.

---
All Phases Complete! Ready for final verification and deployment.
