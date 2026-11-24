# Phase 02: Station Normalization, Coordinate Caching & Name Enrichment
Status: ✅ Completed
Dependencies: [Phase 01: Multi-Tier Repository Fallback & Circuit Breaker Engine](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/261124-1947-evcs-403-here-api-fallback-resilience/phase-01-multi-tier-repository-fallback-and-circuit-breaker.md)

## Objective
Ensure all charging stations retrieved via the secondary HERE Maps EV API fallback pipeline are seamlessly normalized to match domain model conventions expected by the rest of the application. This includes:
1. Enriching generic station names (`"Trạm sạc VinFast"`) with address-based fallback titles (e.g. `"VinFast - Đường Trần Hưng Đạo, Phố Mới, Quế Võ"`), while safely handling blank/empty address edge cases to prevent dangling dashes (`"VinFast - "`).
2. Ensuring `station.evse` is correctly tagged as `"VinFast"` for all VinFast-operated stations (specifically when `cpoId` starts with `"c."`, contains `"vinfast"`, or resolved name contains `"vinfast"`), preventing downstream pruning by `NearbyStationFilter.isVinFastStation`.
3. Symmetric coordinate caching in `EvcsRepository` with bidirectional key resolution (`c.<id>` and bare `<id>`) and a public `getStationCoordinates(locationId)` resolver.
4. Preserving 100% parity for Firestore favorites synchronization and offline routing.

## Scope
- Update `HereModelMapper.mapStationToDomain`:
  - Sanitize and resolve station name: if `rawName` is generic or blank, enrich with `EvcsStationNameResolver.formatFallbackAddress(formattedAddress)` only if `formattedAddress.isNotBlank()`. If address is blank, fall back safely to `rawName.ifBlank { "Trạm sạc VinFast" }`.
  - Resolve `evse`: attribute `"VinFast"` whenever `cpoId?.startsWith("c.", ignoreCase = true) == true`, `cpoId?.contains("vinfast", ignoreCase = true) == true`, or `resolvedName.contains("vinfast", ignoreCase = true)`.
- Update `EvcsRepository`:
  - In `executeHereFallback`, symmetrically cache coordinates under both canonical `cleanKey` and un-prefixed/prefixed aliases (`cleanKey.removePrefix("c.")` and `"c.$cleanKey"`).
  - Add public helper `fun getStationCoordinates(locationId: String): Pair<Double, Double>?` with dual-key alias resolution and fallback to `coordinateResolver`.
- Author exactly one comprehensive file-based test: `HereFallbackStationModelAndNameResolutionTest.kt`.

## Requirements
### Functional
- [x] Safe Name Enrichment:
  - If `hereStation.name` is generic (`"Trạm sạc VinFast"`, `"VinFast"`, etc.) and `address` has non-blank components, format name using `EvcsStationNameResolver.formatFallbackAddress(formattedAddress)`.
  - Edge case safety: If `formattedAddress` is blank or null, do NOT produce `"VinFast - "`. Instead, preserve `rawName` or fallback cleanly to `"Trạm sạc VinFast"`.
  - Non-generic authentic station names (e.g. `"VinFast Landmark 81"`) must be preserved verbatim.
- [x] EVSE Attribution for Filter Pass-Through:
  - Downstream `NearbyStationFilter.isVinFastStation(station)` strictly requires `station.evse.trim().equals("VinFast", ignoreCase = true)`.
  - In `HereModelMapper.mapStationToDomain`, ensure `resolvedEvse` is set to `"VinFast"` whenever:
    1. `cpoId` contains `"vinfast"` (case-insensitive) OR starts with `"c."` (standard VinFast CPO prefix in Vietnam), OR
    2. `resolvedName` contains `"vinfast"` (case-insensitive).
- [x] Identifier Canonicalization & Dual-Key Cache:
  - Station coordinates cached in `EvcsRepository.coordinateCache` must be registered under both `cleanKey` and its counterpart (`cleanKey.removePrefix("c.")` and `"c.$cleanKey"`).
