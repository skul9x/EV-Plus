# Phase 04: Local-First Firestore Favorites Sync & Profile UI Integration

Status: 🟩 Completed
Dependencies: Phase 03

## Objective
Migrate EV-Plus favorites persistence entirely away from the legacy EVCS third-party PHP endpoints (`POST /favorite.html`, OTP email, cookie expiration, 10-station limit) to a robust Local-First Cloud Firestore synchronization architecture. Store favorites under `/users/{userId}/userdata/favorites` with atomic `FieldPath.of("favorites", stationId)` Map operations (safe for IDs containing dots like `C.BNI0012`), full local metadata caching for instant 0ms offline access, a deterministic login sync pipeline with 3 conflict resolution strategies (Merge, Prefer Cloud, Prefer Local), unified two-way reactivity across both `FavoritesViewModel` and `NearbyViewModel`, backward-compatible delegation in `EvcsRepository` to preserve existing unit tests, and a modern Account/Profile header in `FavoritesScreen`.

## Requirements

### Functional
- [x] Add modern `com.google.firebase:firebase-firestore` (non-KTX) to [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts).
- [x] Implement `FirestoreFavoritesDataSource`:
  - Target document: `/users/{userId}/userdata/favorites`.
  - Single-document Free Tier optimization: 1 read on load, 1 write on sync.
  - Document structure (supporting both rich station metadata and scalar timestamps for maximum resilience):
    ```json
    {
      "favorites": {
        "C.BNI0012": {
          "added_at": 1788597858,
          "name": "VinFast - Vincom Plaza Bắc Ninh",
          "address": "Ngã 6, P. Suối Hoa, TP. Bắc Ninh",
          "lat": 21.1843,
          "lon": 106.0741,
          "summary": "✧ 60kW có 2 cổng...",
          "connectors": "60kW 30kW",
          "images": ["https://cpo-prod-s3.vinfastauto.com/..."]
        },
        "C.SGN0001": 1788600120
      },
      "updated_at": 1788600500
    }
    ```
  - Dual parsing: If an entry value is a `Map`, parse full station metadata; if a scalar `Number`, treat as `added_at` timestamp and enrich via local cache/search.
  - Atomic CRUD via `FieldPath` to prevent dot interpretation in station IDs:
    - Add/Update: `document.set(mapOf("favorites" to mapOf(stationId to stationData), "updated_at" to timestamp), SetOptions.merge())` or `document.update(FieldPath.of("favorites", stationId), stationData, FieldPath.of("updated_at"), timestamp)`.
    - Remove: `document.update(FieldPath.of("favorites", stationId), FieldValue.delete(), FieldPath.of("updated_at"), timestamp)`.
