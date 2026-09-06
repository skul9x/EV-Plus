# Plan: Multi-Tier Driving Routing Engine (ETA) & BYOK Architecture

Status: 🟡 In Planning  
Created: 2026-09-03  
Target Platform: Android (Kotlin + Jetpack Compose)  

---

## 1. Overview
Currently, the EVCS Favorites application calculates distances to charging stations using the straight-line **Haversine formula**. While 100% offline and zero-latency, this does not reflect real driving road networks, turns, bridges, or actual driving duration (ETA) and live traffic congestion.

This plan implements a **3-Tier Multi-Engine Driving Routing System** coupled with a **Bring Your Own Key (BYOK)** architecture:
1. **Out-of-the-Box Community Access (100% Free)**: Every user gets real road driving distance and duration immediately via the open-source **OSRM Table Service** without requiring any account or API key.
2. **BYOK Live Traffic (Google Routes API v2)**: Power users can securely enter their own Google Cloud API Key in Settings to unlock real-time traffic conditions (`TRAFFIC_AWARE`), congestion color coding, and precision ETA.
3. **Multi-Engine Arbitration & Automatic Fallback**: The coordinator intelligently routes between Tier 1 (Google Routes v2), Tier 2 (OSRM), and Tier 3 (Haversine), providing seamless automatic fallback when network quotas or connection errors occur.
4. **Smart 2-Step Hybrid Pipeline & ETA Sorting**: Immediate local Haversine sort followed by asynchronous top-candidate routing, with in-memory caching and nearest-ETA station ordering.

---

## 2. Technical Stack
- **Language:** Kotlin 2.0+ (JVM 17)
- **UI Framework:** Jetpack Compose + Material 3
- **Architecture:** MVVM + Clean Architecture + StateFlow
- **Networking:** OkHttp 4.12+ / Kotlinx Serialization JSON 1.6.3
- **Security & Storage:** AndroidX Security Crypto (`EncryptedSharedPreferences`) / SharedPreferences
- **Testing:** JUnit 4 + Kotlinx Coroutines Test + OkHttp MockWebServer

---

## 3. Phases & Verification Strategy

Each phase contains **exactly one comprehensive file-based test** to verify its core functionality after implementation. Only that single test will be run upon phase completion.

| Phase | Title | Core Deliverable | Single Verification Test | Status |
|---|---|---|---|---|
| **01** | Core Routing Clients & Data Models | Domain models (`DrivingMetrics`, `RoutingSettings`) + `GoogleRoutesClient` (computeRouteMatrix v2 with staticDuration delay ratio & Android headers) + `OsrmRoutingClient` (table service with User-Agent & matrix offset) | `RoutingClientsTest.kt` | ✅ Completed |
| **02** | Multi-Tier Coordinator & Fallback Arbitration | Central coordinator managing 3 tiers (`AUTO`, `GOOGLE_ONLY`, `OSRM_ONLY`, `HAVERSINE_ONLY`) with coordinate sanitization and automatic error/quota fallback cascades | `MultiTierRoutingCoordinatorTest.kt` | ✅ Completed |
| **03** | Secure BYOK Preferences & Settings UI | `RoutingPreferencesManager` encrypted storage + key validation test + Compose `RoutingSettingsModal` BottomSheet & TopAppBar action | `RoutingPreferencesManagerTest.kt` | ✅ Completed |
| **04** | ViewModel Hybrid Pipeline & ETA Sorting | 2-step hybrid query (0ms Haversine -> IO coordinator candidate routing) + 200m/3-min cache + shortest-ETA station list sorting | `FavoritesRoutingViewModelTest.kt` | ⬜ Pending |
| **05** | Station Card ETA Pill & Navigation UI | Enhanced `DistanceBadge` in `FlowRow` with traffic color tokens (Green/Amber/Red) + road/air distance labels + MapNavigator 1-tap intent | `StationCardRoutingTest.kt` | ✅ Completed |

---

## 4. Execution Rules
1. Work is executed strictly phase-by-phase.
2. For each phase, implement the required components and its **single test**.
3. Run **only that single test** for verification.
4. Stop immediately after each phase so the user can review before proceeding.
