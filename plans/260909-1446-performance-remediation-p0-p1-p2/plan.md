# Plan: Performance Remediation (P0, P1, P2)

Created: 2026-09-09 14:46
Status: 🟡 In Progress

## Overview

Execute comprehensive remediation for all performance bottlenecks identified in the Android Performance Audit ([2.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/2.md)) across Priority 0 (P0 - Critical), Priority 1 (P1 - High), and Priority 2 (P2 - Medium). The objective is to eliminate main-thread startup stalls, stop idle UI recomposition/invalidation loops, unify dependency injection singletons, eliminate redundant routing matrix API calls, prevent coroutine memory leaks, and bound static in-memory caches.

The implementation is divided into 4 sequential phases:
1. **Phase 01: P0 Critical Performance & Architecture Remediation:** Eliminate synchronous Keystore MasterKey generation and disk `.commit()` calls from startup, halt infinite `basicMarquee` animation loops in `StationCard`, expose `authService` & `firestoreFavoritesRepository` on `AppContainer`, and unify `MainActivity` dependencies onto `DefaultAppContainer`.
2. **Phase 02: P1 UI Recomposition & Startup Cache Optimization:** Remove unused `cookieHeader` and recomposition disk reads from `FavoritesScreen`/`NearbyScreen`, and make `EvcsRepository` coordinate cache loading purely asynchronous.
3. **Phase 03: P1 Centralized Routing Cache & Quota Conservation:** Implement origin-displacement and TTL caching in `MultiTierRoutingCoordinator` to eliminate redundant Google Routes API and OSRM network requests across `NearbyViewModel` and `FavoritesViewModel`.
4. **Phase 04: P2 Concurrency, Network Disk Cache & Memory Resilience:** Fix uncancelled lazy coroutine leaks in `SingleFlight`, eliminate OkHttp disk cache installation race in `AppOkHttpClientProvider`, introduce pure-Kotlin `BoundedLruCache` for static caches (`parsedConnectorsCache`, `connectorCompatibilityCache`, `COOKIE_REGEX_CACHE`), and clean stale rules in `baseline-prof.txt`.

## Tech Stack
- Platform: Android 8.0+ (API 26-34) & Android Auto (Car App Library Level 7)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose (Material 3 BOM 2024.04.01)
- Asynchronous: Kotlinx Coroutines 1.8.0 & StateFlow
- Storage & Cache: EncryptedSharedPreferences, PlainSharedPreferences, Pure Kotlin Bounded LRU Caches
- Networking & Routing: OkHttp 4.12.0, Google Routes API v2, OSRM Table Service, Haversine
- Testing: JUnit 4 + kotlinx-coroutines-test

## Execution Rules
- **Phase isolation:** Complete each phase sequentially.
- **Single test rule:** For each phase, add exactly one comprehensive file-based test to verify the core functionality of that phase after implementation. Do not create or run more than one test per phase.
- **Verification step:** After completing each phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- **Stop for review:** Halt after test execution and await user review before proceeding to the next phase.

## Phases

| Phase | Name | Target Issues | Status | Single Verification Test |
|---|---|---|---|---|
| 01 | P0 Critical Performance & Architecture Remediation | `[STARTUP-01]`, `[COMPOSE-02]`, `[ARCH-01]` | ⬜ Pending | `com.evcs.favorites.performance.Phase01P0PerformanceAndArchitectureTest` |
| 02 | P1 UI Recomposition & Startup Cache Optimization | `[COMPOSE-01]`, `[STARTUP-02]` | ⬜ Pending | `com.evcs.favorites.performance.Phase02P1RecompositionAndStartupCacheTest` |
| 03 | P1 Centralized Routing Cache & Quota Conservation | `[ROUTING-01]` | ✅ Completed | `com.evcs.favorites.performance.Phase03P1RoutingCoordinatorCacheTest` |
| 04 | P2 Concurrency, Network Disk Cache & Memory Resilience | `[COROUTINE-01]`, `[NETWORK-01]`, `[MEMORY-01]`, `[BUILD-01]` | ⬜ Pending | `com.evcs.favorites.performance.Phase04P2ConcurrencyAndMemoryTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- Stop after each phase for user review and validation.
