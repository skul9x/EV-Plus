# Plan: VinFast Station Fixes, Canonical Detail URL, Pre-Filter & Real-Time GPS Refresh

Created: 2026-09-05 14:00
Status: ✅ Completed

## Overview
Implement critical fixes and user-requested enhancements for the EV+ Android application verified against live cURL capture inspection (`curl_capture_20260905_134301`) and user requirements:
1. **VinFast Station Domain Modeling & Canonical Detail URL Builder Fix:**
   - Add `evse: String = "VinFast"` to domain `Station` model and map it from `SearchStationRaw.evse`.
   - Fix canonical URL generation in `StationUrlBuilder.kt` so that VinFast stations always produce valid `https://evcs.vn/tram-sac-vinfast-${slug}-${locationId.toLowerCase()}.html` URLs regardless of whether the station name starts with "vinfast", resolving HTTP 404 errors.
   - Prevent duplicate provider prefixes (`tram-sac-vinfast-vinfast-...`) when station name contains "VinFast".
   - Prevent duplicate `-c.C.` suffixes when partner station IDs start with `C.`.
   - Clean `SearchRequest` to send `{"latitude": ..., "longitude": ...}` without `wattageTypes` key matching live web capture behavior, allowing the backend to return all stations (AC and DC) and delegating precise filtering to client-side `NearbyStationFilter`.
   - Update `EvcsApiClient.fetchStationHtml` to support `evse: String = "VinFast"` and `Station` model overloads.
2. **Real-Time GPS Refresh with Failure Notification (Option B):**
   - Fix `NearbyViewModel.refresh()` so it always requests a fresh GPS fix via `locationService.getFreshLocation()` rather than reusing stale memory coordinates.
   - If GPS acquisition fails during refresh (returns `null`), report a clear location error and prompt the user to retry instead of silently falling back to old coordinates.
3. **Pre-Filter Selection on Initial Screen, Persistence & Immediate Execution:**
   - Display `SmartFilterBar` on the initial hero screen before the user taps "Nhấn để tìm trạm quanh đây".
   - Automatically restore the user's last selected filter from `SmartFilterPreferences` upon app launch.
   - Allow users to toggle, change, or clear filters directly on the initial screen, persisting changes immediately.
   - When the user taps the search button, immediately apply the pre-selected filter to the search results without requiring a second tap.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3 (BOM 2024.04.01)
- Architecture: Clean Architecture + MVVM, StateFlow, Kotlin Coroutines
- Testing: JUnit 4, Kotlin Coroutines Test

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | VinFast Station Domain Modeling, Search Payload & URL Builder Fix | ✅ Completed | 100% | `VinFastStationMappingAndUrlBuilderTest.kt` |
| 02 | Real-Time GPS Refresh & Location Error Handling | ✅ Completed | 100% | `NearbyRealtimeGpsRefreshTest.kt` |
| 03 | Pre-Filter Selection on Initial Screen, Persistence & Immediate Execution | ✅ Completed | 100% | `NearbyPreFilterAndInitialScanTest.kt` |

## Execution Guidelines
- All phase files must be written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
