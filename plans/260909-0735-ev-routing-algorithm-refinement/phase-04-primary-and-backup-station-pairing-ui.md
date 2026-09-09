# Phase 04: Primary & Backup Station Pairing and Timeline UI

Status: ✅ Completed
Dependencies: [Phase 03: Insufficient Power Fallback Modal Flow & Criteria Relaxation](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0735-ev-routing-algorithm-refinement/phase-03-insufficient-power-fallback-dialog-and-flow.md)

## Objective
Pair each charging stop with a designated Primary Station and an adjacent, highway-safe Backup Station. Display the Backup Station directly on the Route Timeline cards with live plug vacancy telemetry and a 1-tap quick swap action, allowing drivers to immediately divert if the primary station is congested, offline, or occupied.

## Requirements
### Functional
- [x] In `EvSmartRoutePlanner`:
  - When evaluating candidate stops for each leg, select the top-scoring candidate as the primary stop (`stop.station`).
  - Identify the optimal `backupStation`:
    1. Proximity: Within $\le 10.0\text{ km}$ straight-line or corridor distance from the primary station.
    2. Highway safety: Must NOT be an opposite-carriageway highway trap (`isHighwayTrap == false`).
    3. Detour penalty: Perpendicular distance from route corridor $\le 5.0\text{ km}$.
    4. Energy reachability: Must be safely reachable from the previous stop ($arrivalSoc \ge 5\%$).
    5. Ranking: Prioritize live available plugs (`totalAvailablePlugs > 0`), higher power, and lower diversion distance.
  - Store `backupStation: Station?` in `EvRouteStop`.
  - Expose helper `distanceFromPrimaryStationKm(primary: Station, backup: Station): Double`.
- [x] In `RouteViewModel`:
  - Implement `swapStopWithBackup(stopIndex: Int)`:
    - Promotes `backupStation` to become the active stop using `recalculateWithAlternateStop`.
    - Seamlessly updates leg arrival SoC, charging duration, destination arrival SoC, and `energyProfile`.
    - Sets the old primary station as the new backup station, enabling 1-tap swap back if desired.
- [x] In `RouteScreen.kt` (Timeline UI):
  - In `TimelineChargingStopNode`, render a dedicated `BackupStationCard` directly below the primary station content:
    - Title: "TRẠM DỰ PHÒNG LÂN CẬN"
    - Station name and address summary.
    - Power badge (e.g. `⚡ 60 kW`).
    - Distance from primary station (e.g. `Cách trạm chính 3.5 km`).
    - Live plug availability badge (e.g. `🟢 Trống 2/4` or `🟠 Đang kín`).
    - High-visibility action button `[Đổi sang trạm này]` with minimum touch height $\ge 48\text{dp}$.
    - If `backupStation == null`, display a subtle secondary note: `Không có trạm dự phòng trong bán kính 10 km`.

### Non-Functional
- [x] Visual hierarchy: The primary station remains the dominant visual focal point with emerald branding; the backup station card uses a subtle secondary container (`DarkSurfaceVariant` / `DarkCardBackground` with 1dp outline).
- [x] Instantaneous Swap: Swapping to the backup station executes locally via in-memory geometry and energy formulas with zero network delay ($< 50\text{ ms}$).

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt`:
   - Add `val backupStation: Station? = null` to `EvRouteStop`.
   - Add distance helper `distanceFromPrimaryStationKm(primary: Station, backup: Station): Double`.
2. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt`:
   - Refine stop generation in `planRoute` to rank and select `backupStation` adhering to highway safety and $\le 10\text{ km}$ proximity.
   - Wire `backupStation` into `EvRouteStop` instantiation.
3. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt`:
   - Add `fun swapStopWithBackup(stopIndex: Int)`.
4. [x] Update `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt`:
   - Create `BackupStationCard` composable.
   - Embed `BackupStationCard` inside `TimelineChargingStopNode`.
   - Connect click handler to `viewModel.swapStopWithBackup(stop.stopIndex)`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt` - Add `backupStation` to `EvRouteStop`.
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt` - Compute and link backup stations for each leg.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteViewModel.kt` - Add `swapStopWithBackup` action.
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt` - Render `BackupStationCard` in timeline.
- `app/src/test/java/com/evcs/favorites/ui/screens/RoutePrimaryAndBackupStationUiTest.kt` - Comprehensive unit/UI test for Phase 04.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.ui.screens.RoutePrimaryAndBackupStationUiTest`
- **Key Assertions:**
  1. Each generated `EvRouteStop` with multiple nearby candidates has a non-null `backupStation`.
  2. The backup station is within $\le 10\text{ km}$ and is not an opposite-lane highway trap.
  3. Calling `swapStopWithBackup(stopIndex)` successfully swaps the primary station with the backup station and updates route energy calculations.
  4. The UI state correctly exposes the backup station name, power, live plug status, and relative distance.

---
Next Phase: Completed
