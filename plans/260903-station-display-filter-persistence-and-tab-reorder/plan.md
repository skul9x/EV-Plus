# Plan: Station Display Refinements, Filter Persistence & Bottom Navigation Tab Reordering

Created: 2026-09-03
Status: ✅ Completed

## Overview
Address four critical UX and UI issues identified during user testing on the live device:
1. **Station Title Distance Prefix Removal**: Strip the redundant estimated distance prefix (e.g., `5.4km » `, `9.1km » `, `500m » `) originally returned by the raw search API, displaying only the actual, clean station name.
2. **Station Title Text Clipping Resolution**: Prevent station names from being truncated with ellipses (`...`) by switching from 1-line truncation to flexible multiline wrapping (up to 3 lines) with top-aligned status badges in `StationCard` and `StationDetailModal`.
3. **Wattage Filter Persistence in "Quanh đây" (Nearby)**: Persist selected wattage filter chips across application restarts using the established `SessionStorage` architecture so that user filter preferences are retained and automatically re-applied on subsequent launches.
4. **Bottom Navigation Tab Reordering**: Swap the positions of the bottom navigation tabs so that **Quanh đây** (Nearby) is on the left and **Yêu thích** (Favorites) is on the right, setting **Quanh đây** as the primary start destination.

## Phases

| Phase | Name | Status | Verification Test | Progress |
|-------|------|--------|-------------------|----------|
| 01 | Station Name Sanitization & Multiline Full-Text Display | ✅ Completed | `StationNameDisplayAndSanitizationTest.kt` | 100% |
| 02 | Wattage Filter Persistence & Restoration Across App Restarts | ✅ Completed | `NearbyFilterPersistenceTest.kt` | 100% |
| 03 | Bottom Navigation Tab Order Swapping (Nearby Left, Favorites Right) | ✅ Completed | `BottomNavigationTabReorderTest.kt` | 100% |

## Strict Testing Protocol
- Each phase defines **exactly one comprehensive file-based test** to verify its core functionality.
- After implementing each phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestName>`
- Stop after each phase verification for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Next Step: `/next`
