# Phase 04: Regex Pre-compilation & CPU Optimization (PERF-04)

Status: ✅ Completed  
Dependencies: Phase 03

## Objective
Eliminate repetitive runtime regular expression compilation across performance-sensitive data extraction and token parsing pathways in [SessionManager.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt), [StationTelemetryParser.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/StationTelemetryParser.kt), and [AuthEngine.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt).

## Requirements

### Functional
- [x] In [SessionManager.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt#L363):
  - Pre-compile standard cookie extraction patterns in `companion object`:
    ```kotlin
    private val EVCS_COOKIE_REGEX = Regex("""(?:^|;\s*)evcs=([^;]+)""")
    private val PHPSESSID_COOKIE_REGEX = Regex("""(?:^|;\s*)PHPSESSID=([^;]+)""")
    private val COOKIE_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    ```
  - In `extractCookieValue(headerOrCookie: String, name: String)`, match against pre-compiled regexes for `"evcs"` and `"PHPSESSID"`, or fetch/compute from `COOKIE_REGEX_CACHE` for arbitrary names.
- [x] In [StationTelemetryParser.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/StationTelemetryParser.kt#L116):
  - Move `Regex("""<div[^>]*class=["']([^"']+)["'][^>]*>""")` out of `isLockedTicker()` into `companion object`:
    ```kotlin
    private val TICKER_DIV_REGEX = Regex("""<div[^>]*class=["']([^"']+)["'][^>]*>""")
    ```
- [x] In [AuthEngine.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt#L298-L303):
  - Move CSRF regex matchers in `extractCsrfToken()` into `companion object`:
    ```kotlin
    private val CSRF_PRIMARY_REGEX = Regex("""window\.EVCS_REWARD\s*=\s*\{[^}]*csrf\s*:\s*["']([^"']+)["']""")
    private val CSRF_FALLBACK_REGEX = Regex("""csrf\s*:\s*["']([a-fA-F0-9]{16,64})["']""")
    ```

### Non-Functional
- [x] Zero runtime Regex object allocation during continuous session validation and station ticker evaluation.
- [x] 100% backward-compatible parsing accuracy across all existing auth and telemetry token formats.

## Implementation Steps
1. [x] Update [SessionManager.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt):
   - Add pre-compiled cookie regex constants and cache.
   - Use pre-compiled regexes in `extractCookieValue`.
2. [x] Update [StationTelemetryParser.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/StationTelemetryParser.kt):
   - Move `TICKER_DIV_REGEX` to `companion object`.
3. [x] Update [AuthEngine.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt):
   - Move `CSRF_PRIMARY_REGEX` and `CSRF_FALLBACK_REGEX` to `companion object`.
4. [x] Create the single comprehensive test file:
   - [PrecompiledRegexPerformanceTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/util/PrecompiledRegexPerformanceTest.kt)
5. [x] Run the verification command to confirm all tests pass.

## Files to Create/Modify
- `[MODIFY]` [SessionManager.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt) - Pre-compile cookie extraction regex patterns and cache.
- `[MODIFY]` [StationTelemetryParser.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/StationTelemetryParser.kt) - Move ticker div regex to companion object constant.
- `[MODIFY]` [AuthEngine.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt) - Move CSRF extraction regexes to companion object constants.
- `[NEW]` [PrecompiledRegexPerformanceTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/util/PrecompiledRegexPerformanceTest.kt) - Single comprehensive test for Phase 04.

## Test Criteria (Single Comprehensive Test File)
- **Target Test File**: [PrecompiledRegexPerformanceTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/util/PrecompiledRegexPerformanceTest.kt)
- **Verification Command**: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.util.PrecompiledRegexPerformanceTest"`
- Test cases included within the single file:
  - `testSessionManager_extractCookieValue_extractsAllStandardCookiesAccurately`: Confirms cookie parsing across headers and formats using pre-compiled patterns.
  - `testStationTelemetryParser_isLockedTicker_detectsLockedStateConsistently`: Confirms ticker locked evaluation produces identical results without dynamic regex compilation.
  - `testAuthEngine_extractCsrf_extractsFromScriptAndPayload`: Confirms primary and fallback CSRF token extraction works identically with static regex constants.

---
Next Phase: [Phase 05: OkHttp Cache & Stats Algorithm Optimization](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1935-performance-remediation/phase-05-okhttp-cache-and-stats-algorithm-optimization.md)
