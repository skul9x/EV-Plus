# Plan: Fix High Severity P1 Performance Defects

Created: 2026-09-04 21:15:00 (GMT+7)  
Updated: 2026-09-04 21:40:00 (GMT+7)  
Status: 🟡 Ready for Execution

## Overview
Remediate the 10 High Severity (P1) performance defects identified in `phanloai.txt` and `loi.md`. These defects cause heavy UI micro-stutters, frame drops (FPS < 15), memory allocation churn, background battery drain, redundant network connections, and bloated APK binaries.

---

## Key Principles & Execution Rules
1. **Strict Single Comprehensive Test Rule Per Phase**:
   - Each phase contains **exactly one** comprehensive file-based unit test to verify core functionality.
   - Do NOT create or run more than one test per phase.
   - Run only that phase's test for verification (`./gradlew testDebugUnitTest --tests com.evcs.favorites.<TestFile>`), avoiding running unrelated test files that may stall or hang.
2. **Deterministic English Phase Documentation**:
   - All phase files are written in English in `.md` format.
   - Every phase defines crisp objectives, functional and non-functional requirements, detailed implementation steps, affected files, and verification commands.
3. **No Regressions**:
   - Preserve existing domain contracts, offline cache functionality, and mock server unit test compatibility.

---

## Phases Overview

| Phase | Name | Issue IDs | Status | Test File |
|-------|------|-----------|--------|-----------|
| 01 | Repository Non-Blocking Async Cache Initialization | PERF-ANR-02 | ✅ Completed | `RepositoryAsyncInitPerformanceTest.kt` |
| 02 | Logging Buffer Churn Reduction & Lazy UI Rendering | PERF-MEM-01, PERF-UI-04 | ⬜ Pending | `DebugLoggingMemoryAndLazyUiTest.kt` |
| 03 | Jetpack Compose Model Stability & Regex Hot-Path Caching | PERF-UI-01, PERF-UI-02 | ⬜ Pending | `ComposeStabilityAndHotPathRecompositionTest.kt` |
| 04 | Centralized OkHttpClient Connection Pool & Conditional Logging | PERF-NET-01, PERF-NET-02 | ⬜ Pending | `SharedOkHttpPoolAndLoggingInterceptorTest.kt` |
| 05 | Static Pre-compiled Regexes in Hot String Parsing Paths | PERF-CPU-01 | ⬜ Pending | `StaticRegexHotPathParsingPerformanceTest.kt` |
| 06 | Lifecycle-Aware Flow Collection & Battery Conservation | PERF-ASYNC-01 | ⬜ Pending | `LifecycleAwareFlowCollectionTest.kt` |
| 07 | R8 Code/Resource Shrinking & Proguard Rules Configuration | PERF-BUILD-01 | ⬜ Pending | `R8ProGuardConfigurationVerificationTest.kt` |

---

## Verification Commands
- **Phase 01**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.RepositoryAsyncInitPerformanceTest
  ```
- **Phase 02**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.DebugLoggingMemoryAndLazyUiTest
  ```
- **Phase 03**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.ComposeStabilityAndHotPathRecompositionTest
  ```
- **Phase 04**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.SharedOkHttpPoolAndLoggingInterceptorTest
  ```
- **Phase 05**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.StaticRegexHotPathParsingPerformanceTest
  ```
- **Phase 06**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.LifecycleAwareFlowCollectionTest
  ```
- **Phase 07**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.R8ProGuardConfigurationVerificationTest
  ```
