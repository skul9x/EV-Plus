# Phase 01: HERE AC Discovery & EVCS HTML Name Resolver
Status: 🟢 Completed
Dependencies: None

## Objective
Implement the core network engine that queries HERE Maps EV API (`ev-v2.cc.api.here.com/ev/stations.json`) for AC charging posts (11kW and 22kW), enforces strict availability (`numberOfAvailable > 0` and status `AVAILABLE`), selects the top 10 closest stations by distance, and resolves their canonical station names concurrently from EVCS HTML detail pages (`https://evcs.vn/tram-sac-vinfast-${locationId}.html`) without persistent caching.

## Requirements
### Functional
- [x] Add `fetchNearbyAvailableAcStations(latitude: Double, longitude: Double, radiusMeters: Int = 10_000, maxLimit: Int = 10): Result<List<Station>>` to `HereEvApiClient.kt`.
- [x] Filter stations from HERE response strictly to those containing at least one AC connector where `maxPowerLevel in (11.0, 22.0)` and `numberOfAvailable > 0` and connector status is `AVAILABLE`.
- [x] Exclude stations where all AC ports are occupied (`OCCUPIED`), faulty/unknown (`OTHER`), or out of service (`OUT_OF_SERVICE`).
- [x] Calculate Haversine distance from target coordinates, sort qualifying stations in ascending order, and take up to `maxLimit` (default 10) items.
- [x] Implement `resolveStationNameFromHtml(locationId: String, fallbackAddress: String = ""): String` in `EvcsStationNameResolver.kt` (or helper utility):
  - Fetches canonical HTML page: `https://evcs.vn/tram-sac-vinfast-${locationId.lowercase()}.html`.
  - Uses `EvcsApiClient.USER_AGENT_BROWSER` mobile headers.
  - Extracts title text from `<title>` or `<meta name="title">`, stripping prefix `"Trạm sạc VinFast - "` and suffix `" - Trạm Sạc EV"`.
  - If network request fails, times out, or HTML lacks a title, falls back to `"VinFast - ${fallbackAddress}"`.
- [x] Concurrently resolve names for all candidate stations using `coroutineScope` and `async/awaitAll` with bounded 5-second timeout.
- [x] Do not save resolved names into any persistent storage or in-memory DB per user specification.
- [x] Map resolved stations into standard `Station` domain models with correct `PowerPort` lists (11kW/22kW AC ports with verified live counts, plus any accompanying DC ports present at the station).

### Non-Functional
- [x] Concurrency & Latency: Parallelize the 10 HTML requests so total resolution overhead is kept under 2 seconds.
- [x] Resilience: Cloudflare HTML requests must not throw uncaught exceptions; any single station failure gracefully degrades to fallback address without failing the entire batch.
- [x] Security: Sanitize all extracted names via `StationNameSanitizer`.

## Implementation Steps
1. [x] Update `HereEvApiClient.kt` to parse connector AC tiers (11kW, 22kW) and check live available counts against `AVAILABLE` status.
2. [x] Add sorting by distance and capping to top 10 stations in `HereEvApiClient.kt`.
3. [x] Update `EvcsStationNameResolver.kt` with `resolveStationNameFromHtml` (and concurrent batch resolver `resolveStationNamesBatch`) using mobile browser headers.
4. [x] Wire `HereEvApiClient` and `EvcsStationNameResolver` to produce fully enriched domain `Station` objects.
5. [x] Create the single comprehensive test file `app/src/test/java/com/evcs/favorites/data/network/here/HereAcDiscoveryAndHtmlNameResolutionEngineTest.kt` verifying:
   - AC tier matching (11kW, 22kW included; 3.5kW, 7kW excluded).
   - Strict availability filter (stations with 0/1 available or non-AVAILABLE state excluded).
   - Proximity sorting and top 10 capping.
   - HTML title extraction and cleaning.
   - Fallback to `"VinFast - $address"` on HTTP failure.
   - Live resolution without cache retention.
6. [x] Execute the single test via Gradle to confirm verification passes.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/network/here/HereEvApiClient.kt` - [MODIFY] Add AC discovery and strict availability filtering.
- `app/src/main/java/com/evcs/favorites/focus/EvcsStationNameResolver.kt` - [MODIFY] Add concurrent HTML title extraction and fallback logic.
- `app/src/test/java/com/evcs/favorites/data/network/here/HereAcDiscoveryAndHtmlNameResolutionEngineTest.kt` - [NEW] Single comprehensive unit test suite for Phase 01.

## Test Criteria
- [x] Only stations with available 11kW or 22kW ports are returned.
- [x] Stations with `OCCUPIED`, `OTHER`, or `OUT_OF_SERVICE` status are excluded even if total plugs > 0.
- [x] Exactly top N nearest stations are returned in ascending distance order.
- [x] Valid HTML responses successfully resolve canonical station names (e.g. `C.BNI11197` -> `"TƯ NHÂN Nguyễn Văn Đức"`).
- [x] Failed/404 HTML responses cleanly fallback to `"VinFast - ${address}"`.
- [x] Resolving the same station ID twice executes fresh lookups without cache retrieval.

---
Next Phase: [phase-02-nearby-hybrid-ac-flow-and-loading-ux.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-1652-here-evcs-hybrid-ac-station-resolver/phase-02-nearby-hybrid-ac-flow-and-loading-ux.md)
