# Android Application Logic Audit Report

## 1. Executive Summary

### Project Overview
The EV+ (TramsacEV) application is a native Android application built with Kotlin 1.9.23 and Jetpack Compose (BOM 2024.04.01). It provides electric vehicle drivers with real-time VinFast and partner charging station telemetry, slot availability metrics, a multi-tier routing engine (Google Routes API v2, OSRM Table Service, and Haversine), OTP authentication, and predictive charging completion forecasts.

### Architecture Summary
The application adheres to clean MVVM and Unidirectional Data Flow (UDF) architectural principles:
- **UI Layer**: Jetpack Compose declarative UI with Material Design 3 components (`StationCard`, `StationDetailModal`, `RoutingSettingsModal`, `WattageFilterChipsRow`).
- **Presentation Layer**: `FavoritesViewModel` and `NearbyViewModel` managing UI state via `StateFlow` and one-shot events via buffered `Channel`.
- **Domain Layer**: Pure business logic modules for distance calculations (`DistanceCalculator`), station filtering and road sorting (`NearbyStationFilter`), and domain models (`StationForecast`, `WattageOption`).
- **Data Layer**: Clean repository pattern (`EvcsRepository`) coordinating network clients (`EvcsApiClient`, `GoogleRoutesClient`, `OsrmRoutingClient`), cryptographic HMAC signing (`EvcsHmacSigner`), HTML parsing (`StationForecastParser`), in-memory caching (`ForecastCache`), and persistent storage (`EncryptedSharedPrefsStorage`).

### Issue Count Summary
- **Total Number of Issues**: 12
- **Critical Issues**: 3
- **High Issues**: 5
- **Medium Issues**: 3
- **Low Issues**: 1

### Overall Reliability Assessment
While the architecture demonstrates strong separation of concerns and thorough unit test scaffolding, the runtime reliability is severely compromised by several high-impact logical defects:
1. **Routing and ETA Inversion**: Haversine fallback assigns `durationSeconds = 0L`, causing distant fallback stations to falsely appear at the top of the favorites list as "0 phút" travel time ahead of real nearby stations.
2. **Cancellation Exception Swallowing & Cooldown Poisoning**: Unhandled `CancellationException` in coroutines records false 60-second network failures in `ForecastCache`, locking out stations from forecast enrichment when users refresh or switch tabs.
3. **OkHttp Connection Pooling Leaks**: HTTP response bodies on non-2xx responses are left unclosed across multiple API clients, exhausting socket connections and triggering premature network timeouts.
4. **URL Construction Defect**: Station URL building fails for VinFast stations prefixed with "Trạm sạc", generating invalid URLs with duplicate prefixes (`tram-sac-tram-sac-...-c.ID.html`) and 404 errors.
5. **Shared State Desynchronization**: Two-way synchronization in `FavoritesViewModel` inadvertently purges driving metrics and forecasts, while settings changes on `NearbyScreen` fail to notify `NearbyViewModel`.

---

## 2. Architecture Overview

### UI Layer
The UI layer is composed entirely of Jetpack Compose components:
- `FavoritesScreen`: Renders the user's saved favorite stations with pull-to-refresh, empty states, and action headers.
- `NearbyScreen`: Provides an on-demand GPS scanning interface, sticky wattage filter chips, and a top 10 nearest station display.
- `LoginScreen`: Email input and 6-digit OTP verification interface.
- `StationCard`: Main list item presenting station status badges, journey badges (ETA/traffic), forecast capsules, power chips, and action buttons.
- `StationDetailModal`: Embedded bottom sheet hosting an Android `WebView` to display official EVCS station detail web views.
- `RoutingSettingsModal` & `GoogleApiKeyGuideModal`: BYOK Google API Key management and step-by-step guidance.

### Presentation Layer
- `FavoritesViewModel`: Exposes `FavoritesUiState` (`LoggedOut`, `RequestingOtp`, `VerifyingOtp`, `Loading`, `Success`, `Error`). Manages the 2-step hybrid routing pipeline and forecast enrichment for top favorite stations.
- `NearbyViewModel`: Exposes `NearbyUiState` and `NearbyUiEvent` (for toasts, login guards, permission prompts). Manages on-demand location acquisition, client-side filtering, and top 10 routing.

### Domain Layer
- `DistanceCalculator`: Great-circle Haversine distance computations, geographic clustering (15 km radius), and human-friendly distance formatting.
- `NearbyStationFilter`: Pure filtering logic for wattage options, active depot statuses, and road distance sorting.
- `LocationService`: Wrapper around Google Play Services `FusedLocationProviderClient`.

### Data Layer
- `EvcsRepository`: Central coordinator for favorites management, cluster-based search queries, coordinate caching, and forecast enrichment.
- `ForecastCache`: In-memory thread-safe cache with 3-minute TTL and 1-minute failure cooldown.
- `MultiTierRoutingCoordinator`: Arbitrates route calculations across Tier 1 (Google Routes v2), Tier 2 (OSRM Table Service), and Tier 3 (Haversine).
- `EvcsApiClient` & `AuthEngine`: OkHttp network communication with EVCS backend endpoints (`/favorite.html`, `/search`, `/reward.html`).
- `StationForecastParser`: SSR HTML regex parser extracting vehicle completion counts and power session data.

