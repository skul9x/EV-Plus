# Phase 02: Multi-Tier Coordinator & Fallback Arbitration
Status: ✅ Completed
Dependencies: [Phase 01: Core Routing Clients & Data Models](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-01-core-routing-clients-and-data-models.md)

## Objective
Build the central coordinator (`MultiTierRoutingCoordinator`) that arbitrates requests across Tier 1 (Google Routes v2), Tier 2 (OSRM Table Service), and Tier 3 (Local Haversine), executing mode selection and resilient automatic fallback cascades.

## Requirements
### Functional
- **Coordinator Logic (`MultiTierRoutingCoordinator.kt`)**:
  - Accepts user origin coordinates `(originLat, originLng)`, a list of destination items `(id, destLat, destLng)`, and user configuration `RoutingSettings`.
  - **Input Sanitization & Validation**:
    - Validates origin coordinates: if `originLat == 0.0 && originLng == 0.0` or invalid (`lat !in -90.0..90.0 || lng !in -180.0..180.0`), returns empty map without executing network calls.
    - Filters destinations: removes stations with invalid/unresolved coordinates `(lat == 0.0 && lng == 0.0)`.
    - If valid destinations list is empty, immediately returns `emptyMap()`.
  - **Mode Arbitration**:
    - `RoutingEngineMode.AUTO`:
      - If `googleApiKey` is non-blank, attempts Tier 1 (Google Routes v2).
      - If Tier 1 succeeds, returns Google metrics.
      - If Tier 1 fails (network error, invalid API key 400/403, quota limit 429) AND `autoFallbackEnabled == true`, falls back to Tier 2 (OSRM).
      - If `googleApiKey` is blank, directly queries Tier 2 (OSRM).
      - If Tier 2 fails AND `autoFallbackEnabled == true`, falls back to Tier 3 (Haversine calculation).
    - `RoutingEngineMode.GOOGLE_ONLY`:
      - Calls Tier 1 (Google Routes v2) only.
      - If Tier 1 fails, returns empty map or failure; strictly no fallback to OSRM or Haversine.
    - `RoutingEngineMode.OSRM_ONLY`:
      - Calls Tier 2 (OSRM) directly, skipping Google even if an API key is provided.
      - If Tier 2 fails and `autoFallbackEnabled == true`, falls back to Haversine.
    - `RoutingEngineMode.HAVERSINE_ONLY`:
      - Immediately computes local Haversine straight-line distances (0ms, zero network calls).
  - Returns `Map<String, DrivingMetrics>` keyed by destination station id.

### Non-Functional
- Thread-safe and non-blocking execution using Kotlin Coroutines `withContext(Dispatchers.IO)`.
- Resilient error handling that guarantees the app never crashes due to upstream provider failures.

## Implementation Steps
1. Create `MultiTierRoutingCoordinator.kt` in `app/src/main/java/com/evcs/favorites/data/routing/`.
2. Implement dispatching methods for each `RoutingEngineMode` with dependency-injected clients for testing.
3. Implement fallback cascade: Tier 1 -> Tier 2 -> Tier 3.
4. Implement `MultiTierRoutingCoordinatorTest.kt` using mocked clients/MockWebServer to verify all mode combinations and fallback scenarios.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` - [New] Central 3-tier routing orchestrator and fallback arbitrator
- `app/src/test/java/com/evcs/favorites/MultiTierRoutingCoordinatorTest.kt` - [New] Comprehensive file-based verification test for Phase 02

## Test Criteria
- Verify `AUTO` mode with valid Google API key routes to Tier 1 and returns Google metrics with live traffic.
- Verify `AUTO` mode with invalid Google API key or 403 error automatically cascades to Tier 2 (OSRM) when `autoFallbackEnabled = true`.
- Verify `AUTO` mode without Google API key routes directly to Tier 2 (OSRM).
- Verify `AUTO` mode cascades from OSRM to Tier 3 (Haversine) when OSRM service is unreachable.
- Verify `GOOGLE_ONLY` mode does not fall back to OSRM or Haversine when Google fails.
- Verify `OSRM_ONLY` mode does not call Google even when an API key is configured.
- Verify `HAVERSINE_ONLY` mode performs instant local calculations without triggering network requests.
- Verify disabling `autoFallbackEnabled` stops fallback cascades on engine errors.

---
Next Phase: [Phase 03: Secure BYOK Preferences & Settings UI](file:///home/skul9x/Desktop/Code/TramsacEV/plans/260903-multi-tier-routing-and-byok/phase-03-secure-byok-preferences-and-settings-ui.md)
