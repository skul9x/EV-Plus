# Plan: Smart Filter (AC, DC Tiers, and Settings Custom Filter)
Created: 2026-09-04 11:38
Status: 🟡 In Progress

## Overview
Implement an intelligent EV charging station filtering system featuring:
- A 3-button top-level selector with distinctive icons (`[ 🎯 Custom ]`, `[ ⚡ DC ]`, `[ 🔌 AC ]`) and integrated `[ ✕ ]` cancel buttons.
- Smooth thumb-friendly `AnimatedContent` transition to single-select DC power tiers (`≤ 30kW`, `30 - 60kW`, `≥ 60kW`, `≥ 120kW`) with a dedicated 48dp `[ ← Quay lại ]` thumb-zone back button.
- A polished Material 3 `CustomConfigPromptDialog` guiding unconfigured users to setup.
- A user-configurable Custom Filter section within `RoutingSettingsModal` with a clear visible scrollbar indicator, number keyboard input with `kW` suffix and 1-tap clear, real-time "Live Preview" human-readable summary, and encrypted persistence.

## Tech Stack
- Frontend: Jetpack Compose (Material 3), Compose Animation (`AnimatedContent`, `togetherWith`, slide/fade), DrawWithContent Scrollbar
- Architecture: MVI / Clean Architecture, StateFlow, Kotlin Coroutines
- Storage: EncryptedSharedPreferences (`SessionStorage`)
- Testing: JUnit 4, Kotlinx Coroutines Test, Compose UI Testing

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Domain Smart Filter Models & Engine | ✅ Completed | 100% | `NearbyStationSmartFilterTest.kt` |
| 02 | Persistence & ViewModel Filter Pipeline | ✅ Completed | 100% | `NearbyViewModelSmartFilterTest.kt` |
| 03 | Settings Custom Filter Modal, Scrollbar & Live Preview | ✅ Completed | 100% | `CustomFilterSettingsValidationTest.kt` |
| 04 | Nearby Screen Smart Filter UI, Icons & Thumb Animations | ⬜ Pending | 0% | `NearbySmartFilterUiStateTest.kt` |

## Execution Guidelines
- Each phase contains **exactly one** comprehensive file-based test.
- Do not create or run more than one test file per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
