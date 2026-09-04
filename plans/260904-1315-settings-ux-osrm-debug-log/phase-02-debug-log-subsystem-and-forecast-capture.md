# Phase 02: Debug Log Subsystem & Forecast Capture Engine
Status: ✅ Completed
Dependencies: `phase-01-default-osrm-routing-and-byok-decoupling.md`

## Objective
Create a lightweight, thread-safe in-memory debug logging subsystem (`AppDebugLogger` and `DebugLoggingInterceptor`) to record all server data retrieval operations (requests, responses, latencies, HTTP status codes, and network errors) with dedicated instrumentation for the EVCS charging forecast handshake (`chargeToken` acquisition + `/charging` request/response + HTML ticker parsing: "Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa"). Provide log management capabilities (export/formatted string, share intent data generation, clipboard copy, delete/clear).

## Requirements
### Functional
- [x] Define `DebugLogEntry` model containing timestamp (`HH:mm:ss.SSS`), tag (`FORECAST`, `NETWORK`, `SEARCH`, `FAVORITES`, `ROUTING`), level (`INFO`, `SUCCESS`, `WARN`, `ERROR`), endpoint URL, HTTP method, status code, latencyMs, request snippet, response snippet, parsed forecast summary, and error details.
- [x] Create `AppDebugLogger` singleton with a thread-safe circular buffer (capacity 500 entries) exposing a reactive `StateFlow<List<DebugLogEntry>>`.
- [x] Implement `DebugLoggingInterceptor` for OkHttp to automatically capture all outgoing request details and incoming responses (peeking body safely using `response.peekBody(...)` without consuming the stream).
- [x] Instrument `EvcsApiClient` and `OsrmRoutingClient` with `DebugLoggingInterceptor` so all server retrieval (Search, Favorites, Station Detail, OSRM routing, and Charging Forecast) is systematically captured.
- [x] Instrument `EvcsApiClient.fetchChargingForecast` with step-by-step diagnostic logging:
  - Step 1: POST station URL with `X-Partial: user` to acquire `chargeToken` (log status, latency, token or failure).
  - Step 2: POST `/charging` with payload `{"id": locationId, "t": "vinfast"}` and header `x-t: <token>` to fetch ticker response (log status, latency, ticker HTML snippet).
- [x] Instrument `EvcsRepository.fetchStationForecast` with Step 3 parsing diagnostic:
  - If parsed successfully: log `DebugLogLevel.SUCCESS` with `"⏱️ Dự kiến $count xe sạc trụ ${kw}kW sẽ xong trong $time nữa"`.
  - If ticker is empty/blank: log `DebugLogLevel.INFO` `"Không có dữ liệu ticker sạc (trụ có thể đang rảnh hoặc không có phiên sạc)"`.
  - If parser fails on non-empty ticker: log `DebugLogLevel.WARN` with raw ticker snippet for diagnosis.
  - If network/token error: log `DebugLogLevel.ERROR` with full exception message.
- [x] Provide log helper methods:
  - `getFormattedLogText()`: produces structured plain-text for Share and Clipboard Copy.
  - `clear()`: purges all logs from the buffer.

### Non-Functional
- [x] Zero memory leak / OOM risk: bounded buffer size (max 500 entries, FIFO drop).
- [x] Stream-safe OkHttp interception: uses `response.peekBody(byteCount)` so network responses are never drained or prematurely closed.
- [x] Non-blocking execution: logging operations are thread-safe and lightweight.

## Implementation Steps
1. [x] Create `DebugLogModels.kt` in `app/src/main/java/com/evcs/favorites/data/logging/` defining `DebugLogEntry`, `DebugLogTag`, and `DebugLogLevel`.
2. [x] Create `AppDebugLogger.kt` in `app/src/main/java/com/evcs/favorites/data/logging/` managing the thread-safe circular log buffer, reactive `StateFlow`, formatted text exporter, and clear operations.
3. [x] Create `DebugLoggingInterceptor.kt` in `app/src/main/java/com/evcs/favorites/data/logging/` intercepting OkHttp calls, logging request/response/latency/errors.
4. [x] Attach `DebugLoggingInterceptor` to `EvcsApiClient`'s default OkHttpClient and `OsrmRoutingClient`'s OkHttpClient.
5. [x] Add domain-specific forecast diagnostic logging in `EvcsApiClient.fetchChargingForecast` and `EvcsRepository.fetchStationForecast`.
6. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/data/logging/AppDebugLoggerPipelineTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/logging/DebugLogModels.kt` - [NEW] Data structures for debug logs
- `app/src/main/java/com/evcs/favorites/data/logging/AppDebugLogger.kt` - [NEW] Circular buffer manager & text exporter
- `app/src/main/java/com/evcs/favorites/data/logging/DebugLoggingInterceptor.kt` - [NEW] OkHttp interceptor using `peekBody`
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - Attach interceptor & log 2-step forecast handshake
- `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt` - Attach interceptor for OSRM network requests
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Log forecast parsing outcome & error diagnosis
- `app/src/test/java/com/evcs/favorites/data/logging/AppDebugLoggerPipelineTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria (Single Test File)
- **Test File**: `app/src/test/java/com/evcs/favorites/data/logging/AppDebugLoggerPipelineTest.kt`
- [x] Verify circular buffer FIFO eviction: oldest logs drop when capacity exceeds 500 entries, and `clear()` purges all logs.
- [x] Verify OkHttp interceptor accurately records request method, URL, status code 200, latency, and peeked response body without breaking stream consumption.
- [x] Verify charging forecast handshake records Step 1 (`chargeToken`) and Step 2 (`/charging`) logs under `DebugLogTag.FORECAST`.
- [x] Verify successful parsing of ticker containing "Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa" logs `DebugLogLevel.SUCCESS` with forecast summary.
- [x] Verify failed HTTP response or malformed HTML logs `DebugLogLevel.ERROR` or `WARN` with full diagnostic exception details.
- [x] Verify `getFormattedLogText()` produces a well-structured text representation ready for Share and Clipboard copying.

---
Next Phase: `phase-03-settings-ui-overhaul-and-debug-log-viewer.md`
