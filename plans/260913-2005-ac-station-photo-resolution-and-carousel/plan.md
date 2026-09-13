# Plan: AC Station Photo Resolution & Carousel Display

Created: 2026-09-13 20:05
Status: ✅ Completed

## Overview
This plan implements on-demand station photo resolution and UI carousel rendering for AC charging stations in EV-Plus:
1. **On-Demand Photo Resolution Pipeline (Data/Coordinator)**:
   - When viewing an AC station (e.g., from HERE EV API where `images` is initially empty), resolve authentic VinFast station photos on-demand during detail loading.
   - Match by station locationId or coordinate proximity against VinFast search API or extract media from the EVCS station detail HTML page (`tram-sac-vinfast-{id}.html`).
   - Decode VinFast CDN CloudFront URLs and update the active station model in `StationDetailUiState`.
   - Preserve all photos returned by the server in their default order without filtering by kW tier.

2. **Unified UI Presentation & Lightbox (Presentation/Compose)**:
   - Render AC station photos at the exact same location as DC stations: in `StationPhotoCarousel` at the bottom of `NativeStationDetailContent`.
   - Ensure identical behavior between DC and AC: 16:9 ratio, horizontal swipe pagination (`HorizontalPager`), dot indicators, page badge (`x/y`), and full-screen lightbox modal (`StationPhotoViewerModal`) with pinch-to-zoom (1x-4x) and pan gestures.
   - Synchronize between Portrait Bottom Sheet (`NativeStationDetailSheet`) and Landscape right pane (`NativeStationDetailContent`).
   - Handle loading and network errors gracefully with neutral placeholder framing without breaking layout.

## Tech Stack
- Language: Kotlin 1.9.23
- UI Framework: Jetpack Compose Material 3, Coil 2.6.0
- Architecture: Clean Architecture + MVVM, StateFlow, Coroutines
- Testing: JUnit 4, Kotlin Coroutines Test

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | AC Station Photo Resolution Pipeline | ✅ Completed | 100% | `AcStationPhotoResolutionPipelineTest.kt` |
| 02 | UI Photo Loading State & Carousel Rendering | ✅ Completed | 100% | `AcStationPhotoCarouselUiTest.kt` |

## Execution Guidelines
- All phase files must be written in English.
- Each phase contains **exactly one** comprehensive file-based test to verify core functionality after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification, then stop for user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
