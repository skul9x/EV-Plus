# Plan: Landscape UI & Carlinkit Tbox Ambient Optimization

Created: 2026-09-09 13:05
Status: 🟡 In Progress

## Overview

Optimize the EV-Plus Android application for automotive in-car displays, specifically tailored for the **Carlinkit Tbox Ambient (Qualcomm QCM6225 8GB/128GB, Android 13)** and wide horizontal automotive screens (16:9, 21:9 ultrawide).

The plan achieves five core architectural and UX objectives:
1. **Station Name Sanitization & Marquee Typography:** Strip redundant brand prefixes (`VinFast - `, `Vinfast - `, `VINFAST - `, etc.) from station titles to immediately highlight the core venue/location name (e.g. "TTTM Dabaco Mart Quế Võ"), and enable smooth horizontal `basicMarquee` text scrolling for long titles so names are never truncated with ellipses (`...`).
2. **Carlinkit Fullscreen Immersive Sticky Mode:** Enforce automatic edge-to-edge immersive mode via `WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` and `hide(WindowInsetsCompat.Type.systemBars())`, hiding the Carlinkit system navigation dock bar by default while allowing drivers to reveal it with a transient edge swipe.
3. **Compact Centered Navigation Rail (58dp):** Shrink the left navigation rail from 72dp to 58dp to maximize horizontal content area, vertically center all navigation items (`Arrangement.Center`), and present large, icon-only touch targets in the exact priority sequence: `Nearby` ➔ `Favorites` ➔ `Settings` ➔ `Refresh`.
4. **Dedicated Landscape Code Separation:** Completely decouple landscape mode from portrait mode into dedicated composable files (`NearbyLandscapeScreen.kt`, `FavoritesLandscapeScreen.kt`), eliminating tangled `if-else` branching.
5. **Vertical Height Reclaiming:** Eliminate the top `TopAppBar` and the filter summary count pill (`"Tìm thấy 10 trạm..."`) on both tabs in landscape mode, reclaiming ~95dp of vertical space to display 3-4 visible station cards simultaneously alongside the full-height charging port detail panel.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 (`WindowCompat`, `WindowInsetsControllerCompat`, `basicMarquee`, `LazyColumn`, `BoxWithConstraints`)
- Architecture: Unidirectional Data Flow (UDF), Kotlin Coroutines `StateFlow`
- Storage: `PlainSharedPrefsStorage` / `OrientationPreferences`
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
| 01 | Station Name Sanitizer VinFast Strip & Marquee Text Support | ⬜ Pending | `com.evcs.favorites.util.StationNameSanitizerTest` |
| 02 | Fullscreen Immersive Sticky Mode & Compact Centered Navigation Rail | ⬜ Pending | `com.evcs.favorites.navigation.LandscapeNavigationRailTest` |
| 03 | Dedicated Landscape UI Screens for Nearby & Favorites | ⬜ Pending | `com.evcs.favorites.ui.LandscapeDedicatedScreensContractTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- Stop after each phase for user review and validation.
