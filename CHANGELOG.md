# Changelog - EV+ (Trạm Sạc EV)

All notable changes to this project will be documented in this file.

## [2026-09-03] - Rebranding to "EV+", Adaptive Launcher Icon, Project Cleanup & GitHub Publication

### Added
- **Android Adaptive Launcher Icon (API 26+)**:
  - Designed modern vector background (`ic_launcher_background.xml`) featuring deep obsidian base (`#071312`) with ambient emerald and cyan energy glows.
  - Designed high-contrast vector foreground (`ic_launcher_foreground.xml`) featuring high-voltage electric bolt in pure white, inner speed gradient, and glowing cyan `+` emblem.
  - Implemented `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` with full square, circle, and squircle mask adaptability across Android & OxygenOS launchers.
  - Added fallback legacy layer-list drawables in `res/drawable/`.
- **Project Structure Reorganization (`original_app_data/`)**:
  - Moved all reverse-engineering artifacts, decompiled code (JADX sources/resources, apktool smali), original APK packages (`.xapk`), HTML web templates, JS scripts, auth dumps, and Python test scripts into `original_app_data/`.
  - Created `original_app_data/README.md` documenting historical assets and reverse-engineering research.
  - Cleaned up root project directory to strictly follow Modern Android Development (MAD) standards.
- **Git & GitHub Publication**:
  - Created comprehensive `.gitignore` filtering Gradle caches, Android build outputs, local properties, IDE configs, and heavy original app binary dumps.
  - Initialized Git repository on `main` branch.
  - Force-pushed clean initial release to GitHub repository: `https://github.com/skul9x/EV-Plus.git`.
- **Documentation**:
  - Wrote comprehensive, professional `README.md` for **EV+** with full feature highlights, architecture table, directory layout, Gradle build guides, and ADB installation steps.

### Changed
- **Concise App Name**:
  - Renamed application display label from "EVCS Favorites" / "Trạm Sạc EV+" to concise **"EV+"** via `app/src/main/res/values/strings.xml`.
  - Updated `AndroidManifest.xml` to reference `@string/app_name` and `@mipmap/ic_launcher`.
  - Updated location permission rationale dialog in `MainActivity.kt` to reflect "EV+".
- **Hardware Deployment**:
  - Rebuilt `app-debug.apk` and installed to OnePlus 13R (`3B658D010BU00000`) with updated launcher icon and concise name.

### Added
- **Wattage Filter Persistence (`NearbyFilterPreferences`)**:
  - Implemented persistent storage layer `NearbyFilterPreferences` backed by `SessionStorage` (`EncryptedSharedPrefsStorage` on Android device, `InMemorySessionStorage` for unit tests).
  - Storage key `nearby_selected_wattages`: securely encodes selected `WattageOption` sets into comma-separated strings and decodes them safely with corrupted/unknown token protection.
  - Added unit test `NearbyFilterPersistenceTest.kt` verifying serialization/deserialization, ViewModel state initialization, filter toggling, filter clearing, and cross-session automatic filter restoration (100% PASS).
- **Bottom Navigation Tab Order Swapping & Discovery Start**:
  - Reordered `AppTab` enum entries: `AppTab.NEARBY` ("Quanh đây" with `LocationOn` icon) is now first/leftmost, and `AppTab.FAVORITES` ("Yêu thích" with `Favorite` icon) is second/rightmost.
  - Updated `MainActivity.kt` default start destination to `AppTab.NEARBY` allowing immediate station discovery for both guests and authenticated drivers without login gating.
  - Implemented guest-to-login navigation bridging when unauthenticated users attempt favorite actions on Nearby screen.
  - Added unit test `BottomNavigationTabReorderTest.kt` verifying enum order, default start tab, bidirectional transitions, and guest login routing contract (100% PASS).
- **Physical Device Deployment & UI Capture**:
  - Rebuilt debug APK (`app-debug.apk`) with all 3 phases applied.
  - Stream-installed APK to connected OnePlus 13R (`3B658D010BU00000`) via ADB MCP tool.
  - Captured on-device screenshot verifying clean station titles, multiline wrapping, active 20kW filter chip, straight-line distance badge, and swapped bottom navigation tabs.

## [2026-09-03] - Station Name Distance Prefix Sanitization & Multiline Full-Text Display

### Added
- Pure Kotlin utility `StationNameSanitizer` for detecting and stripping estimated distance prefixes (`5.4km » `, `9.1km » `, `500m » `, `12,5km - `, etc.) while preserving legitimate milestone names (e.g., `Km 12 Quốc lộ 1A`).
- Comprehensive unit test `StationNameDisplayAndSanitizationTest.kt` verifying sanitization regex, repository mappings, cloud persistence payloads, and canonical URL slug generations (100% PASS).

