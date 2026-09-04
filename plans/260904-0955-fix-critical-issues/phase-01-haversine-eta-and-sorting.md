# Phase 01: Haversine Fallback Duration & ETA Sort Order
Status: ✅ Completed
Dependencies: None

## Objective
Resolve ANDROID-LOGIC-001: Prevent Haversine straight-line fallback from assigning `durationSeconds = 0L` and falsely inverting the ETA sort order in `FavoritesViewModel`. Ensure distant fallback stations are assigned a realistic travel time heuristic (30 km/h urban average speed) and never sort ahead of nearby stations with valid road routes.

## Requirements
### Functional
- Update `MultiTierRoutingCoordinator.computeHaversine`:
  - Calculate estimated `durationSeconds` based on distance using a conservative urban driving speed heuristic (30 km/h ≈ 8.33 m/s).
  - If `distanceMeters > 0`, calculate `val duration = (distanceMeters / (30.0 * 1000.0 / 3600.0)).roundToLong().coerceAtLeast(60L)`.
  - If `distanceMeters <= 0`, duration is `0L`.
- Update `FavoritesViewModel.executeRoutingPipeline`:
  - Enhance station sorting comparator: ensure stations with valid road durations (`engineUsed != HAVERSINE`) sort by actual duration, and Haversine fallback stations sort consistently without jumping ahead of nearer stations.
- Verify `DrivingMetrics.formattedDuration` produces accurate durations for Haversine stations (e.g., 50 km produces "~1 giờ 40 phút" instead of "0 phút").

### Non-Functional
- Offline Reliability: 100% offline calculation with zero network overhead.
- No Regressions: Stations with valid OSRM / Google Routes matrix results remain unaffected.

## Implementation Steps
1. Update `computeHaversine` in `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` to calculate estimated duration.
2. Review and optimize sort comparator in `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`.
3. Create single test file `app/src/test/java/com/evcs/favorites/data/routing/HaversineRoutingEtaSortTest.kt`.
4. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.routing.HaversineRoutingEtaSortTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` - [MODIFY] Calculate estimated duration in `computeHaversine`
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Refine sorting comparator
- `app/src/test/java/com/evcs/favorites/data/routing/HaversineRoutingEtaSortTest.kt` - [NEW] Single comprehensive test for Phase 01

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.routing.HaversineRoutingEtaSortTest`
- [x] Verifies `computeHaversine` generates non-zero duration for positive distances (e.g. 50 km ~ 6000s, not 0s).
- [x] Verifies `formattedDuration` displays realistic time instead of "0 phút" for Haversine stations.
- [x] Verifies that in `FavoritesViewModel`, a 50 km Haversine station sorts *after* a 500m OSRM station (120s duration).
- [x] Verifies sorting stability when all stations use Haversine fallback (ordered ascending by distance/duration).

---
Next Phase: [Phase 02: Cancellation Propagation & Cache Cooldown Safety](phase-02-cancellation-cache-safety.md)
