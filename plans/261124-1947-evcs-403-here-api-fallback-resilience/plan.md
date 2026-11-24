# Plan: EVCS 403 WAF Resilient Multi-Tier Data Source & HERE API Fallback Engine

Created: 2026-11-24 19:47
Status: ✅ Completed

## Overview
Implement an enterprise-grade multi-tier remote data source fallback and circuit breaker engine orchestrated cleanly within the data layer (`EvcsRepository`) and consumed transparently by the presentation layer (`NearbyViewModel`). When the primary `evcs.vn` search API encounters recoverable infrastructural barriers—specifically `HTTP 403 Forbidden` (Cloudflare Turnstile WAF challenge), `HTTP 429 Too Many Requests`, `5xx Server Errors`, or network timeouts—the application automatically and transparently degrades to the secondary Tier 1 telemetry source (`HereEvApiClient`).

The solution ensures 100% operational uptime for nearby EV charging station discovery and real-time DC/AC slot telemetry, protects drivers from disruption, enforces an active atomic circuit breaker cooldown with Half-Open probing to eliminate unnecessary network latency, enriches generic station names with authentic address descriptors (with empty-address safety guards), guarantees `evse = "VinFast"` attribution for filter pass-through, and maintains complete parity with downstream domain filters, road distance routing matrix, and favorites synchronization.

## Tech Stack
- Language: Kotlin 1.9.23 (JVM Target 17)
- Architecture: Clean Architecture, Repository Pattern with Multi-Tier Remote Data Sources (Single Source of Truth), MVVM
- Concurrency: Kotlin Coroutines (`withContext`, `SupervisorJob`, `CancellationException` transparency), SingleFlight request deduplication, Atomic Primitives (`AtomicLong`)
- Networking: OkHttp 4.12.0, REST APIs (HERE Maps EV API v2, EVCS HMAC Search API)
- Testing: JUnit 4, Kotlinx Coroutines Test, MockWebServer

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Multi-Tier Repository Fallback & Circuit Breaker Engine | ✅ Completed | 100% | `EvcsRepositoryFallbackResilienceTest.kt` |
| 02 | Station Normalization, Coordinate Caching & Name Enrichment | ✅ Completed | 100% | `HereFallbackStationModelAndNameResolutionTest.kt` |
| 03 | ViewModel Orchestration, Realtime GPS Refresh & Regression Verification | ✅ Completed | 100% | `NearbyViewModelHereFallbackIntegrationTest.kt` |

## Execution Guidelines
- All phase files are written in English with concrete, production-ready specifications.
- Each phase contains **exactly one** comprehensive file-based test to verify all functional and non-functional requirements upon execution.
- Implementation decisions adhere strictly to Google Android Architecture best practices (data layer owns fallback orchestration; ViewModel remains decoupled from remote data source origins).
- Do not implement code or execute test runners during this planning phase.
- Review all phase files before proceeding to implementation via `/code`.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
