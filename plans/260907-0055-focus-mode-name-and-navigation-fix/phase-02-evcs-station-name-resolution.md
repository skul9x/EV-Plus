# Phase 02: evcs.vn Station Name Resolution & Preservation

Status: 🟢 Completed
Dependencies: Phase 01

## Objective

Ensure the Focus Mode floating window overlay and foreground notifications always display the authentic, standard charging station name from `evcs.vn` (e.g. "VinFast Landmark 81", "VinFast Imperia Smart City") instead of Here EV API's generic name `"Trạm sạc VinFast"`.

## Requirements

### Functional
1. **Name Preservation During Telemetry Polling:**
   - In `FocusModeTelemetryEngine.pollOnce()`, when live telemetry is received from Here EV API (`fetchStationTelemetry`), do NOT overwrite the target station's name with Here API's generic `"Trạm sạc VinFast"`.
   - Preserve the authentic evcs.vn name from `target.name` (or `initialStation.name`) if it is already non-generic.
2. **evcs.vn Station Name Resolver (`EvcsStationNameResolver`):**
   - Provide a resolver utility that resolves/enriches station names using `evcs.vn` station search data (matching by locationId or coordinate proximity <= 100 meters).
   - Cache resolved names in memory (e.g. LRU or ConcurrentHashMap) to prevent redundant network requests.
   - If a station starts with a generic name (e.g. `"Trạm sạc VinFast"`, `"VinFast"`, or empty), resolve its standard name from `evcs.vn`.
3. **Alternative Station Name Enrichment:**
   - When `findAlternativeStation` identifies candidate stations from Here EV API for auto-rerouting, enrich candidates with their standard evcs.vn names so the reroute recommendation displays "Đổi trạm: VinFast Landmark 81 (+1.2km)" instead of generic "Đổi trạm: Trạm sạc VinFast (+1.2km)".
4. **Floating Window UI & Presentation:**
   - Ensure `FocusModeViewLayoutHelper.formatViewState` and `FocusModeFloatingViewManager` render the sanitized, authentic station name.

### Non-Functional
- Performance: Station name resolution must never block the main thread or delay initial floating window attachment (instant 0ms display using `initialStation.name`).
- Resilience: If network or evcs.vn is temporarily unavailable, gracefully retain the existing name without crashing or showing blank text.

## Implementation Steps
1. [x] Create `EvcsStationNameResolver` (in `com.evcs.favorites.data.repository` or `com.evcs.favorites.focus`) to resolve standard station names from `evcs.vn` search results and cache mappings.
2. [x] Update `FocusModeTelemetryEngine`:
   - Add name preservation logic in `pollOnce()` to prevent Here API's `"Trạm sạc VinFast"` from overwriting valid station names.
   - Integrate `stationNameResolver` for resolving missing/generic target station names and alternative station candidate names.
3. [x] Wire up `EvcsStationNameResolver` in `AppContainer` / `DefaultAppContainer` and `FocusModeForegroundService`.
4. [x] Ensure `FocusModeFloatingViewManager` and `FocusModeNotificationHelper` present the resolved station name cleanly.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/focus/EvcsStationNameResolver.kt` - [NEW] Utility for resolving and caching standard station names from evcs.vn.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt` - Update polling and alternative candidate logic to preserve and resolve evcs.vn station names.
- `app/src/main/java/com/evcs/favorites/di/AppContainer.kt` - Expose station name resolver or evcs repository dependency for Focus Mode service.
- `app/src/main/java/com/evcs/favorites/focus/FocusModeForegroundService.kt` - Connect station name resolver to telemetry engine instance.
- `app/src/test/java/com/evcs/favorites/focus/FocusModeEvcsStationNameTest.kt` - [NEW] Single comprehensive test for Phase 02.

## Single Comprehensive Test
- File: `app/src/test/java/com/evcs/favorites/focus/FocusModeEvcsStationNameTest.kt`
- Command: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeEvcsStationNameTest"`
- Verification criteria:
  - Polling loop preserves authentic evcs.vn station name ("VinFast Landmark 81") and ignores generic Here API name ("Trạm sạc VinFast").
  - Name resolver successfully matches evcs.vn search results by location ID or GPS proximity to retrieve standard station name.
  - Alternative station recommendations resolve and display evcs.vn names instead of generic names.
  - Floating window view state displays the standard evcs.vn name across all states.
