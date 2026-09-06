# Phase 04: Centralized OkHttpClient Connection Pool & Conditional Logging

Status: ✅ Completed
Issue IDs: PERF-NET-01, PERF-NET-02
Dependencies: Phase 03

## Objective
Prevent TCP connection duplication, TLS handshake latency overhead (+100ms–300ms per request), and thread pool proliferation across 5 independent network clients by establishing a unified `AppOkHttpClientProvider` with a shared `ConnectionPool`, and make `DebugLoggingInterceptor` conditionally bypass body buffering in production or when disabled.

---

## Requirements

### Functional
- [x] Create `AppOkHttpClientProvider` singleton with:
  - Shared `ConnectionPool(maxIdleConnections = 10, keepAliveDuration = 5, TimeUnit.MINUTES)`.
  - Shared `Dispatcher` to cap and manage total concurrent network worker threads.
  - Helper `getSharedClient(): OkHttpClient` and `newSharedClientBuilder(): OkHttpClient.Builder`.
- [x] Refactor default client creation in all 5 network clients to derive from `AppOkHttpClientProvider.getSharedClient().newBuilder()`:
  - `EvcsApiClient.kt:defaultClient()`
  - `AuthEngine.kt:defaultClient()`
  - `OsrmRoutingClient.kt:defaultClient()`
  - `GoogleRoutesClient.kt` default constructor argument
  - `RoutingPreferencesManager.kt` default constructor argument
- [x] In `DebugLoggingInterceptor`:
  - Add `enabled: Boolean = true` property / constructor argument.
  - When disabled (`!enabled`), bypass all body buffering (`Buffer.writeTo` and `peekBody`), immediately calling `chain.proceed(request)`.
  - When enabled, maintain request/response snippet logging with existing safety bounds.
- [x] Ensure backward compatibility: custom `OkHttpClient` constructor injection remains intact for all MockWebServer unit tests.

### Non-Functional
- [x] Connection reuse: HTTP Keep-Alive connections to `https://evcs.vn` must be shared across authentication, station search, and forecast endpoints.
- [x] Resource conservation: Thread pool Dispatcher memory footprint reduced from 5 separate pools to 1 unified pool.

---

## Implementation Steps
1. **Create `AppOkHttpClientProvider.kt`**:
   - Provide shared `ConnectionPool` and base client builder.
   - Support custom test client injection or reset for unit tests.
2. **Refactor Network Clients**:
   - Update `EvcsApiClient.kt`, `AuthEngine.kt`, `OsrmRoutingClient.kt`, `GoogleRoutesClient.kt`, `RoutingPreferencesManager.kt` to reuse `AppOkHttpClientProvider.getSharedClient().newBuilder()` by default.
3. **Update `DebugLoggingInterceptor.kt`**:
   - Add enable/disable guard to bypass payload buffering when logging is inactive.
4. **Verify Backward Compatibility**:
   - Ensure existing mock web server tests and dependency injection constructors continue working without modification.

---

## Files to Modify/Create
- [NEW] `app/src/main/java/com/evcs/favorites/data/network/AppOkHttpClientProvider.kt` - Centralized OkHttpClient and ConnectionPool provider.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - Derive default client from shared provider.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt` - Derive default client from shared provider.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt` - Derive default client from shared provider.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt` - Derive default client from shared provider.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` - Derive default client from shared provider.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/logging/DebugLoggingInterceptor.kt` - Add conditional bypass for body buffering.
- [NEW] `app/src/test/java/com/evcs/favorites/SharedOkHttpPoolAndLoggingInterceptorTest.kt` - Exactly one comprehensive test for Phase 04.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/SharedOkHttpPoolAndLoggingInterceptorTest.kt`
- **Core Verifications**:
  1. `AppOkHttpClientProvider` provides clients sharing the same `ConnectionPool` and `Dispatcher` instances.
  2. Derived clients (`EvcsApiClient`, `AuthEngine`, etc.) retain customized timeouts and interceptors while sharing socket pools.
  3. `DebugLoggingInterceptor` in enabled mode captures request/response snippets.
  4. `DebugLoggingInterceptor` in disabled mode passes requests through cleanly without buffering request or response bodies.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.SharedOkHttpPoolAndLoggingInterceptorTest
```
