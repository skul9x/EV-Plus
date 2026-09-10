# Plan: Settings Power Filter Simplification & EV+ Commercial Branding Remediation

**Created:** 2026-09-10  
**Status:** 🟡 Pending Execution  
**Target:** EV+ Automotive Charging Station Application (`com.evcs.favorites`)

---

## Executive Summary
This plan addresses visual, functional, and commercial branding remediation in the Settings screen of EV+:
1. **Power Filter Simplification:** Eliminate the redundant "Chọn nhanh loại cổng / công suất" quick chip row from the Settings screen in both landscape and portrait orientations. Preserve exclusively the automotive touch stepper controls (`+/- 10kW`) for Min/Max power selection, and update the card subtitle to "Tùy chỉnh khoảng công suất kW mong muốn".
2. **App Branding & Author Attribution:** Standardize the app name in `AboutAppInfo` to `EV+` (matching `strings.xml`), update author row in `AboutAppCard` to display primary title "Nguyễn Duy Trường" with subtitle "Tác giả", and set copyright to "© 2026 Nguyễn Duy Trường".
3. **Sidebar Cleanup & Canvas Copyright Placement:** Remove the copyright footer text located beneath the "Đặt lại mặc định" button in the left sidebar. Anchor the official `© 2026 Nguyễn Duy Trường` copyright text directly on the right detail panel's dark canvas below `AboutAppCard`.

---

## Phases Overview

| Phase | Description | Status | Verification Test |
|---|---|---|---|
| 01 | Power Filter Simplification in Settings | ⬜ Pending | `SettingsPowerFilterSimplificationTest.kt` |
| 02 | EV+ Branding and Author Attribution in About Card | ⬜ Pending | `EVPlusCommercialBrandingTest.kt` |
| 03 | Settings Screen Layout & Canvas Copyright Placement | ⬜ Pending | `SettingsCopyrightCanvasPlacementTest.kt` |

---

## Constraints & Execution Protocol
1. **Language:** All phase files and documentation are in English.
2. **Single Verification Test per Phase:** Exactly one comprehensive file-based test per phase verifying core contracts.
3. **Execution Gate:** After each phase implementation, execute only its dedicated verification test, then halt for user review.
