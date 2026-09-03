# Plan: EVCS Favorites Minimalist App (MVP)

Status: 🟡 In Planning  
Created: 2026-09-03  
Target Platform: Android (Kotlin + Jetpack Compose)  

---

## 1. Overview
A lightweight, fast, 100% Native Android application written in Kotlin and Jetpack Compose to solve the sluggish, bloated WebView experience of the official `com.evcs.vn` app. The app allows users to authenticate via Email OTP and immediately displays their pre-saved favorite charging stations from the EVCS server with real-time distance and 1-tap navigation.

---

## 2. Technical Stack
- **Language:** Kotlin 2.0+ (JVM 21 / 17)
- **UI Framework:** Jetpack Compose + Material Design 3
- **Architecture:** MVVM + Clean Architecture + StateFlow
- **Networking:** OkHttp 4.12+ / Retrofit 2.11+ / Kotlinx Serialization
- **Security & Storage:** Jetpack DataStore / EncryptedSharedPreferences (saving persistent auth cookie `evcs` [1-year validity], session cookie `PHPSESSID`, device UUID `evcs_did`, and CSRF token)
- **Crypto:** Java `javax.crypto.Mac` (HMAC-SHA256 signature generator matching decompiled secret key)
- **Location:** Google Play Services FusedLocationProviderClient + Haversine Formula

---

## 3. Phases & Verification Strategy

Each phase contains **exactly one comprehensive file-based test** to verify its core functionality after implementation. Only that single test will be run upon phase completion.

| Phase | Title | Core Deliverable | Single Verification Test | Status |
|---|---|---|---|---|
| **01** | Project Setup & Auth Engine | Android Gradle setup + Email OTP flow + `evcs` & `PHPSESSID` cookie session management | `AuthEngineTest.kt` | ✅ Completed |
| **02** | EVCS API & Favorites Repo | HMAC signer + Dual-API repository (`/favorite.html` + `/search` HMAC enrichment for GPS & live plugs) | `EvcsRepositoryTest.kt` | ✅ Completed |
| **03** | Location & Distance Service | GPS tracking + Haversine distance calculator & nearest-first sorting | `DistanceCalculatorTest.kt` | ⬜ Pending |
| **04** | Compose UI & ViewModel | Minimalist Material 3 UI (Login & Station Cards with live plug availability badges) | `FavoritesViewModelTest.kt` | ✅ Completed |
| **05** | Navigation & End-to-End Integration | 1-Tap Google Maps routing (`google.navigation`) + Offline station caching | `MapIntentIntegrationTest.kt` | ⬜ Pending |

---

## 4. Execution Rules
1. Work is executed strictly phase-by-phase.
2. For each phase, implement the required components and its **single test**.
3. Run **only that single test** for verification.
4. Stop immediately after each phase so the user can review before proceeding.
