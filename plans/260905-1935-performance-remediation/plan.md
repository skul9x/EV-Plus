# Plan: Performance Remediation (Critical & Warning Issues)

Created: 2026-09-05 19:35
Status: 🟡 In Progress

## Overview
Remediate all 3 Critical and 3 Warning performance defects identified in the codebase health audit [audit_20260905.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/docs/reports/audit_20260905.md), covering Jetpack Compose recomposition, Coroutines asynchronous threading race conditions, Coil image memory/disk caching, OkHttp network caching, Regex runtime compilation, and 24h usage statistics algorithms:

1. **PERF-02: Threading & Race Condition Elimination**: Eliminate asynchronous initialization race conditions in [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt#L134-L150) so `initialLoadJob` is non-null at instantiation and joining deterministically awaits loading completion; coordinate `defaultDispatcher` with `ioDispatcher` in [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt#L63) to ensure deterministic test execution and zero dropped station results.
2. **PERF-03 & PERF-08: Compose Recomposition & Scroll Optimization**: Implement `contentType = { "station_card" }` for `LazyColumn` item recycling and memoize all action callbacks (`onNavigateClick`, `onFavoriteClick`, `onStationClick`) with `remember` in [NearbyScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt#L637-L649) and [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt#L320-L330) to allow Compose runtime to skip recomposition of unchanged [StationCard.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt) items during scroll and filter changes.
3. **PERF-01: Coil ImageLoader & Cache Management**: Introduce custom [EvPlusApplication.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/EvPlusApplication.kt) implementing `ImageLoaderFactory` with 25% JVM heap memory cache, 50MB disk cache (`cacheDir/image_cache`), `.respectCacheHeaders(false)` for CDN images, and lazy OkHttpClient `callFactory` sharing the connection pool while stripping `okhttp3.Cache` (`.cache(null)`) to prevent double disk caching. Memoize `ImageRequest` with `remember(imageUrl)` in [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt#L1200-L1211) and [StationPhotoViewerModal.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/StationPhotoViewerModal.kt#L337-L352) with automatic downsampling to prevent OutOfMemory crashes on high-res VinFast CDN images.
4. **PERF-04: Regex Pre-compilation & CPU Optimization**: Convert dynamically compiled regular expressions in [SessionManager.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/SessionManager.kt#L363), [StationTelemetryParser.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/StationTelemetryParser.kt#L116), and [AuthEngine.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/auth/AuthEngine.kt#L298-L303) into pre-compiled `companion object` constants and thread-safe caches.
5. **PERF-05 & PERF-06: OkHttp Cache & Stats Algorithm Optimization**: Add 20MB persistent HTTP disk cache (`cacheDir/http_cache`) to [AppOkHttpClientProvider.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/network/AppOkHttpClientProvider.kt) for REST API responses, and refactor 24h peak hour calculation in [Station24hStatsCalculator.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/domain/Station24hStatsCalculator.kt#L72-L86) from $O(24 \times N)$ to single-pass $O(N)$ accumulation with tie-breaking and Vietnam timezone preservation.

## Performance Remediation Architecture Matrix

| Defect ID | Severity | Root Cause | Architectural Remediation | Target Verification Test |
|---|---|---|---|---|
| **PERF-02** | 🔴 Critical | Nested launch in `init` leaves `initialLoadJob` null; uncoordinated `Dispatchers.Default` races test dispatchers. | Assign `favoritesLoadJob` at root of `init`; default `defaultDispatcher` to `ioDispatcher` in `NearbyViewModel`. | [ViewModelThreadingAndRaceConditionTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/viewmodel/ViewModelThreadingAndRaceConditionTest.kt) |
| **PERF-03** | 🔴 Critical | `LazyColumn` lacks `contentType`; inline lambda instantiations defeat Compose smart recomposition skipping. | Add `contentType = { "station_card" }`; memoize action callbacks with `remember(context, viewModel)`. | [StationCardRecompositionAndStabilityTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/components/StationCardRecompositionAndStabilityTest.kt) |
| **PERF-01** | 🔴 Critical | Unbounded memory/disk caching for Coil; `ImageRequest` recreated per frame; double caching risk if sharing OkHttp cache. | Implement `ImageLoaderFactory` in `EvPlusApplication`; 25% heap memory cache; 50MB disk cache; strip OkHttp cache from Coil `callFactory`; memoize requests. | [CoilImageLoaderConfigurationTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/image/CoilImageLoaderConfigurationTest.kt) |
| **PERF-04** | 🟡 Warning | Dynamic `Regex(...)` instantiation in cookie extraction, locked ticker check, and CSRF token parsing. | Pre-compile patterns into static constants in `companion object` and thread-safe caches (`ConcurrentHashMap`). | [PrecompiledRegexPerformanceTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/util/PrecompiledRegexPerformanceTest.kt) |
| **PERF-05** | 🟡 Warning | `AppOkHttpClientProvider` lacks HTTP disk cache, causing redundant cellular bandwidth usage and API latency. | Install 20MB OkHttp disk cache (`cacheDir/http_cache`) in `AppOkHttpClientProvider`, initialized in `onCreate()`. | [NetworkCacheAndPeakHourAlgorithmTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/network/NetworkCacheAndPeakHourAlgorithmTest.kt) |
| **PERF-06** | 🟡 Warning | Peak hour search iterates $24 \times N$ times filtering raw sample buckets. | Single-pass $O(N)$ accumulation into 24-slot hourly aggregate array. | [NetworkCacheAndPeakHourAlgorithmTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/network/NetworkCacheAndPeakHourAlgorithmTest.kt) |

## Tech Stack
- Language: Kotlin 1.9.23 (JVM Target 17)
- UI Framework: Jetpack Compose Material 3 (BOM 2024.04.01)
- Image Loading: Coil Compose 2.6.0 (`ImageLoaderFactory`, `MemoryCache`, `DiskCache`)
- Network: OkHttp 4.12.0 with connection pooling and disk cache (`okhttp3.Cache`)
- Concurrency: Kotlinx Coroutines 1.8.0
- Architecture: Clean Architecture + MVVM/MVI, StateFlow, Local-First
- Testing: JUnit 4, Kotlinx Coroutines Test 1.8.0, OkHttp MockWebServer 4.12.0

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Verification Test |
|---|---|---|---|---|
| 01 | Threading & Race Condition Elimination (PERF-02) | ⬜ Pending | 0% | [ViewModelThreadingAndRaceConditionTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/viewmodel/ViewModelThreadingAndRaceConditionTest.kt) |
| 02 | Compose Recomposition & Scroll Optimization (PERF-03 & PERF-08) | ⬜ Pending | 0% | [StationCardRecompositionAndStabilityTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/ui/components/StationCardRecompositionAndStabilityTest.kt) |
| 03 | Coil ImageLoader & Cache Management (PERF-01) | ⬜ Pending | 0% | [CoilImageLoaderConfigurationTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/image/CoilImageLoaderConfigurationTest.kt) |
| 04 | Regex Pre-compilation & CPU Optimization (PERF-04) | ⬜ Pending | 0% | [PrecompiledRegexPerformanceTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/util/PrecompiledRegexPerformanceTest.kt) |
| 05 | OkHttp Cache & Stats Algorithm Optimization (PERF-05 & PERF-06) | ⬜ Pending | 0% | [NetworkCacheAndPeakHourAlgorithmTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/network/NetworkCacheAndPeakHourAlgorithmTest.kt) |

## Execution Guidelines
- All phase files are written in English.
- For each phase, add **exactly one** comprehensive file-based test to verify the core functionality of that phase after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification.
- Stop after each phase verification for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
