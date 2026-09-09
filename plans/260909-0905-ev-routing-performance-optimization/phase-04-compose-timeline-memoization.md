# Phase 04: Compose Route Timeline Optimization & Memoization

Status: ✅ Completed
Dependencies: Phase 02, Phase 03

## Objective
Prevent redundant trigonometric and string-parsing calculations during Jetpack Compose recomposition cycles by memoizing distance and charger power in `BackupStationCard`. Refine timeline stop rendering in `RouteResultsSection` to leverage stable keys and prevent unnecessary recompositions when unrelated route state updates occur.

## Requirements
### Functional
- In `BackupStationCard` in `RouteScreen.kt`, memoize both `distKm` and `powerKw` using `remember(primaryStation.id, backupStation.id)` so they are computed only when the paired stations change:
  ```kotlin
  val (distKm, powerKw) = remember(primaryStation.id, backupStation.id) {
      Pair(
          distanceFromPrimaryStationKm(primaryStation, backupStation),
          extractStationMaxPowerKw(backupStation)
      )
  }
  ```
- Ensure the formatted distance and power strings are stable and accurate across recompositions.
- In `RouteResultsSection`, ensure charging stop nodes and backup cards maintain stable identity so that changes to one stop or swapping stations do not force invalidation and recomposition across all stops.

### Non-Functional
- Frame rate stability: Zero dropped frames / jank when scrolling the route timeline or toggling controls.
- UI responsiveness: Recomposition overhead for each `BackupStationCard` reduced from >2ms to near 0ms.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt`:
   - In `BackupStationCard`:
     - Wrap `distanceFromPrimaryStationKm(primaryStation, backupStation)` and `extractStationMaxPowerKw(backupStation)` inside `remember(primaryStation.id, backupStation.id)`.
     - Derive `formattedDist` within the memoized block or derived state.
   - In `RouteResultsSection`:
     - Review `plan.stops.forEach`: ensure key stability and optimize rendering structures so stops do not re-render unnecessarily on unrelated state updates.
2. Build and verify using the comprehensive verification test.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/RouteScreen.kt` - Memoize calculations in `BackupStationCard` and optimize timeline rendering.
- `app/src/test/java/com/evcs/favorites/ui/screens/RouteTimelineMemoizationTest.kt` - Comprehensive single verification test for Phase 04.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.ui.screens.RouteTimelineMemoizationTest`
- **Execution Command:** `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.RouteTimelineMemoizationTest"`
- **Key Assertions:**
  1. Memoized distance calculation precisely matches `distanceFromPrimaryStationKm` for diverse station coordinate pairs.
  2. Memoized power calculation accurately reflects `extractStationMaxPowerKw` and remains stable across repeated invocations with the same station IDs.
  3. When station IDs change (e.g. after a station swap), the memoized values update correctly to reflect the new station properties.
  4. Station stop keys and timeline data models preserve structural equality and immutability across UI state flows.

---
All Phases Complete!
