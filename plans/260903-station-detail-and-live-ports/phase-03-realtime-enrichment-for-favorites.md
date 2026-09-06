# Phase 03: Real-Time Enrichment for Favorites
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Enrich all user favorite stations with accurate real-time plug counts (`availablePlugs / totalPlugs`, e.g. `30kW: trống 2/4`, `20kW: trống 2/2`) by extracting station coordinates and performing targeted HMAC search queries, regardless of where the device is physically located.

## Requirements
### Functional
- When favorites are retrieved from `/favorite.html`, resolve coordinates for each favorite station (via cached coordinates or lightweight detail metadata resolution).
- Group favorite station coordinates into geographic clusters (within search radius) and query the signed HMAC `/search` API for each cluster.
- Merge real-time telemetry into the favorite stations, updating:
  - `totalAvailablePlugs`
  - `powers` with exact `availablePlugs` / `totalPlugs`
  - Station status (`Hoạt động` with green badge when plugs are available, `Hết cổng` only if verified all plugs are occupied).
- Maintain graceful degradation: if search fails or network is offline, preserve baseline metadata and offline snapshot.

### Non-Functional
- Limit search calls to distinct geographic clusters (avoiding redundant calls for stations in the same district).
- Non-blocking asynchronous execution in `Dispatchers.IO`.

## Implementation Steps
1. Add station metadata/coordinate parser in `EvcsApiClient`: fetch station detail metadata when coordinates are unknown and cache them persistently.
2. Update `EvcsRepository.kt`:
   - Identify coordinates of favorite stations.
   - Cluster coordinates that are within ~15km of each other.
   - Execute targeted `/search` queries per cluster and merge live metrics.
3. Verify stations in distant provinces (e.g. Bắc Ninh) receive their live `2/4`, `2/2` status seamlessly even without device GPS.
4. Implement `FavoritesLiveEnrichmentTest.kt` verifying coordinate extraction, cluster batching, and accurate domain model enrichment.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - [Modify] Metadata and coordinate resolution
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [Modify] Targeted cluster-based enrichment
- `app/src/main/java/com/evcs/favorites/domain/location/DistanceCalculator.kt` - [Modify] Coordinate clustering helper
- `app/src/test/java/com/evcs/favorites/FavoritesLiveEnrichmentTest.kt` - [New] Core verification test

## Test Criteria
- Verify coordinate resolution from station detail metadata.
- Verify cluster algorithm groups nearby stations and produces minimal search query centers.
- Verify repository merges raw favorites with cluster search responses to produce exact live port metrics (e.g. 2/4, 2/2).

---
Plan Complete: [Return to Plan Overview](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-station-detail-and-live-ports/plan.md)
