# Plan: Hybrid HERE Maps EV API & EVCS Name Resolver for AC Station Filter

Created: 2026-09-13 16:52
Status: 🟡 In Progress

## Overview
Implement a hybrid charging station resolution pipeline when the AC filter (`SmartFilterMode.AC` or `QuickChipOption.AC`) is active in the Nearby tab:
- **Comprehensive AC Discovery (HERE EV API):** Retrieve all car-compatible AC charging posts (11kW and 22kW) from HERE Maps EV API (`ev-v2.cc.api.here.com/ev/stations.json`), uncovering local residential/franchise AC stations that the default EVCS `/search` endpoint omits.
- **Strict 100% Availability Enforcement:** Filter out any station where AC ports are fully occupied or unready (`OCCUPIED`, `OTHER`, `OUT_OF_SERVICE`) directly in the HERE data layer, ensuring only stations with immediately available plugs (`numberOfAvailable > 0`) are selected.
- **Top 10 Proximity Selection:** Sort qualifying stations ascending by distance and select the top 10 nearest stations.
- **Authentic Name Resolution (EVCS HTML):** Resolve canonical station names concurrently from EVCS station detail HTML pages (`https://evcs.vn/tram-sac-vinfast-${locationId}.html`) using mobile User-Agent headers, bypassing Cloudflare blocks and extracting genuine titles (e.g. `C.BNI11197` -> "TƯ NHÂN Nguyễn Văn Đức").
- **Graceful Fallback:** If an EVCS page is unreachable or fails to parse, fallback cleanly to `"VinFast - ${hereAddress}"`.
- **No In-Memory/Persistent Cache:** Execute live resolution per filter request without persistent database/preferences caching per user specification.
- **Blocking Loading UX:** Display a circular loading spinner while fetching and resolving names for the top 10 stations before rendering cards on `NearbyScreen` and `NearbyLandscapeScreen`.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3
- Networking: OkHttp 4.12.0, Kotlinx Coroutines, Kotlinx Serialization
- Architecture: Clean Architecture + MVVM, StateFlow
- Testing: JUnit 4, Kotlin Coroutines Test, MockWebServer

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | HERE AC Discovery & EVCS HTML Name Resolver | 🟢 Completed | 100% | `HereAcDiscoveryAndHtmlNameResolutionEngineTest.kt` |
| 02 | Nearby Hybrid AC Flow Integration & Loading UX | ⬜ Pending | 0% | `NearbyHybridAcFlowAndLoadingUiStateTest.kt` |

## Execution Guidelines
- All phase files must be written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
