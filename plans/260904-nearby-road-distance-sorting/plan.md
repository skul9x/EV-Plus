# Plan: Nearby Charging Stations Road-Distance (Driving Route) Re-sorting

Created: 2026-09-04
Status: 🟡 In Progress

## Overview
Resolve the UX discrepancy in the "Quanh đây" (Nearby) tab where candidate charging stations were previously displayed strictly in order of great-circle straight-line distance (Haversine / "đường chim bay") rather than actual navigable driving road distance ("đường bộ").

### Problem Statement
1. In `NearbyViewModel.executeFilterAndRoutingPipeline`, candidate stations are filtered by wattage/availability and Top 10 nearest stations are extracted via Haversine distance (`NearbyStationFilter.extractTopNearest`).
2. When `MultiTierRoutingCoordinator.calculateRoutes` completes and returns actual road distance (`distanceMeters`) and ETA (`durationSeconds`) via Google Routes v2 / OSRM, the metrics are merged into the Top 10 list using `.map { ... }`, but the list is **never re-sorted**.
3. Consequently, a station located 2.0 km away straight-line (but requiring a 5.5 km detour via road due to bridges/one-way streets) is displayed above a station located 2.5 km away straight-line that is only 2.8 km away via direct road. The UI displays `🚗 12 phút • 5.5 km` above `🚗 6 phút • 2.8 km`, confounding driver expectations.

### Proposed Architecture & Solution (Approach 1)
- Re-sort the Top 10 candidates immediately upon receiving driving route metrics from `MultiTierRoutingCoordinator`.
- Primary sorting key: Driving road distance (`drivingMetrics.distanceMeters` ascending).
- Secondary sorting key (tie-breaker): Driving duration / ETA (`drivingMetrics.durationSeconds` ascending).
- Fallback sorting key: Haversine distance in meters (`distanceKm * 1000.0` ascending) for any stations missing route metrics (e.g., routing timeouts or offline fallback).

## Phases

| Phase | Name | Status | Verification Test | Progress |
|-------|------|--------|-------------------|----------|
| 01 | Domain Road-Distance Sorting Logic & Comparator Engine | ⬜ Pending | `NearbyDrivingMetricsSortTest.kt` | 0% |
| 02 | ViewModel Pipeline Re-sorting Integration & State Verification | ⬜ Pending | `NearbyRoadDistanceRoutingIntegrationTest.kt` | 0% |

## Strict Testing Protocol
- Each phase defines **exactly one comprehensive file-based test** to verify its core functionality.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestName>`
- Stop after each phase verification for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Next Step: `/next`
