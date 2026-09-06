# Phase 02: Station Detail View & Card Interaction
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Enable card clicking on favorite stations to open an in-app interactive **Station Detail View** (via Jetpack Compose ModalBottomSheet with authenticated web engine or native detail viewer), displaying real-time port telemetry, charging density charts (24h / 7d / 30d), peak stats, and station gallery, matching the official EVCS experience.

## Requirements
### Functional
- Add click listener to each station card in `StationCard.kt` (`onStationClick: (Station) -> Unit`).
- Implement `StationDetailModal` Composable using Material 3 `ModalBottomSheet`.
- Construct the correct canonical station URL:
  `slugify(name).startsWith("vinfast") ? "https://evcs.vn/tram-sac-${slug}-${locationId.lowercase()}.html" : "https://evcs.vn/tram-sac-${slug}-c.${locationId}.html"`.
- Inject current authenticated session cookies (`PHPSESSID`, `evcs`) so user has seamless access to station details and historical charts.
- Support smooth gestures (drag down to dismiss, close 'X' button).

### Non-Functional
- Prevent gesture conflicts between ModalBottomSheet drag gestures and internal scrollable content.
- Ensure loading state indicator while the detail content is loading.

## Implementation Steps
1. Create `StationDetailModal.kt` Composable with `ModalBottomSheet`, close header, and an optimized `AndroidView` WebView client with cookie injection.
2. Update `StationCard.kt` to make the card clickable and trigger `onStationClick`.
3. Add `selectedStationForDetail: Station?` state in `FavoritesUiState` / `FavoritesViewModel`.
4. Wire `onStationClick` and `StationDetailModal` in `FavoritesScreen.kt` and `MainActivity.kt`.
5. Create comprehensive unit test `StationDetailModalTest.kt` verifying URL generation, slug normalization, cookie injection parameters, and selection state handling.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/ui/components/StationDetailModal.kt` - [New] Interactive station detail sheet
- `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` - [Modify] Add onStationClick handler
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - [Modify] Host detail modal sheet
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [Modify] Selection state management
- `app/src/main/java/com/evcs/favorites/util/StationUrlBuilder.kt` - [New] URL generation utility for detail endpoints
- `app/src/test/java/com/evcs/favorites/StationDetailModalTest.kt` - [New] Core verification test

## Test Criteria
- Verify that `StationUrlBuilder` correctly generates canonical station detail URLs for both VinFast stations and partner stations matching official slug specifications.
- Verify `FavoritesViewModel` properly transitions through selecting and dismissing station details.
- Verify cookie injection logic constructs the exact cookie header string required by the station detail endpoint.

---
Next Phase: [Phase 03: Real-Time Enrichment for Favorites](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-station-detail-and-live-ports/phase-03-realtime-enrichment-for-favorites.md)
