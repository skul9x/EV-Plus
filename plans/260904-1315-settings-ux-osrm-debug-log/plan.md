# Plan: Settings UX Overhaul, Default OSRM Routing, and Debug Log Subsystem
Created: 2026-09-04 13:15
Status: 📋 Pending Approval

## Overview
Streamline and modernize the app's settings and data observation capabilities:
1. **Routing & BYOK Decoupling**: Eliminate manual routing engine selection and Google BYOK requirements. Set OSRM (Open Source Routing Machine) as the standard out-of-the-box routing engine with automatic fallback to Haversine (straight-line distance) whenever OSRM is unreachable or encounters an error. Remove Google Cloud API key input, connection testing, error remediation, and the 5-step setup guide from the UI.
2. **Scrollbar Elimination**: Remove the visible custom scrollbar indicator (`.verticalScrollbar`) from the settings bottom sheet to eliminate visual clutter while preserving smooth touch scrolling via `Modifier.verticalScroll`.
3. **Filter Input Hint Modernization**: Update custom power filter input labels and validation hints in settings from "Tối thiểu" and "Tối đa" to "Min" and "Max".
4. **Debug Log Subsystem**: Develop a dedicated "Debug Log" feature within settings that captures all server data retrieval activities (requests, responses, latencies, HTTP status codes, and network errors across `EvcsApiClient` and `OsrmRoutingClient`) with specialized tracking for the EVCS charging forecast handshake ("Dự kiến 1 xe sạc trụ 60kW sẽ xong trong 7 phút nữa"). Equip the viewer with Share (Intent chooser), Copy to clipboard (with feedback), and Delete/Clear log actions.

## Tech Stack
- Frontend: Jetpack Compose (Material 3), Compose Foundation Scrolling, Compose Material Icons
- Architecture: MVVM + Clean Architecture, Kotlin Coroutines, StateFlow
- Networking: OkHttp 4.12.0 (`Interceptor`, `peekBody`), MockWebServer
- Logging: In-memory thread-safe FIFO buffer (`AppDebugLogger`), Reactive StateFlow
- Testing: JUnit 4, Kotlinx Coroutines Test, MockWebServer

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Default OSRM Routing & BYOK Decoupling | ⬜ Pending | 0% | `OsrmHaversineDefaultRoutingTest.kt` |
| 02 | Debug Log Subsystem & Forecast Capture Engine | ⬜ Pending | 0% | `AppDebugLoggerPipelineTest.kt` |
| 03 | Settings UI Overhaul, Min/Max Labels & Debug Log Viewer | ⬜ Pending | 0% | `SettingsModalRedesignTest.kt` |

## Execution Guidelines
- All phase files are written in English.
- Each phase contains **exactly one** comprehensive file-based test.
- Do not create or run more than one test file per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
