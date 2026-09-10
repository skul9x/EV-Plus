# Plan: Standalone Commercial-Grade Settings Screen (Landscape Master-Detail & Portrait Fullscreen)
Created: 2026-09-10
Status: ⬜ Pending

## Overview
Elevate the Settings screen from an overlay modal / bottom sheet into a standalone, top-level navigation destination (`AppTab.SETTINGS`) equivalent to Nearby and Favorites. Clean up redundant TopAppBar shortcuts, implement a Tesla / Android Auto inspired Master-Detail layout in Landscape, implement a single-column fullscreen layout in Portrait with a Reset action, enable instant auto-save with subtle UX feedback, provide +/- 10kW steppers and an inline Apply button for power filtering, purge obsolete HTTP debug logs, and embed commercial application information & copyright notice.

## User Constraints & Rules
1. All phase files must be written in English in markdown (`.md`) format.
2. For each phase, add exactly one comprehensive file-based test to verify the core functionality of that phase after implementation.
3. Do not create or run more than one test per phase.
4. After completing each phase, run only that single test for verification. Then stop so user can review.
5. Once completely done, just say "done."

## Architecture & Tech Stack
- **Language:** Kotlin 1.9.23 / JVM 17
- **UI Framework:** Jetpack Compose (Material 3)
- **Navigation & Layout:**
  - Portrait: `AppNavigationBar` (Bottom Bar with 3 tabs) + Fullscreen Single-Column Scroll
  - Landscape: `AppNavigationRail` (58dp rail with active pill indicator) + Master-Detail Split Layout (260dp Sidebar + Detail Canvas)
- **State Management:** StateFlow & Kotlin Coroutines via `StateFlow` + `collectAsStateWithLifecycle`
- **Persistence:** Android DataStore / SharedPreferences (`OrientationPreferences`, `FocusModePreferences`, `RoutingPreferencesManager`)
- **Testing:** JUnit 4 + Kotlin Coroutines Test

## Phases

| Phase | Name | Description | Status | Progress |
|---|---|---|---|---|
| 01 | Navigation Core & Top-Level Tab Integration | Elevate Settings to `AppTab.SETTINGS`, wire into `AppNavigationBar` and `AppNavigationRail`, handle Back navigation, and clean up TopAppBars | 🟩 Completed | 100% |
| 02 | Commercial Settings Screen Components & Auto-Save | Build functional cards with instant auto-save, +/- 10kW steppers, inline Apply button, About & Copyright card, and purge debug logs | 🟩 Completed | 100% |
| 03 | Adaptive Layouts (Landscape Master-Detail & Portrait Fullscreen) & Final Cleanup | Build full `SettingsScreen`, implement Landscape Master-Detail & Portrait layouts, wire into `MainActivity`, and align existing tests | ⬜ Pending | 0% |

## Verification Command Cheat-Sheet
- Phase 01: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.navigation.SettingsNavigationTabIntegrationTest"`
- Phase 02: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.SettingsScreenComponentsAndAutoSaveTest"`
- Phase 03: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.SettingsScreenAdaptiveLayoutTest"`
