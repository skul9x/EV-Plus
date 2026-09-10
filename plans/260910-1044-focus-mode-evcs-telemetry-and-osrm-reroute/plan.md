# Plan: Focus Mode EVCS Telemetry & Conditional OSRM Reroute
Created: 2026-09-10
Status: ⬜ Pending

## Overview
Standardizes Focus Mode telemetry and rerouting architecture based on EVCS.vn production network capture (`curl_capture_20260910_105448`) and empirical polling benchmarks:
1. **Target Station Telemetry Polling:** Continuously monitors the target station via direct EVCS API with a constant 10-second interval (`INTERVAL_FIXED_MS = 10_000L`), retaining the last valid telemetry snapshot on transient network latency or timeouts.
2. **On-Demand Conditional Rerouting:** Decouples candidate discovery from the 10s background loop. When target DC slots reach 0 AND the driver explicitly taps reroute on the Floating HUD, the engine performs a targeted EVCS spatial search (`wattageTypes = ["FAST", "SUPER_FAST"]`), pre-filters top 5–8 DC candidates via Haversine, and queries OSRM Road Distance Matrix (`table/v1/driving/`) to rank by actual driving distance with Haversine fallback.
3. **Voice Guidance & Navigation:** Emits standardized Vietnamese TTS announcement and launches Google Maps turn-by-turn navigation to the chosen alternative station.
4. **Emergency Fallback:** Preserves HERE Maps EV API as an intact offline/audit backup module (Direction A).

## User Constraints & Rules
1. All phase files must be written in English in markdown (`.md`) format.
2. For each phase, add exactly one comprehensive file-based test to verify the core functionality of that phase after implementation.
3. Do not create or run more than one test per phase.
4. After completing each phase, run only that single test for verification. Then stop so user can review.
5. Once completely done, just say "done."

## Architecture & Tech Stack
- **Language:** Kotlin 1.9.23 / JVM 17
- **UI & Overlay:** Jetpack Compose & Android System Alert Window Overlay
- **Data Source (Telemetry & Search):**
  - EVCS Spatial Search: `POST /search?t={token}` (`wattageTypes: ["FAST", "SUPER_FAST"]`)
  - EVCS Station Telemetry: `POST /{slug}.html` (`X-Partial: user`) & `POST /charging` (`x-t: chargeToken`)
- **Routing Engine:** `OsrmRoutingClient` (`table/v1/driving/{coords}?sources=0&annotations=duration,distance`)
- **Voice & Audio:** Android TextToSpeech (`vi-VN`) with transient audio ducking
- **Testing:** JUnit 4 + Kotlinx Coroutines Test + MockWebServer

## Phases

| Phase | Name | Description | Status | Progress |
|-------|------|-------------|--------|----------|
| 01 | Focus Mode EVCS Fixed 10s Telemetry Polling | Replace HERE polling loop with EVCS telemetry polling at fixed 10s interval & retain snapshot on lag | ✅ Completed | 100% |
| 02 | Conditional OSRM Driving Matrix Reroute Engine | Implement on-demand two-tier rerouting (EVCS fast DC search -> Haversine top-8 -> OSRM Table matrix sorting by driving km with Haversine fallback) strictly on target 0 slots + user tap | ⬜ Pending | 0% |
| 03 | Vietnamese TTS Alert Voice Scripting & Floating HUD Integration | Update TTS announcement template to exact spec, wire reroute button on Floating HUD to trigger OSRM recommendation, and verify end-to-end integration | ⬜ Pending | 0% |

## Verification Command Cheat-Sheet
- Phase 01: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeEvcsPollingTest"`
- Phase 02: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeOsrmRerouteTest"`
- Phase 03: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeVoiceAndHudIntegrationTest"`
