# Plan: EVCS Station Detail & Favorite Info Display

Status: 🟡 In Planning  
Created: 2026-09-03  
Target Platform: Android (Kotlin + Jetpack Compose)  

---

## 1. Overview
In the official EVCS application, favorite stations saved to the user's account display their connector configuration (`✧ 30kW, 20kW, 3.5kW`), and tapping on any favorite station card opens the comprehensive **Station Detail View** (showing real-time available/total plugs like `30kW trống 2/4`, `20kW trống 2/2`, charging charts for 24h/7d/30d, occupancy rates, and navigation).

In our current implementation:
1. Favorite station cards attempt to display real-time ports via a single-point geographic radius search. When favorite stations are outside that radius (e.g. stations located in Bắc Ninh while searching around Hanoi), they fall back to displaying `trống 0/0` and an inaccurate `Hết cổng` badge.
2. Clicking a favorite station card does not open any station detail view.

This plan aligns the app with official EVCS behavior: cleanly displaying connector information and status without bogus `0/0` counts, providing full station detail inspection via an in-app interactive sheet, and accurately enriching favorite stations with live plug metrics.

---

## 2. Technical Stack
- **Language:** Kotlin 2.0+ (JVM 21 / 17)
- **UI Framework:** Jetpack Compose + Material 3 + AndroidView (WebView)
- **Architecture:** MVVM + Clean Architecture + StateFlow
- **Session & Network:** OkHttp 4.12+ / CookieManager sync / Kotlinx Serialization
- **Testing:** JUnit 4 + Kotlinx Coroutines Test + MockWebServer

---

## 3. Phases & Verification Strategy

Each phase contains **exactly one comprehensive file-based test** to verify its core functionality after implementation. Only that single test will be run upon phase completion.

| Phase | Title | Core Deliverable | Single Verification Test | Status |
|---|---|---|---|---|
| **01** | Station Card Connectors & Status Fix | Fix favorite card rendering: display actual station connectors and prevent false `0/0` / `Hết cổng` badges when live search data is not yet available | `StationCardStatusTest.kt` | ⬜ Pending |
| **02** | Station Detail View & Card Interaction | Make station cards clickable to open an in-app Station Detail ModalBottomSheet/Screen displaying complete station info, real-time ports, and charging charts matching the official app | `StationDetailModalTest.kt` | ✅ Completed |
| **03** | Real-Time Enrichment for Favorites | Resolve favorite station coordinates and query targeted live metrics so favorite stations show live plug availability (`trống 2/4`, `trống 2/2`) regardless of device location | `FavoritesLiveEnrichmentTest.kt` | ✅ Completed |

---

## 4. Execution Rules
1. Work is executed strictly phase-by-phase.
2. For each phase, implement the required components and its **single test**.
3. Run **only that single test** for verification.
4. Stop immediately after each phase so the user can review before proceeding.
