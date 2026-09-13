# Plan: AC Focus Button Suppression & Portrait Summary Pill Removal

Created: 2026-09-13 19:20
Status: ✅ Completed

## Overview
This plan implements two targeted UX refinements for EV-Plus:
1. **Focus Button Suppression for AC Stations and AC Filter Mode**:
   - Suppress the "Focus" button in the station detail view for stations that do not have fast DC charging ports (pure AC stations), regardless of whether opened from Nearby, Favorites, or Search.
   - When the user is actively filtering by AC (`SmartFilterMode.AC` or `QuickChipOption.AC`), suppress the Focus button on any station opened under that filter.
   - When the Focus button is suppressed, the primary "Chỉ Đường" (Navigate) button dynamically expands to full width (`fillMaxWidth()`) for comfortable one-handed automotive usage.
   - Ensure synchronized behavior between portrait mode (`NativeStationDetailSheet`) and landscape mode (`NativeStationDetailContent`).
   - In landscape mode, switching filter tabs from AC to DC or All immediately restores the Focus button for stations with DC ports.

2. **Portrait Summary Pill Removal & Centered Routing Indicator**:
   - Completely remove the summary pill banner (`filterSummaryPillText`, e.g., "Tìm thấy X trạm có cổng AC khả dụng", "Top 10 trạm sạc VinFast...") across all filter modes on the portrait screen (`NearbyScreen.kt`).
   - Reclaim vertical screen real estate, pulling station cards immediately adjacent to the filter tab chips.
   - Re-center the routing progress spinner (`CircularProgressIndicator` for `uiState.isRoutingLoading`) in the viewport area to provide clear, unobtrusive feedback.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3
- Architecture: Clean Architecture + MVVM, StateFlow, Kotlin Coroutines
- Testing: JUnit 4, Kotlin Coroutines Test

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | AC Focus Button Suppression & Adaptive Button Layout | ✅ Completed | 100% | `StationDetailAcFocusButtonSuppressionTest.kt` |
| 02 | Portrait Summary Pill Removal & Centered Routing Indicator | ✅ Completed | 100% | `NearbyPortraitSummaryPillRemovalTest.kt` |

## Execution Guidelines
- All phase files must be written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
