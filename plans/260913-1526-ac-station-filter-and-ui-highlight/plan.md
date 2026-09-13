# Plan: AC Station Filter Availability Restriction & UI Chip Highlight

Created: 2026-09-13 15:26
Status: 🟡 In Progress

## Overview
Optimize the AC charging station filtering behavior in the Nearby tab and refine the StationCard presentation:
- Enforce strict availability for AC charging: when `SmartFilterMode.AC` (or `QuickChipOption.AC`) is active, only stations that have at least one car-compatible AC port (11kW or 22kW) with available plugs (`availablePlugs > 0`) are returned. Stations where AC ports are fully occupied (`0/1` or `0/2`) are hidden even when `includeFullStations = true`.
- Retain all qualifying AC stations regardless of ownership: both official VinFast stations and private / franchise stations ("Tư nhân") with available 11kW/22kW ports are included.
- Support hybrid / mixed stations: stations with high-power DC posts (e.g. 120kW, 60kW, 30kW) that also have an available 11kW or 22kW AC port are preserved.
- Refine StationCard UI when AC filtering is active: visually highlight 11kW and 22kW `WattageChip` items with an Emerald green border (`EmeraldPrimary`), 1.5dp stroke, and bold font weight to immediately draw user attention to the AC ports.
- Preserve minimalist card design: do not add additional private ("Tư nhân") badges to the card layout.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3
- Architecture: Clean Architecture + MVVM, StateFlow, Kotlin Coroutines
- Testing: JUnit 4, Kotlin Coroutines Test

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | AC Filter Availability Strict Enforcement | ✅ Completed | 100% | `AcFilterAvailabilityStrictEnforcementTest.kt` |
| 02 | StationCard AC Chip Highlight & UI Integration | ⬜ Pending | 0% | `AcWattageChipHighlightAndUiIntegrationTest.kt` |

## Execution Guidelines
- All phase files must be written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
