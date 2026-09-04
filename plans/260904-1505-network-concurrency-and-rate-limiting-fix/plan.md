# Plan: Network Concurrency, Charge Token Caching, and Rate Limit Remediation
Created: 2026-09-04 15:05 (Updated: 2026-09-04 15:15)
Status: 📋 Pending Approval

## Overview
Address and permanently eliminate the network anomalies observed in `debug-log.txt`:
1. **Concurrent Duplicate Requests (Single-Flight Coalescing Engine)**:
   Eliminate identical requests triggered simultaneously (e.g. twin `POST /favorite.html`, twin `POST /search`, twin `/charging` calls) by implementing a thread-safe, coroutine-aware `SingleFlight` deduplication engine backed by an independent `SupervisorJob` scope. Coalesce redundant jobs in `FavoritesViewModel` and `NearbyViewModel`.
2. **Charge Token Session Caching & Forecast Pacing**:
   Eliminate redundant HTML station detail requests. The `chargeToken` is a session-level token valid across all stations (expiration epoch prefix `1788509251...` gives ~10 min validity). Caching it in `EvcsApiClient` cuts network traffic for forecast retrieval by 50%. Smooth out burst traffic with paced dispatch (250ms spacing) and reduced concurrency (Semaphore(2)).
3. **Structured Cloudflare 429 Handling, Dynamic Retry-After Parsing & Instant Batch Abort**:
   When Cloudflare returns HTTP 429 (Error 1015), parse `Retry-After` (integer seconds, HTTP Date RFC 7231, or JSON body `retry_after`) into a dedicated `RateLimitException`. Immediately abort the entire `enrichStationsWithForecast` coroutine batch, stopping all in-flight and pending sibling requests from hitting the server.
4. **Candidate Sanitization & OSRM Coordinate Deduplication**:
   Sanitize candidate stations with `distinctBy { it.id }` in `NearbyStationFilter` and `MultiTierRoutingCoordinator`. Aggregate identical destination coordinates in `OsrmRoutingClient` to generate minimal URL queries and fan out calculated `DrivingMetrics` to all stations sharing those coordinates.

## Tech Stack
- Architecture: MVVM + Clean Architecture, Kotlin Coroutines (`SingleFlight`, `SupervisorJob`, `NonCancellable`, `Mutex`)
- Networking: OkHttp 4.12.0, MockWebServer, `RateLimitException`, `RetryAfterParser`
- State & Logging: Reactive StateFlow, `AppDebugLogger`
- Testing: JUnit 4, Kotlinx Coroutines Test, MockWebServer

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | In-Flight Request Deduplication Engine & Job Coalescing | ⬜ Pending | 0% | `SingleFlightRequestDeduplicationTest.kt` |
| 02 | Charge Token Session Caching, Forecast Pacing & Instant 429 Abort | ⬜ Pending | 0% | `ChargeTokenCachingAndRateLimitAbortTest.kt` |
| 03 | Station Deduplication & Coordinate Aggregation for OSRM Routing | ⬜ Pending | 0% | `OsrmCoordinateDeduplicationTest.kt` |

## Execution Guidelines
- All phase files are written in English.
- Each phase contains **exactly one** comprehensive file-based test.
- Do not create or run more than one test file per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
