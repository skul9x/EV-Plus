# Phase 01: Telemetry Parser Resilience, Integrity Token & RFC 6265bis Cookie Session
Status: ✅ Completed
Dependencies: None

## Objective
Harden the serialization and domain data parsing layer to natively decode both legacy and modern EVCS backend JSON payloads, extract the telemetry integrity token `u`, provide 2D time-series matrix deserialization for historical 24h occupancy points, and implement RFC 6265bis `__Host-tp` cookie persistence and header generation in `SessionManager`.

## Scope
1. **Model & Parser Resilience (`StationTelemetryParser.kt` & `StationTelemetryModels.kt`)**:
   - Annotate `ChargingResponseRaw.busyKw` with `@OptIn(ExperimentalSerializationApi::class) @JsonNames("chargingKw", "busyKw")` and declare as nullable `Map<String, Int>? = emptyMap()` to safely handle null payloads when stations are inactive/offline.
   - Add field `val u: String? = null` to `ChargingResponseRaw` and propagate it to `StationTelemetry.uToken`.
   - Add optional vehicle telemetry metrics `val totalPorts: Int = 0`, `val charging: Int = 0`, `val free: Int = 0` to `ChargingResponseRaw` with default fallback.
   - Implement `parse24hHistory(jsonString: String): Result<List<Pair<Long, Int>>>` supporting nested 2D arrays `[[timestamp, count], ...]`, clamping counts to `maxOf(0, count)`.
2. **Session Cookie Management (`SessionManager.kt`)**:
   - Add `KEY_HOST_TP = "__Host-tp"` constant and pre-compiled regex `HOST_TP_COOKIE_REGEX = Regex("""(?:^|;\s*)__Host-tp=([^;]+)""")`.
   - Add property `var hostTpCookie: String?` backed by `SessionStorage`.
   - Update `extractCookieValue` to clean cookie value with `.trim().removeSurrounding("\"")` and match `KEY_HOST_TP` directly.
   - Update `saveFromSetCookieHeader(setCookieHeader: String)` to parse and persist `__Host-tp`.
   - Update `getCookieHeader(): String` to append `__Host-tp=$hostTp` when present, adhering to RFC 6265bis security conventions.
   - Update `clearSession()` to wipe `KEY_HOST_TP`.
3. **Comprehensive Test Suite**:
   - Author exactly one comprehensive test file: `app/src/test/java/com/evcs/favorites/domain/StationTelemetryParserAndCookieSessionTest.kt`.

## Requirements
### Functional
- [x] `@JsonNames` Compatibility: `StationTelemetryParser.parseChargingResponse` successfully decodes JSON using `"chargingKw": {"60": 2, "120": 4}` as well as legacy `"busyKw": {"60": 2, "120": 4}` into `StationTelemetry.busyByKw`.
- [x] Integrity Token Propagation: The `"u"` token from `/charging` response is parsed and populated into `StationTelemetry.uToken`. If absent in JSON, defaults cleanly to `null`.
- [x] 24h History Parsing: `StationTelemetryParser.parse24hHistory` correctly parses valid JSON matrix strings like `[[1732440000000, 4], [1732441800000, 5]]` into `List<Pair<Long, Int>>`. Validates coordinate points with size >= 2 and handles empty arrays `[]` or malformed tokens without throwing unhandled exceptions.
- [x] RFC 6265bis Cookie Capture: `SessionManager.saveFromSetCookieHeader` extracts `__Host-tp` from headers such as `__Host-tp=eyJhbGciOi...; Secure; Path=/; HttpOnly` and saves it to storage.
- [x] Cookie Header Formatting: `SessionManager.getCookieHeader()` includes `__Host-tp=...` alongside `PHPSESSID`, `evcs`, and `evcs_did`.
- [x] Session Clearance: Calling `SessionManager.clearSession()` removes `__Host-tp`, `evcs`, `PHPSESSID`, `csrf_token`, and `user_email`, while preserving the persistent `evcs_did`.

### Non-Functional
- [x] Memory & Allocations: Zero regex recompilation overhead by leveraging static compiled regexes.
- [x] Main-Safety: All parsing functions execute synchronously without blocking or background thread dispatch requirements.
- [x] Backward Compatibility: Existing unit tests for `StationTelemetryParser` and `SessionManager` must continue to pass without modifications to existing method signatures.

## How to Implement
1. **Update `StationTelemetryModels.kt`**:
   Add `uToken` to `StationTelemetry`:
   ```kotlin
   @Immutable
   @Serializable
   data class StationTelemetry(
       val busyByKw: Map<Int, Int> = emptyMap(),
       val rawTicker: String? = null,
       val cleanForecast: String? = null,
       val isLocked: Boolean = false,
       val uToken: String? = null
   )
   ```
