# Plan: Native Station Detail Sheet UI/UX and Performance Optimization

Created: 2026-09-05 15:45
Status: ✅ Completed

## Overview
Implement critical UI/UX and performance optimizations for the Native Station Detail Bottom Sheet (`NativeStationDetailSheet.kt`) identified through real-device screenshot inspection (`current_screen.png`) and live browser network cURL trace analysis (`curl_capture_20260905_153922`).

### Live cURL Trace & Backend Validation Summary
Analysis of the 3 captured production requests (`curl_capture_20260905_153922`) for station `C.BNI0012` (VinFast - TTTM Dabaco Mart Quế Võ) confirms:
- **Handshake (`POST /{slug}.html` with `X-Partial: user`)**: Returns valid `apiToken`, `chargeToken`, and clean zero-rating state (`avg: 0, count: 0`), verifying the rating badge suppression logic is functioning as intended.
- **Live Telemetry (`POST /charging`)**: Returns `busyKw: {"30": 2}` and forecast ticker with promotional `amd-locked` CTA. Confirms `StationTelemetryParser` successfully extracts real busy counts and strips promotional HTML without data leakage.
- **Sync Ping (`POST https://www2.evcs.vn/update`)**: Payload `{"a": "C.BNI0012", "b": 2}` succeeds with HTTP 204 No Content.
- **Timing & Latency**: Total roundtrip is ~1.5s - 2.5s. This confirms the backend pipeline is 100% correct and healthy; **no backend modifications are required**. All improvements strictly focus on the native Compose presentation and runtime lifecycle.

### Key Optimization Pillars
1. **Primary Navigation CTA Button Truncation Fix & Action Bar Layout Alignment:**
   - Eliminate text truncation where the primary CTA button collapses to `"▲ ."` on standard density screens.
   - Adjust layout hierarchy so that the primary "Chỉ đường" button maintains high visual priority with full label visibility (`weight(1.3f)` or `weight(1.2f, fill = true)`), while auxiliary actions ("Yêu thích" and "Chia sẻ") adapt without compressing the primary action.
2. **Infinite Shimmer Animation Gating & Recomposition Performance Optimization:**
   - Conditionally activate `rememberInfiniteTransition` in `StatCard` only when `model.isLoading == true`, completely halting background CPU/GPU ticker loops when statistics data has loaded.
   - Stabilize inline click lambdas to prevent unnecessary recompositions across the bottom sheet hierarchy during state emissions.
3. **Interactive Refresh Loading Indicator & Live Network Synchronization UX:**
   - Provide explicit visual feedback during station detail reload by displaying a rotation/progress animation on the Refresh icon when `uiState.isRefreshing == true`.
   - Prevent rapid multi-tap spamming during active reload and ensure clean state transition from refreshing back to idle upon Stage 1 (telemetry) and Stage 2 (Socket.io history) completion.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3 (BOM 2024.04.01)
- Architecture: Clean Architecture + MVVM, StateFlow, Kotlin Coroutines
- Testing: JUnit 4, Kotlin Coroutines Test

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Primary Action Button Truncation Fix & Action Bar Layout Alignment | ✅ Completed | 100% | `NativeStationDetailActionBarLayoutTest.kt` |
| 02 | Infinite Shimmer Gating & Recomposition Performance Optimization | ✅ Completed | 100% | `NativeStationDetailPerformanceOptimizationTest.kt` |
| 03 | Refresh Rotation Animation & Interactive Reload Feedback UX | ✅ Completed | 100% | `NativeStationDetailRefreshFeedbackTest.kt` |

## Execution Guidelines
- All phase files are written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
