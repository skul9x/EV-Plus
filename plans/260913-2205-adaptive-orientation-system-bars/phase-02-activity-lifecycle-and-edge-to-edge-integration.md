# Phase 02: Activity Lifecycle & Edge-to-Edge Integration
Status: ✅ Completed
Dependencies: [Phase 01: Adaptive System Bars Helper & Policy](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-2205-adaptive-orientation-system-bars/phase-01-adaptive-system-bars-controller.md)

## Objective
Integrate `AdaptiveSystemBarsHelper` into `MainActivity.kt` and `Theme.kt`, ensuring system bars are updated adaptively across all lifecycle events (`onCreate`, `onWindowFocusChanged`, `onConfigurationChanged`, dynamic orientation settings) and system bars appearance supports transparent edge-to-edge rendering with reactive light/dark icon contrast in portrait mode.

## Requirements
### Functional
- [x] Replace unconditional `applyImmersiveMode()` in `MainActivity.kt` with `applyAdaptiveSystemBars()`.
- [x] In `MainActivity.onCreate()`: apply adaptive system bars immediately based on current configuration orientation.
- [x] In `MainActivity.onWindowFocusChanged(hasFocus: Boolean)`: if `hasFocus`, re-apply adaptive system bars.
- [x] Override `MainActivity.onConfigurationChanged(newConfig: Configuration)`: call `applyAdaptiveSystemBars()` on screen orientation changes.
- [x] In `MainActivity` orientation observer: re-apply system bars whenever reactive `startupOrientationFlow` triggers an orientation update.
- [x] In `EvcsFavoritesTheme` (`Theme.kt`): ensure status bar and navigation bar insets and icon contrast (`isAppearanceLightStatusBars`, `isAppearanceLightNavigationBars`) are properly managed without hardcoding opaque black status bars.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - [Modify] Integrate adaptive system bars across lifecycle and configuration changes.
- `app/src/main/java/com/evcs/favorites/ui/theme/Theme.kt` - [Modify] Support transparent edge-to-edge system bars with contrast awareness.
- `app/src/test/java/com/evcs/favorites/ui/AdaptiveSystemBarsIntegrationTest.kt` - [Create] Exactly one single comprehensive verification test.

## Verification Criteria
- [x] Single verification test `com.evcs.favorites.ui.AdaptiveSystemBarsIntegrationTest` passes.
- [x] System bars behavior respects Portrait edge-to-edge (visible bars) and Landscape immersive sticky mode (hidden bars).
