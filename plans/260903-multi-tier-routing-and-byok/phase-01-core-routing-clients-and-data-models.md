# Phase 01: Core Routing Clients & Data Models
Status: ✅ Completed
Dependencies: None

## Objective
Implement core domain models for driving metrics and routing configuration, and build standalone HTTP network clients for Tier 1 (Google Routes API v2 `computeRouteMatrix`) and Tier 2 (OSRM Table Service `table/v1/driving/`).

## Requirements
### Functional
- **Driving Models (`DrivingMetrics.kt`, `RoutingModels.kt`)**:
  - `DrivingMetrics`: contains `distanceMeters: Long`, `durationSeconds: Long`, `staticDurationSeconds: Long? = null`, `trafficCondition: TrafficCondition`, `engineUsed: RoutingEngineType`.
    - Computed properties: `formattedDuration: String` (e.g. `"15 phút"`, `"1 giờ 10 phút"`), `formattedDistance: String` (e.g. `"4.2 km"`, `"850 m"`).
  - `TrafficCondition`:
    - Computed via traffic delay ratio $R = \frac{\text{durationSeconds}}{\text{staticDurationSeconds}}$ when `staticDuration` is present:
      - `HEAVY_CONGESTION` ($R \ge 1.35$)
      - `MODERATE_CONGESTION` ($1.15 \le R < 1.35$)
      - `FREE_FLOW` ($R < 1.15$)
    - Fallback heuristic when `staticDuration` is absent: speed $v = \frac{\text{distance}}{\text{duration}}$: `FREE_FLOW` ($v \ge 30\text{ km/h}$), `MODERATE_CONGESTION` ($15 \le v < 30$), `HEAVY_CONGESTION` ($v < 15$), or `UNKNOWN`.
  - `RoutingEngineType`: `GOOGLE`, `OSRM`, `HAVERSINE`.
  - `RoutingEngineMode`: `AUTO`, `GOOGLE_ONLY`, `OSRM_ONLY`, `HAVERSINE_ONLY`.
  - `RoutingSettings`: `googleApiKey: String`, `preferredEngine: RoutingEngineMode`, `autoFallbackEnabled: Boolean`, `customOsrmServerUrl: String? = null`.
- **Google Routes Client (`GoogleRoutesClient.kt`)**:
  - HTTP `POST https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix`.
  - Headers:
    - `Content-Type: application/json`
    - `X-Goog-Api-Key: <key>`
    - `X-Goog-FieldMask: originIndex,destinationIndex,status,condition,distanceMeters,duration,staticDuration`
    - `X-Android-Package: com.evcs.favorites` (ensures keys restricted to Android Apps pass validation).
  - Payload: single origin waypoint, multiple destination waypoints, `travelMode: "DRIVE"`, `routingPreference: "TRAFFIC_AWARE"`.
  - Response parsing:
    - Parses JSON array of route elements with duration and staticDuration strings (e.g. `"185s"`, `"3600.5s"`).
    - Note on `condition`: enum `RouteMatrixElementCondition` (`ROUTE_EXISTS`, `ROUTE_NOT_FOUND`). Only parse distance/duration if `condition == "ROUTE_EXISTS"` or `status` is OK.
    - Computes `TrafficCondition` from duration vs staticDuration delay ratio.
    - Matches `destinationIndex` back to corresponding destination station ID.
    - Gracefully maps error HTTP status codes (400 invalid key, 403 billing/restriction/API not enabled, 429 quota limit, 500) into result failures.
- **OSRM Routing Client (`OsrmRoutingClient.kt`)**:
  - HTTP `GET https://router.project-osrm.org/table/v1/driving/{lon0},{lat0};{lon1},{lat1};...?sources=0&annotations=duration,distance`.
  - Free open-source endpoint without requiring user credentials; supports configurable base URL.
  - Header: `User-Agent: EVCSFavorites-Android/1.0` (required to prevent HTTP 403 Forbidden blocks from public OSM/Cloudflare WAF).
  - Matrix Index Mapping:
    - When calling with `sources=0` and omitting `destinations`, index 0 is origin.
    - Column 0 in `durations[0]` is origin -> origin (0.0).
    - Destination station $i$ ($0 \le i < N$) corresponds to matrix column $i + 1$ (`durations[0][i + 1]`, `distances[0][i + 1]`).
    - Handles `null` matrix cells safely where routes cannot be found (e.g. isolated pedestrian areas).
  - Safe error and timeout handling without crashing.

### Non-Functional
- Pure Kotlin coroutine-based asynchronous network execution (`suspend fun`).
- Resilient JSON serialization using `kotlinx.serialization` with optional/nullable fields and default fallbacks.
- Zero Android UI dependencies in client classes to enable fast unit testing on JVM.

## Implementation Steps
1. Create `DrivingMetrics.kt` and `RoutingModels.kt` in `app/src/main/java/com/evcs/favorites/data/routing/`.
2. Create `GoogleRoutesClient.kt` with OkHttp request construction (`X-Goog-FieldMask` with `staticDuration`, `X-Android-Package`) and response parsing for `v2:computeRouteMatrix`.
3. Create `OsrmRoutingClient.kt` with OkHttp request construction (custom `User-Agent`), coordinate formatting, and off-by-one matrix parsing (`matrixIndex = stationIndex + 1`) for OSRM table service.
4. Implement `RoutingClientsTest.kt` using `MockWebServer` to comprehensively verify both clients against mock Google and OSRM responses.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/DrivingMetrics.kt` - [New] Domain models for driving duration, distance, and traffic conditions
- `app/src/main/java/com/evcs/favorites/data/routing/RoutingModels.kt` - [New] Routing settings, engine modes, and raw API DTOs
- `app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt` - [New] Tier 1 Google Routes API v2 client
- `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt` - [New] Tier 2 OSRM Table Service client
- `app/src/test/java/com/evcs/favorites/RoutingClientsTest.kt` - [New] Comprehensive file-based verification test for Phase 01

## Test Criteria
- Verify `GoogleRoutesClient` sets `X-Goog-Api-Key`, `X-Goog-FieldMask` (containing `staticDuration`), and `X-Android-Package` headers, constructing correct `computeRouteMatrix` JSON body.
- Verify `GoogleRoutesClient` parses duration `"405s"` and staticDuration `"300s"` (ratio 1.35) into `HEAVY_CONGESTION` (Ùn tắc).
- Verify `GoogleRoutesClient` parses duration `"300s"` and staticDuration `"300s"` (ratio 1.0) into `FREE_FLOW` (Thông thoáng).
- Verify `GoogleRoutesClient` properly handles HTTP 400 (Invalid Key), HTTP 403 (Billing/Restriction), and HTTP 429 (Quota Exceeded) by returning failed Results without throwing unhandled exceptions.
- Verify `OsrmRoutingClient` builds correct coordinate format (`{lon},{lat}` semicolon-delimited) with `sources=0&annotations=duration,distance` and includes `User-Agent` header.
- Verify `OsrmRoutingClient` correctly maps matrix column $i + 1$ to destination station $i$, ignoring the origin diagonal cell at column 0.
- Verify `OsrmRoutingClient` handles null matrix cells and HTTP 500 server errors gracefully.

---
Next Phase: [Phase 02: Multi-Tier Coordinator & Fallback Arbitration](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-02-multi-tier-coordinator-and-fallback-arbitration.md)
