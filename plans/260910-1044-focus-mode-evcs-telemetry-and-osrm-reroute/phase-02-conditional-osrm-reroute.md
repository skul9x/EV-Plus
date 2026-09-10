# Phase 02: Conditional OSRM Driving Matrix Reroute Engine
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Implement conditional, on-demand OSRM Driving Matrix rerouting. The alternative station resolution algorithm will ONLY execute when two conditions are simultaneously met: (1) target station DC slots are saturated (`availableDcSlots == 0`), AND (2) driver initiates a reroute action. The reroute engine executes an on-demand EVCS fast DC spatial query (`POST /search?t=...` with `wattageTypes = ["FAST", "SUPER_FAST"]`), pre-filters candidate stations via Haversine (top 5–8 eligible candidates), ranks them using `OsrmRoutingClient.computeTable` by actual road driving distance (`distanceMeters` ascending), and falls back seamlessly to Haversine if OSRM is unreachable or errors.

## Requirements
### Functional
- [x] Decouple alternative station calculation from every automatic 10s polling tick. Do not query or update `alternativeStation` while `availableDcSlots > 0`.
- [x] Provide explicit on-demand resolution method: `resolveAlternativeStationOnDemand(...)` triggered strictly via driver action.
- [x] On-demand candidate retrieval: Fetch candidate stations in the driver's vicinity using `EvcsApiClient.searchStations` with `FAST` and `SUPER_FAST` wattage filters.
- [x] Candidate pre-filtering: Take the top 5 to 8 stations with available DC slots (>= target station power tier) based on Haversine proximity.
- [x] OSRM Table Matrix query: Query `OsrmRoutingClient.computeTable()` from driver GPS coordinates to candidate coordinates.
- [x] Road network sorting: Sort candidates by actual driving distance (`distanceMeters`) ascending; select the closest candidate by road network.
- [x] Attach `DrivingMetrics` (distance in meters, duration in seconds) to the recommended `AlternativeStationRecommendation`.
- [x] Robust fallback: If `OsrmRoutingClient` returns failure, timeout, or empty matrix, fall back to ranking candidates by Haversine distance without crashing.

### Non-Functional
- [x] Bounded timeout on OSRM call (3,500ms) to ensure responsiveness while driving.
- [x] Zero background data waste: EVCS spatial candidate search and OSRM matrix are never polled in the continuous 10s loop.

## Implementation Steps
1. Enhance `AlternativeStationRecommendation` to include `drivingDurationSeconds: Long?` and `drivingDistanceMeters: Long?`.
2. Add OSRM-powered resolver in `FocusModeTelemetryEngine`:
   - Accept `OsrmRoutingClient` (optional or injected via constructor).
   - Implement `findAlternativeStationWithOsrm(...)`:
     1. On demand, query EVCS candidate stations.
     2. Filter valid candidates offering matching DC tier with `availablePlugs > 0`.
     3. Take top 8 candidates sorted by Haversine distance.
     4. Call `osrmClient.computeTable(driverLat, driverLon, candidateDestinations)`.
     5. On success: Re-sort candidates by `drivingMetrics.distanceMeters` ascending.
     6. On failure: Log debug warning and sort by Haversine distance.
3. Update trigger gating in `FocusModeTelemetryEngine`:
   - Automatic polling (`pollOnce()`) does NOT invoke OSRM.
   - When `availableDcSlots == 0`, mark reroute candidate as available for query or pre-stage state, but full route resolution is triggered on user demand.
4. Provide unit test in `app/src/test/java/com/evcs/favorites/focus/FocusModeOsrmRerouteTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/FocusModeState.kt` - Update `AlternativeStationRecommendation` to hold driving duration and distance metrics.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt` - Implement on-demand OSRM driving matrix candidate ranking and Haversine fallback.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeOsrmRerouteTest.kt` - [NEW] Single comprehensive unit test for Phase 02.

## Test Criteria
- Exactly one comprehensive test file: `FocusModeOsrmRerouteTest.kt` verifying:
  1. OSRM matrix reroute is NOT triggered when `availableDcSlots > 0`.
  2. Candidate ranking re-orders stations when road distance differs from Haversine (e.g. river/bridge scenario).
  3. Seamless fallback to Haversine ranking when OSRM API returns an error or network timeout.

---
Next Phase: [Phase 03: Vietnamese TTS Alert Voice Scripting & Floating HUD Integration](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260910-1044-focus-mode-evcs-telemetry-and-osrm-reroute/phase-03-voice-and-hud-integration.md)
