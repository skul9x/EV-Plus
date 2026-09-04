# Phase 02: EVCS Telemetry Repository & 24h Stats Engine
Status: 🟢 Completed  
Dependencies: Phase 01

## Objective
Implement `EvcsTelemetryRepository` and `EvcsTelemetryDataSource` to execute on-demand HTTP/Socket telemetry requests and compute 24h usage statistics (Peak, Average, Peak Hour, Fill Rate) using deterministic formulas matching EVCS production logic extracted from `detail.26082102.js` and verified by curl captures.

## Requirements
### Functional
- [x] Add `io.socket:socket.io-client:2.1.1` to `app/build.gradle.kts`.
- [x] Implement `EvcsTelemetryDataSource`:
  - `fetchStationTokens(station: Station)`:
    - Target: `POST {StationUrlBuilder.buildStationDetailUrl(station)}`
    - Headers: `X-Partial: user`, `User-Agent: USER_AGENT_BROWSER`, `Origin: https://evcs.vn`, `Referer: url`, `Cookie: sessionManager.getCookieHeader()`.
    - Returns `Result<StationAccessTokens>` containing `chargeToken`, `apiToken`, `rating` (`avg`, `count`, `mine`), `hasGo`, `hasBiz`.
  - `fetchLiveCharging(stationId: String, chargeToken: String, isVin: Boolean = true)`:
    - Target: `POST https://evcs.vn/charging`
    - Headers: `Content-Type: application/json`, `x-t: chargeToken`, `User-Agent: USER_AGENT_BROWSER`, `Origin: https://evcs.vn`, `Cookie: sessionManager.getCookieHeader()`.
    - Payload: `{"id": stationId, "t": if (isVin) "vinfast" else "other"}`.
    - Returns `Result<StationTelemetry>` (`busyByKw`, `cleanForecast`, `isLocked`).
  - `fetch24hHistory(stationId: String, apiToken: String)`:
    - Target: Socket.io connection to `https://www2.evcs.vn/`.
    - Options:
      - `auth = mapOf("t" to apiToken)`
      - `transports = arrayOf("websocket", "polling")`
      - `callFactory = sharedOkHttpClient`, `webSocketFactory = sharedOkHttpClient` (reuses application connection pool, satisfying `PERF-NET-01`).
      - `timeout = 4000L` (4s timeout to guarantee instant UI responsiveness).
    - Protocol flow:
      1. Connect socket, emit `subscribe` with `stationId`.
      2. Emit `history` with payload `{"stationId": stationId, "hours": 24, "token": "", "detail": false}`.
      3. Await `history_data` array: `List<Pair<Long, Int>>` (timestamp, vehicleCount).
      4. Immediately disconnect socket upon receiving data or error.
  - `sendTelemetryUpdate(stationId: String, apiToken: String, totalBusy: Int)`:
    - Target: `POST https://www2.evcs.vn/update` with `x-t: apiToken` and payload `{"a": stationId, "b": totalBusy}`.
    - Fire-and-forget sync ping.
- [x] Implement `Station24hStatsCalculator`:
  - `calculate(points: List<Pair<Long, Int>>, totalPorts: Int): Station24hStats`:
    - `peakUsage`: `points.maxOfOrNull { it.second } ?: 0`.
    - `rawAvg`: `if (points.isNotEmpty()) points.map { it.second }.average() else 0.0`.
    - `avgUsage`: `if (rawAvg > 0.0 && rawAvg < 1.0) 1 else rawAvg.roundToInt()`.
    - `peakHour`:
      - Group points by hour in Vietnam timezone (UTC+7, offset `+25,200,000ms`):
      - Hour `h in 0..23`, Day `m = (timestamp + 25200000) / 86400000`.
      - Each bucket `(24 * m + h)` tracks `sum`, `cnt`, `max`.
      - Aggregated across hours `0..23`: finds hour `h` with highest peak vehicle count, breaking ties by highest hourly average.
      - Formatted as `"$h-${(h + 1) % 24}h"` (e.g. `"17-18h"`), or `"-"` if sample is empty or all 0.
    - `fillRate`: `if (totalPorts > 0) min(100, (rawAvg / totalPorts * 100).roundToInt()) else 0`.
- [x] Implement `EvcsTelemetryRepository`:
  - Orchestrates token retrieval, live charging telemetry, and 24h stats calculation.
  - Graceful fallback: If Socket.io times out or is unreachable, returns null `stats24h` without failing port availability or crashing.

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/data/repository/EvcsTelemetryRepositoryAndStatsEngineTest.kt`
- **Verifications**:
  1. Mock HTTP server responds to `POST /{slug}.html` with `X-Partial: user` and `POST /charging` with `x-t: chargeToken`.
  2. Telemetry repository parses tokens (including rating) and live charging status into domain models.
  3. `Station24hStatsCalculator` precisely produces `peakUsage`, `avgUsage` (with ceiling for values < 1), `peakHour` (UTC+7 rush hour), and `fillRate` matching EVCS JS engine.
  4. Repository handles network timeouts or socket disconnects gracefully by returning fallback null stats.

---
Next Phase: [phase-03-viewmodel-on-demand-telemetry-pipeline.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-0115-native-station-detail-bottom-sheet/phase-03-viewmodel-on-demand-telemetry-pipeline.md)