### Main Data Flows
1. **Nearby Scan Flow**: User Tap -> `NearbyViewModel.scanNearbyStations()` -> `LocationService.getFreshLocation()` -> `EvcsRepository.searchNearbyVinFast()` -> `NearbyStationFilter.filterStations()` -> `MultiTierRoutingCoordinator.calculateRoutes()` -> `NearbyStationFilter.sortByDrivingDistance()` -> `EvcsRepository.enrichStationsWithForecast()` -> `_uiState.update()`.
2. **Favorites Fetch Flow**: App Startup / Login -> `FavoritesViewModel.fetchFavorites()` -> `EvcsRepository.getFavorites()` -> Haversine Sort (Step 1) -> `MultiTierRoutingCoordinator.calculateRoutes()` (Step 2) -> ETA Sort -> `EvcsRepository.enrichStationsWithForecast()` -> UI State Update.

### Async Boundaries & Shared Mutable State
- **Async Boundaries**: `Dispatchers.Main` for UI/ViewModel state emissions; `Dispatchers.IO` for OkHttp networking, JSON decoding, and coordinate persistence.
- **Shared Mutable State**:
  - `ForecastCache`: Concurrent hash maps shared across ViewModel enrichment jobs.
  - `EvcsRepository.coordinateCache`: In-memory map of station coordinates backed by persistent JSON.
  - `EvcsRepository._favoritesState` and `_favoriteIdsState`: Shared reactive flows observed by both ViewModels.
  - SharedPreferences file `"evcs_secure_session"`: Concurrently accessed by multiple `EncryptedSharedPrefsStorage` instances.

---

## 3. Critical and High Severity Issues

### ANDROID-LOGIC-001: Haversine Routing Fallback Inverts ETA Sort Order and Displays Zero-Minute Travel Time
- **Severity**: Critical
- **Confidence**: High
- **Category**: routing / viewmodel
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt`
  - `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`
  - `app/src/main/java/com/evcs/favorites/data/routing/DrivingMetrics.kt`
- **Affected Symbols**:
  - `MultiTierRoutingCoordinator.computeHaversine` (line 177)
  - `FavoritesViewModel.executeRoutingPipeline` (line 368)
  - `DrivingMetrics.formattedDuration` (line 83)
- **Execution Timeline**:
  - T0: User opens Favorites screen with Station A (500m away, OSRM duration = 120s / 2 mins) and Station B (50km away, unreachable on OSRM network, fallback to Haversine).
  - T1: `MultiTierRoutingCoordinator.computeHaversine` generates `DrivingMetrics(distanceMeters = 50000L, durationSeconds = 0L, engineUsed = HAVERSINE)` for Station B.
  - T2: `FavoritesViewModel.executeRoutingPipeline` sorts stations using comparator: `compareBy<Station> { it.drivingMetrics?.durationSeconds ?: Long.MAX_VALUE }`.
  - T3: Station B has `durationSeconds = 0L`, which is less than Station A's 120s. Station B is sorted to index 0.
  - T4: The UI displays Station B at the top with journey badge "⚡ 0 phút • 50.0 km • Đường thẳng", misleading the driver that a 50km distant station is 0 minutes away.
- **Root Cause**:
  `computeHaversine` initializes `durationSeconds = 0L` because straight-line calculations do not compute road duration. In `FavoritesViewModel`, the primary sort comparator strictly checks `durationSeconds`. Because `0L < 120L`, any station falling back to Haversine automatically beats stations with valid driving times.
- **Actual Behavior**: Fallback stations with zero calculated duration sort to the top of the favorites list.
- **Expected Behavior**: Stations without valid driving road duration should not sort ahead of stations with real travel times; they should either use an estimated speed heuristic (e.g. 30 km/h) or fall back to Haversine distance ranking.
- **Impact**: Materially incorrect sorting and navigation advice; drivers may navigate towards a distant station believing it is immediately reachable.
- **Reproduction Steps**:
  1. Add a station 30km away and a station 1km away to favorites.
  2. Set routing mode to `AUTO` and configure custom OSRM server to reject the distant station (or choose `HAVERSINE_ONLY`).
  3. Observe that the distant station is ranked #1 with "0 phút".
- **Minimal Fix Strategy**:
  In `FavoritesViewModel.executeRoutingPipeline`, separate stations with valid road durations (`durationSeconds > 0L && engineUsed != HAVERSINE`) from fallback stations, or calculate an estimated duration for Haversine (e.g. `(distanceMeters / (30.0 * 1000.0 / 3600.0)).toLong()`).
- **Recommended Architectural Fix**:
  Define `durationSeconds` in `DrivingMetrics` as nullable (`Long?`), where `null` explicitly denotes uncalculated duration. Update comparators to place `null` durations last (`compareBy(nullsLast())`).
- **Regression Test Recommendation**:
  Create a unit test in `FavoritesRoutingViewModelTest` with mixed OSRM and Haversine stations, asserting that stations with valid OSRM durations precede Haversine stations unless road distance is overwhelmingly closer.

---

### ANDROID-LOGIC-002: CancellationException Swallowed in Repository and Client Layers Causes 60-Second Failure Cooldown Poisoning
- **Severity**: Critical
- **Confidence**: High
- **Category**: coroutine / cache
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`
  - `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt`
  - `app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt`
  - `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt`