- [x] Public Coordinate Resolver:
  - Provide `EvcsRepository.getStationCoordinates(locationId: String): Pair<Double, Double>?` resolving both prefixed and un-prefixed keys, falling back to `coordinateResolver`.
- [x] Power Port & Connector Compatibility:
  - Mapped domain stations must contain correctly aggregated `PowerPort` lists with `typeWatts`, `availablePlugs`, and `totalPlugs` matching HERE connector statuses.

### Non-Functional
- [x] Memory Bounding: Caching coordinates must respect `coordinateCache` bounded capacity (`BoundedLruMap` max 500 items).
- [x] Allocation Efficiency: Avoid regex allocations in `HereModelMapper` hot paths by relying on pre-compiled matchers in `EvcsStationNameResolver`.

## How to Implement
1. **Refactor `HereModelMapper.mapStationToDomain`:**
   In `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt`:
   ```kotlin
   fun mapStationToDomain(
       hereStation: HereEvStation,
       dcOnly: Boolean = true,
       carOnly: Boolean = true
   ): Station {
       val aggregations = aggregatePowerTiers(hereStation.allConnectors(), dcOnly = dcOnly, carOnly = carOnly)
       val powerPorts = aggregations.map { it.toDomainPowerPort() }

       val totalAvailable = powerPorts.sumOf { it.availablePlugs }
       val totalPlugs = powerPorts.sumOf { it.totalPlugs }

       val summary = if (totalPlugs > 0) {
           "Trống $totalAvailable/$totalPlugs cổng sạc${if (dcOnly) " DC" else ""}"
       } else {
           "Không có súng sạc${if (dcOnly) " DC" else ""}"
       }

       val connectorsString = powerPorts.joinToString(", ") { it.label }
       val depotStatus = if (totalPlugs > 0 && totalAvailable == 0) "Occupied" else "Normal"

       val domainId = hereStation.cpoId?.takeIf { it.isNotBlank() }
           ?: hereStation.id.ifBlank { "here_${hereStation.name.hashCode()}" }

       // 1. Resolve authentic or address-enriched station name
       val rawName = hereStation.name.trim()
       val formattedAddress = hereStation.address?.toFormattedAddress().orEmpty()
       val resolvedName = when {
           rawName.isBlank() || EvcsStationNameResolver.isGenericStationName(rawName) -> {
               if (formattedAddress.isNotBlank()) {
                   EvcsStationNameResolver.formatFallbackAddress(formattedAddress)
               } else {
                   if (rawName.isNotBlank()) rawName else "Trạm sạc VinFast"
               }
           }
           else -> rawName
       }

       // 2. Resolve EVSE operator ensuring VinFast filter compatibility
       val cleanCpo = hereStation.cpoId?.trim().orEmpty()
       val resolvedEvse = when {
           cleanCpo.isNotEmpty() -> {
               if (cleanCpo.contains("vinfast", ignoreCase = true) || cleanCpo.startsWith("c.", ignoreCase = true)) {
                   "VinFast"
               } else if (resolvedName.contains("vinfast", ignoreCase = true)) {
                   "VinFast"
               } else {
                   cleanCpo
               }
           }
           resolvedName.contains("vinfast", ignoreCase = true) || rawName.contains("vinfast", ignoreCase = true) -> "VinFast"
           powerPorts.isNotEmpty() && powerPorts.all { it.typeWatts <= 22000L } -> "AC"
           else -> "Partner"
       }

       return Station(
           id = domainId,
           name = resolvedName,
           address = formattedAddress,
           latitude = hereStation.position?.latitude ?: 0.0,
           longitude = hereStation.position?.longitude ?: 0.0,
           summary = summary,
           connectors = connectorsString,
           depotStatus = depotStatus,
           powers = powerPorts,
           totalAvailablePlugs = totalAvailable,
           totalPlugs = totalPlugs,
           images = emptyList(),
           image = null,
           addedAt = System.currentTimeMillis(),
           isPublic = true,
           isFreeParking = true,
           workingTimeDescription = "24/7",
           distanceKm = null,
           drivingMetrics = null,
           evse = resolvedEvse
       )
   }
   ```
