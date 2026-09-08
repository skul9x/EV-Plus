# Phase 03: Automotive Navigation Intent Dispatcher & Google Maps Reroute Engine
Status: 🟩 Completed
Dependencies: Phase 01, Phase 02

## Objective
Implement automotive turn-by-turn navigation intent dispatching via `CarContext.startCarApp` using Google's official Android Auto navigation contract (`CarContext.ACTION_NAVIGATE` with `geo:` URI). When the driver selects a charging station on Android Auto, automatically hand off routing to Google Maps on the vehicle screen, while simultaneously triggering EV-Plus `FocusModeForegroundService` on the phone for background port occupancy tracking, voice notifications (TTS with Audio Ducking), and mobile HUD synchronization.

## Requirements

### Functional
1. **Automotive Navigation Intent Dispatcher (`CarNavigationDispatcher.kt`)**:
   - In Android Auto Car App Library, cross-app navigation on the head unit must use `CarContext.ACTION_NAVIGATE` with a `geo:` URI (standard `Intent.ACTION_VIEW` with `google.navigation:` is rejected by the Android Auto projection host):
     - **Primary In-Car Navigation (`CarContext.startCarApp`)**:
       ```kotlin
       val encodedName = Uri.encode(station.name)
       val geoUri = Uri.parse("geo:0,0?q=${station.latitude},${station.longitude}($encodedName)")
       val intent = Intent(CarContext.ACTION_NAVIGATE, geoUri)
       carContext.startCarApp(intent)
       ```
     - **Mobile / Fallback Intent (`MapNavigator`)**:
       - When triggering navigation outside the car projection or as fallback, dispatches `Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${station.latitude},${station.longitude}&mode=d"))` (as explicitly specified in `1.md` Section 3.2).
     - **Reroute Handling**:
       - When the active station becomes full (0 vacant ports) or the driver selects an alternative station, immediately dispatches the new coordinates to the car navigation host to reroute Google Maps on the vehicle screen.

2. **Mobile Background Telemetry & Voice TTS Sync (`CarFocusModeBridge.kt`)**:
   - When navigation is launched from Android Auto:
     - Leverages existing `FocusModeForegroundService.getStartIntentSpec(station)` (or `getRerouteIntentSpec(newStation)`) to serialize station payload.
     - Dispatches Intent to `FocusModeForegroundService`:
       - Action: `FocusModeForegroundService.ACTION_START` (or `ACTION_REROUTE` if already navigating).
       - Extra: `FocusModeForegroundService.EXTRA_STATION_JSON` or `EXTRA_NEW_STATION_JSON`.
     - Launches service via `ContextCompat.startForegroundService(carContext, intent)`.
     - Ensures that the mobile device maintains background live polling, Floating HUD overlay, and **Voice TTS with Audio Ducking** (`1.md` Section 4):
       - Audio Ducking automatically lowers music/radio volume on the car system.
       - Dispatches clear Vietnamese voice prompts via vehicle speakers in 3 critical situations:
         1. Destination station becomes completely full (0 vacant ports).
         2. System discovers an alternative station with vacant ports (e.g. *"Trạm hiện tại đã hết trụ. Đã tìm thấy trạm thay thế cách 1.5km còn 4 trụ trống"*).
         3. Vehicle approaches within 2km of the destination station.
       - Respects `voice_alert_enabled` setting from `FocusModePreferences`.

3. **Screen Action Integration (`MainCarScreen.kt` & `StationDetailCarScreen.kt`)**:
   - Bind `"⚡ DẪN ĐƯỜNG & THEO DÕI"` action in `StationDetailCarScreen` to `CarNavigationDispatcher.startNavigation(carContext, station)`.
   - Add direct "Dẫn đường & Theo dõi" action in `MainCarScreen` station rows for quick 1-tap rerouting without needing to enter the detail screen.

### Non-Functional
- Strict compliance with Android Auto navigation standards (`CarContext.ACTION_NAVIGATE`).
- Decoupled interface (`CarNavigationIntentSpec`) for unit testing without requiring real Android Auto hardware or Android Context runtime.
- Seamless recovery if navigation provider is temporarily unavailable.

## Implementation Steps
1. Create `CarNavigationDispatcher.kt` in `app/src/main/java/com/evcs/favorites/car/`.
2. Create `CarFocusModeBridge.kt` in `app/src/main/java/com/evcs/favorites/car/`.
3. Integrate `CarNavigationDispatcher` into `MainCarScreen.kt` and `StationDetailCarScreen.kt`.
4. Create single verification test `CarNavigationDispatcherTest.kt` in `app/src/test/java/com/evcs/favorites/car/`.
5. Run the single verification test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarNavigationDispatcherTest"`
6. Stop execution and await user review.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/car/CarNavigationDispatcher.kt` - [New] Dispatcher for `CarContext.ACTION_NAVIGATE` on Android Auto and fallback to mobile.
- `app/src/main/java/com/evcs/favorites/car/CarFocusModeBridge.kt` - [New] Bridge to trigger and update FocusModeForegroundService and Voice TTS engine.
- `app/src/main/java/com/evcs/favorites/car/MainCarScreen.kt` - [Modify] Add direct navigation trigger on station list items.
- `app/src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt` - [Modify] Connect primary navigate button to dispatcher.
- `app/src/test/java/com/evcs/favorites/car/CarNavigationDispatcherTest.kt` - [New] Single verification test for Phase 03.

## Test Criteria
- Single test: `com.evcs.favorites.car.CarNavigationDispatcherTest`
  - Verifies generated in-car navigation intent uses `CarContext.ACTION_NAVIGATE` and `geo:` URI format with station label query.
  - Verifies fallback navigation intent generates valid `google.navigation:` URI.
  - Verifies `CarFocusModeBridge` serializes `Station` model and produces correct `FocusModeForegroundService.ACTION_START` / `ACTION_REROUTE` intents.
  - Verifies Voice TTS alert trigger conditions (0 vacant ports, alternative station found, 2km arrival threshold).
  - Verifies error handling when navigation host throws exception or is unavailable.

---
Next Phase: [Phase 04: Desktop Head Unit (DHU) Simulation & Android 15 Automotive Verification](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1136-android-auto-car-app-library/phase-04-dhu-simulation-and-android-15-verification.md)