- **Affected Symbols**:
  - `EvcsRepository.fetchStationForecast` (line 604)
  - `MultiTierRoutingCoordinator.calculateRoutes` (line 66)
- **Execution Timeline**:
  - T0: User opens Favorites screen. Background job `forecastJob` starts fetching forecasts.
  - T1: User pulls to refresh or moves location by > 200m. `FavoritesViewModel` calls `forecastJob?.cancel()`.
  - T2: The active coroutine in `fetchStationForecast` is cancelled, throwing Kotlin's `CancellationException`.
  - T3: `catch (e: Exception)` catches `CancellationException`. It invokes `forecastCache.recordFailure(station.id)` and returns `Result.success(null)`.
  - T4: The new refresh coroutine starts and calls `fetchStationForecast`. Because `isInCooldown(station.id)` is now `true` for 60 seconds, the cache immediately returns `null` without attempting network fetching.
- **Root Cause**:
  `catch (e: Exception)` and `catch (e: Throwable)` catch `CancellationException`. Instead of propagating cancellation to maintain structured concurrency, the repository treats cancellation as a remote server failure, recording an entry in `failureCooldowns`.
- **Actual Behavior**: Coroutine cancellation poisons the cache with a 60-second cooldown, blocking legitimate refresh requests.
- **Expected Behavior**: `CancellationException` must be re-thrown immediately without recording a failure.
- **Impact**: Refreshing the screen or rapid tab switching causes station forecasts to disappear and stay unavailable for 60 seconds.
- **Reproduction Steps**:
  1. Open FavoritesScreen and wait for forecast capsules to appear.
  2. Pull down to refresh immediately.
  3. Observe that forecast capsules disappear and fail to reload for at least 1 minute.
- **Minimal Fix Strategy**:
  In `EvcsRepository.fetchStationForecast`, add `if (e is kotlinx.coroutines.CancellationException) throw e` before `forecastCache.recordFailure`.
- **Recommended Architectural Fix**:
  Refactor all coroutine catch blocks across `EvcsRepository`, `MultiTierRoutingCoordinator`, and routing clients to explicitly exclude `CancellationException` or use `runCatching` with re-throwing.
- **Regression Test Recommendation**:
  Write a coroutine test launching `fetchStationForecast`, cancelling the job midway, and asserting that `forecastCache.isInCooldown(station.id)` remains `false`.

---

### ANDROID-LOGIC-003: Unclosed OkHttp Responses on HTTP Errors Cause Connection and Socket Leaks
- **Severity**: Critical
- **Confidence**: High
- **Category**: network
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt`
  - `app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt`
- **Affected Symbols**:
  - `EvcsApiClient.fetchFavorites` (line 183)
  - `EvcsApiClient.saveFavorites` (line 239)
  - `EvcsApiClient.searchStations` (line 286)
  - `EvcsApiClient.fetchStationHtml` (line 335)
  - `AuthEngine.fetchCsrfToken` (line 106)
  - `AuthEngine.sendOtp` (line 178)
  - `AuthEngine.verifyOtp` (line 240)
- **Execution Timeline**:
  - T0: EVCS backend responds with HTTP 429, 500, or 403 (e.g. Cloudflare rate limiting or server reboot).
  - T1: `EvcsApiClient.fetchStationHtml` executes `val response = client.newCall(...).execute()`.
  - T2: `if (!response.isSuccessful)` condition triggers.
  - T3: Method returns `Result.failure(...)` without calling `response.close()`.
  - T4: OkHttp holds the TCP socket open in the connection pool.
  - T5: After multiple failed requests (such as forecast retries across multiple stations), the connection pool saturates, leading to socket exhaustion, thread stalls, and app-wide network lockup.
- **Root Cause**:
  Direct invocation of `client.newCall().execute()` without enclosing the returned `Response` in a Kotlin `.use { }` block or ensuring `response.close()` in a `finally` block.
- **Actual Behavior**: Unsuccessful HTTP responses leave TCP sockets and response body streams open.
- **Expected Behavior**: All HTTP responses (successful or failed) must close response bodies deterministically.
- **Impact**: App experiences silent network lockouts, socket timeouts, and high memory retention during transient backend instability.
- **Reproduction Steps**:
  1. Enqueue MockWebServer to return HTTP 500 for 10 consecutive requests.
  2. Execute 10 calls to `fetchStationHtml`.
  3. Inspect OkHttp connection pool idle count and open file descriptors.
- **Minimal Fix Strategy**:
  Wrap all `client.newCall(request).execute()` invocations with `.use { response -> ... }`.
- **Recommended Architectural Fix**:
  Enforce response consumption via an internal OkHttp helper function that guarantees `use` scoping across all network calls.
- **Regression Test Recommendation**:
  Create an integration test with MockWebServer asserting that `mockServer.takeRequest()` connections are recycled and closed after HTTP 500 responses.

---

### ANDROID-LOGIC-004: StationUrlBuilder Prepends Duplicate Prefix and Appends Partner Syntax for "Trạm sạc" Prefixed Stations
- **Severity**: High
- **Confidence**: High
- **Category**: parser / network
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt`
  - `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt`
  - `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt`
- **Affected Symbols**:
  - `StationUrlBuilder.buildStationDetailUrl` (line 45)
  - `StationUrlBuilder.slugify` (line 28)
  - `StationNameSanitizer.sanitize` (line 19)
