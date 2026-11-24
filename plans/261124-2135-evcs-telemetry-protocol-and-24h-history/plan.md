# Plan: EVCS Telemetry Protocol Migration & 24h Occupancy History Restoration

Created: 2026-11-24 21:35
Status: ✅ Completed

## Overview
Modernize the EVCS telemetry synchronization engine and restore 24-hour occupancy historical statistics based on real-world HTTP traffic analysis (`curl_capture_20261124_203142`) and Android enterprise best practices. The backend at `www2.evcs.vn` transitioned to a lightweight REST architecture:
1. `POST /update` now requires `Content-Type: text/plain;charset=UTF-8`, embeds the API token `t`, integrity token `u`, and per-tier active power distribution `p: {"60": 2, "120": 4}` directly in the JSON payload, and drops the legacy `x-t` header.
2. `POST /charging` response returns `"chargingKw"` (instead of legacy `"busyKw"`) alongside the station integrity token `"u"`.
3. Cookie transport mandates RFC 6265bis `__Host-tp` session cookie persistence.
4. Historical 24h occupancy data is readily available via REST HTTP GET `https://www2.evcs.vn/history?sid={stationId}&t={apiToken}` returning a 2D time-series matrix `[[timestampMs, vehicleCount], ...]`, allowing the application to completely restore station peak/average occupancy insights without Socket.io overhead.

## Tech Stack
- Language: Kotlin 1.9.23 (JVM Target 17)
- Architecture: Clean Architecture, Repository Pattern, Two-Stage On-Demand Telemetry Enrichment, MVVM
- Networking: OkHttp 4.12.0 (`text/plain` request bodies, RFC 6265bis cookie headers), Non-Cancellable coroutines IO context
- Serialization: Kotlinx Serialization 1.6.3 (`@OptIn(ExperimentalSerializationApi::class) @JsonNames`, 2D matrix deserialization)
- Security: Android Jetpack Security (`EncryptedSharedPreferences`, MasterKey AES256-GCM)
- Concurrency: Kotlin Coroutines (`withContext(NonCancellable)`, `Dispatchers.IO`, `SupervisorJob`, cooperative cancellation)
- UI: Jetpack Compose Material 3, Portrait BottomSheet & Landscape Split-Pane
- Testing: JUnit 4, Kotlinx Coroutines Test, MockWebServer

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Telemetry Parser Resilience, Integrity Token & RFC 6265bis Cookie Session | ✅ Completed | 100% | `StationTelemetryParserAndCookieSessionTest.kt` |
| 02 | Network Data Source & Repository Telemetry Protocol Migration | ✅ Completed | 100% | `EvcsTelemetryProtocolDataSourceAndRepositoryTest.kt` |
| 03 | Coordinator Pipeline, 24h Stats Calculation & End-to-End Regression Verification | ✅ Completed | 100% | `StationDetailTelemetryAnd24hHistoryIntegrationTest.kt` |

## Execution Guidelines
- All phase files are written in English with concrete, production-ready specifications.
- Each phase contains **exactly one** comprehensive file-based test to verify all functional and non-functional requirements upon execution.
- Maintain 100% binary and source backward compatibility across existing constructors and callers.
- Do not implement code or execute test runners during this planning phase.
- Review all phase files before proceeding to implementation via `/code`.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
