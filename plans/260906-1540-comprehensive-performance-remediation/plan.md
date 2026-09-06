# Plan: Comprehensive Performance Remediation (All 14 Issues from 2.md)

Created: 2026-09-06 15:40
Status: 🟡 In Progress

## Overview
Remediate all 14 performance defects identified in the Comprehensive Android Performance Report ([2.md](file:///d:/skul9x/EV-Plus-main/2.md)), covering Android Keystore main-thread blocking, N+1 sequential HTML scraping waterfall, filter algorithm Big-O complexity & GC churn, Compose stability, OkHttp routing timeout cascades, memory caching, CDN decoding, release logging hygiene, and baseline profiling.

## Defect Remediation Architecture Matrix

| Defect ID | Severity | Root Cause | Architectural Remediation | Target Verification Test |
|---|---|---|---|---|
| **PERF-001** | 🔴 P0 | Synchronous `EncryptedSharedPreferences` & Android Keystore IPC probe on UI thread in ViewModels and Repositories. | Migrate `SmartFilterPreferences`, `NearbyFilterPreferences`, and `RoutingPreferencesManager` to `PlainSharedPrefsStorage`; load initial cache asynchronously in `FirestoreFavoritesRepository`. | `ColdStartAndMainThreadUnblockingTest.kt` |
| **PERF-002** | 🔴 P0 | Sequential N+1 HTML scraping loop downloading full HTML pages for unknown station coordinates. | Eliminate sequential waterfall; resolve missing coordinates concurrently using `coroutineScope { async { ... } }` and coordinate caching. | `NetworkRoutingAndCoalescingOptimizationTest.kt` |
| **PERF-003** | 🟠 P1 | Full-list $O(N \log N)$ sorting and 1000 `station.copy()` allocations on every filter toggle; repeated connector parsing in `hasCarCompatiblePorts()`. | Implement bounded PriorityQueue Min-Heap Top-K algorithm $O(N \log 10)$; memoize car-compatible port checks. | `FilterAlgorithmAndAllocationOptimizationTest.kt` |
| **PERF-004** | 🟠 P1 | `NearbyUiState` and `FavoritesUiState.Success` lack `@Immutable`, rendering entire screens unskippable during recomposition. | Add `@Immutable` annotation to `NearbyUiState` and `FavoritesUiState.Success`. | `ComposeStabilityAndDeadCodeTest.kt` |
| **PERF-005** | 🟠 P1 | OkHttp blocking `Call.execute()` in coroutines and 25-30s sequential cascading timeout in `MultiTierRoutingCoordinator`. | Enforce bounded timeouts (3.5s Google, 3.5s OSRM) with overall fallback `withTimeoutOrNull(5000L)` to Haversine. | `NetworkRoutingAndCoalescingOptimizationTest.kt` |
| **PERF-006** | 🟠 P1 | Handwritten 7-line `baseline-prof.txt` lacking Compose, Coroutines, OkHttp, and Serialization AOT rules. | Expand `baseline-prof.txt` with comprehensive AOT compilation rules for critical paths. | `BaselineProfileAndBenchmarkConfigTest.kt` |
| **PERF-007** | 🟡 P2 | VinFast S3 CDN URLs decoded via 3-step Base64 pipeline repeatedly without caching. | Add thread-safe in-memory LRU cache (500 entries) in `VinFastCdnUrlDecoder`. | `MemoryCacheCdnAndLoggingHygieneTest.kt` |
| **PERF-008** | 🟡 P2 | `BoundedLruMap.entries` clones entire `LinkedHashMap` on every access, causing allocation churn in `saveCachedCoordinates`. | Provide `mapValuesThreadSafe` and `forEachThreadSafe` in `BoundedLruMap` to eliminate unnecessary map copies. | `FilterAlgorithmAndAllocationOptimizationTest.kt` |
| **PERF-009** | 🟡 P2 | Synchronous OkHttp Disk Cache opening in `EvPlusApplication.onCreate()` blocks app startup. | Lazy-initialize or background-dispatch `AppOkHttpClientProvider.installDiskCache` to `Dispatchers.IO`. | `ColdStartAndMainThreadUnblockingTest.kt` |
| **PERF-010** | 🟡 P2 | `DebugLoggingInterceptor` and `AppDebugLogger` allocate 4KB request/response buffers in Release builds. | Gate interceptor and log buffer population strictly behind `BuildConfig.DEBUG`. | `MemoryCacheCdnAndLoggingHygieneTest.kt` |
| **PERF-011** | 🟡 P2 | Coil ImageLoader memory cache allocates 25% of JVM heap. | Reduce Coil `MemoryCache` to 15% heap and optimize bitmap configuration. | `MemoryCacheCdnAndLoggingHygieneTest.kt` |
| **PERF-012** | 🟢 P3 | Compute-intensive sorting and filtering executed on `Dispatchers.IO` instead of `Dispatchers.Default`. | Route CPU-bound distance calculations and filter logic cleanly to `Dispatchers.Default`. | `FilterAlgorithmAndAllocationOptimizationTest.kt` |
| **PERF-013** | 🟢 P3 | GPS coordinate micro-jitter bypasses `SingleFlight` request coalescing. | Round coordinates to 4 decimal places (~11m) when building `SingleFlight` cache keys. | `NetworkRoutingAndCoalescingOptimizationTest.kt` |
| **PERF-014** | 🟢 P3 | Dead code `StatCard` composable in `NativeStationDetailSheet.kt`. | Safely remove uncalled `StatCard` composable. | `ComposeStabilityAndDeadCodeTest.kt` |

## Tech Stack
- Language: Kotlin 1.9.23 (JVM Target 17)
- UI Framework: Jetpack Compose Material 3 (BOM 2024.04.01 / Compiler 1.5.11)
- Image Loading: Coil Compose 2.6.0 (`ImageLoaderFactory`, `MemoryCache`, `DiskCache`)
- Network: OkHttp 4.12.0 with connection pooling and HTTP disk cache
- Concurrency: Kotlinx Coroutines 1.8.0
- Storage: SharedPreferences (`PlainSharedPrefsStorage`, `EncryptedSharedPrefsStorage`), Cloud Firestore
- Architecture: Clean Architecture + MVVM/MVI, StateFlow, Local-First
- Testing: JUnit 4, Kotlinx Coroutines Test 1.8.0, OkHttp MockWebServer 4.12.0

## Phases

| Phase | Name | Defects Covered | Status | Single Comprehensive Verification Test |
|---|---|---|---|---|
| 01 | Cold Start & Main Thread Unblocking | PERF-001, PERF-009 | ⬜ Pending | [ColdStartAndMainThreadUnblockingTest.kt](file:///d:/skul9x/EV-Plus-main/app/src/test/java/com/evcs/favorites/performance/ColdStartAndMainThreadUnblockingTest.kt) |
| 02 | Compose Stability & Dead Code Elimination | PERF-004, PERF-014 | ✅ Completed | [ComposeStabilityAndDeadCodeTest.kt](file:///d:/skul9x/EV-Plus-main/app/src/test/java/com/evcs/favorites/performance/ComposeStabilityAndDeadCodeTest.kt) |
| 03 | Filter Algorithm & Allocation Optimization | PERF-003, PERF-008, PERF-012 | ⬜ Pending | [FilterAlgorithmAndAllocationOptimizationTest.kt](file:///d:/skul9x/EV-Plus-main/app/src/test/java/com/evcs/favorites/performance/FilterAlgorithmAndAllocationOptimizationTest.kt) |
| 04 | Network Routing & Coalescing Resilience | PERF-002, PERF-005, PERF-013 | ⬜ Pending | [NetworkRoutingAndCoalescingOptimizationTest.kt](file:///d:/skul9x/EV-Plus-main/app/src/test/java/com/evcs/favorites/performance/NetworkRoutingAndCoalescingOptimizationTest.kt) |
| 05 | Memory Caching, CDN Decoding & Logging Hygiene | PERF-007, PERF-010, PERF-011 | ⬜ Pending | [MemoryCacheCdnAndLoggingHygieneTest.kt](file:///d:/skul9x/EV-Plus-main/app/src/test/java/com/evcs/favorites/performance/MemoryCacheCdnAndLoggingHygieneTest.kt) |
| 06 | Baseline Profile & Benchmark Architecture | PERF-006 | ⬜ Pending | [BaselineProfileAndBenchmarkConfigTest.kt](file:///d:/skul9x/EV-Plus-main/app/src/test/java/com/evcs/favorites/performance/BaselineProfileAndBenchmarkConfigTest.kt) |

## Execution Guidelines
- All phase files are written in English.
- For each phase, add **exactly one** comprehensive file-based test to verify the functionality of that phase after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification.
- Stop after each phase verification for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