2. **Add `getStationCoordinates` in `EvcsRepository`:**
   In `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   ```kotlin
   /**
    * Retrieves station coordinates from in-memory cache supporting both prefixed (c.xxx)
    * and un-prefixed keys, falling back to [coordinateResolver] if not found in cache.
    */
   fun getStationCoordinates(locationId: String): Pair<Double, Double>? {
       if (locationId.isBlank()) return null
       val clean = locationId.trim().lowercase()
       val unPrefixed = clean.removePrefix("c.")
       return coordinateCache[clean]
           ?: coordinateCache[unPrefixed]
           ?: coordinateCache["c.$unPrefixed"]
           ?: coordinateResolver?.invoke(locationId)
   }
   ```
3. **Create the Single Comprehensive Test File:**
   Author `app/src/test/java/com/evcs/favorites/data/repository/HereFallbackStationModelAndNameResolutionTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt` - [MODIFY] Enhance `HereModelMapper.mapStationToDomain` with safe name enrichment, address blank guard, and robust `evse = "VinFast"` attribution.
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Add `getStationCoordinates` with dual-key resolution and ensure symmetric coordinate caching in fallback path.
- `app/src/test/java/com/evcs/favorites/data/repository/HereFallbackStationModelAndNameResolutionTest.kt` - [NEW] Single comprehensive test suite validating station name enrichment, blank address safety, EVSE tagging, coordinate caching, and ID parity.

## Dependencies
- `com.evcs.favorites.data.network.here.model.HereModelMapper`
- `com.evcs.favorites.focus.EvcsStationNameResolver`
- `com.evcs.favorites.data.repository.EvcsRepository`

## Constraints
- Do not introduce UI or Android lifecycle dependencies into `HereModelMapper` or `HereEvModels.kt`.
- Maintain full compatibility with existing `AcStationPreFilterAndMappingOptimizationTest.kt` and `HereAcDiscoveryAndHtmlNameResolutionEngineTest.kt`.

## Acceptance Criteria
- [x] Fallback stations with generic name `"Trạm sạc VinFast"` and valid address are transformed into `"VinFast - <Street/District>"`.
- [x] Fallback stations with generic name and blank/null address do NOT produce trailing dashes (never `"VinFast - "`).
- [x] Fallback stations with authentic custom names (e.g. `"VinFast Mega Mall Thảo Điền"`) preserve their original name.
- [x] All VinFast CPO stations (`c.` prefix or `vinfast` in CPO/name) have `station.evse == "VinFast"`, allowing `NearbyStationFilter.isVinFastStation` to pass.
- [x] Dual-key lookup via `getStationCoordinates("C.BNI0311")` and `getStationCoordinates("bni0311")` successfully resolves cached coordinates.
- [x] The single comprehensive test file passes 100%.

## Exactly One Comprehensive File-Based Test
`app/src/test/java/com/evcs/favorites/data/repository/HereFallbackStationModelAndNameResolutionTest.kt`
- Tests mapping of generic HERE EV station with address into formatted `"VinFast - <Address>"` title.
- Tests mapping of generic HERE EV station with empty address (no trailing dash, fallback to `"Trạm sạc VinFast"`).
- Tests non-generic station name preservation.
- Tests `evse` assignment to `"VinFast"` for stations with `c.` CPO prefix, `vinfast` CPO, or `vinfast` in name.
- Tests symmetric dual-key coordinate caching and retrieval via `getStationCoordinates`.
- Tests power port aggregation (DC vs AC, available vs total plugs) on fallback domain stations.

---
Next Phase: [phase-03-viewmodel-orchestration-and-regression-verification.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/261124-1947-evcs-403-here-api-fallback-resilience/phase-03-viewmodel-orchestration-and-regression-verification.md)
