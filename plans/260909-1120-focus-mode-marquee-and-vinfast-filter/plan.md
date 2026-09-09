# Plan: Focus Mode Floating Window Header Marquee, Distance Removal & Strict VinFast Search Filtering

Created: 2026-09-09 11:20
Status: ✅ Completed

## Overview

Streamline the Focus Mode Floating Window UI overlay by removing the redundant distance badge (`distanceText`), enabling smooth continuous marquee scrolling on overflowing station names (`stationNameView`), and enforcing strict VinFast brand filtering on nearby GPS searches in `EvcsRepository` to eliminate 3rd-party charging stations (e.g. Ford, Esky, Rabbit EVC) from the Nearby list while preserving user-saved favorites in the Favorites tab.

## Architecture & Components Affected

- **Floating Window UI**: `FocusModeFloatingViewManager.kt`, `FocusModeViewLayoutHelper.kt`
- **Data & Repository Layer**: `EvcsRepository.kt` (`searchNearbyVinFastInternal`)
- **Domain Filter Layer**: `NearbyStationFilter.kt` (defense-in-depth provider check)
- **Unit Tests**:
  - `app/src/test/java/com/evcs/favorites/focus/FocusModeFloatingHeaderMarqueeTest.kt` (Phase 01)
  - `app/src/test/java/com/evcs/favorites/data/repository/NearbyVinFastFilteringTest.kt` (Phase 02)

## Phases

| Phase | Name | Description | Status | Verification Test |
|---|---|---|---|---|
| **01** | Floating Window Header Marquee & Distance Removal | Remove `distanceBadgeView` from header row; enable single-line infinite marquee animation on `stationNameView`. | ✅ Completed | `FocusModeFloatingHeaderMarqueeTest.kt` |
| **02** | Strict VinFast Search Filtering | Filter out non-VinFast stations from `searchNearbyVinFast` while preserving favorites tab integrity. | ✅ Completed | `NearbyVinFastFilteringTest.kt` |

## Execution Protocol

1. Execute Phase 01.
2. Run only the Phase 01 test: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.focus.FocusModeFloatingHeaderMarqueeTest"`
3. Stop and wait for user review.
4. Upon approval, execute Phase 02.
5. Run only the Phase 02 test: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.repository.NearbyVinFastFilteringTest"`
6. Stop and complete.
