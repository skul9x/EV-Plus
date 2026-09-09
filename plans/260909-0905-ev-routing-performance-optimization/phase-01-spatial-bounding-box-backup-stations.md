# Phase 01: Spatial Bounding Box Filter for Backup Stations

Status: ✅ Completed
Dependencies: None

## Objective
Eliminate brute-force iteration over the complete nationwide station dataset (~3,000 stations) in `selectBackupStation` by implementing a coarse spatial bounding box filter ($\Delta \text{lat} \le 0.1^\circ, \Delta \text{lng} \le 0.1^\circ$, equivalent to $\approx 11\text{ km}$). This ensures that only geographically proximate stations undergo trigonometric Haversine distance calculations and polyline projections, slashing route computation latency by >99% and preventing ANR / UI lockup during route generation.

## Requirements
### Functional
- Add a coarse bounding box filter in `selectBackupStation` in `EvSmartRoutePlanner.kt` before performing expensive Haversine trigonometric distance or polyline projection calculations:
  - If `abs(st.latitude - primaryCandidate.station.latitude) > 0.10` or `abs(st.longitude - primaryCandidate.station.longitude) > 0.10`, skip immediately.
- Handle zero or invalid station coordinates gracefully (`latitude == 0.0 && longitude == 0.0`).
- Ensure backup station pairing parity: stations within the 10.0 km radius must remain eligible and selected identically to the previous unconstrained algorithm.

### Non-Functional
- Performance: Backup station selection across 3,000+ stations must execute in under 15ms per charging stop (down from >1,500ms).
- Zero memory allocation: Coarse coordinate filtering must use simple primitive math (`kotlin.math.abs`) without instantiating intermediate objects or collections.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt`:
   - Locate the secondary fallback scan loop in `selectBackupStation`: `for (st in allStations)`.
   - Before evaluating `distanceFromPrimaryStationKm` or `projectPointOntoPolyline`, insert the coarse bounding box check:
     ```kotlin
     val latDiff = kotlin.math.abs(st.latitude - primaryCandidate.station.latitude)
     val lngDiff = kotlin.math.abs(st.longitude - primaryCandidate.station.longitude)
     if (latDiff > 0.10 || lngDiff > 0.10) continue
     ```
   - Retain all subsequent corridor, detour, and power checks for candidates that pass the bounding box filter.
2. Verify that no regressions occur in backup station selection accuracy or fallback handling.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt` - Add spatial bounding box filter to `selectBackupStation`.
- `app/src/test/java/com/evcs/favorites/data/routing/EvSmartRouteBackupStationSpatialFilterTest.kt` - Comprehensive single verification test for Phase 01.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.data.routing.EvSmartRouteBackupStationSpatialFilterTest`
- **Execution Command:** `./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.routing.EvSmartRouteBackupStationSpatialFilterTest"`
- **Key Assertions:**
  1. Stations outside the $0.1^\circ$ spatial bounding box (e.g. distant cities across Vietnam) are skipped before any distance or projection calculations.
  2. Nearby candidate stations within $10\text{ km}$ and within the bounding box are correctly evaluated and paired as backup stations.
  3. Benchmarked simulation over 3,000 synthetic stations confirms >99% reduction in projection evaluations and instantaneous execution time.
  4. Edge cases (identical coordinates, bounding box boundary, empty lists, zero coordinates) produce safe, robust behavior without exceptions.

---
Next Phase: [Phase 02: Background Coroutine Dispatch for Alternate Station Swapping](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0905-ev-routing-performance-optimization/phase-02-background-coroutine-swap-dispatch.md)
