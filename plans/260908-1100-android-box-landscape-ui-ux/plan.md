# Plan: Phase 2 - Android Box Landscape UI/UX Optimization

Created: 2026-09-08 11:00
Last Updated: 2026-09-08 11:15
Status: 🟡 In Progress

## Overview

Optimize EV-Plus for in-car Android Box displays, head units, and wide automotive screens (16:9, 21:9 ultrawide) in accordance with Section 2 ("GIAI ĐOẠN 2: Tối ưu UI/UX Màn hình ngang cho Android Box") of `1.md`.
The plan establishes three foundational automotive user experience pillars:
1. **Startup Orientation Setting & Lifecycle Enforcement:** Give drivers complete control over app orientation (`SYSTEM`, `LANDSCAPE`, `PORTRAIT`) with instant synchronous retrieval via `SessionStorage` (`PlainSharedPrefsStorage`), immediate enforcement via `ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE` / `SCREEN_ORIENTATION_PORTRAIT` in `MainActivity.onCreate` (avoiding sensor-based flips on hills/bumps and supporting sensorless Android Boxes), and real-time reactive switching from `RoutingSettingsModal`.
2. **Adaptive Master-Detail Architecture & NavigationRail:** Detect landscape orientation (`widthDp > heightDp && widthDp >= 600dp`), replace the bottom `AppNavigationBar` (which takes 80dp of limited 600-720px vertical car screen height) with a compact vertical `AppNavigationRail` (72dp) on the left edge, and split content into a 2-column layout: Left Column (35-40%, 320-480dp) for the station list with sticky search/filters, and Right Column (60-65%) for embedded station details with full charging port status (250kW, 150kW, 60kW, 30kW, 20kW) and the prominent 1-tap **"⚡ DẪN ĐƯỜNG & THEO DÕI"** action button (Google Maps navigation + Focus Mode HUD). Automatically pre-select the nearest station on wide screens so drivers never face an empty pane.
3. **Automotive Touch Target Safety (≥ 56dp) & High-Contrast Dark Theme:** Enforce Google Automotive touch-target standards ($\ge 56dp$, cards $\ge 76dp$) across all clickable UI components, buttons, and navigation rail items to prevent miss-clicks while driving over bumpy roads. Integrate Hero Metric typography (`24sp Bold` for plug availability `🟢 4/8 TRỐNG`). Implement a high-contrast automotive dark color palette (deep obsidian `#121216`, vivid emerald green `#00E676`, electric cyan `#00E5FF`) satisfying WCAG AAA contrast ratios ($\ge 7:1$) for glanceability in daylight glare and glare-free night driving.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 (`NavigationRail`, `BoxWithConstraints`, `Scaffold`, `Surface`)
- Architecture: Unidirectional Data Flow (UDF), Kotlin Coroutines `StateFlow`
- Storage: `SessionStorage` / `PlainSharedPrefsStorage` (instant synchronous retrieval, zero cold-start delay/flicker)
- Testing: Pure JVM Unit Tests (JUnit4 + Kotlinx Coroutines Test, 100% headless, fast execution)

## Execution Rules
- **Phase isolation:** Complete each phase sequentially.
- **Single test rule:** For each phase, create exactly one comprehensive file-based test. Do not create or run more than one test per phase.
- **Verification step:** After implementing a phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- **Stop for review:** Halt after test execution and await user review before moving to the next phase.

## Phases

| Phase | Name | Status | Single Verification Test |
|---|---|---|---|
| 01 | Startup Orientation Setting, Persistence & Activity Lifecycle Enforcement | ⬜ Pending | `com.evcs.favorites.ui.StartupOrientationSettingsTest` |
| 02 | Adaptive Master-Detail Architecture & Automotive NavigationRail | ⬜ Pending | `com.evcs.favorites.ui.AdaptiveMasterDetailLayoutTest` |
| 03 | Automotive Touch Target Sizing (≥ 56dp) & High-Contrast Car Dark Mode | ⬜ Pending | `com.evcs.favorites.ui.AutomotiveTouchTargetAndThemeTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- Stop after each phase for user review and validation.