- **Execution Timeline**:
  - T0: Backend returns station with name `"Trạm sạc VinFast Mega Mall Smart City"` and ID `"HN005"`.
  - T1: `StationNameSanitizer.sanitize` is called. It only strips distance prefixes, leaving `"Trạm sạc VinFast Mega Mall Smart City"`.
  - T2: `StationUrlBuilder.slugify` produces `"tram-sac-vinfast-mega-mall-smart-city"`.
  - T3: `slug.startsWith("vinfast")` is evaluated. It returns `false` because the slug starts with `"tram-sac"`.
  - T4: Function branches to partner scheme: `"$baseUrl/tram-sac-$slug-c.$encodedId.html"`.
  - T5: Resulting URL is `https://evcs.vn/tram-sac-tram-sac-vinfast-mega-mall-smart-city-c.HN005.html`.
  - T6: GET request to this URL returns HTTP 404. Live forecast and station detail view cannot load.
- **Root Cause**:
  `slug.startsWith("vinfast")` assumes the sanitized name begins directly with `"VinFast"`. However, real stations frequently start with `"Trạm sạc VinFast"`, producing a `"tram-sac-"` slug prefix that fails the condition.
- **Actual Behavior**: URLs for VinFast stations starting with "Trạm sạc" are malformed with duplicate prefixes and invalid partner syntax.
- **Expected Behavior**: The builder should detect "vinfast" anywhere in the slug or strip `"tram-sac-"` before checking.
- **Impact**: HTTP 404 errors for real stations; forecasts and web detail views fail to load.
- **Reproduction Steps**:
  1. Call `StationUrlBuilder.buildStationDetailUrl("Trạm sạc VinFast Times City", "HN001")`.
  2. Inspect output: `https://evcs.vn/tram-sac-tram-sac-vinfast-times-city-c.HN001.html`.
- **Minimal Fix Strategy**:
  In `StationUrlBuilder.buildStationDetailUrl`:
  `val normalizedSlug = slug.removePrefix("tram-sac-")`
  `return if (normalizedSlug.startsWith("vinfast")) "$baseUrl/tram-sac-$normalizedSlug-${cleanLocId.lowercase()}.html" ...`
- **Recommended Architectural Fix**:
  Update `StationNameSanitizer.sanitize` to strip common Vietnamese station prefixes (`"Trạm sạc"`, `"Trạm sạc xe điện"`, `"Trụ sạc"`) in addition to distance markers.
- **Regression Test Recommendation**:
  Add unit tests in `StationNameDisplayAndSanitizationTest` testing stations named `"Trạm sạc VinFast ..."` and asserting canonical single-prefix URL generation.

---

### ANDROID-LOGIC-005: Two-Way Favorites Synchronization in FavoritesViewModel Wipes Driving Metrics and Forecasts
- **Severity**: High
- **Confidence**: High
- **Category**: viewmodel / compose
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`
- **Affected Symbols**:
  - `FavoritesViewModel.init` (lines 118-135)
- **Execution Timeline**:
  - T0: User is viewing FavoritesScreen with 5 stations populated with driving metrics and forecasts.
  - T1: User switches to Nearby tab and toggles a favorite station.
  - T2: `EvcsRepository.favoritesState` emits the updated station list to collectors.
  - T3: `FavoritesViewModel` collector checks `currentIds != repoIds`.
  - T4: It executes `_uiState.value = currentState.copy(stations = updated)` using `repoStations`.
  - T5: Because `repoStations` in the repository do not store UI driving metrics or forecasts, all ETA pills, traffic badges, and forecast capsules vanish from the Favorites screen.
- **Root Cause**:
  The repository's `favoritesState` flow holds domain stations without transient ViewModel enrichments. When the ViewModel syncs with the repository, it replaces existing enriched stations instead of merging them or re-triggering the enrichment pipeline.
- **Actual Behavior**: Adding or removing a favorite in Nearby tab clears all driving and forecast data on Favorites tab.
- **Expected Behavior**: Existing stations should retain their enriched metrics, and new stations should trigger routing and forecast enrichment.
- **Impact**: Jarring UI flicker and lost data for the user until an explicit manual refresh is executed.
- **Reproduction Steps**:
  1. Launch app on Favorites tab and observe green/cyan ETA pills and forecasts.
  2. Switch to Nearby tab and tap heart icon on any station.
  3. Switch back to Favorites tab; all ETA pills and forecast capsules have vanished.
- **Minimal Fix Strategy**:
  In `FavoritesViewModel.init`, preserve existing `drivingMetrics` and `forecast` when merging `repoStations` with `currentState.stations`, and trigger `executeRoutingPipeline` if coordinates exist.
- **Recommended Architectural Fix**:
  Maintain a dedicated UI enrichment mapper that caches metrics by station ID across list mutations.
- **Regression Test Recommendation**:
  Write a ViewModel test emitting a new station to `repository.favoritesState` while `FavoritesViewModel` is in `Success` state, asserting that existing stations retain their `drivingMetrics`.

---

### ANDROID-LOGIC-006: Unhandled Cloud Sync Failures Leave Persistent and In-Memory Favorites Inconsistent
- **Severity**: High
- **Confidence**: High
- **Category**: persistence / viewmodel
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`
  - `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`
