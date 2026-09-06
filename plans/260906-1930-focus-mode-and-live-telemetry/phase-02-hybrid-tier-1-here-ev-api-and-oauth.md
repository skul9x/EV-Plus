# Phase 02: Hybrid Tier 1 HERE EV API & OAuth 1.0a

Status: ⬜ Pending
Dependencies: None

## Objective
Build the Tier 1 zero-login live telemetry infrastructure by integrating the HERE Maps EV API with OAuth 1.0a Client Credentials authentication (extracted from official VinFast app decompilation), providing real-time connector slot availability, charging gun IDs (`cpoEvseEMI3Id`), and exact DC power ratings without requiring driver login.

## Requirements
### Functional
- [ ] Implement `HereOAuthManager` with HMAC-SHA256 OAuth 1.0a Client Credentials signing against `https://account.api.here.com/oauth2/token`.
- [ ] Cache bearer access tokens in memory and proactively refresh them when less than 10 minutes of validity remain.
- [ ] Create data models in `HereEvModels.kt` parsing HERE stations JSON (`prox={lat},{lon},{radius}`), connectors, and `connectorStatuses`.
- [ ] Implement `HereEvApiClient` calling `https://ev-v2.cc.api.here.com/ev/stations.json` and mapping raw responses into `Station` domain models with DC-only port filtering capabilities.
- [ ] Parse `AVAILABLE` vs `OCCUPIED` states to extract live available/total counts per power tier (30kW, 60kW, 150kW, 250kW, 360kW).

### Non-Functional
- [ ] Network timeout bounded at 5 seconds.
- [ ] Thread-safe token acquisition avoiding duplicate concurrent authentication requests.

## Implementation Steps
1. Create `HereEvModels.kt` with kotlinx serialization data classes.
2. Implement `HereOAuthManager.kt` with standard OAuth 1.0a normalized parameter string, base string, HMAC-SHA256 signature, and token caching.
3. Implement `HereEvApiClient.kt` handling stations queries, error handling, and domain mapping to `Station`.

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt` - [HERE EV API DTO models]
- `app/src/main/java/com/evcs/favorites/data/network/here/HereOAuthManager.kt` - [OAuth 1.0a HMAC-SHA256 token manager]
- `app/src/main/java/com/evcs/favorites/data/network/here/HereEvApiClient.kt` - [OkHttp client for HERE EV stations API]

## Test Verification (Single Test per Phase)
- Exactly one comprehensive test file:
  - `app/src/test/java/com/evcs/favorites/data/network/here/HereEvApiClientAndOAuthTest.kt`
- Test cases covered:
  - Validates OAuth 1.0a HMAC-SHA256 signature generation against known test vectors.
  - Verifies token reuse when fresh and automatic renewal when near expiry.
  - Validates mock JSON parsing of multi-gun VinFast stations with accurate AVAILABLE/OCCUPIED slot counts and DC wattage categorization.

---
Next Phase: [phase-03-focus-mode-telemetry-engine-and-polling.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260906-1930-focus-mode-and-live-telemetry/phase-03-focus-mode-telemetry-engine-and-polling.md)
