# Phase 01: Offline Locations Dataset & Repository
Status: 🟢 Completed
Dependencies: None

## Objective
Bundle Vietnam's complete administrative hierarchy (63 provinces/cities and ~700 districts/towns) with precomputed driving centroid coordinates (`lat`, `lng`) into `app/src/main/assets/vietnam_locations.json`, and implement an offline-first, high-performance `VietnamLocationsRepository` to power origin/destination selection without network dependency.

## Requirements
### Functional
- [x] Bundle `vietnam_locations.json` (~26 KB) into `app/src/main/assets/`.
- [x] Define immutable models: `LocationCoordinate(lat: Double, lng: Double)`, `AdministrativeDistrict(name: String, coordinate: LocationCoordinate)`, and `AdministrativeProvince(name: String, districts: List<AdministrativeDistrict>)`.
- [x] Implement `VietnamLocationsRepository` to parse and cache the hierarchy in memory upon first access.
- [x] Provide querying APIs:
  - `getAllProvinces(): List<String>` (alphabetical order with major cities Hà Nội, Hồ Chí Minh, Đà Nẵng pinned at the top).
  - `getDistrictsForProvince(provinceName: String): List<AdministrativeDistrict>`.
  - `getCoordinate(provinceName: String, districtName: String): LocationCoordinate?`.
  - `findClosestDistrict(lat: Double, lng: Double): Pair<String, AdministrativeDistrict>?` (for GPS 1-tap reverse match).

### Non-Functional
- [x] Parse time < 50ms on cold start.
- [x] Zero network requests, 100% offline resilience.
- [x] Memory footprint < 1 MB.

## Implementation Steps
1. [x] Place sanitized `vietnam_locations.json` into `app/src/main/assets/`.
2. [x] Create model file `app/src/main/java/com/evcs/favorites/data/locations/AdministrativeLocation.kt`.
3. [x] Implement `app/src/main/java/com/evcs/favorites/data/locations/VietnamLocationsRepository.kt`.
4. [x] Implement comprehensive test in `app/src/test/java/com/evcs/favorites/data/locations/VietnamLocationsRepositoryTest.kt`.

## Files to Create/Modify
- `app/src/main/assets/vietnam_locations.json` - Bundled JSON dataset.
- `app/src/main/java/com/evcs/favorites/data/locations/AdministrativeLocation.kt` - Domain models.
- `app/src/main/java/com/evcs/favorites/data/locations/VietnamLocationsRepository.kt` - Asset loader & query engine.
- `app/src/test/java/com/evcs/favorites/data/locations/VietnamLocationsRepositoryTest.kt` - Phase 1 verification test.

## Verification Test (Exactly One File-Based Test)
- Test Class: `com.evcs.favorites.data.locations.VietnamLocationsRepositoryTest`
- Execution Command:
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.locations.VietnamLocationsRepositoryTest"
  ```

---
Next Phase: [Phase 02: EV Routing Settings & Vehicle Profile Configuration](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1955-ev-smart-routing-and-navigation/phase-02-ev-routing-settings-and-preferences.md)
