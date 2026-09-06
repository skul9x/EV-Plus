# Plan: Exclude 3.5kW and 7kW Ports from AC Filter & Exclude Motorbike Stations

Created: 2026-09-05 03:28
Status: 🟡 In Progress

## Overview
Refine the EV charging station classification and filtering system in EV+:
- Standardize the AC filter (`SmartFilterMode.AC` and `QuickChipOption.AC`) to strictly match only car-compatible AC tiers (11kW and 22kW).
- Exclude 3.5kW (portable outlet) and 7kW/7.4kW (home wallbox / e-scooter) from AC classification.
- Ignore unrated ports with `type = 0L` even if labeled "AC" or "Type 2".
- Completely exclude pure motorbike/low-power charging stations (stations with no DC ports and no AC ports >= 11kW) from Nearby and Search lists.
- Retain mixed stations (e.g., stations having 60kW DC + 7kW AC), keeping raw port wattage data intact.
- Allow users to query 3.5kW and 7kW stations when manually specifying a custom range (e.g. Min 3kW, Max 7kW) in Settings.
- Remove `KW_3_5` and `KW_7` from `WattageOption` so the power tiers strictly span 11kW to 360kW.
- Update UI labels and live preview strings to `"Cổng AC (11kW, 22kW)"`.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3
- Architecture: Clean Architecture + MVVM, StateFlow, Kotlin Coroutines
- Testing: JUnit 4, Kotlin Coroutines Test

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Domain Models & AC Classification Refinement | ✅ Completed | 100% | `AcPortClassificationAndWattageTierTest.kt` |
| 02 | Motorbike Station Exclusion & Search/Nearby Filtering | ✅ Completed | 100% | `MotorbikeStationExclusionAndFilteringTest.kt` |
| 03 | UI Strings, Settings Live Preview & Regression Alignment | ⬜ Pending | 0% | `AcFilterUiAndSettingsSyncTest.kt` |

## Execution Guidelines
- All phase files must be written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
