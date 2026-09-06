# Phase 03: Dual-Tier Station Repository & Fallback

Status: ✅ Completed  
Dependencies: [Phase 01: VinFast CAPP API Client & Headers](./phase-01-vinfast-capp-api-client-and-headers.md), [Phase 02: DTO Mapping & EV Car Port Filtering](./phase-02-dto-mapping-and-ev-car-port-filtering.md)  
Reference Sources:
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`
- `app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt`
- `app/src/main/java/com/evcs/favorites/data/logging/AppDebugLogger.kt`

---

## 1. Objective

Implement `DualTierStationRepository` to orchestrate station retrieval with automatic dual-tier fault tolerance. 
By subclassing `EvcsRepository`, `DualTierStationRepository` preserves 100% constructor and ABI compatibility with all existing ViewModels, DI factories, and 17 existing unit test suites, while transparently replacing internal data fetch flows with Tier 1 (Direct VinFast CAPP API) and automatically failing over to Tier 2 (`evcs.vn` HMAC-signed public API) on any error or timeout.

---

## 2. Requirements

### Functional Requirements
1. **Subclassing `EvcsRepository`**:
   - `DualTierStationRepository` extends `EvcsRepository`, inheriting all existing state flows (`favoritesState`, `favoriteIdsState`), coordinate caching, single-flight deduplication, and Firestore synchronization.
   - Requires zero breaking changes in `FavoritesViewModel` or `NearbyViewModel`.
2. **Tier 1 (Primary - VinFast CAPP API)**:
   - Enforces a 5-second fast-fail timeout via `VinFastCAppApiClient`.
   - On success:
     - Maps DTOs through `VinFastStationMapper`.
     - Filters out motorbike-only stations via `hasCarCompatiblePorts()`.
     - Tags stations with `sourceTier = "VINFAST_DIRECT"`.
     - Caches geographic coordinates into `coordinateCache`.
3. **Tier 2 (Fallback - EVCS Community Aggregator)**:
   - Triggered automatically if Tier-1 fails (e.g. `VinFastApiException`, HTTP 401 Unauthorized, HTTP 403 Forbidden, HTTP 5xx, or network timeout).
   - Logs fallback event with details to `AppDebugLogger`.
   - Invokes `super` methods in `EvcsRepository` (`super.searchNearbyVinFast` / `super.getFavorites`).
   - Ensures fallback stations also strip 3.5kW/7kW motorbike plugs and tag `sourceTier = "EVCS_FALLBACK"`.
4. **Operations & Dual-Tier Failover Flow**:
   - `override suspend fun searchNearbyVinFast(lat: Double, lon: Double): Result<List<Station>>`:
     - Tier 1: Calls `vinFastApiClient.searchStations(lat, lon, page = 0, size = 50)`.
     - If success: returns mapped car stations tagged `VINFAST_DIRECT`.
     - If failure: logs warning, calls `super.searchNearbyVinFast(lat, lon)` tagged `EVCS_FALLBACK`.
   - `override suspend fun getFavorites(userLat: Double?, userLon: Double?, autoResolveUnknownCoordinates: Boolean): Result<List<Station>>`:
     - If `firestoreFavoritesRepository != null`:
       - Gathers favorite IDs from local-first state.
       - If IDs non-empty, Tier 1: calls `vinFastApiClient.getLocationInfo(ids)`!
       - If success: returns enriched live telemetry tagged `VINFAST_DIRECT`.
       - If failure: logs warning, calls `super.getFavorites(...)` tagged `EVCS_FALLBACK`.
     - If `firestoreFavoritesRepository == null`:
       - Calls `super.getFavorites(...)`.
   - Explicit helper methods for new caller ergonomics:
     - `suspend fun getStationsByIds(ids: List<String>): Result<List<Station>>`
     - `suspend fun searchNearby(lat: Double, lon: Double, radiusMeters: Int = 15000): Result<List<Station>>`
5. **Graceful Failure Contract**:
   - If both Tier-1 and Tier-2 fail, returns `Result.failure(exception)` gracefully without crashing or throwing unhandled exceptions.

### Non-Functional & Performance Requirements
- Thread safety with Kotlin Coroutines (`Dispatchers.IO`).
- Sub-second failover transition (zero perceptible UI hang).
- Diagnostic logging via `AppDebugLogger` to trace which tier served the request.

---

## 3. Implementation Steps

1. **Create Repository Subclass**:
   - Path: `app/src/main/java/com/evcs/favorites/data/repository/DualTierStationRepository.kt`.
   - Subclass `EvcsRepository`, injecting `VinFastCAppApiClient` and `VinFastStationMapper`.
2. **Implement Failover Pipelines**:
   - Override `searchNearbyVinFast` and `getFavorites` with Tier 1 -> Tier 2 failover logic.
3. **Add Telemetry Source Indicator to Model**:
   - In `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt`, add:
     `val sourceTier: String = "VINFAST_DIRECT"` to data class `Station` (with default parameter).

---

## 4. Files to Create / Modify

- **New File**:
  - `app/src/main/java/com/evcs/favorites/data/repository/DualTierStationRepository.kt`
- **Modify File**:
  - `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` (add `val sourceTier: String = "VINFAST_DIRECT"`)
- **Test File**:
  - `app/src/test/java/com/evcs/favorites/DualTierStationRepositoryFallbackTest.kt`

---

## 5. File-Based Test Plan (`DualTierStationRepositoryFallbackTest.kt`)

Create `app/src/test/java/com/evcs/favorites/DualTierStationRepositoryFallbackTest.kt`:
1. `testTier1SearchSuccess_doesNotCallTier2()`:
   - Mock Tier 1 (`VinFastCAppApiClient`) returning valid station DTOs.
   - Mock Tier 2 (`EvcsApiClient`).
   - Call `repository.searchNearbyVinFast(21.0285, 105.8542)`.
   - Assert `result.isSuccess == true`.
   - Assert `result.getOrNull()?.first()?.sourceTier == "VINFAST_DIRECT"`.
   - Verify `EvcsApiClient.searchStations` was never called (0 interactions).
2. `testTier1SearchFailsWith401OrTimeout_seamlesslyFallsBackToTier2()`:
   - Mock Tier 1 to return `Result.failure(VinFastApiException(401, "Authenticate failed"))`.
   - Mock Tier 2 to return valid search stations.
   - Call `repository.searchNearbyVinFast(21.0285, 105.8542)`.
   - Assert `result.isSuccess == true`.
   - Assert `result.getOrNull()?.first()?.sourceTier == "EVCS_FALLBACK"`.
   - Verify fallback event was logged to `AppDebugLogger`.
3. `testTier1FavoritesSuccess_updatesFavoritesWithLiveTelemetry()`:
   - Provide favorite station ID `"C.HNO11417"`.
   - Mock Tier 1 `getLocationInfo` returning live telemetry.
   - Call `repository.getFavorites()`.
   - Assert `result.isSuccess == true`.
   - Assert `result.getOrNull()?.first()?.sourceTier == "VINFAST_DIRECT"`.
4. `testBothTiersFail_returnsCleanFailureWithoutCrash()`:
   - Mock Tier 1 and Tier 2 both failing.
   - Call `repository.searchNearbyVinFast(21.0285, 105.8542)`.
   - Assert `result.isFailure == true` and exception is safely encapsulated in `Result`.

---
Next Phase: [Phase 04: Integration, UI Wiring & Verification](./phase-04-integration-ui-wiring-and-verification.md)
