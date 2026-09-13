# Plan: Android Auto Logic, Contract, and Lifecycle Hardening

Created: 2026-09-13 16:25
Status: 🟡 In Progress

## Overview

Remediate all critical logic bugs, Google Car App Library contract violations, race conditions, and automotive safety flaws discovered during the Android Auto module audit:
1. Fix host validation array parsing crash in release builds (`Invalid allowed host entry`) and replace invalid mipmap notification icon.
2. Eliminate unsafe reflection on `mItemList`, satisfy `PlaceListMapTemplate` `DistanceSpan` contract for non-browsable rows, check location permissions before enabling current location, and fix Car API level compatibility for row actions.
3. Resolve cold-start empty list dead-end, implement vehicle-relative distance calculation and nearest-first sorting (capping at 6 items), and eliminate main-thread disk I/O / JSON parsing.
4. Fix navigation URI encoding for station labels with parentheses `()`, guard against `(0,0)` coordinate routing, correct AC/DC port categorization for high-power AC chargers, and register session lifecycle listeners to terminate foreground services upon Android Auto disconnect.

## Tech Stack
- Platform: Android 8.0+ (API 26-34, Android 15 ready)
- Language: Kotlin 1.9.23 (JVM 17)
- Framework: `androidx.car.app:app:1.7.0` & `androidx.car.app:app-projected:1.7.0`
- Architecture: Decoupled presenters, Car App Library templates, CoroutineScopes, lifecycle observers
- Testing: Pure JVM Unit Tests (JUnit 4 + `app-testing:1.7.0`)

## Execution Rules
- **Phase isolation:** Complete each phase sequentially.
- **Single test rule:** For each phase, add exactly one comprehensive file-based test to verify the core functionality of that phase after implementation. Do not create or run more than one test per phase.
- **Verification step:** After completing each phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- **Stop for review:** Halt after test execution and await user review before proceeding to the next phase.

## Phases

| Phase | Name | Status | Single Verification Test |
|---|---|---|---|
| 01 | Host Validation, Manifest Compliance & Notification SmallIcon | ✅ Completed | `com.evcs.favorites.car.CarHostAndManifestContractTest` |
| 02 | Car Screen Contracts, DistanceSpan Enforcement & Permission Safety | ✅ Completed | `com.evcs.favorites.car.CarScreenContractAndTemplateSafetyTest` |
| 03 | Auto Data Loading, Proximity Sorting & Thread Concurrency | ⬜ Pending | `com.evcs.favorites.car.CarStationDataLoaderAndSortingTest` |
| 04 | Navigation URI Parentheses Encoding, AC/DC Formatter & Session Lifecycle | ⬜ Pending | `com.evcs.favorites.car.CarNavigationAndLifecycleIntegrityTest` |

## Quick Commands
- Start Phase 01: Implement Phase 01 and run `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarHostAndManifestContractTest"`
