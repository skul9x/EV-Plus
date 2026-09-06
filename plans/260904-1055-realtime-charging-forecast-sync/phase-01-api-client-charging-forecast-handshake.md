# Phase 01: API Client Real-Time Charging Forecast Handshake
Status: ✅ Completed
Dependencies: None

## Objective
Implement the 2-step dynamic charging forecast API handshake in `EvcsApiClient` matching `web_charging.js` and EVCS server protocol, replacing static HTML scraping with lightweight real-time JSON responses.

## Requirements
### Functional
- [x] Define data models for dynamic charging forecast responses:
  - `UserPartialResponse`: holds `chargeToken: String? = null`, `apiToken: String? = null`.
  - `ChargingForecastResponse`: holds `ticker: String? = null`, `busyKw: Map<String, Int> = emptyMap()`, `partial: Boolean = false`.
- [x] Add `fetchChargingForecast(stationName: String, locationId: String, isVinFast: Boolean = true): Result<ChargingForecastResponse>` in `EvcsApiClient`:
  - Step 1: Send HTTP `POST` to canonical station detail URL (`StationUrlBuilder.buildStationDetailUrl(stationName, locationId, baseUrl)`) with header `X-Partial: user`, `Referer: $baseUrl/`, `Origin: $baseUrl`, and active session cookies.
  - Step 2: Extract `chargeToken` from response body JSON. If token is absent, return failure.
  - Step 3: Send HTTP `POST` to `${baseUrl}/charging` with headers `x-t: <chargeToken>`, `content-type: application/json`, and body `{"id":"<locationId>","t":"vinfast"}` (or `"other"`).
  - Step 4: Parse response JSON into `ChargingForecastResponse`.
- [x] Ensure safe response socket closure using `.use { ... }` or Kotlin serialization on response body to prevent OkHttp connection pool leaks.

### Non-Functional
- [x] Performance: Payload sizes drop from ~34KB (HTML) to ~500 bytes + ~300 bytes (JSON), reducing network consumption by >95%.
- [x] Resilience: Graceful fallback and error propagation when server returns non-200 or invalid token.

## Implementation Steps
1. [x] Add `UserPartialResponse` and `ChargingForecastResponse` in `com.evcs.favorites.data.model.StationModels.kt`.
2. [x] Implement `fetchChargingForecast` in `com.evcs.favorites.data.api.EvcsApiClient.kt` with OkHttp request builders and JSON serialization.
3. [x] Create single comprehensive MockWebServer test file `ChargingForecastApiClientTest.kt` verifying:
   - Request 1 sends POST with `X-Partial: user` and proper headers to canonical station URL.
   - Request 2 sends POST to `/charging` with `x-t: <chargeToken>` and correct JSON payload `{"id":..., "t":...}`.
   - Successful parsing of `ticker`, `busyKw`, and `partial` fields.
   - Error handling when step 1 fails (HTTP 500 / missing token) or step 2 fails (HTTP 403 / 500).

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - Add response data models.
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - Implement `fetchChargingForecast`.
- `app/src/test/java/com/evcs/favorites/data/api/ChargingForecastApiClientTest.kt` - [NEW] Single comprehensive unit test.

## Test Criteria
- [x] Run only `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.api.ChargingForecastApiClientTest`
- [x] All test cases pass with zero failures.

---
Next Phase: [Phase 02: Repository Real-Time Forecast Pipeline](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260904-1055-realtime-charging-forecast-sync/phase-02-repository-realtime-forecast-pipeline.md)