- **Affected Symbols**:
  - `EvcsRepository.addFavoriteStation` (lines 409-433)
  - `EvcsRepository.removeFavoriteStation` (lines 439-458)
  - `FavoritesViewModel.removeFavorite` (lines 523-537)
- **Execution Timeline**:
  - T0: User is offline or EVCS session cookie has expired.
  - T1: User removes a favorite station in `FavoritesViewModel`.
  - T2: `EvcsRepository.removeFavoriteStation` removes the station from memory and updates offline storage `KEY_OFFLINE_FAVORITES`.
  - T3: `apiClient.saveFavorites` returns `Result.failure(IOException)`.
  - T4: The failure is ignored; local state remains deleted while cloud still has the station.
  - T5: On subsequent cloud refresh or web portal login, the station reappears or remains in an inconsistent state.
- **Root Cause**:
  Optimistic local updates to `_favoritesState` and `saveCachedFavorites` are performed without rollback logic when remote cloud synchronization fails.
- **Actual Behavior**: Failed cloud syncs leave local cache permanently out-of-sync with the server.
- **Expected Behavior**: Local state should either roll back on network failure or queue a pending synchronization sync.
- **Impact**: User favorite modifications are silently lost upon next full synchronization.
- **Reproduction Steps**:
  1. Disconnect internet or mock `saveFavorites` to fail.
  2. Remove a favorite station.
  3. Reconnect internet and call `fetchFavorites()`. The station reappears.
- **Minimal Fix Strategy**:
  Capture previous state in `addFavoriteStation` and `removeFavoriteStation`; restore `_favoritesState` and `saveCachedFavorites` if `syncResult.isFailure`.
- **Recommended Architectural Fix**:
  Implement a local-first sync queue (e.g. WorkManager or pending sync flags) that retries cloud synchronization with exponential backoff.
- **Regression Test Recommendation**:
  Write a repository unit test simulating `apiClient.saveFavorites` failure and verifying that state rolls back to the original list.

---

