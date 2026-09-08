# Plan: Phase 3 - Android Auto Integration (Car App Library)

Created: 2026-09-08 11:36
Last Updated: 2026-09-08 11:50
Status: ✅ Completed

## Overview

Integrate the official Google Car App Library (`androidx.car.app:app:1.7.0` and `androidx.car.app:app-projected:1.7.0`) into EV-Plus according to Section 3 ("GIAI ĐOẠN 3: Tích hợp Android Auto (Car App Library)") and Section 4 ("Cảnh báo Giọng nói Thông minh") of `1.md`.

This enables drivers to view EV charging stations directly on in-vehicle Android Auto infotainment screens, browse nearby and favorite stations with live port availability (e.g. `🟢 4/8 TRỐNG • 250kW, 60kW`), inspect charging connector tiers in a distraction-optimized interface, and trigger 1-tap seamless navigation rerouting to Google Maps on Android Auto while synchronizing with the EV-Plus mobile background telemetry engine (`FocusModeForegroundService` with Voice TTS & Audio Ducking).

The implementation is structured into 4 sequential phases:
1. **Gradle, Manifest & CarAppService Architecture:** Add `androidx.car.app:app:1.7.0`, `androidx.car.app:app-projected:1.7.0`, and `androidx.car.app:app-testing:1.7.0`. Create `automotive_app_desc.xml`, declare `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES"/>` in `AndroidManifest.xml`, configure `<service android:name=".car.EvPlusCarAppService">` with `androidx.car.app.category.POI`, configure `minCarApiLevel = 1` for broad head-unit compatibility, establish host validation policies supporting DHU and release sideload on Android 15 without Play Store enforcement, and wire dependency injection via `AppContainer`.
2. **Car Screens Architecture & Automotive PlaceListMapTemplate UI:** Implement `EvPlusCarSession`, `MainCarScreen`, and `StationDetailCarScreen` adhering strictly to Android Auto Driver Distraction safety rules:
   - `PlaceListMapTemplate`: Displays station list (max 6 items) linked with `Place` & `PlaceMarker` on the host-rendered map, top-level `ActionStrip` with Refresh action, adaptive `setOnContentRefreshListener` for hosts supporting Car App API Level 5+, and real-time availability badges (`🟢 4/8 TRỐNG`).
   - `PaneTemplate`: Strict adherence to Car App host constraints (maximum 4 content rows, maximum 2 actions, backwards-compatible header navigation via `setHeaderAction(Action.BACK)` for Car App API 1-6 with adaptive `Header.Builder` for API 7+), displaying connector tiers (250kW, 150kW, 60kW, 11kW) and prominent `⚡ DẪN ĐƯỜNG & THEO DÕI` action button per `1.md`.
   - Decoupled `CarStationFormatter` & UI models allowing 100% deterministic pure JVM testing.
3. **Automotive Navigation Intent Dispatcher & Google Maps Reroute Engine:** Build `CarNavigationDispatcher` using the official Android Auto standard `CarContext.ACTION_NAVIGATE` with `geo:` URI (`geo:0,0?q=lat,lng(Label)`) for the in-car screen, with fallback to `google.navigation:q=lat,lng&mode=d` via `Intent.ACTION_VIEW` for mobile context per `1.md`. Wire bidirectional synchronization with `FocusModeForegroundService` on the mobile device via `FocusServiceIntentSpec` (`ACTION_START` / `ACTION_REROUTE`) so background telemetry, live port tracking, and Voice TTS with Audio Ducking (for 0 vacant ports, rerouting, and 2km arrival alerts) remain active while Google Maps navigates on the car display.
4. **Desktop Head Unit (DHU) Simulation & Android 15 Automotive Verification:** Provide complete operational workflows for DHU simulation via ADB port forwarding (`adb forward tcp:5277 tcp:5277`), Android 15 developer mode setup ("Unknown Sources" per `1.md` Section 3), and end-to-end automotive contract verification covering service discovery, back-stack safety (depth <= 12), and lifecycle transitions.

## Tech Stack
- Platform: Android 8.0+ (API 26-34, fully compatible with Android 15)
- Language: Kotlin 1.9.23 (JVM 17)
- In-Car UI: AndroidX Car App Library (`androidx.car.app:app:1.7.0` + `androidx.car.app:app-projected:1.7.0`)
- Required Permission: `androidx.car.app.MAP_TEMPLATES`
- App Category: `androidx.car.app.category.POI` (Point of Interest / EV Charging)
- Baseline Car API Level: `minCarApiLevel = 1` (with adaptive API Level 5+ content refresh and API 7+ header support)
- Automotive Templates: `PlaceListMapTemplate` (max 6 items, host map, ActionStrip), `PaneTemplate` (max 4 rows, max 2 actions, Header Action.BACK), `ItemList`, `Row`, `Place`, `PlaceMarker`, `Action`
- Navigation Dispatch: `CarContext.ACTION_NAVIGATE` (`geo:0,0?q=...`) via `CarContext.startCarApp`, fallback to `Intent.ACTION_VIEW` (`google.navigation:...`) on mobile
- Telemetry & Voice Sync: `FocusModeForegroundService` and `FocusModeTelemetryEngine` (Voice TTS + Audio Ducking)
- Testing: Pure JVM Unit Tests (JUnit4 + decoupled Spec/Model architecture for deterministic headless verification)

## Execution Rules
- **Phase isolation:** Complete each phase sequentially.
- **Single test rule:** For each phase, add exactly one comprehensive file-based test to verify the core functionality of that phase after implementation. Do not create or run more than one test per phase.
- **Verification step:** After completing each phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- **Stop for review:** Halt after test execution and await user review before proceeding to the next phase.

## Phases

| Phase | Name | Status | Single Verification Test |
|---|---|---|---|
| 01 | Gradle, Manifest & CarAppService Architecture | ✅ Completed | `com.evcs.favorites.car.CarAppServiceConfigurationTest` |
| 02 | Car Screens Architecture & Automotive PlaceListMapTemplate UI | ✅ Completed | `com.evcs.favorites.car.CarScreensTemplateTest` |
| 03 | Automotive Navigation Intent Dispatcher & Google Maps Reroute Engine | ✅ Completed | `com.evcs.favorites.car.CarNavigationDispatcherTest` |
| 04 | Desktop Head Unit (DHU) Simulation & Android 15 Automotive Verification | ✅ Completed | `com.evcs.favorites.car.AndroidAutoContractVerificationTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- Stop after each phase for user review and validation.

