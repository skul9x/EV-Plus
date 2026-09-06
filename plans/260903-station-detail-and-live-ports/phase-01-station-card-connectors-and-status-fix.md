# Phase 01: Station Card Connectors & Status Fix
Status: ✅ Completed
Dependencies: None

## Objective
Fix the display of favorite station cards in `StationCard.kt` and domain models so they accurately display connector specifications from the user's saved favorites and do not erroneously render `trống 0/0` or orange `Hết cổng` badges when live plug metrics are awaiting synchronization or outside radius.

## Requirements
### Functional
- When a station card has connectors (e.g. "30kW, 20kW, 3.5kW") but live plug counts have not yet been queried (or totalPlugs == 0), render clean connector power chips (e.g. `✧ 30kW`, `✧ 20kW`, `✧ 3.5kW`) instead of `trống 0/0`.
- Display status badge as `Đã lưu` (Saved) or `Hoạt động` (Active) rather than `Hết cổng` (Out of plugs) when `totalPlugs == 0`.
- Preserve live plug display (`trống X/Y`) whenever verified live telemetry (`totalPlugs > 0`) is available.

### Non-Functional
- Smooth Jetpack Compose layout rendering with no layout jumps.
- Clean Dark Mode color harmony matching official theme tokens.

## Implementation Steps
1. Update `StationModels.kt` / `PowerPort.kt`: distinguish between verified live telemetry (`totalPlugs > 0`) and baseline connector tags (`totalPlugs == 0`).
2. Update `StationCard.kt`:
   - Refactor `WattageChip` to show connector label (e.g. `✧ 30kW`) when `totalPlugs == 0`, and live count (`✧ 30kW: trống X/Y`) when `totalPlugs > 0`.
   - Refactor `StatusBadge`: do not show `Hết cổng` if total plugs are unverified (`totalPlugs == 0`); default to `Hoạt động` or `Đã lưu`.
3. Update `EvcsRepository.kt` fallback mapper to cleanly create connector chips without synthetic `0/0` counts.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - [Modify] PowerPort & Station model logic
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [Modify] WattageChip & StatusBadge rendering
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [Modify] Baseline connector mapping
- `app/src/test/java/com/evcs/favorites/StationCardStatusTest.kt` - [New] Core verification test

## Test Criteria
- Verify that favorite stations with connector string "30kW, 20kW, 3.5kW" and no live telemetry render as valid connector chips without any `0/0` string.
- Verify status badge displays `Hoạt động` or `Đã lưu` and never `Hết cổng` when total plugs are 0.
- Verify that when live telemetry is present (e.g. 2 available / 4 total for 30kW), it displays `30kW: trống 2/4` and positive availability badge.

---
Next Phase: [Phase 02: Station Detail View & Card Interaction](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-station-detail-and-live-ports/phase-02-station-detail-view-and-interaction.md)
