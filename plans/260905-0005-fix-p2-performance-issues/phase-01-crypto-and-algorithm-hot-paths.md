# Phase 01: Cryptographic & Algorithm Hot-Paths
Status: ✅ Completed  
Dependencies: None  
Issue IDs: `PERF-CPU-03`, `PERF-ROUT-02`

## Objective
Eliminate redundant memory allocations and repeated computational loops in hot execution paths:
1. **PERF-CPU-03**: Remove 32 calls to `String.format("%02x", b)` per HMAC request signature in `EvcsHmacSigner.kt`, replacing them with zero-allocation bit-shift lookups.
2. **PERF-ROUT-02**: Optimize the geographic clustering algorithm `DistanceCalculator.clusterPoints` by maintaining running coordinate sums (`sumLat`, `sumLon`) and counts in O(1) time, removing repeated `cluster.map { ... }.average()` allocations.

## Requirements
### Functional
- `EvcsHmacSigner.sign` must produce exact byte-for-byte identical HMAC-SHA256 lowercase hex signatures as before.
- `DistanceCalculator.clusterPoints` must group coordinates correctly within `maxDistanceKm`, producing accurate cluster center averages and grouping points without behavioral regression.

### Non-Functional
- **PERF-CPU-03**: Zero `java.util.Formatter` object creation during HMAC signature generation.
- **PERF-ROUT-02**: O(1) center computation per candidate check in `clusterPoints` instead of O(N) map allocations.

## Implementation Steps
1. [x] In `app/src/main/java/com/evcs/favorites/data/crypto/EvcsHmacSigner.kt`:
   - Define a static lookup table `private val HEX_CHARS = "0123456789abcdef".toCharArray()`.
   - Update `sign()` to use bitwise shifts:
     ```kotlin
     val v = b.toInt() and 0xFF
     sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
     ```
2. [x] In `app/src/main/java/com/evcs/favorites/domain/location/DistanceCalculator.kt`:
   - Introduce an internal cluster accumulator class `ClusterAccumulator`:
     ```kotlin
     private class ClusterAccumulator(initialPoint: Pair<Double, Double>) {
         val points = mutableListOf(initialPoint)
         var sumLat: Double = initialPoint.first
         var sumLon: Double = initialPoint.second
         val centerLat: Double get() = sumLat / points.size
         val centerLon: Double get() = sumLon / points.size
         
         fun add(point: Pair<Double, Double>) {
             points.add(point)
             sumLat += point.first
             sumLon += point.second
         }
     }
     ```
   - Refactor `clusterPoints` to check `calculateDistanceKm(cluster.centerLat, cluster.centerLon, point.first, point.second) <= maxDistanceKm` in O(1) without calling `map` or `average`.
   - Map final accumulators to `GeoCluster(Pair(acc.centerLat, acc.centerLon), acc.points)`.
3. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/domain/location/CryptoAndAlgorithmHotPathTest.kt` verifying:
   - Known HMAC-SHA256 vectors against reference outputs.
   - Clustering correctness and O(1) center computation against existing test coordinates.
4. [x] Run single test:
   ```bash
   ./gradlew testDebugUnitTest --tests com.evcs.favorites.domain.location.CryptoAndAlgorithmHotPathTest
   ```

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/crypto/EvcsHmacSigner.kt` - [MODIFY] Optimize hex formatting
- `app/src/main/java/com/evcs/favorites/domain/location/DistanceCalculator.kt` - [MODIFY] O(1) incremental cluster center accumulation
- `app/src/test/java/com/evcs/favorites/domain/location/CryptoAndAlgorithmHotPathTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria
- [x] HMAC signatures match reference test vectors across various payloads and timestamps.
- [x] Cluster points groups points within distance threshold and yields precise average center.
- [x] Only one test file is run and passes cleanly.

---
Next Phase: `phase-02-compose-ui-memoization.md`
