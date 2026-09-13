# Phase 04: Navigation URI Parentheses Encoding, AC/DC Formatter & Session Lifecycle

Status: ✅ Completed
Dependencies: Phase 03

## Objective
Fix navigation URI corruption when station names contain parentheses `()`, guard against invalid `(0.0, 0.0)` coordinate navigation, correct AC/DC port classification for high-power AC chargers (e.g. 43kW AC), and terminate `FocusModeForegroundService` when the vehicle head unit disconnects.

## Requirements
### Functional
- [x] Sanitize or percent-encode parentheses `(` and `)` in station names before embedding into `geo:0,0?q=lat,lng(Label)` so Google Maps and Android Auto parse the destination correctly.
- [x] Guard against invalid `(0.0, 0.0)` coordinate dispatch in `CarNavigationDispatcher`.
- [x] Correct `isDcPort` classification in `CarStationFormatter`: if a connector label explicitly contains "AC", classify it as AC regardless of wattage.
- [x] Register a lifecycle observer on `EvPlusCarSession` to dispatch `ACTION_STOP` to `FocusModeForegroundService` upon `ON_DESTROY`, stopping background battery drain when Android Auto disconnects.

### Non-Functional
- [x] Robust Navigation: 100% compliant URI syntax with Google Maps intent filter and `AndroidAutoContractValidator.GEO_URI_PATTERN`.
- [x] Resource Cleanliness: Zero orphaned foreground services after disconnecting phone from car.

## Implementation Steps
1. [x] In `CarNavigationDispatcher.encodeStationName()`:
   - Sanitize parentheses: replace `(` and `)` with `[` and `]` or percent-encode as `%28` and `%29` before appending into the outer `(...)` query parameter.
   - Guard `startNavigation` and `rerouteNavigation`: if `station.latitude == 0.0 && station.longitude == 0.0`, return false with appropriate warning.
2. [x] Update `AndroidAutoContractValidator.GEO_URI_PATTERN` to reflect sanitized URI rules.
3. [x] In `CarStationFormatter.isDcPort()`:
   - Check negative indicators first: `if (port.label.contains("AC", ignoreCase = true) || port.displayString.contains("AC", ignoreCase = true)) return false`.
   - Then check positive indicators: `port.typeWatts >= DC_POWER_THRESHOLD_WATTS || port.label.contains("DC", ignoreCase = true) || port.displayString.contains("DC", ignoreCase = true)`.
4. [x] In `EvPlusCarSession`:
   - Register a `DefaultLifecycleObserver` or lifecycle listener on `session.lifecycle`.
   - In `onDestroy(owner: LifecycleOwner)`, invoke `FocusModeForegroundService.createStopIntent(carContext)` to cleanly terminate the mobile telemetry engine upon vehicle detachment.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/car/CarNavigationDispatcher.kt` - Sanitize parentheses, guard 0,0 coordinates
- `app/src/main/java/com/evcs/favorites/car/AndroidAutoContractValidator.kt` - Update regex and validation
- `app/src/main/java/com/evcs/favorites/car/CarStationFormatter.kt` - Fix AC/DC precedence check
- `app/src/main/java/com/evcs/favorites/car/EvPlusCarSession.kt` - Add lifecycle observer to stop background service on disconnect
- `app/src/test/java/com/evcs/favorites/car/CarNavigationAndLifecycleIntegrityTest.kt` - Comprehensive single verification test for Phase 04

## Single Verification Test
- **Test Class:** `com.evcs.favorites.car.CarNavigationAndLifecycleIntegrityTest`
- **Execution Command:**
  `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarNavigationAndLifecycleIntegrityTest"`
- **Verifications:**
  - Geo URI correctly escapes/sanitizes names containing nested parentheses without breaking URI format.
  - Routing with (0,0) coordinates is safely rejected.
  - High-power AC ports (e.g. 43kW AC) are correctly grouped under AC breakdown rather than DC.
  - Session ON_DESTROY event triggers termination of FocusModeForegroundService.

---
Plan Complete: All 4 phases defined.
