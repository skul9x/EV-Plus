# Plan: Focus Mode evcs.vn Station Name & 1-Tap Direct Turn-by-Turn Navigation

Created: 2026-09-07 00:55
Status: 🟢 Completed

## Overview

Refine Focus Mode in EV Plus to resolve two critical driver experience issues:
1. **Accurate Station Name from evcs.vn:** The floating window capsule currently displays the generic Here API name `"Trạm sạc VinFast"`. Telemetry polling overwrites the authentic station name with Here API's generic name. We will preserve the authentic station name from `evcs.vn`, implement an `EvcsStationNameResolver` to resolve proper station names by coordinates/ID, and enrich candidate stations for auto-rerouting.
2. **1-Tap Direct Turn-by-Turn Navigation:** Tapping "⚡ Focus Mode" currently triggers a standard `geo:0,0?q=lat,lon(name)` intent, opening Google Maps on the station address/pin preview screen and forcing the driver to manually tap "Bắt đầu" (Start). We will align Focus Mode activation with the "Chỉ đường" button to dispatch direct turn-by-turn driving navigation (`google.navigation:q=lat,lon&mode=d`) so navigation starts immediately with a single tap.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 & Android WindowManager Overlay
- Telemetry: HERE EV API (Tier 1) + evcs.vn API (Station Names & Tier 2)
- Unit Testing: JUnit4 + Kotlinx Coroutines Test + Android Intent Specs

## Phases

| Phase | Name | Status | Test |
|-------|------|--------|------|
| 01 | Turn-by-Turn Direct Navigation on Focus Mode Activation | 🟢 Completed | `com.evcs.favorites.focus.FocusModeDirectNavigationTest` |
| 02 | evcs.vn Station Name Resolution & Preservation | 🟢 Completed | `com.evcs.favorites.focus.FocusModeEvcsStationNameTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Run via `./gradlew testDebugUnitTest --tests "<TestClass>"`.
