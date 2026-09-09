# Plan: EV Routing Performance Optimization & Recomposition Remediation

Created: 2026-09-09 09:05
Status: ✅ Completed

## Overview

Remediate the 4 critical performance bottlenecks and resource churn issues identified in the comprehensive performance audit (`docs/reports/audit_20260909.md`):
1. **Spatial Bounding Box for Backup Stations:** Eliminate full brute-force iteration over ~3,000 national stations in `selectBackupStation` by adding a coarse coordinate bounding box filter ($\Delta \text{lat} \le 0.1^\circ, \Delta \text{lng} \le 0.1^\circ$), reducing trigonometric and polyline calculations by >99%.
2. **Background Coroutine Dispatch for Station Swapping:** Move synchronous, heavy route recalculation in `swapStation` and `swapStopWithBackup` from `Dispatchers.Main` to a background worker dispatcher (`Dispatchers.Default` / `Dispatchers.IO`), eliminating UI freezes (150ms–500ms) during station swapping.
3. **Pre-compiled Static Regexes & Power Extraction Deduplication:** Pre-compile dynamic regular expressions into static constants and deduplicate `extractMaxPowerKw` and `extractStationMaxPowerKw` into a single canonical function, eliminating repeated heap allocations and GC churn.
4. **Compose Route Timeline Lazy Rendering & Memoization:** Memoize Haversine distance and station power calculations inside `BackupStationCard` via `remember(primaryStation.id, backupStation.id)` and optimize timeline stop rendering to ensure smooth 60fps scrolling and eliminate redundant recomposition overhead.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 (Automotive High-Contrast Dark Theme)
- Concurrency: Kotlin Coroutines & Flow (`viewModelScope`, `Dispatchers.Default`, `Dispatchers.IO`)
- Testing: JUnit 4, Kotlinx Coroutines Test (`runTest`, `StandardTestDispatcher`)

## Phases

| Phase | Name | Status | Verification Test |
|---|---|---|---|
| 01 | Spatial Bounding Box Filter for Backup Stations | ✅ Completed | `com.evcs.favorites.data.routing.EvSmartRouteBackupStationSpatialFilterTest` |
| 02 | Background Coroutine Dispatch for Alternate Station Swapping | ✅ Completed | `com.evcs.favorites.ui.screens.RouteViewModelStationSwapAsyncTest` |
| 03 | Pre-compiled Static Regexes & Power Extraction Deduplication | ✅ Completed | `com.evcs.favorites.data.routing.EvSmartRouteRegexOptimizationTest` |
| 04 | Compose Route Timeline Optimization & Memoization | ✅ Completed | `com.evcs.favorites.ui.screens.RouteTimelineMemoizationTest` |

## Verification Strategy
- Exactly **one** comprehensive file-based test per phase.
- Do not create or run more than one test per phase.
- After completing each phase, run **only** that single test for verification (`./gradlew testDebugUnitTest --tests "<TestClass>"`).
- Stop after each phase test execution for user review before proceeding to the next phase.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Next steps: `/next`
