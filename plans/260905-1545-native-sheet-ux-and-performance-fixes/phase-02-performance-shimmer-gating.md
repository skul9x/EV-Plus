# Phase 02: Infinite Shimmer Gating & Recomposition Performance Optimization

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Optimize Jetpack Compose runtime performance in `NativeStationDetailSheet.kt` by conditionally gating the infinite shimmer transition in `StatCard` so that no background animation tickers run when metrics have completed loading. In addition, optimize lambda stability and memoization to minimize unnecessary recompositions across the bottom sheet during telemetry and socket stream updates.

## Requirements

### Functional
- [x] In `StatCard`, run `rememberInfiniteTransition` and `animateFloat` strictly when `model.isLoading == true`.
- [x] When `model.isLoading == false`, do not instantiate or animate infinite transitions; immediately render static text values (`headlineMedium`, 22sp) with zero GPU/CPU overhead.
- [x] Retain existing shimmer visual pulse effect during initial loading and refresh states when `isLoadingStats == true`.
- [x] Stabilize callback lambdas (`onNavigate`, `onToggleFavorite`, `onShare`, `onRefresh`, `onDismiss`) so recompositions are skipped for stable sub-composables.

### Non-Functional
- [x] Zero background thread or frame loop utilization after 24h usage statistics data is populated.
- [x] Smooth 60/120 FPS vertical scrolling on mid-range and low-end Android devices.

## Implementation Steps
1. [x] Refactor `StatCard` in [NativeStationDetailSheet.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt) to extract shimmer placeholder into a dedicated composable or conditional branch `if (model.isLoading) { StatCardShimmer() } else { ... }`.
2. [x] Audit lambda instantiation in `NativeStationDetailSheet` and memoize callbacks with `remember` or method references to ensure compiler stability.
3. [x] Verify `StatCardModel` data integrity and ensure `isLoading` flag defaults to `false` when real data arrives.
4. [x] Create exactly one comprehensive file-based test: `app/src/test/java/com/evcs/favorites/ui/components/NativeStationDetailPerformanceOptimizationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt` - Conditional shimmer gating and lambda stability enhancements.
- `app/src/test/java/com/evcs/favorites/ui/components/NativeStationDetailPerformanceOptimizationTest.kt` - [NEW] Single comprehensive test verifying shimmer state logic, static card rendering model, and state update transformations.

## Test Criteria
- [x] Single comprehensive test `NativeStationDetailPerformanceOptimizationTest.kt` PASSES with 0 failures:
  - Verifies `StatCardModel` generation transitions from `isLoading = true` to `isLoading = false` upon receiving valid `Station24hStats`.
  - Verifies that static values (Peak, Average, Rush Hour, Fill Rate) are correctly formatted and memoized.
  - Verifies zero shimmer state leakage when error occurs or fallback values are rendered.

---
Next Phase: [Phase 03: Refresh Rotation Animation & Interactive Reload Feedback UX](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260905-1545-native-sheet-ux-and-performance-fixes/phase-03-refresh-animation-and-feedback.md)