### ANDROID-LOGIC-007: Settings Changes on NearbyScreen Do Not Re-evaluate Nearby Routing or Notify NearbyViewModel
- **Severity**: High
- **Confidence**: High
- **Category**: viewmodel / routing
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/MainActivity.kt`
  - `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`
- **Affected Symbols**:
  - `MainActivity.FavoritesApp` (line 227)
  - `NearbyViewModel`
- **Execution Timeline**:
  - T0: User is on Nearby tab viewing stations with OSRM routes.
  - T1: User opens Settings modal from NearbyScreen, enters a valid Google API key, and clicks "Lưu cài đặt".
  - T2: `MainActivity` forwards the callback to `favoritesViewModel.updateRoutingSettings(it)`.
  - T3: `FavoritesViewModel` saves settings to `routingPreferencesManager` and recalculates favorites routing.
  - T4: `NearbyViewModel` does not observe `routingPreferencesManager.settings` and receives no notification.
  - T5: NearbyScreen continues to display OSRM routes without live traffic until a manual scan is triggered.
- **Root Cause**:
  `NearbyViewModel` does not collect `routingPreferencesManager.settings` and does not provide an updater hook to recalculate its visible top 10 routing metrics.
- **Actual Behavior**: Changing routing settings in the Nearby tab does not apply to the Nearby screen.
- **Expected Behavior**: Updating settings should immediately refresh routing calculations on both screens.
- **Impact**: Driver believes Google Routes with live traffic is active, but NearbyScreen continues using OSRM or Haversine.
- **Reproduction Steps**:
  1. Open Nearby tab and scan stations.
  2. Open settings and switch mode from OSRM to GOOGLE_ONLY with a valid key.
  3. Close modal; stations still show "Đường bộ" (OSRM) instead of Google traffic.
- **Minimal Fix Strategy**:
  In `NearbyViewModel.init`, launch a coroutine to collect `prefsManager.settings` and trigger `executeFilterAndRoutingPipeline` if `hasSearched` is true.
- **Recommended Architectural Fix**:
  Centralize routing preferences observation into a shared Domain UseCase or state stream shared by all presentation models.
- **Regression Test Recommendation**:
  Write a test in `NearbyRoutingViewModelTest` updating settings in `RoutingPreferencesManager` and asserting that `NearbyViewModel.uiState` reflects the new routing engine.

---

### ANDROID-LOGIC-008: Multiple Concurrent EncryptedSharedPreferences Instances for "evcs_secure_session"
- **Severity**: High
- **Confidence**: High
- **Category**: persistence
- **Affected Files**:
  - `app/src/main/java/com/evcs/favorites/MainActivity.kt`
  - `app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt`
  - `app/src/main/java/com/evcs/favorites/data/preferences/NearbyFilterPreferences.kt`
  - `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt`
  - `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`
- **Affected Symbols**:
  - `MainActivity.sessionManager` (line 65)
  - `MainActivity.repository` (line 70)
  - `MainActivity.routingPreferencesManager` (line 76)
  - `MainActivity.nearbyFilterPreferences` (line 77)
- **Execution Timeline**:
  - T0: Application boots up in `MainActivity`.
  - T1: Four separate `EncryptedSharedPrefsStorage` instances are constructed, each calling `EncryptedSharedPreferences.create(context, "evcs_secure_session", ...)`.
  - T2: `NearbyFilterPreferences` writes `nearby_selected_wattages` on Dispatchers.Main.
  - T3: Concurrently on Dispatchers.IO, `EvcsRepository` writes `evcs_station_coordinates_cache`.
  - T4: In-memory cache desynchronization occurs between instances, causing key overwrite or Keystore access contention.
- **Root Cause**:
  Lack of a singleton or dependency injection provider for `SessionStorage`. Each component creates an independent `EncryptedSharedPreferences` wrapper on the same underlying XML file.
- **Actual Behavior**: Multiple instances can overwrite each other's keys and experience stale in-memory reads.
- **Expected Behavior**: A single shared `SessionStorage` instance must be shared across all components.
- **Impact**: Loss of saved API keys, lost wattage filter preferences, or corrupted offline coordinate caches.
- **Reproduction Steps**:
  1. Save wattage filters from `NearbyFilterPreferences`.
  2. Concurrently save routing settings from `RoutingPreferencesManager`.
  3. Kill and restart the process; verify whether both keys survived.
- **Minimal Fix Strategy**:
  In `MainActivity`, instantiate a single `val sharedSessionStorage = EncryptedSharedPrefsStorage(applicationContext)` and pass it to all managers.
- **Recommended Architectural Fix**:
  Use Hilt or a simple dependency injection graph to provide a `@Singleton SessionStorage`.
- **Regression Test Recommendation**:
  Write an instrumentation test performing concurrent writes to `NearbyFilterPreferences` and `RoutingPreferencesManager` on the same storage instance.

---

## 4. Complete Issue List

| Issue ID | Severity | Confidence | Category | Title | Affected Files |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ANDROID-LOGIC-001** | **Critical** | High | routing / viewmodel | Haversine Routing Fallback Inverts ETA Sort Order and Displays Zero-Minute Travel Time | `MultiTierRoutingCoordinator.kt`, `FavoritesViewModel.kt`, `DrivingMetrics.kt` |
| **ANDROID-LOGIC-002** | **Critical** | High | coroutine / cache | CancellationException Swallowed in Repository and Client Layers Causes 60-Second Failure Cooldown Poisoning | `EvcsRepository.kt`, `MultiTierRoutingCoordinator.kt` |
| **ANDROID-LOGIC-003** | **Critical** | High | network | Unclosed OkHttp Responses on HTTP Errors Cause Connection and Socket Leaks | `EvcsApiClient.kt`, `AuthEngine.kt` |
| **ANDROID-LOGIC-004** | **High** | High | parser / network | StationUrlBuilder Prepends Duplicate Prefix and Appends Partner Syntax for "Trạm sạc" Prefixed Stations | `StationUrlBuilder.kt`, `StationNameSanitizer.kt`, `EvcsApiClient.kt` |
| **ANDROID-LOGIC-005** | **High** | High | viewmodel / compose | Two-Way Favorites Synchronization in FavoritesViewModel Wipes Driving Metrics and Forecasts | `FavoritesViewModel.kt` |
| **ANDROID-LOGIC-006** | **High** | High | persistence / viewmodel | Unhandled Cloud Sync Failures Leave Persistent and In-Memory Favorites Inconsistent | `EvcsRepository.kt`, `FavoritesViewModel.kt` |
| **ANDROID-LOGIC-007** | **High** | High | viewmodel / routing | Settings Changes on NearbyScreen Do Not Re-evaluate Nearby Routing or Notify NearbyViewModel | `MainActivity.kt`, `NearbyViewModel.kt` |
| **ANDROID-LOGIC-008** | **High** | High | persistence | Multiple Concurrent EncryptedSharedPreferences Instances for "evcs_secure_session" | `MainActivity.kt`, `SessionManager.kt`, `RoutingPreferencesManager.kt`, `NearbyFilterPreferences.kt`, `EvcsRepository.kt` |
| **ANDROID-LOGIC-009** | **Medium** | High | forecast / viewmodel | Regression / Out-of-Sync Contract: StationForecastViewModelPipelineTest Failing in Repository Pipeline | `FavoritesViewModel.kt`, `NearbyViewModel.kt`, `StationForecastViewModelPipelineTest.kt` |
| **ANDROID-LOGIC-010** | **Medium** | Medium | parser | StationForecast HTML Parser Lacks Station Validation and Regex Truncates Nested HTML | `StationForecastParser.kt` |
| **ANDROID-LOGIC-011** | **Medium** | High | cache | ForecastCache Lacks Stampede Protection and Stale Request Overwrite Protection | `ForecastCache.kt`, `EvcsRepository.kt` |
| **ANDROID-LOGIC-012** | **Low** | High | compose / lifecycle | StationDetailModal AndroidView Leaks WebView Context on Dismiss | `StationDetailModal.kt` |

---

## 5. Race Condition Analysis

### RACE-001: Stale Route Overwrite
- **Risk Level**: High
- **Analysis**:
  In `FavoritesViewModel.executeRoutingPipeline`, when location changes, `routingJob?.cancel()` is called before launching a new calculation. However, in `NearbyViewModel.scanNearbyStations`, if the user rapidly toggles wattage filter chips, `toggleWattageFilter` launches `executeFilterAndRoutingPipeline` without tracking the previous job if `scanNearbyStations` was already active.
  Furthermore, `OkHttp` calls in `GoogleRoutesClient` and `OsrmRoutingClient` run synchronously inside `withContext(Dispatchers.IO)`. When a coroutine job is cancelled, the underlying OkHttp thread continues executing until the network socket completes. While Kotlin coroutines ignore the result after cancellation, socket and CPU resources are wasted.

### RACE-002: Cache Stampede in Forecast Loading
- **Risk Level**: High
- **Analysis**:
  In `EvcsRepository.fetchStationForecast`, multiple callers requesting the same station ID concurrently upon cache expiration both detect `forecastCache.get(station.id) == null`. While `forecastSemaphore(3)` limits total concurrent network operations to 3, it does not deduplicate requests for the same station. Both coroutines acquire permits sequentially and make duplicate HTTP requests to `evcs.vn` for the same station.

### RACE-003 & RACE-007: Stale Cache Overwrite
- **Risk Level**: High
- **Analysis**:
  If Request A for station X starts at T0 (delayed by cellular network jitter) and Request B for station X starts at T1 with `forceRefresh = true`, Request B finishes first at T2 and writes fresh telemetry into `ForecastCache`. Request A finishes later at T3 and calls `forecastCache.put(station.id, forecast)`, overwriting the newer forecast with older data. There is no versioning, sequence counter, or timestamp validation on `put`.

### RACE-005: Location-Route Mismatch
- **Risk Level**: Medium
- **Analysis**:
  In `FavoritesViewModel.updateUserLocation`, if the user moves while `executeRoutingPipeline` is running, the pipeline calculates routes based on `userLat` and `userLon` captured at job launch. If a second location arrives with < 200m displacement, it launches a second pipeline. If the second pipeline finishes before the first, the first pipeline's completion block can overwrite `_uiState.value.stations` with routes computed from the older origin.

### RACE-006: Concurrent UiState Overwrite
- **Risk Level**: Medium
- **Analysis**:
  In `FavoritesViewModel.enrichTopStationsWithForecast`:
  ```kotlin
  onStationUpdated = { updatedStation ->
      launch(dispatcher) {
          val current = _uiState.value
          if (current is FavoritesUiState.Success) {
              _uiState.value = current.copy(...)
          }
      }
  }
  ```
  `_uiState.value = current.copy(...)` is a classic read-modify-write race. If the user simultaneously pulls to refresh or location updates, the read `current` can be stale, silently undoing the other coroutine's state mutation. `MutableStateFlow.update { }` should be used consistently instead.

---

## 6. Cache Analysis

### ForecastCache Analysis
- **Thread Safety**: Backed by `ConcurrentHashMap<String, ForecastCacheEntry>`. Key operations (`get`, `put`, `invalidate`) are thread-safe at map level.
- **TTL Correctness**: 3 minutes (`180_000L`). Evaluated against `System.currentTimeMillis()`. If the system clock changes (e.g. automatic network time sync or manual change), entries may expire prematurely or persist indefinitely. Monotonic `SystemClock.elapsedRealtime()` should be used.
- **Cache Eviction**: Expired entries are only removed lazily when `get()` is called for that specific key. If hundreds of stations are scanned across different regions, expired entries linger in memory indefinitely.
- **Failed Refresh Behavior**: When a refresh fails, `forecastCache.recordFailure` sets a 1-minute cooldown. However, this cooldown is also triggered by job cancellation (Issue `ANDROID-LOGIC-002`).

### In-Flight Deduplication & Stampede Protection
There is no in-flight request map or `Mutex`/`Deferred` table in `ForecastCache` or `EvcsRepository`. Duplicate requests for identical stations are not merged.

---

## 7. Routing Engine Analysis

### Engine Responsibilities
- **Tier 1 (Google Routes API v2)**: High-precision driving metrics with live traffic awareness (`duration`, `staticDuration`, `condition`). Requires user BYOK API key.
- **Tier 2 (OSRM Table Service)**: Open-source road-network matrix calculation. Fast, free, zero-key, but lacks real-time traffic congestion.
- **Tier 3 (Haversine)**: Offline 0ms straight-line geometric distance. Zero latency, used as fallback baseline.

### Fallback Arbitration & Conflicts
- In `MultiTierRoutingCoordinator.executeAuto`:
  1. Tries Google Routes if API key is not blank.
  2. If Google fails or key is blank, falls back to OSRM if `autoFallbackEnabled` is true.
  3. If OSRM fails, falls back to Haversine.
- **Flaw**: As analyzed in `ANDROID-LOGIC-001`, Haversine returns `durationSeconds = 0L`. When fallback occurs alongside valid OSRM routes, the resulting list mixes real durations with zero durations, breaking list sorting.

---

## 8. Telemetry Parser and Forecast Analysis

### HTML Input Validation & Assumptions
- `StationForecastParser` assumes that the station detail page contains a container `#stationTicker` or inline script with attribute `data-charging-sessions`.
- **Regex Fragility**: `TICKER_WRAPPER_REGEX` fails on nested `<div>` structures (Issue `ANDROID-LOGIC-010`).
- **Locale & Decimal Separators**: `textMatch.groupValues[2].toDoubleOrNull()` parses wattage kW. In Vietnamese locale, decimals may use commas (e.g. `22,5 kW`), which `toDoubleOrNull()` fails to parse, defaulting to `0.0`.
- **Session Grouping Logic**: `getGroupedPowerForecasts()` correctly groups sessions by wattage and sorts them. However, `formatSingleSummary()` mixes `wattageKw` and `group?.kw`, leading to potential discrepancies between multi-line and single-line presentations.

