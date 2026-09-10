# Phase 03: Settings Screen Layout & Canvas Copyright Placement

**Status:** ✅ Completed  
**Target File:** `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt`

---

## Objective
Remove the copyright footer text located beneath the "Đặt lại mặc định" button in the left sidebar. Position the official `© 2026 Nguyễn Duy Trường` copyright text on the dark screen canvas (`MaterialTheme.colorScheme.background`) beneath `AboutAppCard` in the "Thông tin ứng dụng" tab. Also align the portrait layout footer and update legacy contract tests.

---

## Requirements

### Functional
1. **Remove Sidebar Footer Text:**
   - In `SettingsScreenLandscape`, remove `SettingsCopyrightFooter()` from underneath the "Đặt lại mặc định" button in the master sidebar.
2. **Display Copyright on Dark Canvas in About Tab:**
   - In `SettingsScreenLandscape` when `selectedCategory == SettingsCategory.ABOUT`, render `AboutAppCard()` followed by a spacer and a centered copyright text:
     ```
     © 2026 Nguyễn Duy Trường
     ```
   - This text must reside directly on the right detail container's dark canvas (`MaterialTheme.colorScheme.background`), completely outside the navy `AboutAppCard`.
3. **Portrait Layout Alignment:**
   - In `SettingsScreenPortrait`, update the copyright text at the bottom to display `© 2026 Nguyễn Duy Trường`.
4. **Update Legacy Test Assertions:**
   - Update `CommercialBrandingAndCopyrightContractTest.kt` and `SettingsScreenComponentsAndAutoSaveTest.kt` so they conform to the new sidebar cleanup and canvas placement design contract.

### Non-Functional
- Copyright text styled with `MaterialTheme.typography.labelSmall` and `MaterialTheme.colorScheme.onSurfaceVariant`.
- Centered alignment and clean margins.

---

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt`:
   - In `SettingsScreenLandscape`, remove the `SettingsCopyrightFooter()` invocation from the sidebar footer column.
   - In the `SettingsCategory.ABOUT` branch, after `AboutAppCard()`, add:
     ```kotlin
     Spacer(modifier = Modifier.height(16.dp))
     Text(
         text = AboutAppInfo.COPYRIGHT,
         style = MaterialTheme.typography.labelSmall,
         color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
         textAlign = TextAlign.Center,
         modifier = Modifier.fillMaxWidth()
     )
     ```
   - In `SettingsCopyrightFooter()`, update text to `AboutAppInfo.COPYRIGHT` (`© 2026 Nguyễn Duy Trường`).
2. Update legacy tests in:
   - `app/src/test/java/com/evcs/favorites/ui/screens/CommercialBrandingAndCopyrightContractTest.kt`
   - `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenComponentsAndAutoSaveTest.kt`
3. Create test file `app/src/test/java/com/evcs/favorites/ui/screens/SettingsCopyrightCanvasPlacementTest.kt` to verify:
   - Sidebar in `SettingsScreenLandscape` does NOT contain copyright text below "Đặt lại mặc định".
   - `SettingsCategory.ABOUT` branch renders copyright text outside `AboutAppCard`.
   - Portrait layout displays `AboutAppInfo.COPYRIGHT`.

---

## Files to Create / Modify
- `app/src/main/java/com/evcs/favorites/ui/screens/SettingsScreen.kt` - [MODIFY]
- `app/src/test/java/com/evcs/favorites/ui/screens/CommercialBrandingAndCopyrightContractTest.kt` - [MODIFY]
- `app/src/test/java/com/evcs/favorites/ui/screens/SettingsScreenComponentsAndAutoSaveTest.kt` - [MODIFY]
- `app/src/test/java/com/evcs/favorites/ui/screens/SettingsCopyrightCanvasPlacementTest.kt` - [NEW] (Phase 03 Single Verification Test)

---

## Verification Test
- **Test File:** `app/src/test/java/com/evcs/favorites/ui/screens/SettingsCopyrightCanvasPlacementTest.kt`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest --tests "com.evcs.favorites.ui.screens.SettingsCopyrightCanvasPlacementTest"
  ```
