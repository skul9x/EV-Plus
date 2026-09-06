# Phase 01: Turn-by-Turn Direct Navigation on Focus Mode Activation

Status: ✅ Completed
Dependencies: None

## Objective

Fix the Focus Mode activation flow so that tapping "⚡ Focus Mode" immediately initiates turn-by-turn driving navigation in Google Maps (`google.navigation:q=lat,lon&mode=d`), behaving identically to the primary "Chỉ đường" (Navigate) button, rather than stopping at the station address/pin preview screen (`geo:0,0?q=...`) which requires the driver to manually tap "Bắt đầu" (Start).

## Requirements

### Functional
- When activating Focus Mode from `NativeStationDetailSheetHelper.startFocusMode(context, station)`, use `MapNavigator.navigate(context, station.latitude, station.longitude, station.name)` or equivalent turn-by-turn intent dispatching.
- Update `NativeStationDetailSheetHelper.launchNavigation(context, station)` to dispatch turn-by-turn navigation via `MapNavigator.navigate` instead of static `geo:0,0?q=lat,lon(stationName)`.
- Update `NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)` so `navigationIntentSpec` outputs turn-by-turn URI `google.navigation:q=latitude,longitude&mode=d` targeting `com.google.android.apps.maps`.
- Preserve fallback mechanism: if Google Maps is not installed, fallback gracefully to generic `geo:` intent and web browser.

### Non-Functional
- 0ms additional latency on tap: navigation intent must be dispatched synchronously without waiting for network or telemetry response.
- Safety: Zero crashes on devices without Google Maps installed.

## Implementation Steps
1. [x] In `NativeStationDetailSheet.kt`, update `NativeStationDetailSheetHelper.buildFocusModeActivationSpec(station)` to use `MapNavigator.getGoogleMapsIntentSpec(station.latitude, station.longitude)`.
2. [x] In `NativeStationDetailSheet.kt`, update `NativeStationDetailSheetHelper.launchNavigation(context, station)` to delegate to `MapNavigator.navigate(context, station.latitude, station.longitude, station.name)`.
3. [x] Verify that `NativeStationDetailSheetHelper.startFocusMode(context, station)` starts the foreground service and launches immediate navigation.
4. [x] In existing tests that inspect `activationSpec.navigationIntentSpec` (such as `FocusModeEndToEndIntegrationTest.kt`), update expectations to match the turn-by-turn navigation URI format.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Update Focus Mode navigation intent generation and dispatching to use turn-by-turn navigation.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeDirectNavigationTest.kt` - [NEW] Single comprehensive test for Phase 01.

## Single Comprehensive Test
- File: `app/src/test/java/com/evcs/favorites/focus/FocusModeDirectNavigationTest.kt`
- Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeDirectNavigationTest"`
- Verification criteria:
  - Focus Mode activation spec contains `google.navigation:q=lat,lon&mode=d` targeting `com.google.android.apps.maps`.
  - Intent spec for Focus Mode matches the "Chỉ đường" turn-by-turn navigation format.
  - Fallback intent descriptors are preserved when Google Maps is unavailable.

---
Next Phase: `phase-02-evcs-station-name-resolution.md`
