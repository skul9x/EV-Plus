# Plan: Landscape Automotive UI Polish & Carlinkit Dock Remediation

Created: 2026-09-09 19:30
Status: 🟡 In Progress

## Overview

Refine and optimize EV-Plus for Android Automotive and Android Box in-car displays (specifically the **Carlinkit Tbox Ambient** Qualcomm QCM6225 8GB/128GB on Android 13). This plan addresses all 7 user-reported issues identified during in-car testing, hardened with online automotive best practices and edge-case handling:

1. **System Home Rail Button (Issue 4):** Add a top-anchored `HOME` action on the left navigation rail using `Intent.ACTION_MAIN` + `Intent.CATEGORY_HOME` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` and graceful fallback to `moveTaskToBack(true)`.
2. **Carlinkit System Dock Bar Pop-up Remediation (Issue 5):** Prevent the Android Box left dock bar from unhiding when the settings button is pressed by converting `RoutingSettingsModal` into an In-App Landscape Modal / Panel rendered inside the existing Activity window tree with `BackHandler` support, while retaining `ModalBottomSheet` for portrait mode.
3. **Station Detail Auto-Scroll Reset (Issue 3):** Instantly reset detail scroll position to top (`scrollTo(0)`) whenever the driver selects a different station from the master list.
4. **Remove Star Rating Badge from UI (Issue 6):** Strip star rating metrics (`★ 4.8 (25)`) from the detail pane UI to maximize glanceability, while maintaining backwards-compatible helper signatures for existing unit tests.
5. **Compact Horizontal Charging Port Pills (Issue 7):** Format ports concisely as `${kw}kW  ${avail}/${total}` (e.g. `30kW  2/4`), render exhausted/busy ports (`avail == 0 && total > 0`) with red offline alert coloring matching maintenance, display unverified ports (`total == 0`) with clean neutral styling, and arrange badges horizontally using `FlowRow` to prevent vertical driver scrolling.
6. **Station Charger Distribution Summary on StationCard (Issue 9):** Group, aggregate, and display a dedicated power summary line below the station name in `StationCard` (e.g. `120kW x 6 | 60kW x 10 | 30kW x 20`), sorted descending by kW, distinguishing count text with an accent color and wrapping with `basicMarquee`.
7. **On-Demand Realtime Telemetry for Favorites (Issue 1):** Automatically enrich favorite stations with live port availability and power metrics upon opening the Favorites tab or tapping the Refresh button, bounded by a `Semaphore(3)` concurrency throttler, with 2-way sync-back from detail inspection.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 (`basicMarquee`, `FlowRow`, `LazyColumn`, `BoxWithConstraints`, `WindowInsetsControllerCompat`, `BackHandler`)
- Architecture: Unidirectional Data Flow (UDF), Kotlin Coroutines `StateFlow`, Clean MVVM
- Testing: Pure JVM Unit Tests (JUnit4 + Mockito / Fake Repositories, 100% headless, fast execution)

## Execution Rules
- **Phase isolation:** Complete each phase sequentially.
- **Single test rule:** For each phase, add exactly one comprehensive file-based test to verify the core functionality of that phase after implementation. Do not create or run more than one test per phase.
- **Verification step:** After completing each phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- **Stop for review:** Stop after each phase so the user can review before proceeding to the next phase.

## Phases

| Phase | Name | Status | Single Verification Test |
|---|---|---|---|
| 01 | Navigation Rail System Home Action & In-App Immersive Settings Panel | ✅ Completed | `com.evcs.favorites.navigation.SystemHomeAndImmersiveSettingsTest` |
| 02 | Station Detail Redesign: Compact Horizontal Pills, Rating Removal & Scroll Reset | ⬜ Pending | `com.evcs.favorites.ui.StationDetailAutomotiveFormattingTest` |
| 03 | Station Card Charger Distribution Line & Favorites Realtime Enrichment | ⬜ Pending | `com.evcs.favorites.ui.StationCardPowerDistributionAndFavoritesRealtimeTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- Stop after each phase for user review and validation.
