# Phase 01: Startup Orientation Setting, Persistence & Activity Lifecycle Enforcement
Status: ✅ Completed
Dependencies: None

## Objective
Enable drivers to configure the startup screen orientation (`SYSTEM`, `LANDSCAPE`, `PORTRAIT`). Ensure persistent storage using `SessionStorage`, immediate enforcement during `MainActivity.onCreate` before `setContent` using fixed orientation constants (`ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE` / `PORTRAIT`), and reactive runtime switching from the settings modal. This ensures car displays and Android Boxes lock reliably into landscape without visual flicker, sensor-induced flipping on slopes/bumps, or dependency on hardware accelerometers.

## Requirements

### Functional
1. **Orientation Model Definition (`StartupOrientation`)**:
   - Define `StartupOrientation` enum in `com.evcs.favorites.data.preferences`:
     - `SYSTEM`: Default system auto-rotate / sensor (`ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`).
     - `LANDSCAPE`: Fixed landscape orientation (`ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`).
     - `PORTRAIT`: Fixed portrait orientation (`ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`).
   - *Automotive Hardware Design Note:* We explicitly use fixed `SCREEN_ORIENTATION_LANDSCAPE` instead of `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`. In-car Android Boxes often lack accelerometer hardware (or have dummy sensors), and driving over hills, ramps, banking turns, or bumps can cause erratic 180° sensor flips. Fixed landscape is the automotive industry standard for in-dash displays.
   - Include display titles and helper descriptions in Vietnamese:
     - `SYSTEM` -> "Mặc định hệ thống (Tự xoay)"
     - `LANDSCAPE` -> "Luôn mở màn hình ngang (Landscape)" with badge: "Khuyên dùng cho Android Box ô tô"
     - `PORTRAIT` -> "Luôn mở màn hình dọc (Portrait)" with badge: "Khuyên dùng cho điện thoại"

2. **Persistence via `OrientationPreferences`**:
   - Create `OrientationPreferences` using `SessionStorage` abstraction (`PlainSharedPrefsStorage` for production, `InMemorySessionStorage` for JVM testing).
   - Storage key: `KEY_STARTUP_ORIENTATION = "startup_orientation"`.
   - Expose `startupOrientationFlow: StateFlow<StartupOrientation>` and getter/setter functions:
     - `getStartupOrientation(): StartupOrientation` (synchronous instant read for cold start)
     - `setStartupOrientation(orientation: StartupOrientation)`
   - Default fallback: `StartupOrientation.SYSTEM`.
   - Zero cold-start delay: Reading from `PlainSharedPrefsStorage` occurs synchronously during `MainActivity.onCreate`, guaranteeing that `requestedOrientation` is set before the window decor and Compose hierarchy are initialized, eliminating visual layout jumps or orientation flickers.

3. **Decoupled Orientation Helper (`OrientationHelper`)**:
   - Implement pure Kotlin helper `OrientationHelper` to map `StartupOrientation` to the corresponding Android `ActivityInfo` orientation constants:
     - `SYSTEM` -> `ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`
     - `LANDSCAPE` -> `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`
     - `PORTRAIT` -> `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`
   - Implement reverse mapping and validation logic for robust edge-case handling.

4. **Settings UI Integration in `RoutingSettingsModal.kt`**:
   - Add a high-visibility, automotive-friendly orientation selection section (`StartupOrientationCard`) in `RoutingSettingsModal`.
   - Present 3 clear options with icons (`ScreenRotation`, `StayCurrentLandscape`, `StayCurrentPortrait`).
   - Highlight the automotive recommendation badge on `LANDSCAPE` ("Khuyên dùng cho Android Box ô tô") with `EmeraldPrimary` / `EmeraldContainerDark` accent.
   - Tapping an option updates `OrientationPreferences` and immediately triggers orientation transition.

5. **Activity Lifecycle Integration in `MainActivity.kt`**:
   - Instantiate `OrientationPreferences` in `MainActivity`.
   - Apply `requestedOrientation = OrientationHelper.toActivityInfoOrientation(orientationPreferences.getStartupOrientation())` immediately in `onCreate` before `setContent`.
   - Observe `orientationPreferences.startupOrientationFlow` in `lifecycleScope` to dynamically update `requestedOrientation` when user changes the setting at runtime, without requiring app restart.

### Non-Functional
- Pure JVM testability without requiring Robolectric or Android emulator.
- Thread-safe persistence and instant synchronous initial read to prevent visual layout flicker on startup.

## Implementation Steps
1. Create `StartupOrientation.kt` and `OrientationPreferences.kt` in `app/src/main/java/com/evcs/favorites/data/preferences/`.
2. Create `OrientationHelper.kt` in `app/src/main/java/com/evcs/favorites/util/`.
3. Update `RoutingSettingsModal.kt` with the `StartupOrientationCard` selector.
4. Update `MainActivity.kt` to bind orientation preference to `requestedOrientation` in `onCreate` and observe runtime updates.
5. Create single verification test: `StartupOrientationSettingsTest.kt` in `app/src/test/java/com/evcs/favorites/ui/`.
6. Run the single verification test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.StartupOrientationSettingsTest"`
7. Stop execution and await user review.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/preferences/StartupOrientation.kt` - [New] Orientation enum with titles, badges, and serialization.
- `app/src/main/java/com/evcs/favorites/data/preferences/OrientationPreferences.kt` - [New] SessionStorage persistence & StateFlow.
- `app/src/main/java/com/evcs/favorites/util/OrientationHelper.kt` - [New] Mapping logic between StartupOrientation and ActivityInfo constants.
- `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` - [Modify] Add StartupOrientationCard selector with automotive recommendation badges.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [Modify] Apply and observe requestedOrientation during activity lifecycle.
- `app/src/test/java/com/evcs/favorites/ui/StartupOrientationSettingsTest.kt` - [New] Exactly one comprehensive unit test for Phase 01.

## Test Criteria
- Single test: `com.evcs.favorites.ui.StartupOrientationSettingsTest`
  - Verifies default orientation is `SYSTEM`.
  - Verifies persistence write/read across all enum values (`SYSTEM`, `LANDSCAPE`, `PORTRAIT`).
  - Verifies invalid/corrupt string values safely fall back to `SYSTEM`.
  - Verifies reactive `StateFlow` emits new orientation values upon update.
  - Verifies `OrientationHelper.toActivityInfoOrientation` accurately maps all enum values to target `ActivityInfo` flags (`SCREEN_ORIENTATION_UNSPECIFIED`, `SCREEN_ORIENTATION_LANDSCAPE`, `SCREEN_ORIENTATION_PORTRAIT`).

---
Next Phase: [Phase 02: Adaptive Master-Detail Architecture & Automotive NavigationRail](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1100-android-box-landscape-ui-ux/phase-02-adaptive-master-detail-and-navigation-rail.md)
