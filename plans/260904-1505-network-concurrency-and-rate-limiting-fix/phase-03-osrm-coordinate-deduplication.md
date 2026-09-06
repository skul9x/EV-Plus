# Phase 03: Station Deduplication & Coordinate Aggregation for OSRM Routing
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Prevent sending duplicate station IDs or identical coordinate pairs to OSRM matrix routing, eliminating redundant matrix calculations and bloated query URLs.

## Requirements
### Functional
- **Candidate Station ID Deduplication**:
  - Update `NearbyStationFilter.extractTopNearest` to explicitly deduplicate candidate stations by ID (`stations.distinctBy { it.id }`) before distance computation and slicing top candidates.
  - Update `MultiTierRoutingCoordinator.calculateRoutes` to sanitize incoming destinations with `validDestinations.distinctBy { it.id }`.
- **Coordinate Aggregation & Metric Fan-Out in `OsrmRoutingClient`**:
  - When constructing the OSRM table URL in `computeTable`:
    - Group destination stations by coordinate pair:
      `val groupedByCoord: Map<Pair<Double, Double>, List<RoutingDestination>> = destinations.groupBy { Pair(it.longitude, it.latitude) }`
      `val uniqueCoords = groupedByCoord.keys.toList()`
    - Append only origin and `uniqueCoords` to the OSRM request URL:
      `coordinatesBuilder.append(";${coord.first},${coord.second}")`
  - When decoding the matrix response:
    - Unique coordinate at index `i` corresponds to matrix column `columnIndex = i + 1`.
    - Extract `durationSec` and `distanceM` from column `columnIndex`.
    - Compute `DrivingMetrics`.
    - Fan out the computed `DrivingMetrics` to **all** station IDs associated with that coordinate:
      `for (dest in groupedByCoord[coord].orEmpty()) { resultMap[dest.id] = metrics }`
  - When multiple charging posts share identical coordinates (as observed in `debug-log.txt`: `106.089996,21.1675`), the coordinate appears only once in the OSRM query string, reducing URL size and matrix computation while ensuring all matching stations receive driving metrics.

### Non-Functional
- Backwards compatible with single-station queries.
- Zero loss of accuracy in distance, duration, or traffic condition computation.
- Safely handles unreachable destinations or empty destination lists.

## Implementation Steps
1. Update `NearbyStationFilter.kt` under `com.evcs.favorites.domain.filter` to deduplicate stations by ID.
2. Update `MultiTierRoutingCoordinator.kt` to sanitize destinations by unique ID.
3. Update `OsrmRoutingClient.kt` to aggregate unique destination coordinates and fan out metrics.
4. Create and run the single comprehensive test `OsrmCoordinateDeduplicationTest.kt`.

## Files to Create/Modify
- [MODIFY] `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - Deduplicate station candidates by ID in `extractTopNearest`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` - Sanitize destinations by unique ID.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt` - Aggregate unique coordinate pairs and fan out results.
- [NEW] `app/src/test/java/com/evcs/favorites/data/routing/OsrmCoordinateDeduplicationTest.kt` - Single comprehensive test for Phase 03.

## Test Criteria
- [x] OSRM table query URL contains no duplicate coordinate pairs even when multiple stations share identical coordinates.
- [x] Multiple stations sharing the same coordinate receive the correct driving distance and duration from the single matrix column.
- [x] Candidate lists with duplicate station IDs are sanitized to unique IDs before routing.
- [x] Matrix column count strictly equals `uniqueCoordinateCount + 1`.

## Verification Command
```bash
./gradlew test --tests "com.evcs.favorites.data.routing.OsrmCoordinateDeduplicationTest"
```
