# Plan: Online EV Smart Routing Algorithm Refinement & Backup Station Pairing

Created: 2026-09-09 07:35
Updated: 2026-09-09 07:42
Status: 🟡 In Progress

## Overview

Enhance the EV smart routing engine to implement production-ready, industry-standard EV routing principles (inspired by ABRP, HERE EV Routing API, and Google Maps EV) tailored for Vietnamese charging infrastructure (VinFast/V-Green):
1. **Online 4G Integration:** Rely fully on live 4G network telemetry (OSRM + HERE EV API + EVCS API) without requiring heavy local offline station databases.
2. **Dynamic Starting SoC Range & Battery Simulation:** Calculate the first leg strictly from the active starting battery percentage slider ($SoC_{start}$), intermediate legs replenished to recommended 85% fast-charging threshold, and safe arrival buffer ($SoC_{buffer}$) at the final destination.
3. **Lookahead Reachability with Minimum Power Filtering:** Replace naive single-step greedy jumps with forward-reachability lookahead to prevent dead-end trapping. Filter candidates along the corridor by minimum charger power ($\ge 60\text{ kW}$ DC default, user-configurable to 30 kW, 60 kW, 150 kW, 250 kW).
4. **Insufficient Power Fallback & Complete Route Generation:** When no station meets the desired power requirement in a reachable window, do not halt or break routing. Instead, synthesize the complete feasible route using the highest-power available fallback station ($\ge 20\text{ kW}$) and present an interactive M3 dialog allowing 1-tap criteria acceptance or manual station swap.
5. **Primary & Backup Station Pairing:** For every charging stop, select and pair both a Primary Station and a designated highway-safe Backup Station in close proximity ($\le 10\text{ km}$ along corridor, non-highway-trap), enabling 1-tap instant diversion if the primary station is occupied or offline.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 (Automotive High-Contrast Dark Theme)
- Architecture: MVVM + Repository Pattern + Kotlin Coroutines & Flow
- Routing & Telemetry: MultiTierRoutingCoordinator (Google Routes v2 + OSRM + Haversine) + HERE Maps EV API + EVCS Live Telemetry

## Phases

| Phase | Name | Status | Verification Test |
|---|---|---|---|
| 01 | Minimum Power Criteria & Starting SoC Settings Integration | ⬜ Pending | `com.evcs.favorites.data.routing.EvRoutingMinPowerSettingsTest` |
| 02 | Lookahead Corridor Routing Engine with Power Filtering & Fallback Detection | ⬜ Pending | `com.evcs.favorites.data.routing.EvSmartRoutePowerFilterPlannerTest` |
| 03 | Insufficient Power Fallback Modal Flow & Criteria Relaxation | ⬜ Pending | `com.evcs.favorites.ui.screens.RouteInsufficientPowerDialogTest` |
| 04 | Primary & Backup Station Pairing and Timeline UI | ⬜ Pending | `com.evcs.favorites.ui.screens.RoutePrimaryAndBackupStationUiTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Verification command: `./gradlew testDebugUnitTest --tests "<TestClass>"`.
- Stop after each phase test completes for user review.
