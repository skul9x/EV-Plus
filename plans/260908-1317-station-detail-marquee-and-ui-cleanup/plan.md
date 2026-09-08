# Plan: Station Detail Marquee Scrolling & Hero UI Streamlining

Created: 2026-09-08 13:17
Status: 🟡 In Progress

## Overview

Optimize the station detail presentation in EV-Plus (particularly for in-car landscape displays and mobile orientations) by:
1. **Removing Redundant "Cổng sạc" Label:** Eliminate the static `"Cổng sạc"` title above the charging port pills in `NativeStationDetailSheet.kt`. The individual port pills already explicitly indicate charger power and vacancy (e.g., `🟢 22kW: Trống 1/1 cổng`, `🟢 150kW: Trống 1/2 cổng`), so the extra label wastes precious vertical space on car displays.
2. **Single-Line Marquee Scrolling for Name & Address:** Constrain both the station name and detailed station address to a single line (`maxLines = 1`, `softWrap = false`) with `Modifier.basicMarquee()`. For stations with long names or extensive addresses, text will smoothly scroll horizontally after an initial reading pause (2000ms delay, ~35dp/s velocity), preventing multi-line overflow and ensuring primary CTAs (`⚡ DẪN ĐƯỜNG & THEO DÕI`) remain visible above the fold.

## Tech Stack
- Platform: Android 8.0+ (API 26-34)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose Material 3 & Compose Foundation (`Modifier.basicMarquee`)
- Unit Testing: JUnit4 + Compose UI Contract Testing

## Phases

| Phase | Name | Status | Test |
|-------|------|--------|------|
| 01 | Remove Redundant "Cổng sạc" Label & Compact Hero Section | ⬜ Pending | `com.evcs.favorites.ui.StationDetailPortHeroStreamlineTest` |
| 02 | Single-Line Smooth Marquee for Station Name & Address | ⬜ Pending | `com.evcs.favorites.ui.StationDetailMarqueeTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Run via `./gradlew testDebugUnitTest --tests "<TestClass>"`.
