# Phase 03: Live Cluster Search & Background Telemetry Re-Enrichment
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Re-enable and optimize targeted geographic cluster search enrichment for saved favorite stations so that stations loaded from Firestore/cache with unverified or stale telemetry are seamlessly refreshed with live server port counts, depot statuses, and coordinates without delaying initial render.

## Requirements
### Functional
- [x] Non-Blocking Cluster Telemetry Re-Enrichment in `EvcsRepository.getFavorites`:
  - Return cached favorites immediately (0ms) so UI displays instantly.
  - Check if any favorite stations require telemetry re-enrichment (e.g. `powers.isEmpty()`, `totalPlugs <= 0`, missing coordinates, or explicit refresh).
  - Execute background cluster search queries:
    - Collect coordinates of favorites and group them into geographic clusters using `DistanceCalculator.clusterCoordinates` (15km radius).
    - Concurrently fetch `apiClient.searchStations(clusterLat, clusterLon)` for each cluster.
    - Merge matched search results (`ApiStation`) into domain `Station` models, enriching `powers`, `totalPlugs`, `totalAvailablePlugs`, `depotStatus`, `isFreeParking`, and `workingTimeDescription`.
- [x] Cache & Persistence Synchronization:
  - Update `_favoritesState` with enriched stations.
  - Save enriched stations into local cache and sync to `firestoreFavoritesRepository` when changes occur.
- [x] Connect with `FavoritesViewModel`:
  - Ensure manual pull-to-refresh (`refreshFavorites()`) triggers full cluster re-enrichment.
  - Ensure station updates from re-enrichment seamlessly update the UI state and detail coordinator without flickering.

## Files to Create/Modify
- [EvcsRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt) - Implement cluster re-enrichment pipeline in `getFavorites` when `firestoreFavoritesRepository != null`.
- [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt) - Connect pull-to-refresh and background re-enrichment updates.
- [FavoritesClusterSearchAndTelemetryEnrichmentTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/FavoritesClusterSearchAndTelemetryEnrichmentTest.kt) - Single comprehensive test for Phase 03.

## Test Criteria (Single Verification Test)
- `FavoritesClusterSearchAndTelemetryEnrichmentTest.kt` verifying:
  1. `getFavorites` immediately emits cached favorites when `firestoreFavoritesRepository` is present.
  2. Geographic cluster coordinates are grouped correctly for favorite stations.
  3. Search results are matched by location ID and merged into domain stations, re-populating missing powers and port counts.
  4. Enriched stations update repository state and trigger Firestore/local cache persistence.

---
Next Phase: None (Plan Complete)
