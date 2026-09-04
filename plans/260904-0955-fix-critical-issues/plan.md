# Plan: Fix 3 Critical Logic Defects (P0)
Created: 2026-09-04 09:55:00 (GMT+7)
Status: 🟡 Pending Review

## Overview
Remediate the three Critical Severity issues identified in `report.md` (ANDROID-LOGIC-001, ANDROID-LOGIC-002, and ANDROID-LOGIC-003):
1. **ANDROID-LOGIC-001 (Routing/Sorting Inversion)**: Haversine fallback calculates `durationSeconds = 0L`, causing distant stations to sort ahead of nearby stations as "0 phút".
2. **ANDROID-LOGIC-002 (Cancellation & Cache Poisoning)**: Catching `CancellationException` causes false network failure recording in `ForecastCache`, locking out stations for 60 seconds upon user refresh or tab switch.
3. **ANDROID-LOGIC-003 (OkHttp Socket Leaks)**: HTTP response bodies are left unclosed on non-2xx responses across `EvcsApiClient` and `AuthEngine`, exhausting OkHttp connection pools.

## Key Rules & Architectural Decisions
1. **Single Comprehensive Test Per Phase**:
   - Each phase defines exactly ONE file-based test.
   - Run ONLY that single test for verification before pausing for user review.
   - No additional or ad-hoc tests created or run.
2. **Haversine Duration Heuristic & Sort Guard**:
   - Compute a realistic driving duration estimate for Haversine fallback based on urban driving speed (30 km/h ≈ 8.33 m/s) with a minimum duration (e.g. 60s for non-zero distance), ensuring straight-line distances never show "0 phút" for distant locations and never sort ahead of real nearby stations.
   - Guard `FavoritesViewModel` sort comparator so that valid road-routed stations take precedence or compare consistently.
3. **Strict Coroutine Cancellation Contract**:
   - Re-throw `CancellationException` in all catch blocks across repository and routing clients before handling general errors.
   - Ensure `ForecastCache.recordFailure` is never called when a job is cancelled.
4. **Deterministic OkHttp Response Resource Scoping**:
   - Enforce `.use { response -> ... }` across all `client.newCall().execute()` calls in `EvcsApiClient` and `AuthEngine` to guarantee connection pool socket closure on both 2xx and non-2xx responses.

## Phases

| Phase | Name | Issue ID | Status | Test File |
|-------|------|----------|--------|-----------|
| 01 | Haversine Fallback Duration & ETA Sort Order | ANDROID-LOGIC-001 | ✅ Completed | `HaversineRoutingEtaSortTest.kt` |
| 02 | Cancellation Propagation & Cache Cooldown Safety | ANDROID-LOGIC-002 | ✅ Completed | `CoroutineCancellationCacheSafetyTest.kt` |
| 03 | OkHttp Response Deterministic Cleanup & Socket Leak Prevention | ANDROID-LOGIC-003 | ✅ Completed | `OkHttpConnectionLeakSafetyTest.kt` |

## Quick Commands
- Start Phase 1: Implement `phase-01-haversine-eta-and-sorting.md`
- Verify Phase 1: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.routing.HaversineRoutingEtaSortTest`
- Start Phase 2: Implement `phase-02-cancellation-cache-safety.md`
- Verify Phase 2: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.CoroutineCancellationCacheSafetyTest`
- Start Phase 3: Implement `phase-03-okhttp-response-leak-prevention.md`
- Verify Phase 3: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.api.OkHttpConnectionLeakSafetyTest`
