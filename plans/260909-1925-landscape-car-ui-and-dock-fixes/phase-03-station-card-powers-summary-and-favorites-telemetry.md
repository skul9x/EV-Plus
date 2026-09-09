# Phase 03: Station Card Charger Distribution Line & Favorites Realtime Enrichment

Status: ✅ Completed  
Dependencies: Phase 02

## Objective

1. Add a dedicated charger power distribution summary line directly beneath the station title on each `StationCard` (e.g. `"120kW x 6 | 60kW x 10 | 30kW x 20"`).
2. Correctly group (`groupBy`) and aggregate (`sumOf`) multi-gun chargers, sort descending by wattage (`sortedByDescending`), style the power ratings and charger counts with contrasting colors (neutral light gray for power, vibrant emerald/cyan for count), and enable `Modifier.basicMarquee` so long configurations scroll seamlessly without truncation.
3. Automatically trigger background live telemetry enrichment for stations in the Favorites tab whenever the driver opens Favorites or presses "Làm mới" (Refresh), bounded by a `Semaphore(3)` throttler to protect cellular 4G bandwidth.
4. Establish 2-way sync-back from the station detail coordinator to the master favorites list so clicking any station immediately reflects its live telemetry on the list card.

## Requirements

### Functional
- [x] Add `StationCardHelper.formatPowerDistributionSummary(powers: List<PowerPort>, connectors: String): List<Pair<String, Int>>`:
  - Groups ports by `typeWatts` (or `label`) to avoid fragmented duplicate entries (e.g. `"60kW x 2 | 60kW x 2"` ➔ `"60kW x 4"`).
  - Sums `totalPlugs` for each group: `val total = group.sumOf { if (it.totalPlugs > 0) it.totalPlugs else 1 }`.
  - Sorts descending by power tier (`it.typeWatts`).
  - Gracefully falls back to parsed connectors when `powers` is empty.
- [x] In `CompactStationCardContent` (and `StationCard`), render the power distribution line using an `AnnotatedString`:
  - Power text (e.g. `120kW`): Neutral color (`MaterialTheme.colorScheme.onSurface`).
  - Count text (e.g. `x 6`): Vibrant accent color (`EmeraldPrimary` or `ElectricCyan`).
  - Separator (` | `): Muted divider color.
  - Apply `Modifier.basicMarquee(iterations = Int.MAX_VALUE, delayMillis = 2000, velocity = 30.dp)`.
- [x] In `FavoritesViewModel`, implement an on-demand `enrichFavoritesWithTelemetry()` routine triggered during initial load, tab activation, and manual refresh:
  - Uses `Semaphore(3)` to throttle concurrent network requests.
  - Updates `_uiState.value` (`FavoritesUiState.Success`) with live `powers`, `totalAvailablePlugs`, and `totalPlugs`.
  - Syncs live port results back from `stationDetailCoordinator.stationDetailState` to the matching favorite station in the list.
  - Does NOT initiate any continuous background polling timer or loop.

### Non-Functional
- [x] Keep power distribution computation memoized (`remember(station.powers, station.connectors)`) to prevent GC churn during LazyColumn scroll.
- [x] Ensure instantaneous UI response (0ms local display) while background network enrichment completes asynchronously.

## Implementation Steps

1. **Update `StationCardHelper.kt` & `StationCard.kt`:**
   - Implement `formatPowerDistributionSummary` in `StationCardHelper` with grouping, aggregation, and descending sort.
   - Add the styled marquee row displaying `120kW x 6 | 60kW x 10 | 30kW x 20` below the station title in `CompactStationCardContent`.
2. **Update `FavoritesViewModel.kt` & `FavoritesLandscapeScreen.kt`:**
   - Add throttled `enrichFavoritesWithTelemetry(stations: List<Station>)` using `Semaphore(3)` in `FavoritesViewModel`.
   - Observe `stationDetailCoordinator.stationDetailState` to sync live port counts back into the selected favorite station card.
   - Connect tab selection in `FavoritesLandscapeScreen` to trigger on-demand enrichment if data is unverified.
3. **Implement Single Verification Test:**
   - Create `com.evcs.favorites.ui.StationCardPowerDistributionAndFavoritesRealtimeTest` to verify:
     - `formatPowerDistributionSummary` groups duplicate tiers and sums counts correctly.
     - Formatter produces descending sorted list (e.g. `120kW` before `60kW` before `30kW`).
     - Annotated string builder assigns contrasting styles to power vs count.
     - Favorites telemetry enrichment updates station models with live available plug counts upon on-demand trigger.
     - Sync-back correctly updates the target station in the favorites list.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCardHelper.kt` (MODIFY)
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` (MODIFY)
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` (MODIFY)
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/FavoritesLandscapeScreen.kt` (MODIFY)
- `app/src/test/java/com/evcs/favorites/ui/StationCardPowerDistributionAndFavoritesRealtimeTest.kt` (NEW)

## Test Criteria (Single Test)
- Test Class: `com.evcs.favorites.ui.StationCardPowerDistributionAndFavoritesRealtimeTest`
- Verification Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StationCardPowerDistributionAndFavoritesRealtimeTest"`
- Assertions:
  - Aggregation of power ports yields sorted distribution pairs without duplicates.
  - Formatted power distribution summary produces expected structure.
  - Favorites enrichment updates `Station` instances with non-zero live telemetry plugs.
  - Sync-back updates target station in `FavoritesUiState.Success`.

---
Plan Complete. Ready for Phase 01 execution upon user approval.
