# Phase 07: APK Bloat Reduction & Baseline Profiles Optimization
Status: ✅ Completed  
Dependencies: `phase-06-filter-routing-debounce.md`  
Issue IDs: `PERF-BUILD-02`, `PERF-START-01`

## Objective
Shrink final application binary size and optimize cold startup time with Ahead-Of-Time (AOT) interpretation profiles:
1. **PERF-BUILD-02**: Remove the monolithic `androidx.compose.material:material-icons-extended` dependency (which pulls thousands of unused icons and causes 15–25MB of debug DEX overhead). Consolidate all ~25 extended icons currently used across the codebase into a centralized, lightweight `AppIcons.kt` object, while retaining `androidx.compose.material:material-icons-core` for standard core icons.
2. **PERF-START-01**: Integrate `androidx.profileinstaller:profileinstaller` in `app/build.gradle.kts` and create `app/src/main/baselineProfiles/baseline-prof.txt` (AGP 8.2+ standard) along with `app/src/main/baseline-prof.txt` with critical user journey (CUJ) AOT rules for MainActivity, Jetpack Compose runtime, and Favorites/Nearby screens.

## Requirements
### Functional
- All UI screens and components (Favorites, Nearby, Login, StationDetailModal, RoutingSettingsModal, GoogleApiKeyGuideModal, CustomFilterSettingsCard, CustomConfigPromptDialog, LoginRequiredDialog, DebugLogViewerCard, AppNavigationBar) render their respective icons accurately without visual degradation.
- Release builds package the baseline profile rules for runtime ART compilation.

### Non-Functional
- Remove `material-icons-extended` dependency completely from `app/build.gradle.kts`.
- Include `androidx.compose.material:material-icons-core` and `androidx.profileinstaller:profileinstaller:1.3.1` runtime dependencies.
- Include valid `baseline-prof.txt` containing package rules for `com.evcs.favorites.*` and core Jetpack Compose layouts.

## Implementation Steps
1. [x] Create `app/src/main/java/com/evcs/favorites/ui/theme/AppIcons.kt`:
   - Define lightweight custom `ImageVector` definitions (or vector drawables) for all 25 extended icons used in the app:
     - Core UI: `AccessTime`, `AccountCircle`, `Bolt`, `CheckCircle`, `CloudOff`, `ContentCopy`, `DeleteOutline`, `DirectionsCar`
     - Status & Error: `Error`, `ErrorOutline`, `EvStation`, `ExpandLess`, `ExpandMore`, `FilterListOff`
     - Action & Navigation: `Key`, `Lightbulb`, `Logout`, `Navigation`, `NearMe`, `OpenInNew`, `Save`, `Security`, `Settings`, `Terminal`, `Tune`
     - Outlined navigation: `FavoriteBorder`, `LocationOn`
2. [x] In UI files using extended icons, update imports to use `AppIcons` or core icons:
   - `app/src/main/java/com/evcs/favorites/navigation/AppTab.kt`
   - `app/src/main/java/com/evcs/favorites/ui/screens/LoginScreen.kt`
   - `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt`
   - `app/src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/GoogleApiKeyGuideModal.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/CustomConfigPromptDialog.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/LoginRequiredDialog.kt`
   - `app/src/main/java/com/evcs/favorites/ui/components/DebugLogViewerCard.kt`
   - `app/src/main/java/com/evcs/favorites/MainActivity.kt`
3. [x] In `app/build.gradle.kts`:
   - Remove `implementation("androidx.compose.material:material-icons-extended")`.
   - Add `implementation("androidx.compose.material:material-icons-core")`.
   - Add `implementation("androidx.profileinstaller:profileinstaller:1.3.1")`.
4. [x] Create baseline profile files at `app/src/main/baselineProfiles/baseline-prof.txt` and `app/src/main/baseline-prof.txt`:
   - Define baseline profile AOT compilation rules for startup and Compose navigation paths:
     ```
     HSPLcom/evcs/favorites/MainActivity;-><init>()V
     HSPLcom/evcs/favorites/MainActivity;->onCreate(Landroid/os/Bundle;)V
     HSPLcom/evcs/favorites/ui/screens/FavoritesScreenKt;->*
     HSPLcom/evcs/favorites/ui/screens/NearbyScreenKt;->*
     HSPLcom/evcs/favorites/ui/components/StationCardKt;->*
     HSPLcom/evcs/favorites/domain/location/DistanceCalculator;->*
     ```
5. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/build/BuildOptimizationAndBaselineProfileTest.kt`:
   - Verifies `material-icons-extended` is absent from `build.gradle.kts`.
   - Verifies `material-icons-core` and `profileinstaller` are present in dependencies.
   - Verifies `baseline-prof.txt` exists, is non-empty, and contains valid ART syntax rules.
   - Verifies `AppIcons` resolves all 25 required icon vectors without runtime null/exceptions.
6. [x] Run single test:
   ```bash
   ./gradlew testDebugUnitTest --tests com.evcs.favorites.build.BuildOptimizationAndBaselineProfileTest
   ```

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/theme/AppIcons.kt` - [NEW] Centralized lightweight icon definitions for all 25 extended icons
- `app/build.gradle.kts` - [MODIFY] Remove extended icons, add core icons and profileinstaller
- `app/src/main/baselineProfiles/baseline-prof.txt` - [NEW] Baseline profile rules for AGP 8.2+
- `app/src/main/baseline-prof.txt` - [NEW] Baseline profile rules for legacy AGP compatibility
- All 13 affected Composable/UI files - [MODIFY] Update icon imports to AppIcons
- `app/src/test/java/com/evcs/favorites/build/BuildOptimizationAndBaselineProfileTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria
- [x] Build compiles cleanly without `material-icons-extended`.
- [x] Baseline profile rules file is syntactically valid and packaged.
- [x] All icon vectors load cleanly in all screens.
- [x] Exactly one test file is executed and passes cleanly.

---
End of Plan.