- [x] Implement `FirestoreFavoritesRepository`:
  - Local-First storage layer ($L$) persisting full `Station` metadata snapshots (including `images` and `addedAt`) via `PlainSharedPrefsStorage` for instant 0ms offline availability.
  - Expose `val favoritesState: StateFlow<List<Station>>` and `val favoriteIdsState: StateFlow<Set<String>>`.
  - Login Sync Decision Matrix (adhering to [thuattoan.txt](file:///home/skul9x/Desktop/Code/EV-Plus-main/thuattoan.txt) and [docs/BRIEF.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/docs/BRIEF.md)):
    - **Branch 1 ($L = \emptyset, C = \emptyset$):** No action required.
    - **Branch 2 ($L \neq \emptyset, C = \emptyset$):** Auto-upload $L \rightarrow C$.
    - **Branch 3 ($L = \emptyset, C \neq \emptyset$):** Auto-download $C \rightarrow L$ (instantly restores full station metadata to local cache).
    - **Branch 4 ($L \neq \emptyset, C \neq \emptyset$):** If `L.keys == C.keys`, in sync; if conflicting, trigger 3 conflict resolution strategies:
      - **Merge (Recommended Default):** Union of all stations ($L \cup C$), keeping earliest timestamp `min(L.addedAt, C.addedAt)`. Write union to both local cache and Firestore.
      - **Prefer Cloud:** Overwrite local cache with cloud data ($L \leftarrow C$).
      - **Prefer Local:** Overwrite cloud document with local data ($C \leftarrow L$).
  - Runtime CRUD: Optimistic local update (0ms UI feedback) followed by asynchronous cloud update when authenticated.
- [x] Cross-Tab & App Architecture Integration:
  - Update [MainActivity.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/MainActivity.kt):
    - In `AppTab.FAVORITES`, render `FavoritesScreen` directly in Local-First mode regardless of login status (eliminate the full-page Email OTP `LoginScreen` gate).
    - Provide unified favorites repository to both `FavoritesViewModel` and `NearbyViewModel`.
  - Update [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt):
    - Ensure favorite toggles on Nearby screen immediately dispatch to `FirestoreFavoritesRepository`, keeping heart indicators synchronized across both tabs without email login barriers.
  - Update [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt):
    - Render [FavoritesProfileHeader.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/FavoritesProfileHeader.kt) at the top of the favorites list:
      - **Guest mode:** Banner prompting *"Đăng nhập để đồng bộ đám mây"* with 1-Tap Google Sign-In button.
      - **Authenticated mode:** Display Google avatar, account name/email, sync status indicator ("Đã đồng bộ"), and *"Đăng xuất"* button.
    - Remove legacy OTP dialogs, cookie warning alerts, and the artificial 10-station limit.
  - Maintain backward compatibility in [EvcsRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt):
    - Delegate `addFavoriteStation`, `removeFavoriteStation`, `favoritesState`, and `favoriteIdsState` to `FirestoreFavoritesRepository`, ensuring that all existing repository tests (`NearbyFilteringAndFavoriteSyncTest.kt`, `CloudSyncRollbackSafetyTest.kt`, etc.) continue to compile and pass without regressions.

### Non-Functional
- [x] Instant 0ms display on tab navigation from local cache before cloud response returns.
- [x] Offline resilience: Adding/removing favorites offline retains local state without crashing.
- [x] Free Tier cost safety: Avoid excessive reads/writes through single document aggregation and local caching.
- [x] Full JVM testability: Provide fake data source for Firestore testing without live Firebase connection.

## Implementation Steps
1. [x] Add `implementation("com.google.firebase:firebase-firestore")` to [app/build.gradle.kts](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/build.gradle.kts).
2. [x] Create [FirestoreFavoritesDataSource.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesDataSource.kt) with Firestore document read/write and `FieldPath.of("favorites", stationId)` Map updates.
3. [x] Create [FirestoreFavoritesRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt):
   - Local-First storage layer.
   - Login Sync Decision Matrix & 3 Conflict Resolution Strategies.
   - Expose `favoritesState: StateFlow<List<Station>>` and `favoriteIdsState: StateFlow<Set<String>>`.
4. [x] Build [FavoritesProfileHeader.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/FavoritesProfileHeader.kt) composable with guest CTA and authenticated profile state.
5. [x] Integrate `FirestoreFavoritesRepository` into [MainActivity.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/MainActivity.kt), [FavoritesViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt), [NearbyViewModel.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt), [FavoritesScreen.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt), and delegate in [EvcsRepository.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt).
6. [x] Create exactly one comprehensive file-based test: [LocalFirstFirestoreFavoritesSyncTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/data/repository/LocalFirstFirestoreFavoritesSyncTest.kt).
7. [x] Run only this single test to verify Phase 04 completion: `./gradlew testDebugUnitTest --tests com.evcs.favorites.data.repository.LocalFirstFirestoreFavoritesSyncTest`.

## Files to Create/Modify
- `app/build.gradle.kts` - Add Firebase Firestore dependency.
- `app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesDataSource.kt` - [NEW] Firestore document wrapper with `FieldPath` Map updates.
- `app/src/main/java/com/evcs/favorites/data/repository/FirestoreFavoritesRepository.kt` - [NEW] Local-First repo with sync decision matrix, conflict algorithms, and StateFlows.
- `app/src/main/java/com/evcs/favorites/ui/components/FavoritesProfileHeader.kt` - [NEW] Account banner, Google avatar, and 1-Tap Google Sign-In trigger.
- `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` - Render profile header and bind Firestore favorites flow.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - Connect to Firestore repository and Auth state.
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt` - Sync favorite toggle and IDs with Firestore repository.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - Delegate favorites operations to Firestore repository for backward compatibility.
- `app/src/main/java/com/evcs/favorites/MainActivity.kt` - Local-First routing for Favorites tab without Email OTP gate.
- `app/src/test/java/com/evcs/favorites/data/repository/LocalFirstFirestoreFavoritesSyncTest.kt` - [NEW] Single comprehensive test verifying local-first offline CRUD, `FieldPath` Map serialization, login sync decision matrix (branches 1-4), 3 conflict resolution strategies, cross-tab state synchronization, and flow emissions on JVM.

## Test Criteria
- [x] Single comprehensive test `LocalFirstFirestoreFavoritesSyncTest.kt` PASSES:
  - Verifies local cache delivers immediate 0ms favorites without network access.
  - Verifies Map structure serialization (`favorites.<stationId>`: metadata/timestamp) via `FieldPath` and atomic delete mutations.
  - Verifies login sync branch 2 (auto-upload), branch 3 (auto-download), and branch 4 (conflict detection).
  - Verifies Conflict Strategy 1 (Merge with earliest timestamp), Strategy 2 (Prefer Cloud), and Strategy 3 (Prefer Local).
  - Verifies optimistic UI updates immediately reflect toggles before cloud acknowledgment.
  - Verifies `favoriteIdsState` emits updates visible to both Favorites and Nearby subscribers.
  - Verifies backward-compatible delegation through `EvcsRepository` does not break existing test suites.

---
End of Plan.


