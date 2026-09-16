# Plan: AC Charging Station Search Performance Optimization

Created: 2026-09-16 19:25
Status: 🟡 In Progress

## Overview
Optimize the end-to-end performance and user experience of the AC charging station discovery pipeline (`SmartFilterMode.AC` / `QuickChipOption.AC`) across the data, networking, and presentation layers:
- **Low-Allocation Pre-Filtering & Truncated Mapping:** Optimize `hasAvailableAcConnector()` to eliminate temporary list allocations (`allConnectors()`, `filter()`). Calculate distance on raw positions and take the top 10 stations *before* calling `HereModelMapper.mapStationToDomain`, cutting mapping CPU and heap churn by ~70-80%.
- **Streaming HTML Head Parser & In-Memory TTL Cache:** Parse canonical station titles by streaming the HTTP response and breaking immediately upon reaching `</head>` or `<title>`, preventing buffering large HTML bodies. Introduce an in-memory TTL Cache (1-hour lifespan) to eliminate redundant network round-trips upon refresh or tab toggle, with a 1.8s timeout guard.
- **Progressive (2-Stage) Staged UI Rendering:** Render the top 10 stations with fallback addresses and available plug counts immediately upon receiving HERE EV API results (~300-500ms) with `isLoading = false`, and enrich canonical names asynchronously in the background.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3
- Networking: OkHttp 4.12.0, Kotlinx Coroutines, Kotlinx Serialization
- Architecture: Clean Architecture + MVVM, StateFlow
- Testing: JUnit 4, Kotlin Coroutines Test, MockWebServer

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Low-Allocation Pre-Filtering & Mapping Optimization | ⬜ Pending | 0% | `AcStationPreFilterAndMappingOptimizationTest.kt` |
| 02 | Streaming HTML Resolution, TTL Caching & Timeout Guard | ⬜ Pending | 0% | `AcStationStreamingNameResolutionAndCacheTest.kt` |
| 03 | Progressive Staged UI Rendering & Pipeline Optimization | ⬜ Pending | 0% | `NearbyAcProgressiveRenderingAndOptimizationTest.kt` |

## Execution Guidelines
- All phase files must be written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