2. **Update `StationTelemetryParser.kt`**:
   Import `kotlinx.serialization.ExperimentalSerializationApi` and `kotlinx.serialization.json.JsonNames`.
   Update `ChargingResponseRaw`:
   ```kotlin
   @OptIn(ExperimentalSerializationApi::class)
   @Serializable
   private data class ChargingResponseRaw(
       val ticker: String? = null,
       @JsonNames("chargingKw", "busyKw")
       val busyKw: Map<String, Int>? = emptyMap(),
       val partial: Boolean = false,
       val u: String? = null,
       val totalPorts: Int = 0,
       val charging: Int = 0,
       val free: Int = 0
   )
   ```
   In `parseChargingResponse(jsonString: String)`:
   ```kotlin
   fun parseChargingResponse(jsonString: String): Result<StationTelemetry> = runCatching {
       val raw = json.decodeFromString<ChargingResponseRaw>(jsonString)
       val busyByKw = (raw.busyKw ?: emptyMap()).mapNotNull { (k, v) ->
           val kw = k.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
           kw to v
       }.toMap()

       val locked = isLockedTicker(raw.ticker)
       val cleanForecast = if (locked) null else sanitizeForecast(raw.ticker)

       StationTelemetry(
           busyByKw = busyByKw,
           rawTicker = raw.ticker,
           cleanForecast = cleanForecast,
           isLocked = locked,
           uToken = raw.u
       )
   }
   ```
   Add `parse24hHistory`:
   ```kotlin
   fun parse24hHistory(jsonString: String): Result<List<Pair<Long, Int>>> = runCatching {
       if (jsonString.isBlank()) return@runCatching emptyList()
       val rawMatrix = json.decodeFromString<List<List<Long>>>(jsonString)
       rawMatrix.mapNotNull { point ->
           if (point.size >= 2) {
               Pair(point[0], maxOf(0, point[1].toInt()))
           } else {
               null
           }
       }
   }
   ```
3. **Update `SessionManager.kt`**:
   Add constants and properties:
   ```kotlin
   companion object {
       const val KEY_HOST_TP = "__Host-tp"
       private val HOST_TP_COOKIE_REGEX = Regex("""(?:^|;\s*)__Host-tp=([^;]+)""")

       fun extractCookieValue(headerOrCookie: String, name: String): String? {
           val regex = when (name) {
               KEY_AUTH_COOKIE -> EVCS_COOKIE_REGEX
               KEY_PHP_SESSION -> PHPSESSID_COOKIE_REGEX
               KEY_HOST_TP -> HOST_TP_COOKIE_REGEX
               else -> COOKIE_REGEX_CACHE.computeIfAbsent(name) {
                   Regex("""(?:^|;\s*)$it=([^;]+)""")
               }
           }
           return regex.find(headerOrCookie)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
       }
   }

   var hostTpCookie: String?
       get() = storage.getString(KEY_HOST_TP)
       set(value) = storage.putString(KEY_HOST_TP, value)

   fun saveFromSetCookieHeader(setCookieHeader: String) {
       val evcsVal = extractCookieValue(setCookieHeader, "evcs")
       if (evcsVal != null) authCookie = evcsVal
       val phpVal = extractCookieValue(setCookieHeader, "PHPSESSID")
       if (phpVal != null) phpSessionId = phpVal
       val hostTpVal = extractCookieValue(setCookieHeader, KEY_HOST_TP)
       if (hostTpVal != null) hostTpCookie = hostTpVal
   }

   fun getCookieHeader(): String {
       val pairs = mutableListOf<String>()
       phpSessionId?.takeIf { it.isNotBlank() }?.let { pairs.add("PHPSESSID=$it") }
       authCookie?.takeIf { it.isNotBlank() }?.let { pairs.add("evcs=$it") }
       hostTpCookie?.takeIf { it.isNotBlank() }?.let { pairs.add("__Host-tp=$it") }
       if (deviceId.isNotBlank()) pairs.add("evcs_did=$deviceId")
       return pairs.joinToString("; ")
   }

   fun clearSession() {
       storage.remove(KEY_AUTH_COOKIE)
       storage.remove(KEY_PHP_SESSION)
       storage.remove(KEY_HOST_TP)
       storage.remove(KEY_CSRF_TOKEN)
       storage.remove(KEY_USER_EMAIL)
   }
   ```

## Files to Create/Modify
- Modify `app/src/main/java/com/evcs/favorites/domain/StationTelemetryModels.kt` - Add `uToken` to `StationTelemetry`.
- Modify `app/src/main/java/com/evcs/favorites/domain/StationTelemetryParser.kt` - `@JsonNames`, `uToken`, `parse24hHistory`.
- Modify `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt` - Add `__Host-tp` handling, update cookie header generation.
- Create `app/src/test/java/com/evcs/favorites/domain/StationTelemetryParserAndCookieSessionTest.kt` - Comprehensive single test file.

## Dependencies
- `kotlinx.serialization` with `ExperimentalSerializationApi`
- `SessionStorage` interface

## Constraints
- Do not remove or alter existing public methods in `StationTelemetryParser` or `SessionManager`.
- Keep regex patterns compiled statically in companion objects.

## Acceptance Criteria
- [x] `parseChargingResponse` successfully parses both `{"chargingKw":{"60":2}}` and `{"busyKw":{"60":2}}`.
- [x] `parseChargingResponse` extracts `u` into `uToken`.
- [x] `parse24hHistory` parses 2D arrays into `List<Pair<Long, Int>>` with boundary validation.
- [x] `SessionManager` persists `__Host-tp` and includes it in `getCookieHeader()`.
- [x] Exactly one test file `StationTelemetryParserAndCookieSessionTest.kt` passes with 100% green assertions.

## Single Comprehensive Test File
- `app/src/test/java/com/evcs/favorites/domain/StationTelemetryParserAndCookieSessionTest.kt`
