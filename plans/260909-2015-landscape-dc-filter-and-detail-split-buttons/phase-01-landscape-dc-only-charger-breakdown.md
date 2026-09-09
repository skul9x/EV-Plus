# Phase 01: DC-Only Charger Breakdown in Landscape Nearby List
Status: ✅ Completed
Dependencies: None

## Objective
Filter out AC charging ports from the station card's power breakdown line (`formatPowerDistributionSummary`) in the Nearby tab when a DC filter is active in landscape mode, displaying only DC chargers (e.g., `120kW x 4 | 60kW x 2` instead of `120kW x 4 | 60kW x 2 | 7kW x 4`). This shortens the line so it fits comfortably within the master list card width without triggering unnecessary marquee text scrolling animations.

## Requirements

### Functional
- [x] Extend `StationCardHelper.formatPowerDistributionSummary(powers, connectors, filterDcOnly: Boolean = false)`:
  - When `filterDcOnly = false`, retain default behavior grouping all ports (both AC and DC).
  - When `filterDcOnly = true`, filter `effectivePorts` using `it.isDc()` so AC ports (e.g. 7kW, 11kW, 22kW AC) are excluded.
  - Correctly sort remaining DC tiers descending by wattage and aggregate counts.
  - If a station has only AC ports and `filterDcOnly = true`, return an empty list (`emptyList()`).
- [x] Provide helper `NearbyFilterUiHelper.isDcFilterActive(...)` to reliably determine whether a DC filter is active:
  - Returns `true` when `activeFilterMode == SmartFilterMode.DC` or `isDcSubFilterVisible == true` or `selectedDcTier != null`.
  - Returns `true` when `activeFilterMode == SmartFilterMode.CUSTOM` and config is a DC quick-chip (`DC_LE_30KW`, `DC_BETWEEN_30_60KW`, `DC_GE_60KW`, `DC_GE_120KW`) or custom range with `(minKw ?: 0) >= 20`.
  - Returns `false` for `SmartFilterMode.NONE`, `SmartFilterMode.AC`, or non-DC custom configs.
- [x] Update `StationCard` and `CompactStationCardContent` to accept `filterDcOnly: Boolean = false` and pass it to `StationCardHelper.formatPowerDistributionSummary(station.powers, station.connectors, filterDcOnly)`.
- [x] In `NearbyLandscapeScreen` (and `NearbyScreen` landscape master list), compute `isDcFilterActive` and pass `filterDcOnly = isDcFilterActive` into each `StationCard`.
- [x] In `StationCard`, if `powerDistribution` is empty (e.g. station has no DC chargers under DC filter), cleanly omit the power summary `Text` composable without leaving empty blank padding.
- [x] Bound `basicMarquee` on the power distribution text in `StationCard` to `iterations = 2` (matching station title marquee) to prevent unbounded frame drawing loops when scrolling does occur.

### Non-Functional
- [x] Performance: Zero extra object allocations during recomposition using `remember(station.powers, station.connectors, filterDcOnly)`.
- [x] Backward compatibility: Default parameter `filterDcOnly = false` preserves existing behavior across all other callers (Favorites, portrait list, etc.).

## Implementation Steps
1. [x] Update `StationCardHelper.kt`:
   - Add `filterDcOnly: Boolean = false` to `formatPowerDistributionSummary`.
   - Filter `effectivePorts.filter { it.isDc() }` when `filterDcOnly` is true.
   - Update overload `buildPowerDistributionAnnotatedString(powers, connectors, powerColor, countColor, separatorColor, filterDcOnly: Boolean = false)`.
2. [x] Add `isDcFilterActive` helper logic to `SmartFilterBar.kt` (or dedicated `NearbyFilterUiHelper.kt`).
3. [x] Update `StationCard.kt`:
   - Add `filterDcOnly: Boolean = false` parameter to `StationCard` and `CompactStationCardContent`.
   - Forward `filterDcOnly` into `remember(station.powers, station.connectors, filterDcOnly) { StationCardHelper.formatPowerDistributionSummary(...) }`.
   - Update `basicMarquee(iterations = 2, delayMillis = 2000, velocity = 30.dp)` on the power summary text.
4. [x] Update `NearbyLandscapeScreen.kt`:
   - Compute `isDcFilterActive = remember(...) { NearbyFilterUiHelper.isDcFilterActive(...) }`.
   - Pass `filterDcOnly = isDcFilterActive` to `StationCard`.
5. [x] Update `NearbyScreen.kt`:
   - Pass `filterDcOnly = (isLandscape && isDcFilterActive)` to `StationCard` in `NearbyResultContent`.
6. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/ui/StationCardDcOnlyFilterTest.kt` verifying:
   - AC port exclusion under `filterDcOnly = true`.
   - Preserved all-port display under `filterDcOnly = false`.
   - Fallback connector string parsing with `filterDcOnly = true`.
   - Edge cases: stations with only DC ports remain unchanged; stations with only AC ports yield empty list cleanly.
   - `isDcFilterActive` resolution across DC mode, DC tiers, Custom DC quick chips, and Custom DC ranges.
   - Contract compliance for `StationCard` and `NearbyLandscapeScreen`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCardHelper.kt` - Add `filterDcOnly` support to power summary formatting
- `app/src/main/java/com/evcs/favorites/ui/components/SmartFilterBar.kt` - Add `isDcFilterActive` helper
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - Propagate `filterDcOnly` and bound marquee iterations
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt` - Pass `filterDcOnly = isDcFilterActive`
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - Pass `filterDcOnly` in landscape result content
- `app/src/test/java/com/evcs/favorites/ui/StationCardDcOnlyFilterTest.kt` - Single comprehensive test for Phase 01

## Test Criteria
- Single Test Class: `com.evcs.favorites.ui.StationCardDcOnlyFilterTest`
- Execution Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationCardDcOnlyFilterTest"`
- Verification: All test cases pass with 0 failures, 0 regressions.

---
Next Phase: [Phase 02: Station Detail Action Split: "Chỉ Đường" & "Focus" Buttons](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-2015-landscape-dc-filter-and-detail-split-buttons/phase-02-station-detail-split-nav-and-focus-buttons.md)
