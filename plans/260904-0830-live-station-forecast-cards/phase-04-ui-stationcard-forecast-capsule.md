# Phase 04: UI StationCard Forecast Capsule & Polish
Status: ✅ Completed
Dependencies: Phase 03

## Objective
Design and implement the Amber Forecast Capsule and status badge updates on `StationCard` (used by both Nearby and Favorites screens), ensuring a clean, premium, and informative visual presentation for all upcoming charging completions strictly matching [1.md](file:///home/skul9x/Desktop/Code/TramsacEV/1.md).

## Requirements
### Functional
- [x] Update `resolveStatusBadge` in `StationCard.kt`:
  - Add parameter `forecast: StationForecast? = null`.
  - When `station.forecast != null` and station is full (`totalPlugs > 0 && totalAvailablePlugs == 0`):
    - Status label: `"⏱️ Sắp trống"`
    - Colors: Dot `#F59E0B` (Amber), Container `Color(0x26F59E0B)`.
  - When `station.forecast == null` and station is full: retains `"Hết cổng"` with red busy badge (`#EF4444`, `Color(0x26EF4444)`).
  - When station has available ports (`totalAvailablePlugs > 0`): retains green `"Hoạt động"`.
- [x] Update `StatusBadge` composable in `StationCard.kt`:
  - Accept `forecast: StationForecast? = null`.
  - Wrap content with `AnimatedContent` for smooth crossfade transition between red `Hết cổng` and amber `⏱️ Sắp trống`.
- [x] Create `ForecastCapsule` composable in `StationCard.kt`:
  - Placement: Inside card content, positioned directly above `WattageChip` connector chips and below the address row.
  - Background: Warm Amber container `Color(0x1AF59E0B)` (Amber 10%).
  - Border: `BorderStroke(1.dp, Color(0x4DF59E0B))` rounded `10.dp`.
  - Padding: `horizontal = 12.dp, vertical = 8.dp`.
  - Content formatting:
    - **Case 1 (Single session / 1 power line)**:
      `⏱️ Dự kiến 2 xe sạc trụ 20kW sẽ xong trong 7-14 phút nữa`
    - **Case 2 (Multiple sessions / multi-power levels)**:
      Header: `⚡ DỰ KIẾN CỔNG SẮP TRỐNG:`
      Bullets:
      `• 20kW:  ~7-14 phút (2 xe)`
      `• 60kW:  ~13 phút (1 xe)`
      `• 250kW: ~8 phút (1 xe)`
  - Entrance animation: `AnimatedVisibility(visible = station.forecast != null, enter = fadeIn() + expandVertically())`.
- [x] Pass `station.forecast` seamlessly to `StationDetailModal` if user taps the card.

### Non-Functional
- [x] Visual polish: High contrast, dark/light theme compatible, no layout jank or text clipping.
- [x] Strict typography: Monospace / tabular numbers for time ranges if needed, clean alignment.

## Implementation Steps
1. [x] Update `resolveStatusBadge` and `StatusBadge` in `StationCard.kt` to accept forecast and support `AnimatedContent` crossfade.
2. [x] Implement `ForecastCapsule` composable component with exact 1.md styling tokens.
3. [x] Integrate `ForecastCapsule` into `StationCard` layout above wattage chips.
4. [x] Create and run unit test `StationCardForecastBadgeTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [MODIFY] Add ForecastCapsule & update StatusBadge with AnimatedContent
- `app/src/test/java/com/evcs/favorites/ui/components/StationCardForecastBadgeTest.kt` - [NEW] Single comprehensive UI model & resolution test

## Test Criteria (Single File-Based Test)
Run single test:
`./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationCardForecastBadgeTest`
- [x] Verifies full station with forecast resolves to Amber "Sắp trống" badge.
- [x] Verifies full station without forecast resolves to Red "Hết cổng" badge.
- [x] Verifies station with available plugs resolves to Green "Hoạt động" badge regardless of forecast.
- [x] Verifies capsule text formatting for single session summary.
- [x] Verifies capsule text formatting for multiple sessions with bullet list and header matching 1.md.

---
All Phases Complete! Ready for final verification and deployment.
