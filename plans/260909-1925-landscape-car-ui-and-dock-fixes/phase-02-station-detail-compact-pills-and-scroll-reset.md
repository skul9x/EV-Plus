# Phase 02: Station Detail Redesign: Compact Horizontal Pills, Rating Removal & Scroll Reset

Status: ✅ Completed  
Dependencies: Phase 01

## Objective

1. Automatically and instantaneously reset detail pane scroll offset to top (`scrollTo(0)`) whenever the driver selects a different station from the master list.
2. Remove the star rating badge (`★ 4.8 (25)`) completely from the station detail UI layout while preserving helper signatures for backwards compatibility with existing test suites.
3. Streamline charging port status pills into concise automotive format `${kw}kW  ${avail}/${total}` (e.g. `30kW  2/4`).
4. Set prominent warning red color styling (`StatusOffline` / `StatusOfflineContainer`) for exhausted charging ports (`avail == 0 && total > 0`), treating full stations with high visual priority matching maintenance.
5. Handle unverified ports (`total == 0`) cleanly with neutral styling (`${kw}kW`) rather than synthetic `0/0`.
6. Arrange port status pills using horizontal flow layout (`FlowRow`) with compact padding (8dp horizontal, 4dp vertical), eliminating vertical stacking and preventing unnecessary driver scrolling.

## Requirements

### Functional
- [x] In `NativeStationDetailContent`, add `LaunchedEffect(station.id) { scrollState.scrollTo(0) }` to reliably reset scroll offset instantly (0ms) without frame drops on automotive hardware.
- [x] Remove `ratingText` Surface/Text block from `NativeStationDetailContent` layout.
- [x] Retain `NativeStationDetailSheetHelper.formatRatingBadge` (annotated `@Deprecated`) so existing repository unit tests pass without compile errors.
- [x] Refactor `NativeStationDetailSheetHelper.resolvePortBadge`:
  - When maintaining/out of service: Label is `"${kw}kW  Bảo trì"`, with `StatusOffline` / `StatusOfflineContainer` (red alert).
  - When `total > 0 && avail == 0`: Label is `"${kw}kW  0/$total"`, with **`StatusOffline` / `StatusOfflineContainer`** (red alert matching maintenance).
  - When `avail > 0`: Label is `"${kw}kW  $avail/$total"`, with `StatusAvailable` / `StatusAvailableContainer` (emerald green).
  - When unverified (`total == 0`): Label is `"${kw}kW"`, with neutral `MaterialTheme.colorScheme.surfaceVariant` styling.
- [x] In `PortStatusPill`, configure compact dimensions (`padding(horizontal = 8.dp, vertical = 4.dp)`), ensuring 3-4 pills fit on a single horizontal line within the landscape detail surface (~400-550dp width).

### Non-Functional
- [x] Zero regression on portrait bottom sheet behavior.
- [x] Maintain fast, deterministic rendering with memoized badge resolvers (`remember(portStatus, depotStatus)`).

## Implementation Steps

1. **Update `NativeStationDetailSheet.kt`:**
   - Modify `NativeStationDetailSheetHelper.resolvePortBadge` to implement the compact format and new color matrix (red for exhausted/maintenance, green for available, neutral for unverified).
   - Remove rating badge UI Composable in `NativeStationDetailContent`.
   - Add `LaunchedEffect(station.id) { scrollState.scrollTo(0) }` right after `val scrollState = rememberScrollState()`.
   - Update `PortStatusPill` with compact padding and typography for automotive glanceability.
2. **Implement Single Verification Test:**
   - Create `com.evcs.favorites.ui.StationDetailAutomotiveFormattingTest` to verify:
     - Available port badge produces `"${kw}kW  ${avail}/${total}"` with green container/dot.
     - Exhausted port badge (`avail == 0 && total > 0`) produces `"${kw}kW  0/${total}"` with red container/dot (`StatusOffline`).
     - Maintaining port badge produces `"${kw}kW  Bảo trì"` with red container/dot.
     - Unverified port badge (`total == 0`) produces clean `"${kw}kW"` without `0/0`.
     - Deprecated rating helper preserves contract while UI omission is validated.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` (MODIFY)
- `app/src/test/java/com/evcs/favorites/ui/StationDetailAutomotiveFormattingTest.kt` (NEW)

## Test Criteria (Single Test)
- Test Class: `com.evcs.favorites.ui.StationDetailAutomotiveFormattingTest`
- Verification Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationDetailAutomotiveFormattingTest"`
- Assertions:
  - Port badge label format strictly conforms to `"${kw}kW  ${avail}/${total}"`.
  - Zero-availability ports return `StatusOffline` red color tokens.
  - Maintained ports return `StatusOffline` red color tokens and `"Bảo trì"`.
  - Unverified ports return clean kW text without synthetic plug counts.

---
Next Phase: [phase-03-station-card-powers-summary-and-favorites-telemetry.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-1925-landscape-car-ui-and-dock-fixes/phase-03-station-card-powers-summary-and-favorites-telemetry.md)
