# Phase 02: EVCS API & Favorites Repository

Status: ✅ Completed  
Dependencies: Phase 01  

---

## 1. Objective
Implement the EVCS Backend Client combining two complementary endpoints:
1. `POST https://evcs.vn/favorite.html`: Retrieves user's favorite station IDs and basic info (`locationId`, `name`, `address`, `summary`, `connectors`).
2. `POST https://evcs.vn/search?t=eepe5dp9zpipl102`: Native background API signed with HMAC-SHA256 secret (`"I3mrBi6aytR0q3P1o01O3HJIUU0P3YYdFzA9n1psJoht5ZOvn5FYE49pOalRGpk2vaplKyOM57LDwBkjtjts0X"`) to enrich favorites with exact GPS coordinates (`latitude`, `longitude`) and real-time plug availability (`evsePowers`).

---

## 2. Requirements

### Functional
- Implement `EvcsHmacSigner`:
  - Secret key: `"I3mrBi6aytR0q3P1o01O3HJIUU0P3YYdFzA9n1psJoht5ZOvn5FYE49pOalRGpk2vaplKyOM57LDwBkjtjts0X"`.
  - Computes `HmacSHA256(body + timestamp)` and outputs lowercase hex string.
  - Adds required headers: `User-Agent: EVCS/A1.57`, `X-App-Timestamp`, `X-App-Signature`, `Referer: https://evcs.vn/`, `Origin: https://evcs.vn`.
- Implement API data models:
  - `FavoriteStationRaw`: `locationId`, `name`, `address`, `summary`, `connectors`, `image`.
  - `FavoritesResponse`: `sync: Boolean`, `csrf: String`, `server: List<FavoriteStationRaw>?`.
  - `SearchStationRaw`: `locationId`, `stationName`, `stationAddress`, `latitude`, `longitude`, `depotStatus`, `evsePowers: List<EvsePowerRaw>`.
  - `EvsePowerRaw`: `type: Long` (e.g. 60000 = 60kW), `numberOfAvailableEvse: Int`, `totalEvse: Int`.
  - `Station` (Domain Model):
    - `id: String`, `name: String`, `address: String`, `latitude: Double`, `longitude: Double`
    - `summary: String`, `connectors: String`, `depotStatus: String`
    - `powers: List<PowerPort>` (e.g., "60kW: trống 1/2 cổng")
    - `totalAvailablePlugs: Int`, `totalPlugs: Int`
- Implement `EvcsRepository`:
  - `getFavorites(userLat: Double?, userLon: Double?)`:
    1. Calls `POST /favorite.html` with `X-Partial: fav` and auth cookies (`PHPSESSID=...; evcs=...`).
    2. Calls `POST /search?t=eepe5dp9zpipl102` with HMAC headers centered around user coordinates (or default center) to fetch real-time station metrics.
    3. Merges favorite IDs with search details, matching by `locationId`.
    4. Fallback for stations outside search radius: extract coordinates from station detail cache or URL `/tram-sac-...-{locationId}.html`.

### Non-Functional
- Graceful degradation: If HMAC search fails, favorites still display name and connectors (distance is computed once coordinates are resolved).
- Robust error handling for network timeouts and invalid responses.

---

## 3. Implementation Steps
1. Create `EvcsHmacSigner` utility implementing HMAC-SHA256 signing matching decompiled `y/a.java`.
2. Define Kotlinx Serialization data classes for favorites, search responses, and domain `Station`.
3. Implement `EvcsApiClient` with OkHttp interceptors for cookies and HMAC headers.
4. Implement `EvcsRepository` with data merging and coordinate resolution logic.
5. Implement the single comprehensive test file: `src/test/java/com/evcs/favorites/EvcsRepositoryTest.kt`.

---

## 4. Files to Create / Modify
- `app/src/main/java/com/evcs/favorites/data/crypto/EvcsHmacSigner.kt`: HMAC-SHA256 signer.
- `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt`: API and Domain data models.
- `app/src/main/java/com/evcs/favorites/data/api/EvcsApiClient.kt`: OkHttp client with dual endpoints (`/favorite.html` and `/search`).
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`: Repository coordinating network fetch, merging, and caching.
- `app/src/test/java/com/evcs/favorites/EvcsRepositoryTest.kt`: Single verification test for this phase.

---

## 5. Single Verification Test
- **File:** `app/src/test/java/com/evcs/favorites/EvcsRepositoryTest.kt`
- **Scope:**
  - Validates HMAC-SHA256 signing against verified test vectors (verified against live EVCS backend).
  - Validates JSON deserialization of `/favorite.html` partial response and `/search` response.
  - Validates merging logic matching `locationId` across favorite items and search stations to construct complete domain `Station` objects with coordinates and plug counts.

---
Next Phase: [phase-03-location-and-distance-service.md](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-evcs-favorites-mvp/phase-03-location-and-distance-service.md)
