# Plan: EV Smart Route Planning & Corridor Navigation System

Created: 2026-09-08 19:55
Status: 🟢 Completed

## Overview

Implement an offline-resilient, VinFast-tailored smart routing and charging navigation system for EV-Plus.
Key capabilities:
1. **Offline Locations Asset:** Bundle Vietnam's 63 provinces and ~700 districts with driving centroid coordinates directly into `app/src/main/assets/vietnam_locations.json` for 0ms, zero-dependency offline lookup.
2. **Vehicle & Route Settings:** User-configured safe driving range at 100% SoC (100 - 500 km), starting SoC %, arrival safety reserve (default 10%), target charging SoC (default 85%), and charging duration buffer (+25% toggle).
3. **Corridor Polyline & Anti-Trap Algorithm:** OSRM corridor buffering ($D_{buffer} \le 3-5$ km), strict rejection of opposite-lane highway stations with dual-carriageway barriers (detour penalty $> 3$ km), high-power DC station prioritization ($\ge 60$ kW $\to$ 30 kW $\to$ exclude AC 11 kW), greedy multi-stop leapfrog planning, and dead-zone detection.
4. **Three-Tab Bottom Navigation & Route Screen UI:** Reorganize app tabs into `NEARBY`, `FAVORITES` (centered), and `ROUTE`. Provide Origin/Destination selection with GPS 1-tap `[🎯]`, safe range slider, vertical stop timeline, live plug status badges with vacancy ETA for busy stations, Energy Corridor Bar, and "Đổi trạm khác" bottom sheet.
5. **Settings Integration & Navigation Handoff:** Dedicated EV Smart Routing section in Settings modal and auto-advancing navigation handoff to Focus Mode / turn-by-turn navigation.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 & Automotive HMI Dark Theme
- Architecture: MVVM + Repository Pattern + Kotlin Coroutines & Flow
- Testing: JUnit4 + Kotlinx Coroutines Test + Compose Contract Testing

## Phases

| Phase | Name | Status | Verification Test |
|-------|------|--------|-------------------|
| 01 | Offline Locations Dataset & Repository | 🟢 Completed | `com.evcs.favorites.data.locations.VietnamLocationsRepositoryTest` |
| 02 | EV Routing Settings & Vehicle Profile Configuration | 🟢 Completed | `com.evcs.favorites.data.preferences.EvRoutingSettingsPreferencesTest` |
| 03 | Smart EV Corridor Route Planner Engine | 🟢 Completed | `com.evcs.favorites.data.routing.EvSmartRoutePlannerTest` |
| 04 | Three-Tab Navigation & Route Screen UI | 🟢 Completed | `com.evcs.favorites.ui.screens.RouteTabNavigationAndUiStateTest` |
| 05 | Settings Modal Integration & Multi-Stop Navigation Handoff | 🟢 Completed | `com.evcs.favorites.ui.EvRoutingSettingsAndHandoffIntegrationTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Verification command: `./gradlew testDebugUnitTest --tests "<TestClass>"`.
- Stop after each phase test completes for user review.
