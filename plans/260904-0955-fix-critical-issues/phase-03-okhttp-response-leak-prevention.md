# Phase 03: OkHttp Response Deterministic Cleanup & Socket Leak Prevention
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Resolve ANDROID-LOGIC-003: Wrap all OkHttp `client.newCall().execute()` calls in `EvcsApiClient` and `AuthEngine` with `.use { response -> ... }` or deterministic `response.close()` in finally blocks. This ensures that HTTP response bodies and underlying TCP sockets are recycled immediately on both successful responses and HTTP errors (4xx, 5xx), preventing connection pool starvation and socket leaks.

## Requirements
### Functional
- Update `EvcsApiClient.kt`:
  - `fetchFavorites`: Enclose response handling in `client.newCall(...).execute().use { response -> ... }`
  - `saveFavorites`: Enclose response handling in `client.newCall(...).execute().use { response -> ... }`
  - `searchStations`: Enclose response handling in `client.newCall(...).execute().use { response -> ... }`
  - `fetchStationHtml`: Enclose response handling in `client.newCall(...).execute().use { response -> ... }`
- Update `AuthEngine.kt`:
  - `fetchCsrfToken`: Enclose response handling in `client.newCall(...).execute().use { response -> ... }`
  - `sendOtp`: Enclose response handling in `client.newCall(...).execute().use { response -> ... }`
  - `verifyOtp`: Enclose response handling in `client.newCall(...).execute().use { response -> ... }`

### Non-Functional
- Resource Efficiency: Response bodies and sockets are deterministically freed upon method exit.
- Exception Safety: Even if JSON decoding or regex parsing throws, response streams are closed.

## Implementation Steps
1. Refactor response handling in `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` with `.use { }`.
2. Refactor response handling in `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt` with `.use { }`.
3. Create single test file `app/src/test/java/com/evcs/favorites/data/api/OkHttpConnectionLeakSafetyTest.kt`.
4. Run verification command:
   `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.api.OkHttpConnectionLeakSafetyTest`

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - [MODIFY] Deterministic `.use` response closing
- `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt` - [MODIFY] Deterministic `.use` response closing
- `app/src/test/java/com/evcs/favorites/data/api/OkHttpConnectionLeakSafetyTest.kt` - [NEW] Single comprehensive test for Phase 03

## Test Criteria (Single File-Based Test)
- Run single test:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.api.OkHttpConnectionLeakSafetyTest`
- [x] Verifies `EvcsApiClient.fetchFavorites` closes response on HTTP 500 / 403 / 429 without leaking socket.
- [x] Verifies `EvcsApiClient.fetchStationHtml` closes response on HTTP 500 without leaking socket.
- [x] Verifies `EvcsApiClient.searchStations` closes response on HTTP 500 without leaking socket.
- [x] Verifies `AuthEngine.fetchCsrfToken`, `sendOtp`, `verifyOtp` close responses on HTTP 500 without leaking socket.
- [x] Verifies that 50 consecutive failed requests do not exhaust OkHttp MockWebServer connection limits.

---
End of Plan
