# Plan: Focus Mode 20kW DC, Auto-Scroll Filter Fix, and AC-Only Station Sheet Refinement
Created: 2026-09-06T23:18:00+07:00
Status: 🟡 In Progress

## Overview
This plan implements 3 core improvements requested by the user:
1. **Focus Mode support for 20kW DC chargers:** Lower the minimum DC threshold to 20kW, align with `PowerPort.isDc()` excluding 22kW AC, support 20kW slots in telemetry calculations, and implement smart auto-reroute to find nearest available candidate with matching or higher DC tier (`typeWatts >= targetMaxDcWatts`).
2. **Auto-Scroll to Top on Filter Changes & Refresh:** Resolve the bug where only manual refresh in AC mode triggered auto-scroll to the top nearest station. Ensure every user-initiated filter change (Mode change, DC tier selection, Custom filter application/clearing) and refresh action in any mode triggers smooth auto-scroll to index 0.
3. **Hide Focus Mode button for AC-only stations:** When viewing station details, if a station has no DC charging ports (`powers.none { it.isDc() }`), hide the "⚡ Focus Mode" button and expand the "Chỉ đường" (Navigate) button to full width (`fillMaxWidth()`).

## Tech Stack
- Platform: Android (Kotlin 1.9.23, JVM Target 17)
- UI: Jetpack Compose (Material 3 BOM 2024.04.01)
- Architecture: MVVM + Coroutines StateFlow / SharedFlow + Decoupled Domain Engines
- Telemetry & Network: HERE EV API (OAuth 1.0a HMAC-SHA256) & EVCS fallback

## Verification Strategy
- Exactly **one** comprehensive unit test file per phase to verify core functionality.
- No more than one test run per phase.
- Gradle daemon execution using verified local Temurin JDK 17.

## Phases

| Phase | Name | Description | Verification Test File | Status | Progress |
|---|---|---|---|---|---|
| 01 | Focus Mode 20kW DC Support & Smart Reroute | Lower DC threshold to 20kW, update HERE model DC classifier, and adapt auto-reroute algorithm to candidates >= target tier | `FocusMode20kWSupportTest.kt` | ⬜ Pending | 0% |
| 02 | Auto-Scroll To Top on Refresh & Filter Changes | Extend `NearbyUiHelper.shouldScrollToTop` to accept `FILTER_CHANGE` and wire `NearbyViewModel` filter transitions with fresh timestamps | `NearbyAutoScrollFilterFixTest.kt` | ⬜ Pending | 0% |
| 03 | AC-Only Station Focus Button Visibility Refinement | Update `NativeStationDetailSheet` to check DC port availability, conditionally hide Focus Mode button and make Navigate button full width | `StationDetailFocusButtonVisibilityTest.kt` | ⬜ Pending | 0% |

## Quick Commands
- Start Phase 01: `/code phase-01`
- Review Plan: Inspect individual phase files in `plans/260906-2318-focus-mode-20kw-and-filter-fixes/`