---

## 9. Coroutine and Flow Analysis

### Structured Concurrency & Ownership
- Coroutines are owned by `viewModelScope` in `FavoritesViewModel` and `NearbyViewModel`.
- **Defect**: Swallowing `CancellationException` in repository catch blocks violates structured concurrency contracts.
- **Dispatcher Usage**: Heavy regex parsing in `StationForecastParser` runs inside `withContext(ioDispatcher)`. While this avoids blocking `Dispatchers.Main`, CPU-intensive parsing should ideally use `Dispatchers.Default`.

### Flow & Channel Mechanics
- `NearbyViewModel.events` is backed by `Channel<NearbyUiEvent>(Channel.BUFFERED)` exposed as `receiveAsFlow()`.
- If collected by `LaunchedEffect(Unit)` in `NearbyScreen`, events emitted while the UI is not in the foreground or during screen recreation can be dropped or consumed out of order.

---

## 10. Compose and UI State Analysis

### Recomposition & State Ownership
- `StationCard` cleanly separates visual badge resolution functions (`formatJourneyBadge`, `resolveStatusBadge`, `resolveForecastCapsuleData`) from composables, ensuring previewability.
- Stable keys (`key = { it.id }`) are properly provided to `LazyColumn` items in both `FavoritesScreen` and `NearbyScreen`.
- `StationDetailModal` does not release its `WebView` instance, causing memory leaks across repeated bottom sheet interactions (Issue `ANDROID-LOGIC-012`).

