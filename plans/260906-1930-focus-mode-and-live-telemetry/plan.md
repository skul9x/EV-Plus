# Plan: Focus Mode (Live DC Telemetry Navigation Tracker) & Nearby Auto-Scroll on Refresh

Created: 2026-09-06 19:30
Status: 🟡 In Progress

## Overview
Implement the complete "Focus Mode" subsystem for EV-Plus along with the Nearby screen auto-scroll polish. When a driver selects a charging station and taps "Focus Mode", the app launches Google Maps turn-by-turn navigation while simultaneously launching a draggable Floating Window (System Alert Overlay) or persistent Foreground Notification. This floating bubble polls DC fast charging slots in real time using a 2-tier hybrid network architecture (Tier 1: HERE Maps EV API direct from VinFast credentials, Tier 2: EVCS fallback) with dynamic distance-based polling intervals (15s -> 10s -> 5s). It features Vietnamese audio/TTS voice alerts when all DC slots are taken, a 1-tap auto-reroute to the nearest equivalent-tier DC station with available slots, offline detection in basements, and an auto-scroll to top feature upon refreshing the "Nearby" list.

## Architecture Matrix

| Component | Responsibility | Architectural Details | Target Verification Test |
|---|---|---|---|
| **Nearby Auto-Scroll** | Auto-scroll to top on list refresh | `LazyListState.animateScrollToItem(0)` triggered on successful data arrival in `NearbyScreen`. | `NearbyAutoScrollOnRefreshTest.kt` |
| **HERE EV Telemetry** | Tier 1 Zero-Login DC telemetry | OAuth 1.0a HMAC-SHA256 Client Credentials + `HereEvApiClient` mapping VinFast station statuses. | `HereEvApiClientAndOAuthTest.kt` |
| **Focus Telemetry Engine** | Dynamic polling & DC slot state | `FocusModeManager` & `FocusModeForegroundService` managing polling intervals, DC-only filter ($\ge 30$kW), offline detection, and reroute search. | `FocusModeTelemetryEngineTest.kt` |
| **Voice / TTS Alerts** | Driver audio announcements | `FocusModeTtsManager` announcing DC full/available transitions in Vietnamese without screen glances. | `FocusModeVoiceAlertTest.kt` |
| **Floating Window & Fallback** | Draggable overlay on Google Maps | `WindowManager` overlay bubble with manual `[X]` dismiss, reroute button, and notification fallback if permission denied. | `FocusModeFloatingWindowAndFallbackTest.kt` |
| **Sheet Entry Point & Flow** | User trigger & intent binding | `NativeStationDetailSheet` button, onboarding permission dialog, and Google Maps intent launching. | `FocusModeEndToEndIntegrationTest.kt` |

## Tech Stack
- Language: Kotlin 1.9.23 (JVM Target 17)
- UI Framework: Jetpack Compose Material 3 (BOM 2024.04.01) + Android `WindowManager` Floating Views
- Network: OkHttp 4.12.0, OAuth 1.0a HMAC-SHA256, MockWebServer
- Concurrency: Kotlinx Coroutines 1.8.0, StateFlow, Foreground Service Lifecycle
- Audio: Android TextToSpeech (TTS) + SoundPool
- Architecture: Clean Architecture + MVVM/MVI, Dual-Tier Repository, Local-First
- Testing: JUnit 4, Kotlinx Coroutines Test 1.8.0, Mockito / Robolectric-free pure JVM units

## Phases

| Phase | Name | Scope & Deliverables | Status | Single Comprehensive Verification Test |
|---|---|---|---|---|
| 01 | Nearby Auto-Scroll on Refresh | Auto-scroll `LazyListState` to index 0 on successful refresh in `NearbyScreen`. | ⬜ Pending | [NearbyAutoScrollOnRefreshTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/NearbyAutoScrollOnRefreshTest.kt) |
| 02 | Hybrid Tier 1 HERE EV API & OAuth | OAuth 1.0a signing, token caching, and `HereEvApiClient` DC status parser. | ⬜ Pending | [HereEvApiClientAndOAuthTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/network/here/HereEvApiClientAndOAuthTest.kt) |
| 03 | Focus Mode Telemetry Engine & Polling | Dynamic polling (15s/10s/5s), DC filter, offline detection, and auto-reroute math. | ⬜ Pending | [FocusModeTelemetryEngineTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/focus/FocusModeTelemetryEngineTest.kt) |
| 04 | Voice Alert & Audio Announcement | Vietnamese TTS & chime alerts on DC slot saturation / availability state shifts. | ⬜ Pending | [FocusModeVoiceAlertTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/focus/FocusModeVoiceAlertTest.kt) |
| 05 | Floating Window UI & Permission Fallback | Draggable overlay bubble over Google Maps with `[X]` dismiss & notification fallback. | ⬜ Pending | [FocusModeFloatingWindowAndFallbackTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/focus/FocusModeFloatingWindowAndFallbackTest.kt) |
| 06 | Sheet Entry Point & E2E Integration | NativeStationDetailSheet `[⚡ Focus Mode]` button, permission dialog, and intent trigger. | ⬜ Pending | [FocusModeEndToEndIntegrationTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/focus/FocusModeEndToEndIntegrationTest.kt) |

## Execution Guidelines
- All phase files are written in English.
- For each phase, add **exactly one** comprehensive file-based test to verify the functionality of that phase after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification.
- Stop after each phase verification for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
