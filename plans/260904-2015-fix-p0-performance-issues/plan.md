# Plan: Fix Critical P0 Performance Defects

Created: 2026-09-04 20:15:00 (GMT+7)
Status: 🟡 Pending Review

## Overview
Remediate the two Critical Severity (P0) performance defects identified in the audit (`loi.md` and `1.md`):
1. **PERF-ANR-01 (Startup & Main Thread Blocking)**: Synchronous evaluation of `MasterKey` and `EncryptedSharedPreferences` on the Android Main Thread during cold start (`MainActivity.onCreate` -> `FavoritesViewModel.init`), blocking the UI thread for 250ms–900ms and risking ANR.
2. **PERF-MEM-02 (Native WebView Memory Leak)**: Unreleased `WebView` instance in `StationDetailModal` leaking 15MB–45MB of native RAM on every modal open/close, causing eventual `OutOfMemoryError` (OOM) crashes.

---

## Key Rules & Architectural Decisions
1. **Strict Single Test Rule Per Phase**:
   - Each phase contains **exactly one** comprehensive file-based unit test to verify core functionality.
   - Do NOT create or run more than one test per phase.
   - After completing each phase, run only that single test for verification, then stop for review.
2. **Asynchronous Non-Blocking Cold Start**:
   - Never invoke `MasterKey.Builder`, `EncryptedSharedPreferences.create()`, or `commit()` probes synchronously on the Main Thread.
   - Decouple `FavoritesViewModel` and `AuthEngine` initial state initialization from synchronous disk I/O and Keystore IPC. Initial UI state defaults to `FavoritesUiState.Loading` while session validation executes on `Dispatchers.IO`.
3. **Deterministic Native WebView Lifecycle Management**:
   - Implement lifecycle-aware disposal via `AndroidView(onRelease = ...)` and `DisposableEffect` in `StationDetailModal`.
   - Ensure the native Chromium engine is completely detached from the view hierarchy, cleared of history and child views, and terminated via `WebView.destroy()`.
   - Safeguard against renderer termination via `onRenderProcessGone` returning `true`.

---

## Phases

| Phase | Name | Issue ID | Status | Test File |
|-------|------|----------|--------|-----------|
| 01 | Async Keystore Warmup & Non-Blocking ViewModel Startup | PERF-ANR-01 | ⬜ Pending | `AsyncSessionStorageColdStartTest.kt` |
| 02 | Deterministic WebView Lifecycle & Native Memory Leak Prevention | PERF-MEM-02 | ⬜ Pending | `StationDetailWebViewLifecycleTest.kt` |

---

## Verification Commands
- **Phase 1 Verification**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.AsyncSessionStorageColdStartTest
  ```
- **Phase 2 Verification**:
  ```bash
  ./gradlew testDebugUnitTest --tests com.evcs.favorites.StationDetailWebViewLifecycleTest
  ```
