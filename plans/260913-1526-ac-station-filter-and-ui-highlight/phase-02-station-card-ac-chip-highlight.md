# Phase 02: StationCard AC Chip Highlight & UI Integration
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Enhance the presentation of charging ports on `StationCard` by visually highlighting 11kW and 22kW `WattageChip` items whenever AC filtering mode is active in the Nearby tab. When `isAcFilterActive` is true, ports satisfying `powerPort.isAc()` are rendered with a prominent Emerald green border (`EmeraldPrimary`), 1.5dp stroke, and bold font styling. Keep the card clean and minimalist without adding any extra "Tư nhân" badge labels.

## Requirements
### Functional
- [x] Add `isAcFilterActive: Boolean = false` parameter to `StationCard` composable in `StationCard.kt`.
- [x] Add `isHighlighted: Boolean = false` parameter to `WattageChip` composable in `StationCard.kt`.
- [x] When `isHighlighted == true`, style `WattageChip` with an Emerald green border (`BorderStroke(1.5.dp, EmeraldPrimary)`), elevated background tint if applicable, and bold text weight (`FontWeight.Bold`), making the 11kW / 22kW chip stand out immediately.
- [x] In `StationCard`, pass `isHighlighted = isAcFilterActive && powerPort.isAc()` to each rendered `WattageChip`.
- [x] In `NearbyScreen.kt`, pass `isAcFilterActive = (uiState.activeFilterMode == SmartFilterMode.AC)` to `StationCard`.
- [x] In `NearbyLandscapeScreen.kt`, pass `isAcFilterActive = (uiState.activeFilterMode == SmartFilterMode.AC)` to `StationCard`.
- [x] Ensure that stations with mixed DC and AC posts (e.g. 120kW DC + 11kW AC) highlight only the 11kW AC chip, leaving the DC chips in their standard styling.
- [x] Strictly omit adding any private or franchise badges ("Tư nhân" badge) to the card layout per user specification.

### Non-Functional
- [x] Compose Recomposition Stability: Ensure default parameter values (`isAcFilterActive = false`, `isHighlighted = false`) preserve binary and call-site compatibility across other screens (`FavoritesScreen`, `FavoritesLandscapeScreen`).
- [x] Zero Performance Degradation: Avoid allocations in `WattageChip` during recomposition.

## Implementation Steps
1. [x] Update `WattageChip` in `StationCard.kt` to accept `isHighlighted: Boolean = false` and apply the `EmeraldPrimary` 1.5dp border and bold typography when highlighted.
2. [x] Update `StationCard` signature in `StationCard.kt` to accept `isAcFilterActive: Boolean = false` (default false) and pass `isHighlighted = isAcFilterActive && powerPort.isAc()` to `WattageChip`.
3. [x] Connect `NearbyScreen.kt` station list rendering to supply `isAcFilterActive = (uiState.activeFilterMode == SmartFilterMode.AC)`.
4. [x] Connect `NearbyLandscapeScreen.kt` station list rendering to supply `isAcFilterActive = (uiState.activeFilterMode == SmartFilterMode.AC)`.
5. [x] Create the single comprehensive test file `app/src/test/java/com/evcs/favorites/ui/components/AcWattageChipHighlightAndUiIntegrationTest.kt` validating highlight resolution logic, filter mode mapping, and backwards compatibility.
6. [x] Execute the single test via Gradle to confirm verification passes.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [MODIFY] Add `isAcFilterActive` to `StationCard` and `isHighlighted` to `WattageChip`.
- `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt` - [MODIFY] Pass `isAcFilterActive` from `uiState.activeFilterMode`.
- `app/src/main/java/com/evcs/favorites/ui/screens/landscape/NearbyLandscapeScreen.kt` - [MODIFY] Pass `isAcFilterActive` from `uiState.activeFilterMode`.
- `app/src/test/java/com/evcs/favorites/ui/components/AcWattageChipHighlightAndUiIntegrationTest.kt` - [NEW] Single comprehensive test for AC chip highlight resolution and UI state plumbing.

## Test Criteria
- [x] `powerPort.isAc()` returns true for 11kW and 22kW, false for DC (20kW, 30kW, 60kW, 120kW) and sub-11kW (3.5kW, 7kW).
- [x] When `isAcFilterActive = true`, a mixed station [120kW DC, 11kW AC] flags the 11kW chip as highlighted and leaves the 120kW chip unhighlighted.
- [x] When `isAcFilterActive = false`, no chips are flagged as highlighted regardless of wattage.
- [x] StationCard maintains full backward compatibility for `FavoritesScreen` callers without specifying `isAcFilterActive`.
- [x] No "Tư nhân" badge text is inserted into the card composition.

---
Next Phase: None (Feature Complete)
