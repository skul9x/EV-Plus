# Plan: Fix Medium Severity P2 Performance Defects

Created: 2026-09-05 00:05:00 (GMT+7)  
Status: 🟡 Ready for Execution  

## Overview
Remediate the 10 Medium Severity (P2) performance defects cataloged in `phanloai.txt` and `loi.md`. These defects lead to gradual memory accumulation, excessive CPU and object churn in hot cryptographic and geometric paths, unmemoized Compose rendering overhead, unnecessary AES-GCM disk encryption for public data, GPS jitter pipeline thrashing, undebounced Google Routes API matrix calls, Main thread blocking during sorting/filtering, APK DEX bloat from `material-icons-extended`, and cold-start interpretation lag due to missing Android Baseline Profiles.

---

## Key Principles & Execution Rules
1. **Strict Single Comprehensive Test Rule Per Phase**:
   - Each phase defines and executes **exactly one** comprehensive file-based test.
   - Do NOT create or run more than one test per phase.
   - After completing each phase, run only that single test for verification (`./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestFile>`), then stop for user review.
2. **Deterministic English Phase Documentation**:
   - All phase files are written in English in `.md` format.
   - Each phase file defines clear objectives, functional/non-functional requirements, step-by-step implementation tasks, modified files, and verification commands.
3. **Zero Architecture Regressions**:
   - Preserve existing session management, offline cache guarantees, routing multi-tier fallbacks, and Compose UI contracts.

---

## Phases Overview

| Phase | Name | Issue IDs | Status | Test File |
|-------|------|-----------|--------|-----------|
| 01 | Cryptographic & Algorithm Hot-Paths | PERF-CPU-03, PERF-ROUT-02 | ⬜ Pending | `CryptoAndAlgorithmHotPathTest.kt` |
| 02 | Compose Memoization & UI Allocation Reduction | PERF-UI-03 | ⬜ Pending | `StationCardMemoizationPerformanceTest.kt` |
| 03 | Public Station Snapshot Storage Decoupling | PERF-STOR-01 | ⬜ Pending | `PublicStationStorageDecouplingTest.kt` |
| 04 | Bounded LRU Memory Caching | PERF-CACHE-01 | ⬜ Pending | `BoundedLruMemoryCacheTest.kt` |
| 05 | GPS Location Jitter Filtering & Async Compute Dispatching | PERF-LOC-01, PERF-ASYNC-02 | ⬜ Pending | `LocationJitterAndAsyncComputeTest.kt` |
| 06 | Filter Chip Routing Debounce & Quota Protection | PERF-ROUT-01 | ⬜ Pending | `FilterRoutingDebounceTest.kt` |
| 07 | APK Bloat Reduction & Baseline Profiles Optimization | PERF-BUILD-02, PERF-START-01 | ⬜ Pending | `BuildOptimizationAndBaselineProfileTest.kt` |

---

## Verification Commands
- **Phase 01**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.domain.location.CryptoAndAlgorithmHotPathTest
  ```
- **Phase 02**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.components.StationCardMemoizationPerformanceTest
  ```
- **Phase 03**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.PublicStationStorageDecouplingTest
  ```
- **Phase 04**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.data.cache.BoundedLruMemoryCacheTest
  ```
- **Phase 05**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.LocationJitterAndAsyncComputeTest
  ```
- **Phase 06**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ui.viewmodel.FilterRoutingDebounceTest
  ```
- **Phase 07**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.build.BuildOptimizationAndBaselineProfileTest
  ```
