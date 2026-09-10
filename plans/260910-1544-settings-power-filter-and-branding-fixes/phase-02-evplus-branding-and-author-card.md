# Phase 02: EV+ Branding and Author Attribution in About Card

**Status:** ✅ Completed  
**Target File:** `app/src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt`

---

## Objective
Standardize the application name in `AboutAppInfo` to `EV+`, update author attribution in `AboutAppCard` to display primary title "Nguyễn Duy Trường" with subtitle "Tác giả", and update the copyright constant to `"© 2026 Nguyễn Duy Trường"`.

---

## Requirements

### Functional
1. **Application Name Standardization:**
   - Update `AboutAppInfo.APP_NAME` from `"EV+ Station Navigator"` to `"EV+"`.
2. **Author Row Refinement:**
   - In `AboutAppCard`, the person icon row must display:
     - Primary Title: `AboutAppInfo.AUTHOR` (`"Nguyễn Duy Trường"`)
     - Subtitle: `"Tác giả"`
   - Eliminate redundant repeats of the author's name and the legacy "Copyright 2026" phrase from inside this row.
3. **Copyright Constant:**
   - Update `AboutAppInfo.COPYRIGHT` to `"© 2026 Nguyễn Duy Trường"`.

### Non-Functional
- Strict typography hierarchy (`titleMedium`, `bodyMedium`, `bodySmall`).
- Preservation of commercial version `1.0` and contact email `skul9x@gmail.com`.

---

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt`:
   - Change `AboutAppInfo.APP_NAME` to `"EV+"`.
   - Change `AboutAppInfo.COPYRIGHT` to `"© 2026 Nguyễn Duy Trường"`.
   - In `AboutAppCard`, update the Author surface row:
     - Primary title text: `AboutAppInfo.AUTHOR` (`"Nguyễn Duy Trường"`).
     - Subtitle text: `"Tác giả"`.
2. Create test file `app/src/test/java/com/evcs/favorites/ui/screens/EVPlusCommercialBrandingTest.kt` to verify:
   - `AboutAppInfo.APP_NAME` equals `"EV+"`.
   - `AboutAppInfo.COPYRIGHT` equals `"© 2026 Nguyễn Duy Trường"`.
   - `AboutAppInfo.AUTHOR` equals `"Nguyễn Duy Trường"`.
   - `AboutAppCard.kt` contains primary author display and `"Tác giả"` subtitle.

---

## Files to Create / Modify
- `app/src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt` - [MODIFY]
- `app/src/test/java/com/evcs/favorites/ui/screens/EVPlusCommercialBrandingTest.kt` - [NEW] (Phase 02 Single Verification Test)

---

## Verification Test
- **Test File:** `app/src/test/java/com/evcs/favorites/ui/screens/EVPlusCommercialBrandingTest.kt`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.EVPlusCommercialBrandingTest"
  ```
