# Phase 02: Lookahead Corridor Routing Engine with Power Filtering & Fallback Detection

Status: ✅ Completed
Dependencies: [Phase 01: Minimum Power Criteria & Starting SoC Settings Integration](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0735-ev-routing-algorithm-refinement/phase-01-settings-and-power-criteria.md)

## Objective
Upgrade `EvSmartRoutePlanner` to implement forward-reachability lookahead simulation along the polyline corridor, adhering to modern EV routing standards (ABRP, HERE EV Routing API):
1. Compute the first leg reachability strictly from active $SoC_{start}$ minus arrival reserve buffer ($SoC_{buffer}$).
2. Filter candidates along the corridor by user-selected `minChargerPowerKw`.
3. Apply forward-reachability lookahead to avoid greedy traps where selecting a station leaves the next leg without reachable chargers.
4. When a window has no station meeting `minChargerPowerKw`, seamlessly complete the route using the best available fallback station ($\ge 20\text{ kW}$) and attach an `InsufficientPowerWarning` instead of halting mid-route.

## Requirements
### Functional
- [x] Calculate first-leg reach limit: $D_1 = Range_{safe} \times \frac{(SoC_{start} - SoC_{buffer}).coerceAtLeast(0)}{100.0}$.
- [x] Calculate subsequent leg reach limits assuming replenishment to $SoC_{charge\_target}$ (85%): $D_k = Range_{safe} \times \frac{85 - SoC_{buffer}}{100.0}$.
- [x] Classify projected corridor candidates ($\le 4.0\text{ km}$, non-trap) into two tiers:
  - **Tier 1 (Target Power):** Stations with $\text{maxPowerKw} \ge minChargerPowerKw$.
  - **Tier 2 (Fallback Power):** Stations with $20.0\text{ kW} \le \text{maxPowerKw} < minChargerPowerKw$.
- [x] **Forward-Reachability Lookahead:**
  - In reachable window $(currentDist + 1.0\text{ km}, reachLimitKm]$, verify that selecting a candidate leaves at least one subsequent station (or destination) reachable on the next leg ($D_k$).
  - If a candidate with slightly more progress leads to an unavoidable dead-end while an earlier candidate in the window has forward connectivity, prioritize the forward-viable candidate.
- [x] **Seamless Fallback Route Generation:**
  - If no Tier 1 candidate is reachable in the window, evaluate Tier 2 fallback candidates.
  - Choose the best Tier 2 candidate and **continue planning all remaining legs to the destination** so the driver receives a complete route.
  - Attach `InsufficientPowerWarning` to the resulting `EvSmartRoutePlan`:
    ```kotlin
    data class InsufficientPowerWarning(
        val requiredPowerKw: Double,
        val fallbackStation: Station,
        val fallbackPowerKw: Double,
        val stopIndex: Int,
        val legDistanceKm: Double,
        val message: String
    )
    ```
- [x] **Dead Zone Detection:** If neither Tier 1 nor Tier 2 stations exist before battery reserve is exhausted, emit `DeadZoneWarning` indicating the exact gap metrics.
- [x] Provide helper `planRouteWithRelaxedPower(...)` to smoothly recalculate the route if the user confirms a lower power threshold.

### Non-Functional
- [x] Algorithmic efficiency: $O(N)$ candidate scan along prefix-sum polyline; execution time $< 1500\text{ ms}$ on standard Android devices.
- [x] Energy profile consistency: Sawtooth energy waypoints must accurately reflect $SoC_{start}$, arrival SoC, departure SoC (85%), and destination arrival SoC ($\ge SoC_{buffer}$).

## Implementation Steps
1. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt`:
   - Introduce `@Immutable @Serializable data class InsufficientPowerWarning(...)`.
   - Add `insufficientPowerWarning: InsufficientPowerWarning? = null` to `EvSmartRoutePlan`.
2. [x] Update `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt`:
   - Incorporate `sanitizedSettings.minChargerPowerKw` in candidate evaluation.
   - Refactor window selection to check forward connectivity before committing to a stop.
   - Implement Tier 1 selection with Tier 2 fallback propagation.
   - Ensure the simulation loop finishes all legs to the destination even when a fallback station is used, capturing the `InsufficientPowerWarning`.
   - Add `fun planRouteWithRelaxedPower(...)`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRouteModels.kt` - Add `InsufficientPowerWarning` model.
- `app/src/main/java/com/evcs/favorites/data/routing/EvSmartRoutePlanner.kt` - Lookahead corridor scheduler with power filtering and fallback route generation.
- `app/src/test/java/com/evcs/favorites/data/routing/EvSmartRoutePowerFilterPlannerTest.kt` - Comprehensive unit test for Phase 02.

## Test Criteria (Single Verification Test)
- **Test Class:** `com.evcs.favorites.data.routing.EvSmartRoutePowerFilterPlannerTest`
- **Key Assertions:**
  1. Low starting SoC (e.g., 40%) forces an earlier first charging stop than 100% SoC.
  2. Stations with power below `minChargerPowerKw` (e.g. 30 kW when 60 kW required) are excluded when Tier 1 stations exist in the window.
  3. Lookahead chooses a viable intermediate station over a greedy candidate that leads to an avoidable dead-end.
  4. When only < 60 kW stations exist in a window, a complete multi-stop route to destination is generated with `insufficientPowerWarning` properly populated.
  5. True dead zone (no station of any power reachable) flags `deadZoneWarning`.

---
Next Phase: [Phase 03: Insufficient Power Fallback Modal Flow & Criteria Relaxation](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-0735-ev-routing-algorithm-refinement/phase-03-insufficient-power-fallback-dialog-and-flow.md)
