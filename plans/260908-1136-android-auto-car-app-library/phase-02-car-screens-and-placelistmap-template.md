# Phase 02: Car Screens Architecture & Automotive PlaceListMapTemplate UI
Status: 🟢 Completed
Dependencies: Phase 01

## Objective
Implement automotive UI screens using Google Car App Library templates (`PlaceListMapTemplate` and `PaneTemplate`). Enable drivers to glance at their favorite and nearby charging stations on the in-vehicle screen, showing real-time port availability breakdown (e.g. `🟢 4/8 TRỐNG • 250kW, 60kW`), distance metrics, and power ratings with strict adherence to Car App Driver Distraction Safety rules (maximum 6 items per list, maximum 4 rows per PaneTemplate).

## Requirements

### Functional
1. **Car Session Lifecycle (`EvPlusCarSession.kt`)**:
   - Implement `EvPlusCarSession` extending `androidx.car.app.Session`.
   - On `onCreateScreen(intent: Intent)`, initialize and return `MainCarScreen(carContext)`.

2. **Main Car Screen (`MainCarScreen.kt`)**:
   - Implement `MainCarScreen` extending `androidx.car.app.Screen`.
   - Implement `onGetTemplate(): Template`:
     - Builds and returns a `PlaceListMapTemplate`.
     - Displays title: "EV-Plus - Trạm sạc".
     - Sets `setCurrentLocationEnabled(true)` to display user's vehicle location on the host-rendered map (using existing location permissions).
     - Configures top-level `ActionStrip` with a Refresh action (`Action.Builder().setTitle("Làm mới").setOnClickListener { refreshStations() }.build()`), ensuring refresh capability on all Car App API levels (Level 1+).
     - Conditionally calls `setOnContentRefreshListener { refreshStations() }` only when supported by the host (`carContext.carAppApiLevel >= 5`).
     - Loading state: Returns `PlaceListMapTemplate.Builder().setLoading(true).build()` when fetching stations.
     - Empty state: Provides `ItemList.Builder().setNoItemsMessage("Không tìm thấy trạm sạc khả dụng")` when list is empty.
     - Maps station locations: Builds `Place` with `CarLocation.create(latitude, longitude)` and `PlaceMarker.Builder().setColor(markerColor).build()`. Color-codes marker (Green if available ports > 0, Red if full).
     - Station Items (`ItemList`):
       - Adheres strictly to Android Auto Car App guidelines: maximum 6 rows per list (`ItemLimit = 6`) to prevent driver distraction.
       - Formats Station title with sanitization and provider badges.
       - Formats Subtitle with live hero metric: `🟢 X/Y TRỐNG • 250kW, 60kW` and formatted distance (e.g., `1.8 km`).
       - Links each row to map via `Row.Builder.setMetadata(Metadata.Builder().setPlace(place).build())`.
       - Row click: Opens `StationDetailCarScreen` (`screenManager.push(StationDetailCarScreen(carContext, station))`).

3. **Station Detail Screen (`StationDetailCarScreen.kt`)**:
   - Implement `StationDetailCarScreen` extending `androidx.car.app.Screen`.
   - Utilizes `PaneTemplate` adhering strictly to Android Auto safety constraints:
     - **Constraint 1 - Maximum 4 Content Rows:** (Host throws `IllegalArgumentException` if rows > 4):
       - Row 1 (Location): Street address and live distance (e.g., `1.2 km • 123 Nguyễn Trãi, Q.1`).
       - Row 2 (Hero Availability): `🟢 4/8 Trụ trống (Tổng 8 trụ sạc)`.
       - Row 3 (DC Fast Charging): High-power breakdown (e.g., `⚡ DC: 250kW (2 trụ), 60kW (2 trụ)`).
       - Row 4 (AC Charging): Standard power breakdown (e.g., `🔌 AC: 11kW (4 trụ)`).
     - **Constraint 2 - Maximum 2 Actions in Pane:**
       - Primary action: `"⚡ DẪN ĐƯỜNG & THEO DÕI"` (per `1.md` Section 2.2 line 83 and Section 3.1 line 96) with `CarColor.GREEN`. Triggers Phase 03 navigation flow.
     - **Header Action & API Compatibility:**
       - Uses `PaneTemplate.Builder.setHeaderAction(Action.BACK).setTitle(station.name)` for universal Car App API Level 1-6 compatibility.
       - For hosts supporting Car App API Level 7+, adaptively supports `Header.Builder().setStartHeaderAction(Action.BACK).setTitle(station.name).build()` without breaking older head units.

