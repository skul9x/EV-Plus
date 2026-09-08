# Phase 03: Smart EV Corridor Route Planner Engine
Status: 🟢 Completed
Dependencies: [Phase 02: EV Routing Settings & Vehicle Profile Configuration](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-02-ev-routing-settings-and-preferences.md)

## Objective
Implement `EvSmartRoutePlanner`, the algorithmic brain of EV-Plus smart routing. It consumes an Origin, Destination, EV specifications, and VinFast station dataset to generate an optimal route with required charging stops, highway anti-trap filtering, power hierarchy selection, dead-zone warnings, live vacancy estimations, and energy depletion profiles.

## Requirements
### Functional
- [x] **Base Polyline & Distance Profiling:**
  - Route calculation using existing `MultiTierRoutingCoordinator` (OSRM primary / Google Routes fallback).
  - Prefix-sum cumulative distance calculation along the polyline path: $H[i] = \sum_{k=1}^i \text{dist}(P_{k-1}, P_k)$.
- [x] **Spatial Corridor Buffer Filtering:**
  - Scan local VinFast stations and retain candidates within corridor buffer distance $D_{\text{buffer}} \le 4.0\text{ km}$ from the route polyline.
  - Compute snap-point projection index and distance from origin along the route for each candidate.
- [x] **Highway Dual-Carriageway Anti-Trap:**
  - Detect highway/expressway segments.
  - Compute detour routing distance from candidate station back to the ongoing route direction. If detour $> 3.0\text{ km}$ (indicating a divided expressway opposite-lane trap requiring an impossible or distant U-turn), reject station immediately.
- [x] **Charger Power Hierarchy:**
  - Prioritize Super/Ultra-Fast DC ($\ge 60\text{ kW}$) over Standard DC ($30\text{ kW}$).
  - Strictly exclude slow AC ($\le 11\text{ kW}$) from mid-trip stop suggestions.
- [x] **Multi-Stop Greedy Leapfrog Scheduler:**
  - Starting with `startBatteryPercent` (default 100%), compute safe travel distance before dipping below `arrivalBufferSocPercent` (default 10%).
  - If destination is within safe range, output 0 charging stops.
  - If destination is beyond safe range:
    - Select optimal charging stop within the reach window.
    - If no station exists in the reach window: raise `DeadZoneWarning` with unreachability details.
    - If station selected: compute arrival SoC %, estimate charging duration to reach `targetChargingSocPercent` (default 85%), apply duration buffer (+25% if enabled), set departure SoC to `targetChargingSocPercent`, and repeat for subsequent legs.
- [x] **Busy Station Handling (0 Live Plugs):**
  - If best station currently has 0 available plugs, still propose it but flag status as `STATION_BUSY` with estimated queue/clear time based on live telemetry, and provide alternative stations within reach.
- [x] **Energy Corridor Profile:**
  - Output an array of `EnergyWaypoint(distanceKm, batteryPercent, isChargingStop)` to render the visual battery depletion graph.

### Non-Functional
- [x] Route calculation and stop planning completed in < 1500ms on real devices.
- [x] Deterministic output for reproducible unit testing.

## Implementation Steps
1. [x] Define route result models in `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt`:
  - `EvSmartRoutePlan`
  - `EvRouteStop`
  - `EnergyWaypoint`
  - `DeadZoneWarning`
2. [x] Implement `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt`.
3. [x] Implement comprehensive test in `app/src/test/java/com/evcs/favorites/data/routing/EvSmartRoutePlannerTest.kt`.


## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt` - Domain models for smart routing results.
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt` - Core corridor planning & anti-trap engine.
- `app/src/test/java/com/evcs/favorites/data/routing/EvSmartRoutePlannerTest.kt` - Phase 3 verification test.

## Verification Test (Exactly One File-Based Test)
- Test Class: `com.evcs.favorites.data.routing.EvSmartRoutePlannerTest`
- Execution Command:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.routing.EvSmartRoutePlannerTest"
  ```

---
Next Phase: [Phase 04: Three-Tab Navigation & Route Screen UI](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-04-navigation-tabs-and-route-screen-ui.md)
