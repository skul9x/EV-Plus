# Phase 02: DTO Mapping & EV Car Port Filtering

Status: ✅ Completed  
Dependencies: [Phase 01: VinFast CAPP API Client & Headers](./phase-01-vinfast-capp-api-client-and-headers.md)  
Reference Sources:
- `output_vinfast/sources/vn/vinfast/chargingkit/datasources/remote/transformers/RemoteStationTransformerKt.java`
- `app/src/main/java/com/evcs/favorites/util/VinFastCdnUrlDecoder.kt`
- `app/src/main/java/com/evcs/favorites/util/StationNameSanitizer.kt`
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt`

---

## 1. Objective

Transform raw station telemetry from both Tier-1 (`VinFastStationStatusDto`) and Tier-2 fallback into EV-Plus clean domain models (`Station`, `PowerPort`). 
Enforce a strict hard filter that eliminates 2-wheeler / motorbike charging plugs (3.5kW and 7kW AC, `type <= 7000`), drops pure-motorbike charging locations where no car ports exist, re-aggregates plug counts strictly from automobile bays, and integrates direct S3/CloudFront CDN image resolution.

---

## 2. Requirements

### Functional Requirements
1. **Automobile Port Hard-Filtering**:
   - Filter out all connectors where `type <= 7000` (specifically 3.5kW / 3500W and 7kW / 7000W motorbike AC plugs).
   - Retain only car charging standards:
     - 11kW AC (11,000W)
     - 20kW DC (20,000W)
     - 22kW AC (22,000W)
     - 30kW DC (30,000W)
     - 40kW DC (40,000W)
     - 60kW DC (60,000W)
     - 80kW DC (80,000W)
     - 120kW DC (120,000W)
     - 150kW DC (150,000W)
     - 180kW DC (180,000W)
     - 250kW DC (250,000W)
     - 300kW DC (300,000W)
     - 360kW DC (360,000W)
2. **Pure-Motorbike Station Elimination**:
   - If after filtering, a station has no car-compatible ports remaining (`carPowers.isEmpty()`), the station MUST be discarded from search results.
   - VinFast operates numerous motorbike-only showrooms and residential basements; dropping them prevents ghost cards with 0/0 plugs on driver screens.
3. **Accurate Car-Only Plug Count Re-Aggregation**:
   - Because top-level `numberOfAvailableEvse` and `totalEvse` from VinFast backend include motorbike plugs, the station's total bays and available bays must be re-calculated:
     - `totalAvailablePlugs = carPowers.sumOf { it.availablePlugs }`
     - `totalPlugs = carPowers.sumOf { it.totalPlugs }`
4. **Connector to PowerPort Transformation**:
   - Map raw connectors to `PowerPort`:
     - `typeWatts`: raw integer watts (`type.toLong()`).
     - `label`: formatted kW label (e.g. `11kW`, `60kW`, `120kW`, `250kW`).
     - `availablePlugs`: available bays (`count ?: 0`).
     - `totalPlugs`: total installed bays (`total ?: 0`).
     - `displayString`: localized string formatted as `"$label: trống $availablePlugs/$totalPlugs cổng"`.
5. **Direct S3 CDN Image Resolution**:
   - Tier-1 Images:
     - If URL is relative (e.g. `charging-station-car/depot/images/123.jpg`), prepend VinFast resource CDN: `https://cpo-prod-s3.vinfastauto.com/` or `https://d1aza9v8tzxrkt.cloudfront.net/`.
     - If absolute `https://`, retain directly.
   - Tier-2 Images:
     - Reuse existing `VinFastCdnUrlDecoder.decodeList(media)` to decode double-base64 tokens directly into `https://cpo-prod-s3.vinfastauto.com/...`.
6. **Data Contract Invariance**:
   - Map `locationId` (e.g. `C.HNO11417`) directly to `Station.id`.
   - Set `evse = "VinFast"`.
   - Apply `StationNameSanitizer.sanitize(stationName)` to remove trailing `(...)` noise.
   - Default `workingTimeDescription` to `"24/7"` if missing or null.
   - Calculate optional `distanceKm` using Haversine formula when user coordinate is provided.

### Non-Functional & Performance Requirements
- Pure mapping functions without side effects.
- In-place / allocation-conscious filtering to prevent GC pauses during high-density map clustering.

---

## 3. Implementation Steps

1. **Create VinFastStationMapper**:
   - Path: `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastStationMapper.kt`.
   - Methods:
     - `fun toDomainStation(dto: VinFastStationStatusDto, userLat: Double? = null, userLon: Double? = null): Station?` (returns `null` if no car-compatible ports).
     - `fun toDomainStations(dtos: List<VinFastStationStatusDto>, userLat: Double? = null, userLon: Double? = null): List<Station>`
     - `fun toDomainPowerPort(connector: VinFastConnectorDto): PowerPort?` (returns `null` when `type <= 7000`).
     - `fun resolveImageUrl(rawUrl: String?): String?`
     - `fun formatWattageLabel(typeWatts: Long): String`

---

## 4. Files to Create / Modify

- **New File**:
  - `app/src/main/java/com/evcs/favorites/data/network/vinfast/VinFastStationMapper.kt`
- **Test File**:
  - `app/src/test/java/com/evcs/favorites/VinFastStationMapperTest.kt`

---

## 5. File-Based Test Plan (`VinFastStationMapperTest.kt`)

Create `app/src/test/java/com/evcs/favorites/VinFastStationMapperTest.kt`:
1. `testCarPortFiltering_excludesMotorbikePlugsAndReaggregatesTotals()`:
   - Provide a station DTO containing:
     - Connector 1: type = 3500 (AC 3.5kW, count = 4, total = 8) -> Excluded
     - Connector 2: type = 7000 (AC 7kW, count = 2, total = 2) -> Excluded
     - Connector 3: type = 11000 (AC 11kW, count = 1, total = 2) -> Kept
     - Connector 4: type = 60000 (DC 60kW, count = 2, total = 4) -> Kept
     - Connector 5: type = 120000 (DC 120kW, count = 3, total = 6) -> Kept
     - Top-level raw availableEvse = 12, totalEvse = 22.
   - Invoke `VinFastStationMapper.toDomainStation(dto)`.
   - Assert `station != null`.
   - Assert `station.powers.size == 3` (only 11kW, 60kW, 120kW present).
   - Assert `station.powers.none { it.typeWatts <= 7000 }` is true.
   - Assert `station.totalPlugs == 12` (2 + 4 + 6, excluding 8 + 2 bike plugs).
   - Assert `station.totalAvailablePlugs == 6` (1 + 2 + 3, excluding 4 + 2 bike plugs).
2. `testPureMotorbikeStation_returnsNull()`:
   - Provide a station DTO containing only 3.5kW and 7kW plugs.
   - Invoke `VinFastStationMapper.toDomainStation(dto)`.
   - Assert `station == null`.
3. `testImageCdnResolution_HandlesRelativeAndAbsoluteUrls()`:
   - Input relative: `"charging-station-car/depot/images/sample.jpg"`
   - Assert output starts with `"https://"` and contains `"sample.jpg"`.
   - Input absolute: `"https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/sample.jpg"`
   - Assert output matches input unchanged.
4. `testLocationIdPreservation()`:
   - Provide `locationId = "C.HNO11417"`.
   - Assert resulting `station.id == "C.HNO11417"`.
   - Verify compatibility with Firestore document paths (`users/{uid}/userdata/favorites`).

---
Next Phase: [Phase 03: Dual-Tier Station Repository & Fallback](./phase-03-multi-tier-station-repository-and-fallback.md)
