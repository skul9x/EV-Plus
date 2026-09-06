# Master Plan: VinFast CAPP Direct API Integration with Dual-Tier Fallback

Created: 2026-09-06  
Status: 🟡 Ready for Execution  
Target Project: `D:\skul9x\EV-Plus-main`  
Reference Specifications:
- Architectural Brief: [`docs/BRIEF_VINFAST_DIRECT.md`](file:///D:/skul9x/EV-Plus-main/docs/BRIEF_VINFAST_DIRECT.md)
- Reverse Engineered Sources: `output_vinfast/sources/vn/vinfast/chargingkit/` & `com/vinfast/companion/corenetwork/`
- Performance Classification: [`phanloai.txt`](file:///D:/skul9x/EV-Plus-main/phanloai.txt)

---

## 1. Executive Summary & Objective

The primary objective of this integration is to eliminate EV-Plus's single point of failure and heavy dependency on the 3rd-party community aggregator `evcs.vn`. 
By integrating the official VinFast Connected Car (CAPP) station API directly into the Android client alongside our proven HMAC-signed fallback proxy, EV-Plus achieves:

1. **Direct Data Lineage**: Real-time plug telemetry directly from the official VinFast backend (`https://mobile.connected-car.vinfast.vn/`) with ultra-low latency (<200ms) and authentic upstream telemetry.
2. **Dual-Tier Fault Tolerance**: 
   - **Tier 1 (Primary)**: VinFast CAPP Public API with strict 5-second fast-fail timeout.
   - **Tier 2 (Automatic Fallback)**: `evcs.vn` HMAC-signed proxy seamlessly takes over when Tier 1 fails (HTTP 401 Unauthorized, HTTP 403/5xx, network timeout, or maintenance).
3. **100% ID & Schema Invariance (Zero Cloud Migration)**: 
   Both VinFast and `evcs.vn` share identical `locationId` scheme (e.g. `C.HNO11417`). Existing user favorites stored in Cloud Firestore (`users/{uid}/userdata/favorites`) remain 100% compatible with zero migration required.
4. **Strict Automobile Port & Station Filtering**: 
   - Connector-level: Hard filter to discard motorbike AC charging plugs (3.5kW & 7kW, `type <= 7000`), retaining only valid automobile EV chargers (11kW AC, 30kW - 360kW DC).
   - Station-level: Automatically exclude pure-motorbike charging locations where car-compatible ports count is 0.
   - Re-aggregate station telemetry: `totalAvailablePlugs` and `totalPlugs` are computed strictly from car ports.
5. **Direct S3 CDN Image Resolution**: 
   Direct image decoding without proxy bottlenecks, serving high-resolution station photos directly from VinFast S3/CloudFront CDN (`https://cpo-prod-s3.vinfastauto.com/` or `https://d1aza9v8tzxrkt.cloudfront.net/`) utilizing the existing `VinFastCdnUrlDecoder`.
6. **Zero-Breaking-Change Architecture**: 
   `DualTierStationRepository` subclasses `EvcsRepository`, preserving complete constructor and ABI compatibility with `FavoritesViewModel`, `NearbyViewModel`, `MainActivity`, and all 17 existing unit test suites.

---

## 2. Architectural Blueprint

```
+-----------------------------------------------------------------------------------+
|                            Jetpack Compose UI Layer                              |
|           (FavoritesScreen, NearbyStationsScreen, StationDetailBottomSheet)       |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                      FavoritesViewModel / NearbyViewModel                         |
|            (Consumes EvcsRepository interface - 100% ABI compatibility)           |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                            DualTierStationRepository                              |
|                         (Subclasses EvcsRepository)                               |
|            Coordinates data fetching with seamless dual-tier degradation          |
+-----------------------------------------------------------------------------------+
        |                                                    |
        | (Tier 1 - Primary: 5s fast-fail)                   | (Tier 2 - Fallback: HMAC signed)
        v                                                    v
+------------------------------------+   +------------------------------------------+
|       VinFastCAppApiClient         |   |              EvcsApiClient               |
| (mobile.connected-car.vinfast.vn)  |   |           (https://evcs.vn/search)       |
+------------------------------------+   +------------------------------------------+
        |                                                    |
        +--> VinFastHeaderInterceptor                        +--> EvcsHmacSigner
        |    (X-APP-VERSION: 2.25.7,                              (HMAC-SHA256 Signed,
        |     X-SERVICE-NAME: CAPP,                                Token: eepe5dp9zpipl102)
        |     X-Device-Platform: android...)                 |
        |                                                    |
        v                                                    v
+-----------------------------------------------------------------------------------+
|                            VinFastStationMapper                                   |
|   - Strips 3.5kW/7kW motorbike plugs (type <= 7000)                               |
|   - Drops pure motorbike stations (where car-compatible ports == 0)               |
|   - Maps raw connectors to domain PowerPort (11kW AC, 30kW - 360kW DC)            |
|   - Re-aggregates totalAvailablePlugs & totalPlugs strictly from car bays         |
|   - Decodes S3 CDN photos (integrates with VinFastCdnUrlDecoder)                  |
+-----------------------------------------------------------------------------------+
```

---

## 3. Performance & Resource Guarantees (Addressing `phanloai.txt`)

- **PERF-NET-01**: `VinFastCAppApiClient` builds its `OkHttpClient` via `AppOkHttpClientProvider.newSharedClientBuilder()`, sharing `ConnectionPool` (max 10 idle, 5m keep-alive) and `Dispatcher` with `EvcsApiClient` to eliminate thread proliferation and duplicate TLS handshakes.
- **PERF-CPU-02**: Uses shared singleton `Json` instance with `ignoreUnknownKeys = true`, `isLenient = true`, and `encodeDefaults = false` to avoid redundant serializer descriptor caching.
- **PERF-ASYNC-02**: All network I/O and DTO mapping operations are dispatched on `Dispatchers.IO`.

---

## 4. Implementation Phases

| Phase | Phase Name | Status | Key Deliverables & Test Verification |
| :--- | :--- | :--- | :--- |
| **01** | [VinFast CAPP API Client & Headers](./phase-01-vinfast-capp-api-client-and-headers.md) | ⬜ Pending | `VinFastStationDtos`, `VinFastHeaderInterceptor`, `VinFastCAppApiClient`, `VinFastCAppApiClientTest` |
| **02** | [DTO Mapping & EV Car Port Filtering](./phase-02-dto-mapping-and-ev-car-port-filtering.md) | ⬜ Pending | `VinFastStationMapper`, 3.5kW/7kW filter, Car-only aggregation, Direct S3 CDN Image decoder, `VinFastStationMapperTest` |
| **03** | [Dual-Tier Station Repository & Fallback](./phase-03-multi-tier-station-repository-and-fallback.md) | ⬜ Pending | `DualTierStationRepository` (Tier 1 VinFast -> Tier 2 EVCS failover, subclasses `EvcsRepository`), `DualTierStationRepositoryFallbackTest` |
| **04** | [Integration, UI Wiring & Verification](./phase-04-integration-ui-wiring-and-verification.md) | ⬜ Pending | `MainActivity` wiring, `FavoritesViewModel` & `NearbyViewModel` telemetry integration, `Station.sourceTier`, `VinFastDirectIntegrationTest` |

---

## 5. Verification & Testing Strategy

Each phase includes a dedicated JUnit4 test suite using MockWebServer and Coroutine test dispatchers:
1. **Network Contract (`VinFastCAppApiClientTest`)**:
   - Verify exact headers (`X-APP-VERSION: 2.25.7`, `X-SERVICE-NAME: CAPP`, `X-Device-Platform: android`, `User-Agent`, etc.).
   - Verify `page` and `size` are passed as URL query parameters (`/stations/search?page=0&size=50`) while search coordinates reside in the body.
   - Verify graceful encapsulation of HTTP 401 (`code: 40300`), 403, 500, and network timeout within 5 seconds.
2. **Mapper & Car Filtering Contract (`VinFastStationMapperTest`)**:
   - Verify exclusion of 3.5kW and 7kW motorbike plugs (`type <= 7000`).
   - Verify motorbike-only stations are discarded.
   - Verify `totalPlugs` and `totalAvailablePlugs` accurately reflect car plugs only.
   - Verify image URL resolution (CloudFront relative paths and S3 CDN double-base64 tokens).
3. **Dual-Tier Fallback Contract (`DualTierStationRepositoryFallbackTest`)**:
   - Tier 1 200 OK -> returns data tagged `VINFAST_DIRECT`, Tier 2 is never invoked.
   - Tier 1 401/Timeout -> automatically degrades to Tier 2 EVCS, returns data tagged `EVCS_FALLBACK`.
   - Both tiers fail -> clean `Result.failure` without uncaught exceptions or crashes.
4. **End-to-End UI Integration (`VinFastDirectIntegrationTest`)**:
   - Verify ViewModel receives enriched telemetry without recomposition loops.
   - Verify Firestore favorites document path and `locationId` invariance.
