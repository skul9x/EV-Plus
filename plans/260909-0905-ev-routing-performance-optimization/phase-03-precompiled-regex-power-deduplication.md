# Phase 03: Pre-compiled Static Regexes & Power Extraction Deduplication

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Eliminate repeated heap allocations and garbage collection overhead caused by ad-hoc `Regex(...)` instantiations inside hot loops and composables. Deduplicate the identical charger power extraction logic between `EvSmartRouteModels.kt` and `EvSmartRoutePlanner.kt`, consolidating into a single pre-compiled, highly optimized function.

## Requirements
### Functional
- Consolidate charger power extraction into a single canonical implementation: `extractStationMaxPowerKw(station: Station): Double` in `EvSmartRouteModels.kt`.
- Deprecate or delegate `extractMaxPowerKw(station: Station)` in `EvSmartRoutePlanner.kt` directly to `extractStationMaxPowerKw`.
- Pre-compile all regex instances into static top-level or companion constants:
  - Power parsing: `private val POWER_KW_REGEX = Regex("""(\d+(?:\.\d+)?)\s*k[wW]""")`
  - Detour parsing: `private val DETOUR_KM_REGEX = Regex("""detour[:\s]+(\d+(?:\.\d+)?)\s*km""", RegexOption.IGNORE_CASE)`
- Maintain exact parsing compatibility with existing inputs:
  - Numeric power from `station.powers.maxOfOrNull { it.typeWatts }`
  - Text patterns like "60 kW", "120.5kw", "30kW", "250 kW"
  - Heuristic keywords like "DC" and "Super" (defaulting to 60.0 kW)
  - Default fallback of 11.0 kW for AC / unspecified plugs.

### Non-Functional
- Zero regex compilation overhead during runtime route planning and recomposition.
- Significant reduction in heap garbage creation during high-frequency route and timeline updates.

## Implementation Steps
1. In `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt`:
   - Define top-level static regex:
     ```kotlin
     private val POWER_KW_REGEX = Regex("""(\d+(?:\.\d+)?)\s*k[wW]""")
     ```
   - Update `extractStationMaxPowerKw(station: Station): Double` to use `POWER_KW_REGEX.findAll(allText)`.
2. In `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt`:
   - Define top-level static regex:
     ```kotlin
     private val DETOUR_KM_REGEX = Regex("""detour[:\s]+(\d+(?:\.\d+)?)\s*km""", RegexOption.IGNORE_CASE)
     ```
   - Replace dynamic regex in `evaluateHighwayDetour`:
     ```kotlin
     val detourMatch = DETOUR_KM_REGEX.find(text)
     ```
   - Replace the body of `extractMaxPowerKw(station: Station)` to delegate directly:
     ```kotlin
     fun extractMaxPowerKw(station: Station): Double = extractStationMaxPowerKw(station)
     ```
3. Run verification test to confirm matching behavior and performance.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt` - Pre-compile power regex and optimize `extractStationMaxPowerKw`.
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt` - Pre-compile detour regex and delegate `extractMaxPowerKw`.
- `app/src/test/java/com/evcs/favorites/data/routing/EvSmartRouteRegexOptimizationTest.kt` - Comprehensive single verification test for Phase 03.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.data.routing.EvSmartRouteRegexOptimizationTest`
- **Execution Command:** `./gradlew testDebugUnitTest --tests "com.evcs.favorites.data.routing.EvSmartRouteRegexOptimizationTest"`
- **Key Assertions:**
  1. `extractStationMaxPowerKw` accurately extracts power across all variations (raw typeWatts, integer kW, decimal kW, "DC" heuristic, default AC).
  2. Detour distance extraction in `evaluateHighwayDetour` correctly matches highway detour patterns case-insensitively using the static regex.
  3. Parity assertion: `extractMaxPowerKw` and `extractStationMaxPowerKw` produce identical outputs for all test stations.
  4. Benchmark / GC sanity check: 10,000 successive invocations execute in <50ms without instantiating new `Regex` objects.

---
Next Phase: [Phase 04: Compose Route Timeline Optimization & Memoization](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0905-ev-routing-performance-optimization/phase-04-compose-timeline-memoization.md)