---

## 11. Persistence and Lifecycle Analysis

### Storage Conflicts & Lifecycle
- Four components independently instantiate `EncryptedSharedPrefsStorage` referencing `"evcs_secure_session"`. This violates Android Security Best Practices and leads to concurrent write corruption (Issue `ANDROID-LOGIC-008`).
- Process death restoration: `FavoritesApp` stores `currentTab` in `rememberSaveable`, preserving the active tab across process death. However, `NearbyUiState` is in-memory only and is reset upon process restoration.

---

## 12. Recommended Fix Priority

### Immediate Fixes (P0)
1. **Fix `ANDROID-LOGIC-002`**: Re-throw `CancellationException` in `EvcsRepository.fetchStationForecast` and remove erroneous failure cooldown recording.
2. **Fix `ANDROID-LOGIC-001`**: Fix Haversine fallback duration sorting in `FavoritesViewModel` to prevent zero-duration stations from jumping to the top of the list.
3. **Fix `ANDROID-LOGIC-003`**: Wrap all OkHttp calls in `EvcsApiClient` and `AuthEngine` with `.use { }` to prevent socket leaks.
4. **Fix `ANDROID-LOGIC-004`**: Update `StationUrlBuilder.buildStationDetailUrl` to strip `"tram-sac-"` prefixes before checking for `"vinfast"`.

### Short-Term Fixes (P1)
5. **Fix `ANDROID-LOGIC-005`**: Preserve existing driving metrics and forecasts in `FavoritesViewModel` during repository synchronization.
6. **Fix `ANDROID-LOGIC-007`**: Connect `NearbyViewModel` to observe `RoutingPreferencesManager.settings`.
7. **Fix `ANDROID-LOGIC-008`**: Create a single shared `SessionStorage` singleton in `MainActivity`.
8. **Fix `ANDROID-LOGIC-009`**: Align test expectations and domain filtering regarding forecast enrichment on available stations.

### Architectural Improvements (P2)
9. Add in-flight request deduplication to `ForecastCache`.
10. Add `DisposableEffect` cleanup for `WebView` in `StationDetailModal`.
11. Implement persistent pending sync queue for cloud favorites mutations.

---

## 13. Regression Test Recommendations

1. **Routing Inversion Test**: Create `FavoritesRoutingEtaSortTest` verifying that a 10km Haversine station is not sorted ahead of a 500m OSRM station.
2. **Cancellation Cooldown Test**: Create `ForecastCancellationTest` asserting that cancelling a forecast enrichment job leaves `isInCooldown` false.
3. **Socket Leak Test**: Create `OkHttpLeakTest` with MockWebServer verifying socket reuse across 50 HTTP 500 responses.
4. **URL Slug Test**: Add unit tests for `"Trạm sạc VinFast ..."` station names in `StationNameDisplayAndSanitizationTest`.
5. **Two-Way Sync Preservation Test**: Test `FavoritesViewModel` receiving repository updates without losing driving metrics.

---

## 14. Negative Findings

1. **HMAC Signing Correctness**: `EvcsHmacSigner.sign` correctly replicates the SHA256 HMAC protocol, UTF-8 byte conversion, and lowercase hex formatting required by EVCS backend.
2. **Haversine Math**: `DistanceCalculator.calculateDistanceKm` implements the standard Haversine formula accurately with proper radian conversion and spherical Earth radius (6371.0 km).
3. **Map Navigation Intent Safety**: `MapNavigator.navigate` includes comprehensive `ActivityNotFoundException` fallback handling (Google Maps -> generic geo URI -> browser URL), preventing any unhandled intent crashes.
4. **Wattage Filter Logic**: `NearbyStationFilter.filterStations` properly evaluates active plugs, correctly matches wattage tiers, and ignores maintaining/out-of-service stations.
