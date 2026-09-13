# Phase 02: Car Screen Contracts, DistanceSpan Enforcement & Permission Safety

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Eliminate reflection hacks on private Car App Library fields (`mItemList`), satisfy the strict `PlaceListMapTemplate` `DistanceSpan` contract for non-browsable rows, safeguard `setCurrentLocationEnabled` behind location permission checks, and ensure action flag compliance on `PaneTemplate`.

## Requirements
### Functional
- [x] Ensure `PlaceListMapTemplate.Builder.setItemList(itemList)` passes cleanly without throwing `IllegalArgumentException` and without needing reflection.
- [x] Format station distances using `DistanceSpan` on `SpannableString` attached to the row subtitle/text, or mark list rows with `.setBrowsable(true)` when directing to `StationDetailCarScreen`.
- [x] Guard `setCurrentLocationEnabled(true)` with a permission check (`ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`) using `carContext.checkSelfPermission()`.
- [x] Set `Action.FLAG_PRIMARY` on `StationDetailCarScreen` primary action to adhere to Car App Library `ActionsConstraints.restrictBackgroundColorToPrimaryAction`.
- [x] Honor Car App API level constraints for `Row.addAction()`: either restrict row action to Car API Level 6+ or rely on full-row click for detail navigation.

### Non-Functional
- [x] ProGuard / R8 Safety: 100% free of internal reflection against AndroidX library private fields.
- [x] Driver Distraction: Adhere to Google Car App Library quotas (max 6 list items, max 4 pane rows, max 2 pane actions).

## Implementation Steps
1. [x] Remove reflection hack `PlaceListMapTemplate.Builder::class.java.getDeclaredField("mItemList")` in `MainCarScreen.kt`.
2. [x] In `MainCarScreen.buildItemList()`:
   - Attach `DistanceSpan` using `Distance.create(km, Distance.UNIT_KILOMETERS)` on a `SpannableString` for the distance portion of the subtitle.
   - Set `.setBrowsable(true)` on each `Row.Builder` since tapping the row navigates to the detailed `StationDetailCarScreen`.
3. [x] In `MainCarScreen.onGetTemplate()`:
   - Check `carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || carContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED`.
   - Only call `.setCurrentLocationEnabled(true)` if location permission is granted.
4. [x] In `StationDetailCarScreen.onGetTemplate()`:
   - Call `.setFlags(Action.FLAG_PRIMARY)` on `primaryAction` so `ActionsConstraints.validateOrThrow` recognizes it as an authorized colored action.
5. [x] Provide safe adaptation for `Row.addAction()`: check `effectiveCarApiLevel >= 6` before calling `row.addAction()`, keeping row click as the universal navigation mechanism.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/car/MainCarScreen.kt` - Remove reflection, add DistanceSpan/browsable flag, guard location permission
- `app/src/main/java/com/evcs/favorites/car/StationDetailCarScreen.kt` - Add `FLAG_PRIMARY` to primary action
- `app/src/main/java/com/evcs/favorites/car/CarStationFormatter.kt` - Provide DistanceSpan spannable text builder
- `app/src/test/java/com/evcs/favorites/car/CarScreenContractAndTemplateSafetyTest.kt` - Comprehensive single verification test for Phase 02

## Single Verification Test
- **Test Class:** `com.evcs.favorites.car.CarScreenContractAndTemplateSafetyTest`
- **Execution Command:**
  `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarScreenContractAndTemplateSafetyTest"`
- **Verifications:**
  - `PlaceListMapTemplate.Builder.setItemList` builds cleanly without reflection or exceptions.
  - Rows contain valid `DistanceSpan` or browsable flag.
  - `setCurrentLocationEnabled` is true only when location permission is granted, and false when revoked.
  - `StationDetailCarScreen` primary action has `FLAG_PRIMARY` and builds valid `PaneTemplate`.

---
Next Phase: [Phase 03: Auto Data Loading, Proximity Sorting & Thread Concurrency](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-1625-android-auto-logic-and-contract-fixes/phase-03-data-loading-nearest-station-sorting-and-concurrency.md)