### Changed
- `EvcsRepository`: Integrated `StationNameSanitizer.sanitize(...)` into `toDomainStation`, `mergeToDomainStation`, and `toFavoriteStationRaw` preventing unsanitized prefixes from persisting or syncing back to EVCS cloud.
- `StationUrlBuilder`: Enforced name sanitization before slug generation, guaranteeing canonical VinFast detail URLs (`tram-sac-vinfast-...`) instead of partner slug fallbacks.
- `StationCard`: Changed title row to `Alignment.Top` and enabled multiline wrapping (`maxLines = 3`, `overflow = TextOverflow.Ellipsis`) to prevent station title clipping on 2-3 line Vietnamese addresses.
- `StationDetailModal`: Set `maxLines = 3` on station title header for consistent multiline full-text display.

## [2026-09-03] - Nearby Charging Stations with Multi-Tier Routing & Bottom Navigation

### Added
- **Nearby Charging Stations Discovery**:
  - Implemented `NearbyScreen` with on-demand GPS scan hero layout, phased loading indicators, and error handling.
  - Implemented `NearbyViewModel` managing state, location permissions, and Top 10 routing execution.
- **Multi-Power Wattage Filter Engine**:
  - Modeled 14 EV power tiers in `WattageOption` (3.5kW to 360kW) with descending power sorting.
  - Implemented pure client-side filter engine `NearbyStationFilter` enforcing strict port availability and OR wattage criteria.
  - Created `WattageFilterChipsRow` with interactive chip selection, checkmarks, and "Xóa bộ lọc" action.
- **Top 10 Multi-Tier Matrix Routing**:
  - Implemented `MultiTierRoutingCoordinator` with 3-tier arbitration: Tier 1 (Google Routes API v2 with live traffic & ETA), Tier 2 (OSRM Table Service road distance), Tier 3 (Haversine baseline).
  - Implemented `GoogleRoutesClient` supporting ComputeRouteMatrix with traffic awareness.
  - Implemented `OsrmRoutingClient` with robust fallback to public demo or custom servers.
  - Implemented `RoutingPreferencesManager` supporting Bring-Your-Own-Key (BYOK) Google API keys and validation.
  - Implemented `JourneyBadge` and updated `StationCard` with ETA and traffic condition styling.
- **Bottom Navigation Bar & Two-Way Favorites Sync**:
  - Implemented `AppTab` enum and Material 3 `AppNavigationBar` with EV Emerald styling.
  - Updated `MainActivity` with root `Scaffold` hosting `AppNavigationBar` with Favorites as start destination.
  - Implemented real-time two-way cloud favorites synchronization across screens via shared singleton `EvcsRepository`.
  - Added `LoginRequiredDialog` for unauthenticated favorite attempts on `NearbyScreen`.
- **Physical Device Deployment**:
  - Successfully packaged `app-debug.apk` and installed to OnePlus 13R (`3B658D010BU00000`) via ADB MCP.

## [2026-09-03] - MVP Release & Hardware Deployment

### Added
- **Reverse Engineering & Auth Engine**:
  - Implemented `SessionManager` supporting encrypted credentials with `EncryptedSharedPreferences` (AES-256 GCM).
  - Implemented `AuthEngine` supporting EVCS 3-step Email OTP login flow (`reward.html` CSRF extraction, `send_otp`, `verify_otp`).
- **Data & Background Search Integration**:
  - Implemented `EvcsApiClient` supporting `POST /favorite.html` (`X-Partial: fav`) and HMAC-SHA256 signed `POST /search?t=...`.
  - Implemented `EvcsRepository` with real-time plug enrichment, Haversine GPS sorting, and offline snapshot caching.
- **Jetpack Compose UI**:
  - Implemented `LoginScreen` with 2-step OTP entry, clipboard paste, countdown timer, and inline error banner.
  - Implemented `FavoritesScreen` with EV Emerald theme, pull-to-refresh, plug badge statuses, and 1-tap navigation button.
  - Implemented `MainActivity` with runtime location permissions, rationale modal, and reactive navigation graph.
- **1-Tap Navigation**:
  - Implemented `MapNavigator` with 3-tier fallback strategy: Google Maps Turn-by-Turn Navigation Intent -> Geo URI Intent -> Web Browser Maps URL.
- **MCP ADB Integration**:
  - Integrated `@landicefu/android-adb-mcp-server` into Antigravity for automated device deployment, testing, logcat inspection, and UI screenshots.

### Fixed
- **Login Navigation Routing**: Fixed issue where `FavoritesUiState.Error` redirected unauthenticated users to `FavoritesScreen` instead of displaying the error banner on `LoginScreen`.
- **OTP Step State Retention**: Fixed issue where OTP validation error reset `LoginScreen` back to email input step.
- **Cloudflare VPN Blockage Detection**: Diagnosed and added explicit error messaging when EVCS Cloudflare WAF triggers HTTP 403 Managed Challenge over datacenter VPNs (ProtonVPN).
- **USB Device Permission**: Resolved ADB `no permissions` by adding custom udev rule `/etc/udev/rules.d/51-android.rules` for OnePlus 13R vendor `22d9`.
