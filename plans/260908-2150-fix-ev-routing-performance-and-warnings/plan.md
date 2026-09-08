# Plan: Fix EV Smart Routing Performance Bottlenecks & UI Warnings

Created: 2026-09-08 21:50
Status: ✅ Completed

## Overview

Remediate the critical algorithmic performance bottleneck and warnings diagnosed during the `/audit` inspection of the EV Smart Route Planning & Corridor Navigation System:
1. **Algorithmic Complexity & Trigonometry Optimization (Critical):** Eliminate $O(N \times M)$ polyline projection storm and excessive Haversine calculations in `EvSmartRoutePlanner` using spatial Bounding Box pre-filtering and localized flat Euclidean segment projection.
2. **Slider Disk Thrashing Prevention (Warning):** Prevent 60 writes/sec to SharedPreferences and JSON serialization while dragging EV settings sliders by deferring disk commits to `onValueChangeFinished`.
3. **Comprehensive Route Station Sourcing (Warning):** Ensure `RouteViewModel` sources highway charging stations from the complete station cache rather than sparse personal favorites, preventing false "Dead Zone" alerts on long journeys.
4. **Compose UI Performance & Zero-Allocation Rendering (Warning/Suggestion):** Memoize district name lists, optimize `DropdownSelector` rendering for 63 provinces, cache sorted province collator lists, and eliminate garbage collection allocations in `EnergyCorridorBar` drawing.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 & Automotive HMI Dark Theme
- Architecture: MVVM + Repository Pattern + StateFlow
- Testing: JUnit4 + Kotlinx Coroutines Test

## Phases

| Phase | Name | Status | Verification Test |
|-------|------|--------|-------------------|
| 01 | Algorithmic Optimization & Bounding Box Filtering | ✅ Completed | `com.evcs.favorites.data.routing.EvSmartRoutePlannerOptimizationTest` |
| 02 | Slider Disk Thrashing Prevention & State Updates | ✅ Completed | `com.evcs.favorites.data.preferences.EvSliderPersistenceDebounceTest` |
| 03 | Comprehensive Route Station Sourcing | ✅ Completed | `com.evcs.favorites.ui.screens.RouteStationSourcingIntegrationTest` |
| 04 | Compose Dropdown & Zero-Allocation UI Optimizations | ✅ Completed | `com.evcs.favorites.ui.screens.RouteUiPerformanceOptimizationTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Verification command: `JAVA_HOME=/home/skul9x/.jdks/jdk-17.0.20.1+1 ./gradlew testDebugUnitTest --tests "<TestClass>"`.
- Stop after each phase test completes for user review.
