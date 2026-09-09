# Plan: Landscape DC-Only Charger Breakdown & Station Detail Navigation/Focus Button Split

Created: 2026-09-09 20:15
Status: ✅ Completed

## Overview

Refine and optimize EV-Plus landscape in-car UI for electric vehicle drivers based on recent field testing:

1. **Landscape DC-Only Charger Breakdown (Issue 1):** In the Nearby tab in landscape orientation, when a DC filter is active (e.g. `SmartFilterMode.DC` with tiers like `≥ 120kW`, `≥ 60kW`, or custom DC range), filter out AC charging ports from the station card's power breakdown line on the left master list. Only display DC chargers (e.g. `120kW x 4 | 60kW x 2` instead of including `7kW x 4`) to eliminate unnecessary text overflow and marquee scrolling animations.
2. **Station Detail Navigation & Focus Button Split (Issue 2):** In the station detail view/pane, replace the single "Dẫn đường & Theo dõi" action button with two dedicated, side-by-side, automotive-grade action buttons:
   - **"Chỉ Đường"**: Launches pure Google Maps turn-by-turn navigation directly to the station without starting Focus Mode background service or overlay.
   - **"Focus"**: Retains the complete "Dẫn đường & Theo dõi" functionality (renamed to "Focus"), which activates Focus Mode real-time telemetry overlay with automatic background tracking and Google Maps navigation.
   - **Automotive UI/UX Optimization**: Both buttons comply with automotive driver ergonomics with `height >= 56dp`, rounded corners, distinct color palettes (Navigation Blue for "Chỉ Đường" vs. Signature Emerald for "Focus"), clear iconography (`AppIcons.Navigation` and `AppIcons.Bolt`), and independent debounce protection against rapid tapping.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 (`basicMarquee`, `FlowRow`, `LazyColumn`, `Row`, `Button`, `ButtonDefaults`, `AutomotiveDimens`)
- Architecture: Unidirectional Data Flow (UDF), Clean MVVM
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
| 01 | DC-Only Charger Breakdown in Landscape Nearby List | ✅ Completed | `com.evcs.favorites.ui.StationCardDcOnlyFilterTest` |
| 02 | Station Detail Action Split: "Chỉ Đường" & "Focus" Buttons | ✅ Completed | `com.evcs.favorites.ui.StationDetailActionSplitTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- Stop after each phase for user review and validation.
