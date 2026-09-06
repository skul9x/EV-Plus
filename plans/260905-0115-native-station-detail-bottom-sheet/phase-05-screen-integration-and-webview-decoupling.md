# Phase 05: Screen Integration & Legacy WebView Decoupling
Status: 🟢 Completed  
Dependencies: Phase 04

## Objective
Integrate `NativeStationDetailSheet` across `MainActivity`, `FavoritesScreen`, and `NearbyScreen`, and decommission the legacy WebView modal (`StationDetailModal.kt`), CSS injection rules, and related web lifecycle cleanup code. Permanently resolve `PERF-MEM-02` (WebView memory leaks).

## Requirements
### Functional
- [x] Connect `NativeStationDetailSheet` in `MainActivity.kt`, `FavoritesScreen.kt`, and `NearbyScreen.kt`:
  - Observe `stationDetailState` from `FavoritesViewModel` / `NearbyViewModel`.
  - Display `NativeStationDetailSheet` whenever `stationDetailState.station != null`.
  - Pass callbacks: `onDismiss`, `onNavigate`, `onToggleFavorite`, `onShare`, `onRefresh`.
- [x] Update `FavoritesScreen.kt` and `NearbyScreen.kt`:
  - Wire station card click events directly to `selectStationForDetail(station)`.
  - Ensure zero residual WebView instances or memory leaks.
- [x] Safely remove legacy WebView modal and clean dependencies:
  - Remove `StationDetailModal.kt` and `FORECAST_OVERLAP_FIX_CSS`.
  - Update legacy unit tests (`StationDetailModalTest.kt`, `StationDetailModalOnDemandForecastTest.kt`, `StationCardForecastRemovalTest.kt`) to retire WebView assertions and validate native bottom sheet contracts.
  - Formally close `PERF-MEM-02` (15-45MB RAM leak per station view) and `ANDROID-LOGIC-012`.
- [x] Validate end-to-end regression across both tabs (Favorites and Nearby).

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/ui/NativeStationDetailIntegrationTest.kt`
- **Verifications**:
  1. Full integration flow: Selecting a station card opens native bottom sheet with populated data and zero delay.
  2. Clicking "Chỉ đường" triggers Google Maps navigation intent correctly.
  3. Clicking "Yêu thích" updates favorite state in repository and cloud sync.
  4. Dismissing the bottom sheet cleanly clears state and tears down all jobs without leaks.
  5. Zero WebView references or AndroidView components remaining in station detail presentation.

---
Next Phase: Complete!

