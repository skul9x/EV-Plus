# Phase 01: VinFast Station Domain Modeling, Search Payload & URL Builder Fix
Status: ✅ Completed
Dependencies: None

## Objective
Standardize VinFast charging station domain mapping, fix `StationUrlBuilder` canonical URL generation to eliminate 404 errors (verified against live curl capture `curl_capture_20260905_134301`), and update `SearchRequest` to retrieve all stations (both AC and DC) with a clean `{"latitude": ..., "longitude": ...}` payload matching official web capture behavior.

## Requirements
### Functional
- [x] In `StationModels.kt`:
  - Add `val evse: String = "VinFast"` to domain model `Station`.
  - Update `SearchRequest`:
    - Remove hardcoded `wattageTypes = listOf("FAST", "SUPER_FAST")`.
    - `SearchRequest` should only serialize `{"latitude": ..., "longitude": ...}` without the `wattageTypes` key, exactly matching live web capture `20260905_134205_POST_search.txt`.
    - Ensure Kotlin serialization produces clean JSON payload without extra fields.
- [x] In `EvcsRepository.kt`:
  - Map `evse = raw.evse ?: "VinFast"` inside `SearchStationRaw.toDomainStation()`.
  - Maintain backwards compatibility in `Station.toFavoriteStationRaw()` and existing extensions.
- [x] In `StationUrlBuilder.kt`:
  - Update `buildStationDetailUrl(name: String, locationId: String, baseUrl: String = BASE_URL, evse: String? = null): String`:
    - Determine provider: `val isVinFast = evse?.equals("VinFast", ignoreCase = true) ?: normalizedSlug.startsWith("vinfast")`.
    - For VinFast stations (`isVinFast == true`):
      - Strip any existing `"vinfast-"` prefix from `normalizedSlug` (and set to `""` if `normalizedSlug == "vinfast"`) to prevent duplicate `"tram-sac-vinfast-vinfast-..."`.
      - Construct prefix: `if (normalizedSlug.isNotEmpty()) "$baseUrl/tram-sac-vinfast-$normalizedSlug" else "$baseUrl/tram-sac-vinfast"`.
      - Construct ID suffix: if `cleanLocId.lowercase().startsWith("c.")`, use `cleanLocId.lowercase()`; otherwise use `cleanLocId.lowercase()` (e.g. `c.bni0031.html` or `hn001.html` for backward compatibility).
      - Produce: `"$prefix-${cleanLocId.lowercase()}.html"`.
    - For partner stations (`isVinFast == false`):
      - Provider slug: `val providerSlug = slugify(evse).ifBlank { "partner" }` or use `slug` if no `evse`.
      - Strip provider prefix if present.
      - Normalize partner ID: strip any leading `"c."` or `"C."` from `cleanLocId` before prepending `"-c."` to avoid double `"-c.C."` suffixes.
      - Produce: `"$prefix-c.$cleanPartnerId.html"`.
  - Overload `buildStationDetailUrl(station: Station, baseUrl: String = BASE_URL)`:
    - Pass `station.name`, `station.id`, `baseUrl`, and `station.evse`.
- [x] In `EvcsApiClient.kt`:
  - Update `searchStations`:
    - Remove default `wattageTypes` argument; serialize clean `SearchRequest(latitude, longitude)`.
  - Update `fetchStationHtml`:
    - Accept `evse: String = "VinFast"`:
      `open suspend fun fetchStationHtml(stationName: String, locationId: String, evse: String = "VinFast"): Result<String>`
    - Overload: `open suspend fun fetchStationHtml(station: Station): Result<String> = fetchStationHtml(station.name, station.id, station.evse)`.

### Non-Functional
- [x] Pure Kotlin logic, zero Android framework dependencies.
- [x] Zero reflection overhead, fully compatible with kotlinx.serialization.
- [x] 100% backwards compatible with existing tests and callers.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt`:
   - Add `val evse: String = "VinFast"` to `Station`.
   - Update `SearchRequest` to only contain `latitude: Double` and `longitude: Double`.
2. [x] Update `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt`:
   - Update `searchStations` signature and payload construction.
   - Update `fetchStationHtml` to support `evse` parameter and `Station` overload.
3. [x] Update `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   - Map `evse` in `SearchStationRaw.toDomainStation()`.
4. [x] Update `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt`:
   - Implement slug deduplication, `evse` provider detection, and clean ID suffix normalization.
5. [x] Implement single comprehensive test: `app/src/test/java/com/evcs/favorites/util/VinFastStationMappingAndUrlBuilderTest.kt`.
6. [x] Run verification test: `./gradlew test --tests "com.evcs.favorites.util.VinFastStationMappingAndUrlBuilderTest"`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - Add `evse` to `Station`, update `SearchRequest`
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - Clean search payload without `wattageTypes`, add `evse` support to `fetchStationHtml`
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Map `evse` in `toDomainStation`
- `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt` - Fix VinFast canonical URL logic and partner prefix handling
- `app/src/test/java/com/evcs/favorites/util/VinFastStationMappingAndUrlBuilderTest.kt` - Single comprehensive test for Phase 01

## Test Criteria
- [x] Real captured station mapping: `SearchStationRaw(stationName = "Cửa hàng xăng dầu Tiến Minh Cách Bi", locationId = "C.BNI0031", evse = "VinFast").toDomainStation()` yields `Station.evse == "VinFast"`.
- [x] Real captured station URL: `StationUrlBuilder.buildStationDetailUrl(station)` for station with `name = "Cửa hàng xăng dầu Tiến Minh Cách Bi"`, `id = "C.BNI0031"`, `evse = "VinFast"` produces `https://evcs.vn/tram-sac-vinfast-cua-hang-xang-dau-tien-minh-cach-bi-c.bni0031.html`.
- [x] Name containing "VinFast" does NOT produce duplicate `vinfast-vinfast`: `"VinFast Mega Mall Smart City"`, `id = "C.HN005"`, `evse = "VinFast"` produces `https://evcs.vn/tram-sac-vinfast-mega-mall-smart-city-c.hn005.html`.
- [x] Name without other words `"VinFast"`, `id = "C.BNI0012"`, `evse = "VinFast"` produces `https://evcs.vn/tram-sac-vinfast-c.bni0012.html`.
- [x] Partner station with `"C."` prefix does NOT produce double `"-c.C."`: `name = "Audi Hà Nội"`, `id = "C.EVO001"`, `evse = "EV ONE"` produces `https://evcs.vn/tram-sac-ev-one-audi-ha-noi-c.evo001.html`.
- [x] `SearchRequest(latitude = 21.1388332, longitude = 106.1808943)` serializes to JSON string without `"wattageTypes"` key.

## Notes
- Single test verification only: Run `VinFastStationMappingAndUrlBuilderTest` upon phase completion and stop for user review.

---
Next Phase: [Phase 02: Real-Time GPS Refresh & Location Error Handling](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1400-vinfast-station-fixes-prefilter-and-gps-refresh/phase-02-realtime-gps-refresh.md)
