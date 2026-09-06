# Handover Document: VinFast CAPP Direct API Integration

**Date**: 2026-09-06  
**Status**: 🟡 Plan Ready & Approved -> Ready to Code  
**Project**: `D:\skul9x\EV-Plus-main`

---

## 📍 Current State & Objective
- Completed in-depth APK analysis of VinFast Connected Car (CAPP) endpoints (`output_vinfast`) and verified real-world HTTP responses.
- Refined 4-phase implementation plan with Dual-Tier architecture (Tier 1: VinFast CAPP Direct -> Tier 2: EVCS Fallback), eliminating offline cache snapshot (Tier 3) as instructed.
- Corrected query params vs body params for `/stations/search` and integrated direct S3 image double-base64 decoding.
- Ready to execute Phase 01 (`VinFastCAppApiClient` & `VinFastHeaderInterceptor`).

---

## ✅ Completed Deliverables
1. **Architectural Brief**: [`docs/BRIEF_VINFAST_DIRECT.md`](file:///D:/skul9x/EV-Plus-main/docs/BRIEF_VINFAST_DIRECT.md) (Tier 3 cache removed)
2. **Master Plan**: [`plans/260906-1005-vinfast-capp-direct-api-and-fallback/plan.md`](file:///D:/skul9x/EV-Plus-main/plans/260906-1005-vinfast-capp-direct-api-and-fallback/plan.md)
3. **Phase 01 Spec**: [`phase-01-vinfast-capp-api-client-and-headers.md`](file:///D:/skul9x/EV-Plus-main/plans/260906-1005-vinfast-capp-direct-api-and-fallback/phase-01-vinfast-capp-api-client-and-headers.md)
4. **Phase 02 Spec**: [`phase-02-dto-mapping-and-ev-car-port-filtering.md`](file:///D:/skul9x/EV-Plus-main/plans/260906-1005-vinfast-capp-direct-api-and-fallback/phase-02-dto-mapping-and-ev-car-port-filtering.md)
5. **Phase 03 Spec**: [`phase-03-multi-tier-station-repository-and-fallback.md`](file:///D:/skul9x/EV-Plus-main/plans/260906-1005-vinfast-capp-direct-api-and-fallback/phase-03-multi-tier-station-repository-and-fallback.md)
6. **Phase 04 Spec**: [`phase-04-integration-ui-wiring-and-verification.md`](file:///D:/skul9x/EV-Plus-main/plans/260906-1005-vinfast-capp-direct-api-and-fallback/phase-04-integration-ui-wiring-and-verification.md)

---

## ⏳ Pending Implementation Phases
1. **Phase 01**: VinFast CAPP API Client & Headers (`VinFastCAppApiClient`, `VinFastHeaderInterceptor`, DTOs, `VinFastCAppApiClientTest.kt`)
2. **Phase 02**: DTO Mapping & EV Car Port Filtering (`VinFastStationMapper`, drop 3.5kW/7kW, S3 CDN decoder, `VinFastStationMapperTest.kt`)
3. **Phase 03**: Dual-Tier Station Repository & Fallback (`DualTierStationRepository`, `DualTierStationRepositoryFallbackTest.kt`)
4. **Phase 04**: Integration, UI Wiring & Verification (`MainActivity`, ViewModels, `VinFastDirectIntegrationTest.kt`)

---

## 🔧 Key Architectural Decisions
- **Direct Client Integration (Option A)**: Direct communication with VinFast CAPP backend (`https://mobile.connected-car.vinfast.vn/`) with fast-fail timeout (5s).
- **Dual-Tier Fallback Coordinator**: Tier 1 (VinFast direct) -> Tier 2 (`evcs.vn` HMAC signed). Offline cache (Tier 3) removed per user request.
- **Automobile-Only Filtering**: Exclude all connectors with `type <= 7000` (strips 3.5kW and 7kW motorbike plugs); keep 11kW AC and 30kW - 360kW DC.
- **Direct S3 CDN Photos**: Double-layer Base64 decode for `cpo-prod-s3.vinfastauto.com`, bypassing Cloudflare 403 blocks.
- **100% ID & Schema Invariance**: `locationId` is identical between VinFast and `evcs.vn`, keeping Cloud Firestore favorites (`users/{uid}/userdata/favorites`) 100% intact.

---

## 📁 Quick Commands
- Resume / Start Coding: `/code phase-01`
- Review Context: `/recap`
