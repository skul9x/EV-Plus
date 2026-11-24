# Phase 02: Network Data Source & Repository Telemetry Protocol Migration
Status: ✅ Completed
Dependencies: Phase 01 (`StationTelemetryParser`, `SessionManager`, `StationTelemetryModels`)

## Objective
Migrate the network telemetry communication layer to adhere strictly to the EVCS HTTP protocol captured in `curl_capture_20261124_203142`. Reconfigure `EvcsTelemetryDataSource.sendTelemetryUpdate` to use `Content-Type: text/plain`, embed tokens `{t, a, b, p, u}` in the payload without the `x-t` header, implement `sendBatchTelemetryUpdate` for bulk station sync, and implement REST HTTP GET `fetch24hHistory` in both data source and repository layers.

## Scope
1. **Telemetry Update Modernization (`EvcsTelemetryDataSource.kt`)**:
   - Modernize `sendTelemetryUpdate`:
     - Retain 3-parameter overload `open suspend fun sendTelemetryUpdate(stationId: String, apiToken: String, totalBusy: Int): Result<Unit>` delegating to the 5-parameter overload to maintain 100% binary/source override compatibility with existing test fakes (`FakeEvcsTelemetryRepository`).
     - Add 5-parameter overload `open suspend fun sendTelemetryUpdate(stationId: String, apiToken: String, totalBusy: Int, chargingKw: Map<Int, Int> = emptyMap(), uToken: String? = null): Result<Unit>`.
     - Set `Content-Type: text/plain` using `toRequestBody("text/plain".toMediaType())` matching browser curl capture without duplicate header addition.
     - Remove the `x-t` header from request builder.
     - Build JSON payload: `{"t":"$apiToken","a":"$stationId","b":$totalBusy,"p":{...},"u":"${uToken.orEmpty()}"}`.
     - Wrap call in idiomatic `withContext(ioDispatcher + NonCancellable)` to ensure fire-and-forget sync operations complete reliably without premature cancellation.
   - Add model `TelemetryBatchItem(val a: String, val b: Int, val p: Map<String, Int> = emptyMap(), val u: String = "")`.
   - Add method `sendBatchTelemetryUpdate(apiToken: String, items: List<TelemetryBatchItem>): Result<Unit>` using `{"t":"$apiToken","items":[...]}`.
2. **24h History REST Endpoint (`EvcsTelemetryDataSource.kt`)**:
   - Implement `fetch24hHistory(stationId: String, apiToken: String): Result<List<Pair<Long, Int>>>`:
     - Issue `GET {socketBaseUrl}history?sid={stationId}&t={apiToken}`.
     - Adhere to RFC 6265bis: `__Host-tp` cookie is Host-only (bound to `evcs.vn`) and is not required for `www2.evcs.vn/history`, which authenticates via `?sid=...&t=...`.
     - Inject `User-Agent: EvcsApiClient.USER_AGENT_BROWSER`, `Origin`, and `Referer`.
     - Decode payload using `StationTelemetryParser.parse24hHistory(body)`.
3. **Repository Modernization (`EvcsTelemetryRepository.kt`)**:
   - Maintain both 3-parameter and 5-parameter overloads of `sendTelemetryUpdate` forwarding to `dataSource.sendTelemetryUpdate`.
   - Remove `@Deprecated` annotation from `fetch24hHistory(stationId: String, apiToken: String): Result<List<Pair<Long, Int>>>` and delegate directly to `dataSource.fetch24hHistory(stationId, apiToken)`.
   - In `fetchStationTelemetrySnapshot(station: Station, totalPorts: Int)`: retain `uToken` on `StationDetailTelemetrySnapshot`.
4. **Comprehensive Test Suite**:
   - Author exactly one comprehensive test file: `app/src/test/java/com/evcs/favorites/data/telemetry/EvcsTelemetryProtocolDataSourceAndRepositoryTest.kt`.

