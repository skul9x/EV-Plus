# Phase 01: Domain Models, Wattage Filter Engine & Cloud Favorites Sync API
Status: ✅ Completed
Dependencies: None

## Objective
Establish the core data structures and pure business logic for filtering VinFast charging stations by wattage rating and active port availability, sorting and extracting the Top 10 nearest stations via Haversine distance, and enabling reactive two-way cloud synchronization to save and remove favorites against the EVCS backend API.

## Requirements

### Functional
- **Wattage Filter Modeling (`WattageOption.kt`)**:
  - Model the 14 supported EV power tiers:
    - Ultra-fast DC: `KW_360` (360,000W, "360kW"), `KW_300` (300,000W, "300kW"), `KW_250` (250,000W, "250kW"), `KW_180` (180,000W, "180kW"), `KW_150` (150,000W, "150kW"), `KW_120` (120,000W, "120kW").
    - Fast DC: `KW_80` (80,000W, "80kW"), `KW_60` (60,000W, "60kW"), `KW_40` (40,000W, "40kW"), `KW_30` (30,000W, "30kW"), `KW_20` (20,000W, "20kW").
    - AC Slow / Destination: `KW_22` (22,000W, "22kW"), `KW_11` (11,000W, "11kW"), `KW_7` (7,000W, "7kW"), `KW_3_5` (3,500W, "3.5kW").
  - Provide mapping helpers: `matchesWattage(typeWatts: Long): Boolean`.
- **Pure Filter & Sorter Engine (`NearbyStationFilter.kt`)**:
  - `filterStations(stations: List<Station>, selectedWattages: Set<WattageOption>): List<Station>`:
    - **Depot Status Check**: Exclude stations where `depotStatus.equals("Maintaining", ignoreCase = true)` or `depotStatus.equals("OutOfService", ignoreCase = true)`.
    - **Wattage & Port Availability Rule**:
      - If `selectedWattages.isEmpty()`: Station must have at least one active port available (`totalAvailablePlugs > 0`). Stations with 0 available ports are excluded.
      - If `selectedWattages.isNotEmpty()`: Station must have at least one connector matching any of the selected `WattageOption` tiers (`OR` condition) **AND** that matching connector must have `availablePlugs > 0`.
  - `extractTopNearest(userLat: Double, userLon: Double, stations: List<Station>, limit: Int = 10): List<Station>`:
    - Computes great-circle Haversine distance in km from `(userLat, userLon)` to each station and assigns `station.distanceKm`.
    - Sorts stations in ascending order of `distanceKm`.
    - Returns strictly the first `limit` (default 10) elements.
- **EVCS Cloud Favorites Sync API (`EvcsApiClient.kt` & `EvcsRepository.kt`)**:
  - Define `@Serializable data class SaveFavoritesRequest(val action: String = "save", val csrf: String, val stations: List<FavoriteStationRaw>)`.
  - In `EvcsApiClient.kt`:
    - `saveFavorites(stations: List<FavoriteStationRaw>, csrf: String): Result<Boolean>`: Sends `POST $baseUrl/favorite.html` with authenticated cookie header (`SessionManager.getCookieHeader()`), Content-Type `application/json`, and `SaveFavoritesRequest` body.
  - In `EvcsRepository.kt`:
    - Expose `val favoritesState: StateFlow<List<Station>>` and `val favoriteIdsState: StateFlow<Set<String>>`.
    - `addFavoriteStation(station: Station): Result<Unit>`: Appends station to the in-memory list, updates persistent cache, and dispatches cloud sync via `apiClient.saveFavorites`.
    - `removeFavoriteStation(locationId: String): Result<Unit>`: Removes station from in-memory list, updates persistent cache, and dispatches cloud sync.
    - Transform helper: `SearchStationRaw.toDomainStation(userLat: Double? = null, userLon: Double? = null): Station`.

### Non-Functional
- Pure business logic in `NearbyStationFilter` must be 100% JVM-testable without Android framework dependencies.
- Strict cookie and CSRF token propagation ensures compatibility with Cloudflare and PHP session management.

## Implementation Steps
1. Create `WattageOption.kt` in `com.evcs.favorites.domain.model` with power ratings and wattage matcher.
2. Implement `NearbyStationFilter.kt` in `com.evcs.favorites.domain.filter` containing `filterStations` and `extractTopNearest`.
3. Add `SaveFavoritesRequest` and implement `saveFavorites` in `EvcsApiClient.kt`.
4. Update `EvcsRepository.kt` with `favoritesState`, `favoriteIdsState`, `addFavoriteStation`, and `removeFavoriteStation`.
5. Create comprehensive unit test `NearbyFilteringAndFavoriteSyncTest.kt` verifying:
   - OR filtering with multiple wattages.
   - Exclusion of maintaining stations and stations with 0 available ports for selected wattages.
   - Truncation to Top 10 sorted by Haversine distance.
   - MockWebServer verification of `POST /favorite.html` payload and headers.

## Files to Create/Modify
- [NEW] `app/src/main/java/com/evcs/favorites/domain/model/WattageOption.kt` - Wattage rating enum & mappings.
- [NEW] `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt` - Pure station filtering and Top 10 selection engine.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` - Add `SaveFavoritesRequest`.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt` - Add `saveFavorites` method.
- [MODIFY] `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Add reactive favorite flows and toggle methods.
- [NEW] `app/src/test/java/com/evcs/favorites/NearbyFilteringAndFavoriteSyncTest.kt` - Single comprehensive test for Phase 01.

## Test Criteria
- `NearbyFilteringAndFavoriteSyncTest.kt`:
  - Verify OR filtering across multiple selected wattages (e.g. 250kW or 180kW).
  - Verify exclusion of stations where matching wattage has 0 available ports or is maintaining.
  - Verify top 10 truncation from a list of 25+ stations correctly orders by nearest Haversine distance.
  - Verify `saveFavorites` produces correct JSON body with `action: save` and headers via `MockWebServer`.

---
Next Phase: [Phase 02: Top 10 Multi-Tier Routing Pipeline & Nearby ViewModel](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-nearby-charging-stations-and-multi-tier-routing/phase-02-nearby-viewmodel-and-top10-routing.md)
