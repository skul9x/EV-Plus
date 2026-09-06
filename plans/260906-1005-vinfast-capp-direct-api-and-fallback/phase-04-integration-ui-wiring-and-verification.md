# Phase 04: Integration, UI Wiring & Verification

Status: ✅ Completed  
Dependencies: [Phase 01: VinFast CAPP API Client & Headers](./phase-01-vinfast-capp-api-client-and-headers.md), [Phase 02: DTO Mapping & EV Car Port Filtering](./phase-02-dto-mapping-and-ev-car-port-filtering.md), [Phase 03: Dual-Tier Station Repository & Fallback](./phase-03-multi-tier-station-repository-and-fallback.md)  
Reference Sources:
- `app/src/main/java/com/evcs/favorites/MainActivity.kt`
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`
- `app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt`

---

## 1. Objective

Wire `DualTierStationRepository` into the application's dependency graph in `MainActivity.kt`. Ensure that station listings, live plug availability chips, and station detail bottom sheets receive live telemetry directly from VinFast CAPP endpoints while preserving 100% compatibility with user favorites in Cloud Firestore (`users/{uid}/userdata/favorites`). Verify end-to-end flow with comprehensive integration tests.

---

## 2. Requirements

### Functional Requirements
1. **Dependency Injection & Wiring in `MainActivity.kt`**:
   - Initialize `VinFastHeaderInterceptor` using `applicationContext` for persistent client UUID generation.
   - Initialize `VinFastCAppApiClient` (sharing application-level OkHttp connection pool via `AppOkHttpClientProvider.newSharedClientBuilder()`).
   - Instantiate `VinFastStationMapper`.
   - Instantiate `DualTierStationRepository` subclassing `EvcsRepository`, injecting `vinFastApiClient`, `mapper`, `apiClient`, `cacheStorage`, `autoResolveCoordinates = true`, and `firestoreFavoritesRepository`.
   - Pass `DualTierStationRepository` to `FavoritesViewModel.provideFactory` and `NearbyViewModel.provideFactory`.
   - Because `DualTierStationRepository` is an `EvcsRepository`, ViewModels and their factories require zero breaking constructor changes, preserving all 17 existing unit test suites.
2. **Favorites Screen Telemetry Flow**:
   - `FavoritesViewModel` loads user favorites locally (0ms offline-first) from `FirestoreFavoritesRepository`.
   - Fresh telemetry is obtained via `DualTierStationRepository.getFavorites(userLat, userLon)`:
     - Tier 1: Calls `VinFastCAppApiClient.getLocationInfo(ids)` to obtain live connector status directly from VinFast.
     - Tier 2 Fallback: If VinFast fails, gracefully falls back to EVCS search or cached snapshot.
   - Preserves routing pipeline, shortest-ETA sorting, and user favorites modifications.
3. **Nearby Stations Screen Telemetry Flow**:
   - `NearbyViewModel` queries `DualTierStationRepository.searchNearbyVinFast(lat, lon)`.
   - Motorbike plugs (3.5kW/7kW) and pure-motorbike stations are filtered out by `VinFastStationMapper`.
   - UI chips display accurate automobile EV plug counts (`totalAvailablePlugs` and `totalPlugs` re-aggregated strictly from car bays).
4. **Data Source Transparency**:
   - Telemetry source indicator (`sourceTier = "VINFAST_DIRECT"` vs `"EVCS_FALLBACK"`) is available on each `Station` model and logged to `AppDebugLogger`.
5. **Firestore Favorites Compatibility**:
   - `locationId` scheme is 100% identical between VinFast and `evcs.vn`.
   - Existing user favorites under `/users/{uid}/userdata/favorites` continue working seamlessly without any data migration.

### Non-Functional Requirements
- Backwards compatibility: zero breaking changes to existing ViewModels or tests.
- Smooth Compose UI rendering without recomposition loops or latency spikes.

---

## 3. Implementation Steps

1. **Wire in `MainActivity.kt`**:
   - Update `repository` lazy initializer to instantiate `DualTierStationRepository`.
2. **End-to-End Integration Verification**:
   - Write integration tests in `VinFastDirectIntegrationTest.kt` validating that real-world payloads populate UI state accurately with car-only ports in both Tier 1 direct mode and Tier 2 fallback mode.

---

## 4. Files to Create / Modify

- **Modify File**:
  - `app/src/main/java/com/evcs/favorites/MainActivity.kt`
- **Test File**:
  - `app/src/test/java/com/evcs/favorites/VinFastDirectIntegrationTest.kt`

---

## 5. File-Based Test Plan (`VinFastDirectIntegrationTest.kt`)

Create `app/src/test/java/com/evcs/favorites/VinFastDirectIntegrationTest.kt`:
1. `testFullFlow_VinFastDirectToViewModel()`:
   - Setup `MockWebServer` serving sample VinFast response with:
     - 1 station with 3.5kW (bike), 7kW (bike), 11kW (car), and 60kW (car) plugs.
   - Wire `VinFastCAppApiClient` -> `VinFastStationMapper` -> `DualTierStationRepository` -> `NearbyViewModel`.
   - Trigger nearby search.
   - Assert station is loaded with:
     - `station.powers.size == 2` (only 11kW and 60kW).
     - Live plug counts match car plugs only (excluding bike plugs).
     - `station.sourceTier == "VINFAST_DIRECT"`.
2. `testFullFlow_FallbackToEvcsWhenVinFastUnavailable()`:
   - Configure MockWebServer to return HTTP 401 / 503 for VinFast endpoint.
   - Configure MockWebServer to return HTTP 200 for EVCS fallback endpoint.
   - Trigger nearby search.
   - Assert `uiState.value.rawStations` contains stations served from fallback tier (`EVCS_FALLBACK`).
   - Assert UI does not show error state or crash.
3. `testFirestoreFavoritesCompatibility()`:
   - Provide Firestore favorite document with ID `"C.HNO11417"`.
   - Verify VinFast station with `locationId = "C.HNO11417"` matches and links properly without ID mismatch.

---
Master Plan: [Master Plan Overview](./plan.md)
