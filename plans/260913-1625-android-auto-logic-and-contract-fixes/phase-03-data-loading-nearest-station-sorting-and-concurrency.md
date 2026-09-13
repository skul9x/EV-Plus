# Phase 03: Auto Data Loading, Proximity Sorting & Thread Concurrency

Status: 🟢 Completed
Dependencies: Phase 02

## Objective
Eliminate cold-start empty list dead-ends by triggering automatic background data refresh, calculate vehicle-relative distance using `DistanceCalculator`, sort stations nearest-first before list truncation, and eliminate disk I/O and JSON deserialization from the Car App Main Thread.

## Requirements
### Functional
- [x] Automatically trigger `refreshStations()` on screen initialization if cached stations are empty or to fetch fresh port availability.
- [x] Calculate real-time distance for each station using GPS location (via `FusedLocationProviderClient` or location resolver) and `DistanceCalculator.calculateDistanceKm()`.
- [x] Sort stations ascending by distance (nearest first) before applying `CarStationFormatter.truncateStations(stations, 6)`.
- [x] Prevent race conditions by cancelling prior in-flight refresh jobs before starting a new one.

### Non-Functional
- [x] ANR Prevention: Ensure all disk reads, network calls, and JSON decoding occur strictly on `Dispatchers.IO`, never blocking Car App main thread.
- [x] UX Consistency: Always show the 6 most relevant/closest stations to the vehicle rather than an arbitrary first 6 saved stations.

## Implementation Steps
1. [x] In `MainCarScreen`:
   - Store active coroutine job: `private var refreshJob: Job? = null`.
   - In `init`, if `stations.isEmpty()`, automatically invoke `refreshStations()`.
   - In `refreshStations()`, cancel `refreshJob` if active before launching a new coroutine.
2. [x] Move all calls to `repo.getCachedFavorites()` inside `withContext(Dispatchers.IO)` so disk I/O and JSON deserialization never touch the Main/UI thread.
3. [x] Implement a location resolver for `MainCarScreen`:
   - Retrieve last known location via `LocationServices.getFusedLocationProviderClient(carContext)` (when permission granted).
   - Map stations with calculated `distanceKm` using `DistanceCalculator.calculateDistanceKm(userLat, userLng, station.latitude, station.longitude)`.
4. [x] In `CarStationFormatter`:
   - Add `sortStationsByProximity(stations: List<Station>, userLocation: Pair<Double, Double>?): List<Station>`.
   - Sort stations by `effectiveDistanceKm ?: distanceKm ?: Double.MAX_VALUE` ascending before taking `MAX_LIST_ITEMS`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/car/MainCarScreen.kt` - Auto-refresh on init, coroutine cancellation, background IO dispatching, location-based sorting
- `app/src/main/java/com/evcs/favorites/car/CarStationFormatter.kt` - Nearest-first proximity sorting and distance calculation
- `app/src/test/java/com/evcs/favorites/car/CarStationDataLoaderAndSortingTest.kt` - Comprehensive single verification test for Phase 03

## Single Verification Test
- **Test Class:** `com.evcs.favorites.car.CarStationDataLoaderAndSortingTest`
- **Execution Command:**
  `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarStationDataLoaderAndSortingTest"`
- **Verifications:**
  - Auto-refresh triggers when initial stations are empty.
  - Stations with coordinates are properly sorted nearest-first before truncation to 6 items.
  - Prior refresh jobs are cancelled on subsequent refresh invocations without race condition.
  - Fallback cache loading executes on IO dispatcher without throwing ANR/main thread violations.

---
Next Phase: [Phase 04: Navigation URI Parentheses Encoding, AC/DC Formatter & Session Lifecycle](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-1625-android-auto-logic-and-contract-fixes/phase-04-navigation-encoding-ac-dc-formatter-and-session-lifecycle.md)
