# Phase 03: UI Strings, Settings Live Preview & Regression Alignment
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Update UI presentation layers (Settings Card, Live Preview badges, Wattage Chip rows) to reflect `"Cổng AC (11kW, 22kW)"`, align `NearbyUiHelper.SORTED_WATTAGE_OPTIONS` with the 13 car power tiers (360kW down to 11kW), and update legacy test assertions to ensure zero regression across the test suite.

## Requirements
### Functional
- [x] Update `CustomFilterSettingsCard.kt`:
  - When `QuickChipOption.AC` is selected, display live preview message: `"👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống"`.
- [x] Update `NearbyUiHelper.kt` & `WattageFilterChipsRow.kt`:
  - Update `SORTED_WATTAGE_OPTIONS` documentation and list to reflect 13 power tiers (360kW down to 11kW).
  - Verify lowest wattage chip is `WattageOption.KW_11` ("11kW").
- [x] Update existing unit test files referencing removed `KW_3_5` / `KW_7` or old AC expectations:
  - `NearbyUiComponentsTest.kt` (update lowest wattage tier assertions to `KW_11`).
  - `NearbyStationSmartFilterTest.kt` (align AC assertions with 11kW/22kW).
  - `CustomFilterSettingsValidationTest.kt` (align expected live preview text).
  - `NearbyFilteringAndFavoriteSyncTest.kt` (remove obsolete `KW_7` assertions).

### Non-Functional
- [x] Maintain consistent Material 3 styling and localized Vietnamese typography.
- [x] Ensure 100% clean JVM test execution without regression.

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt`:
   - Change AC live preview label to `"👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống"`.
2. [x] Update `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt` and `WattageFilterChipsRow.kt`:
   - Clean up comments and ensure `SORTED_WATTAGE_OPTIONS` smoothly adapts to remaining 13 tiers.
3. [x] Align legacy test files with new domain signatures and strings.
4. [x] Implement single comprehensive test: `app/src/test/java/com/evcs/favorites/ui/components/AcFilterUiAndSettingsSyncTest.kt`.
5. [x] Run verification test: `./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.components.AcFilterUiAndSettingsSyncTest"`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt` - Update AC preview text
- `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt` - Update wattage options documentation
- `app/src/main/java/com/evcs/favorites/ui/components/WattageFilterChipsRow.kt` - Update chip documentation
- `app/src/test/java/com/evcs/favorites/domain/filter/NearbyStationSmartFilterTest.kt` - Update legacy test cases
- `app/src/test/java/com/evcs/favorites/ui/components/CustomFilterSettingsValidationTest.kt` - Update legacy preview test
- `app/src/test/java/com/evcs/favorites/NearbyUiComponentsTest.kt` - Update legacy wattage chip test
- `app/src/test/java/com/evcs/favorites/NearbyFilteringAndFavoriteSyncTest.kt` - Update legacy wattage enum test
- `app/src/test/java/com/evcs/favorites/ui/components/AcFilterUiAndSettingsSyncTest.kt` - Single comprehensive test for Phase 03

## Test Criteria
- [x] Settings Live Preview text for `QuickChipOption.AC` is `"👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống"`.
- [x] `NearbyUiHelper.SORTED_WATTAGE_OPTIONS.last()` is `WattageOption.KW_11`.
- [x] Total count of `SORTED_WATTAGE_OPTIONS` is exactly 13.
- [x] All updated legacy test suites compile and pass.

## Notes
- Single test verification only: Run `AcFilterUiAndSettingsSyncTest` upon phase completion and stop for user review.

---
All Phases Complete!