4. **Automotive Formatting Utility & Decoupled Models (`CarStationFormatter.kt` & `CarTemplateModels.kt`)**:
   - Decoupled pure Kotlin formatter and data models (`CarStationUiModel`, `CarRowSpec`, `CarPaneSpec`):
     - Availability indicators with color coding (`🟢`, `🟡`, `🔴`).
     - Grouping connector power ratings into concise, single-line DC and AC summaries (`250kW, 150kW, 60kW, 30kW, 20kW, 11kW`).
     - Distance and address string formatting.
     - Strict 6-item list truncation and empty-state placeholders.
     - 100% decoupled from Android framework stubs to allow pure, fast JVM unit testing.

### Non-Functional
- Strict compliance with Car App template limits (max 6 items in list, max 4 rows in pane).
- Smooth screen transitions respecting backstack depth limits (depth <= 12).
- Pure JVM testable using decoupled specifications and JUnit4 assertions.

## Implementation Steps
1. Create `CarTemplateModels.kt` and `CarStationFormatter.kt` in `app/src/main/java/com/evcs/favorites/car/`.
2. Implement `EvPlusCarSession.kt` in `app/src/main/java/com/evcs/favorites/car/`.
3. Implement `StationDetailCarScreen.kt` in `app/src/main/java/com/evcs/favorites/car/`.
4. Implement `MainCarScreen.kt` in `app/src/main/java/com/evcs/favorites/car/`.
5. Create single verification test `CarScreensTemplateTest.kt` in `app/src/test/java/com/evcs/favorites/car/`.
6. Run the single verification test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarScreensTemplateTest"`
7. Stop execution and await user review.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/car/CarTemplateModels.kt` - [New] Decoupled automotive UI models and specifications.
- `app/src/main/java/com/evcs/favorites/car/CarStationFormatter.kt` - [New] Automotive text and template formatting utility.
- `app/src/main/java/com/evcs/favorites/car/EvPlusCarSession.kt` - [New] Session implementation managing Car screens.
- `app/src/main/java/com/evcs/favorites/car/MainCarScreen.kt` - [New] Main Car Screen rendering PlaceListMapTemplate.
- `app/src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt` - [New] Station detail PaneTemplate screen.
- `app/src/test/java/com/evcs/favorites/car/CarScreensTemplateTest.kt` - [New] Single verification test for Phase 02.

## Test Criteria
- Single test: `com.evcs.favorites.car.CarScreensTemplateTest`
  - Verifies station list truncation strictly complies with the 6-item automotive safety limit.
  - Verifies row title, metadata, and subtitle text formatting includes hero availability counts and power tiers.
  - Verifies POI coordinates, CarLocation, and PlaceMarker color mapping for host map placement.
  - Verifies station detail PaneTemplate adheres to the <= 4 rows and <= 2 actions limits.
  - Verifies action button text is `"⚡ DẪN ĐƯỜNG & THEO DÕI"`.
  - Verifies empty and loading state handling when no stations or network is available.

---
Next Phase: [Phase 03: Automotive Navigation Intent Dispatcher & Google Maps Reroute Engine](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1136-android-auto-car-app-library/phase-03-automotive-navigation-and-google-maps-reroute.md)