## Requirements
### Functional
- [x] Text/Plain Payload Transmission: `sendTelemetryUpdate` sends requests with `Content-Type: text/plain` and does NOT send `x-t` header.
- [x] Payload JSON Integrity: The single-update payload contains keys `t`, `a`, `b`, `p`, `u` formatted accurately as JSON text.
- [x] Batch Update Capability: `sendBatchTelemetryUpdate` formats and dispatches batch array `items` with `t` root key to `/update`.
- [x] Non-Cancellable Sync Protection: `sendTelemetryUpdate` uses `ioDispatcher + NonCancellable` context to prevent socket connection aborts during view dismissals.
- [x] REST 24h History Retrieval: `fetch24hHistory` queries `GET /history?sid=...&t=...` and returns decoded `List<Pair<Long, Int>>`.
- [x] Dual Overload Backward Compatibility: Both 3-parameter and 5-parameter `sendTelemetryUpdate` signatures are available, guaranteeing 100% compile and override compatibility for all existing test suites.

### Non-Functional
- [x] Resource Hygiene: All OkHttp `Response` and `ResponseBody` instances are cleanly closed using `.use { }` to prevent connection leaks.
- [x] Timeout Budget: `fetch24hHistory` executes within OkHttp client read timeouts without stalling the caller.
- [x] Error Transparency: HTTP errors (e.g. 403, 500) and IO exceptions are returned via `Result.failure`, never throwing unhandled runtime exceptions.

## How to Implement
1. **Update `EvcsTelemetryDataSource.kt`**:
   Define `TEXT_PLAIN_MEDIA_TYPE`:
   ```kotlin
   companion object {
       const val DEFAULT_SOCKET_URL = "https://www2.evcs.vn/"
       private val TEXT_PLAIN_MEDIA_TYPE = "text/plain".toMediaType()
       private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
   }
   ```
   Define batch item model:
   ```kotlin
   data class TelemetryBatchItem(
       val a: String,
       val b: Int,
       val p: Map<String, Int> = emptyMap(),
       val u: String = ""
   )
   ```
   Modernize `sendTelemetryUpdate` (preserving 3-arg overload for test fakes):
   ```kotlin
   open suspend fun sendTelemetryUpdate(
       stationId: String,
       apiToken: String,
       totalBusy: Int
   ): Result<Unit> = sendTelemetryUpdate(stationId, apiToken, totalBusy, emptyMap(), null)

   open suspend fun sendTelemetryUpdate(
       stationId: String,
       apiToken: String,
       totalBusy: Int,
       chargingKw: Map<Int, Int> = emptyMap(),
       uToken: String? = null
   ): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
       runCatching {
           val url = if (socketBaseUrl.endsWith("/")) "${socketBaseUrl}update" else "$socketBaseUrl/update"
           val pJson = chargingKw.entries.joinToString(prefix = "{", postfix = "}") {
               "\"${it.key}\":${it.value}"
           }
           val payload = """{"t":"$apiToken","a":"$stationId","b":$totalBusy,"p":$pJson,"u":"${uToken.orEmpty()}"}"""
           val request = Request.Builder()
               .url(url)
               .post(payload.toRequestBody(TEXT_PLAIN_MEDIA_TYPE))
               .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
               .addHeader("Origin", baseUrl)
               .addHeader("Referer", "$baseUrl/")
               .build()

           client.newCall(request).execute().use { response ->
               if (!response.isSuccessful) {
                   throw IOException("HTTP ${response.code} from telemetry update")
               }
           }
           Unit
       }
   }
   ```
   Add `sendBatchTelemetryUpdate`:
   ```kotlin
   open suspend fun sendBatchTelemetryUpdate(
       apiToken: String,
       items: List<TelemetryBatchItem>
   ): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
       runCatching {
           val url = if (socketBaseUrl.endsWith("/")) "${socketBaseUrl}update" else "$socketBaseUrl/update"
           val itemsJson = items.joinToString(prefix = "[", postfix = "]") { item ->
               val pJson = item.p.entries.joinToString(prefix = "{", postfix = "}") {
                   "\"${it.key}\":${it.value}"
               }
               """{"a":"${item.a}","b":${item.b},"p":$pJson,"u":"${item.u}"}"""
           }
           val payload = """{"t":"$apiToken","items":$itemsJson}"""
           val request = Request.Builder()
               .url(url)
               .post(payload.toRequestBody(TEXT_PLAIN_MEDIA_TYPE))
               .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
               .addHeader("Origin", baseUrl)
               .build()

           client.newCall(request).execute().use { response ->
               if (!response.isSuccessful) {
                   throw IOException("HTTP ${response.code} from batch telemetry update")
               }
           }
           Unit
       }
   }
   ```
   Add `fetch24hHistory`:
   ```kotlin
   open suspend fun fetch24hHistory(
       stationId: String,
       apiToken: String
   ): Result<List<Pair<Long, Int>>> = withContext(ioDispatcher) {
       try {
           val url = "${socketBaseUrl.trimEnd('/')}/history?sid=$stationId&t=$apiToken"
           val request = Request.Builder()
               .url(url)
               .get()
               .addHeader("User-Agent", EvcsApiClient.USER_AGENT_BROWSER)
               .addHeader("Origin", baseUrl)
               .addHeader("Referer", "$baseUrl/")
               .build()

           client.newCall(request).execute().use { response ->
               if (!response.isSuccessful) {
                   return@withContext Result.failure(
                       IOException("Failed to fetch 24h history: HTTP ${response.code}")
                   )
               }
               val body = response.body?.string().orEmpty()
               StationTelemetryParser.parse24hHistory(body)
           }
       } catch (e: Exception) {
           Result.failure(e)
       }
   }
   ```
