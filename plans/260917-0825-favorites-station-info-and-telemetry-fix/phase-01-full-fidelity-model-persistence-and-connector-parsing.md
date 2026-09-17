# Phase 01: Full-Fidelity Favorites Model Persistence & Connector Parsing
Status: ✅ Completed
Dependencies: None

## Objective
Ensure that station metadata (`powers`, `totalPlugs`, `totalAvailablePlugs`, `depotStatus`, `isFreeParking`, `workingTimeDescription`, `evse`) is never dropped or degraded when saving to or loading from Firestore and local cache, and enhance connector parsing to accurately extract port counts.

## Requirements
### Functional
- [x] Update `Station.toFirestoreMap()` to serialize full station metadata:
  - `total_plugs`: Int
  - `total_available_plugs`: Int
  - `depot_status`: String
  - `is_free_parking`: Boolean
  - `working_time`: String
  - `evse`: String
  - `powers`: List of serialized power specifications containing `type_watts`, `label`, `available_plugs`, `total_plugs`, and `display_string`.
- [x] Update `parseFirestoreFavoritesDocument()`:
  - Parse `powers`, `total_plugs`, `total_available_plugs`, `depot_status`, `is_free_parking`, `working_time`, and `evse` when present.
  - Maintain backward compatibility: if parsing legacy documents without these fields, gracefully fall back to existing defaults.
- [x] Enhance `EvcsRepository.parseConnectorsToPowers(connectors: String?)`:
  - Incorporate multiplier regexes (`x 4`, `(4)`, `/ 4`, `: 4`) from `StationTelemetryParser` so that `PowerPort` has accurate `totalPlugs` and `availablePlugs` instead of defaulting to 0.
- [x] Update `StationTelemetryParser.derivePortStatuses(powers: List<PowerPort>, busyByKw: Map<Int, Int>)`:
  - When `power.totalPlugs <= 0`, inspect `connectors` definitions via `parseConnectorDefinitions` before clamping `totalPorts` to `maxOf(busyByKw, 1)`.

## Files to Create/Modify
- [FirestoreFavoritesDataSource.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesDataSource.kt) - Enhance `toFirestoreMap` and `parseFirestoreFavoritesDocument`.
- [EvcsRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt) - Upgrade `parseConnectorsToPowers` with multiplier parsing.
- [StationTelemetryParser.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/StationTelemetryParser.kt) - Prevent synthetic 1-port clamping when connectors string defines count.
- [FavoritesPersistenceAndConnectorParsingTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/FavoritesPersistenceAndConnectorParsingTest.kt) - Single comprehensive test for Phase 01.

## Test Criteria (Single Verification Test)
- `FavoritesPersistenceAndConnectorParsingTest.kt` verifying:
  1. Full-fidelity Firestore map serialization and deserialization preserving all metadata fields.
  2. Legacy Firestore map fallback resilience.
  3. Connector string parsing correctly extracting counts from `"60kW x 4"`, `"120kW (2)"`, `"30kW: 2 cổng"`.
  4. Port status derivation preserving parsed multiplier counts without clamping to 1.

---
Next Phase: [phase-02-station-detail-state-synchronization-and-ac-safety.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260917-0825-favorites-station-info-and-telemetry-fix/phase-02-station-detail-state-synchronization-and-ac-safety.md)
