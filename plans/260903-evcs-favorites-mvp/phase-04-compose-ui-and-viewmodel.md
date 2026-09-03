# Phase 04: Jetpack Compose UI & ViewModel

Status: ✅ Completed  
Dependencies: Phase 03  

---

## 1. Objective
Build a clean, minimalist, high-performance Jetpack Compose user interface conforming to Material Design 3 guidelines, and implement `FavoritesViewModel` with reactive state management (`StateFlow`) to drive both the Login flow and the Favorites list view.

---

## 2. Requirements

### Functional
- `FavoritesViewModel`:
  - Maintains `FavoritesUiState` sealed interface (`LoggedOut`, `RequestingOtp`, `VerifyingOtp`, `Loading`, `Success(stations: List<Station>)`, `Error(message: String)`).
  - Handles `requestOtp(email)`, `verifyOtp(otpCode)`, `fetchFavorites()`, `refresh()`, and `logout()`.
- Material 3 Theme (`Theme.kt`, `Color.kt`): Sleek modern dark & light modes tailored for EV drivers (emerald green primary accents).
- Compose Components:
  - `LoginDialog / LoginScreen`: Email text field, OTP numeric input, animated progress indicator.
  - `FavoritesScreen`: Clean TopAppBar with sync status, pull-to-refresh container, empty state illustration.
  - `StationCard`:
    - Station name and status badge (Available / Maintaining).
    - Distance pill (e.g., "⚡ 1.4 km" / "⚡ 850 m").
    - Real-time connector availability badges (e.g., "✧ 120kW: trống 1/4 • 60kW: trống 1/2" with color coding for available slots).
    - Address, connectors list, and working hours (e.g., "Mở 24/7 • Gửi xe tính phí").
    - Primary "Chỉ đường" (1-Tap Navigate) button and "Xóa yêu thích" option.

### Non-Functional
- Instant render with zero stutter (60/120 FPS).
- Responsive layout supporting both phone portrait and tablet/landscape view.

---

## 3. Implementation Steps
1. Create Material 3 color palette, typography, and theme definitions.
2. Implement `FavoritesUiState` and `FavoritesViewModel`.
3. Build Compose components: `StationCard`, `WattageChip`, `DistanceBadge`.
4. Build screens: `LoginContent` and `FavoritesListContent`.
5. Implement the single comprehensive test file: `src/test/java/com/evcs/favorites/FavoritesViewModelTest.kt`.

---

## 4. Files to Create / Modify
- `app/src/main/java/com/evcs/favorites/ui/theme/Theme.kt`: M3 Theme definitions.
- `app/src/main/java/com/evcs/favorites/ui/theme/Color.kt`: EV color palette.
- `app/src/main/java/com/evcs/favorites/ui/state/FavoritesUiState.kt`: Sealed UI states.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`: Core business logic & state holder.
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt`: Individual station card composable.
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt`: Main screen composable.
- `app/src/main/java/com/evcs/favorites/ui/screens/LoginScreen.kt`: Email OTP login composable.
- `app/src/test/java/com/evcs/favorites/FavoritesViewModelTest.kt`: Single verification test for this phase.

---

## 5. Single Verification Test
- **File:** `app/src/test/java/com/evcs/favorites/FavoritesViewModelTest.kt`
- **Scope:**
  - Validates initial state and transition to `RequestingOtp` and `Success` upon login.
  - Validates `fetchFavorites` data loading, error handling, and state emission to Compose UI.
  - Validates user logout resetting session state back to `LoggedOut`.

---
Next Phase: [phase-05-app-integration-and-navigation.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-evcs-favorites-mvp/phase-05-app-integration-and-navigation.md)