2. **Update `EvcsTelemetryRepository.kt`**:
   ```kotlin
   open suspend fun sendTelemetryUpdate(
       stationId: String,
       apiToken: String,
       totalBusy: Int
   ): Result<Unit> {
       return dataSource.sendTelemetryUpdate(stationId, apiToken, totalBusy)
   }

   open suspend fun sendTelemetryUpdate(
       stationId: String,
       apiToken: String,
       totalBusy: Int,
       chargingKw: Map<Int, Int> = emptyMap(),
       uToken: String? = null
   ): Result<Unit> {
       return dataSource.sendTelemetryUpdate(stationId, apiToken, totalBusy, chargingKw, uToken)
   }

   open suspend fun fetch24hHistory(
       stationId: String,
       apiToken: String
   ): Result<List<Pair<Long, Int>>> {
       return dataSource.fetch24hHistory(stationId, apiToken)
   }
   ```

## Files to Create/Modify
- Modify `app/src/main/java/com/evcs/favorites/data/telemetry/EvcsTelemetryDataSource.kt` - Update `sendTelemetryUpdate`, add `sendBatchTelemetryUpdate`, implement `fetch24hHistory`.
- Modify `app/src/main/java/com/evcs/favorites/data/repository/EvcsTelemetryRepository.kt` - Delegate `fetch24hHistory` and modernized `sendTelemetryUpdate`.
- Create `app/src/test/java/com/evcs/favorites/data/telemetry/EvcsTelemetryProtocolDataSourceAndRepositoryTest.kt` - Comprehensive single test file with MockWebServer.

## Dependencies
- Phase 01 parser & cookie mechanisms.
- OkHttp 4.12.0 and MockWebServer.

## Constraints
- Keep `fetch24hHistory` signature compatible with `EvcsTelemetryRepository.calculate24hStats`.
- Do not remove `socketBaseUrl` constructor parameter to avoid breaking existing test fixtures.

## Acceptance Criteria
- [x] `sendTelemetryUpdate` issues POST request to `/update` with `Content-Type: text/plain; charset=utf-8` and payload containing `t`, `a`, `b`, `p`, `u`.
- [x] No `x-t` header is sent on `/update`.
- [x] `sendBatchTelemetryUpdate` dispatches `items` array payload with `t` token.
- [x] `fetch24hHistory` performs GET `/history?sid=...&t=...` with cookies and returns valid point list.
- [x] Exactly one test file `EvcsTelemetryProtocolDataSourceAndRepositoryTest.kt` executes successfully.

## Single Comprehensive Test File
- `app/src/test/java/com/evcs/favorites/data/telemetry/EvcsTelemetryProtocolDataSourceAndRepositoryTest.kt`
