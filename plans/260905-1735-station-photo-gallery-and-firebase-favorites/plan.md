# Plan: Station Photo Gallery & Firebase Firestore Favorites Migration

Created: 2026-09-05 17:35
Status: 🟡 In Progress

## Overview
Implement a major station detail and user account modernization for the EV-Plus Android application based on `docs/BRIEF.md` and `thuattoan.txt`:
1. **Performance & Socket.io Elimination**: Strip `io.socket:socket.io-client` completely, eliminate the 4-second Stage 2 telemetry timeout, and accelerate Native Station Detail Bottom Sheet loading to ~150-200ms. Adapt existing telemetry tests (`EvcsTelemetryRepositoryAndStatsEngineTest.kt`) to ensure project-wide `compileDebugUnitTestKotlin` safety without socket dependencies.
2. **Station Photo Gallery & Lightbox Viewer**: Extract direct VinFast CloudFront S3 CDN URLs (`cpo-prod-s3.vinfastauto.com`) by decoding multi-pass base64 tokens using JVM-compatible `java.util.Base64` and URI query parsing (bypassing Cloudflare 403 blocks), integrate Coil Compose 2.6.0 with disk and memory pooling, render a 16:9 carousel with page indicator pills that smoothly collapses to zero height when empty, and build a full-screen Lightbox dialog (`Dialog` with `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`) to prevent gesture collisions with the parent `ModalBottomSheet`, supporting pinch-to-zoom (1x-4x), double-tap zoom (1x <-> 2.5x), boundary-clamped pan, horizontal paging, and downward swipe-to-dismiss.
3. **Firebase Auth & Google Sign-In**: Configure Google Services plugin 4.4.2, integrate Firebase Auth (BOM 33.10.0, non-KTX) and AndroidX Credential Manager 1.3.0 + `googleid:1.1.1` (`GetGoogleIdOption` with Web Client ID `49442747133-giv98l3ik6b124kdr6o9t257sntgp8c1.apps.googleusercontent.com`), supporting frictionless Local-First anonymous guest mode, 1-tap Google Sign-In using `Activity` context, and seamless account upgrade with `FirebaseAuthUserCollisionException` handling.
4. **Local-First Firestore Favorites Sync**: Migrate from legacy third-party EVCS email OTP/PHP cookie favorites to a single-document Cloud Firestore model (`/users/{userId}/userdata/favorites`) with atomic `FieldPath.of("favorites", stationId)` Map operations (immune to dot splitting in IDs like `C.BNI0012`). Store rich station metadata snapshots locally and on cloud for 0ms instant offline access across devices, support dual parsing (rich object or scalar timestamp), implement the login sync decision matrix with 3 conflict resolution strategies (Merge, Prefer Cloud, Prefer Local), eliminate the Email OTP `LoginScreen` gate in `MainActivity`, and maintain backward-compatible delegation in `EvcsRepository` to keep all existing unit tests passing.

## Tech Stack
- Language: Kotlin 1.9.23 (JVM Target 17)
- UI Framework: Jetpack Compose Material 3 (BOM 2024.04.01)
- Image Loading: Coil Compose 2.6.0
- Cloud Backend: Firebase BOM 33.10.0 (`firebase-auth`, `firebase-firestore` non-KTX artifacts)
- Authentication: AndroidX Credential Manager 1.3.0 + `com.google.android.libraries.identity.googleid:googleid:1.1.1`
- Gradle Plugins: `com.google.gms.google-services:4.4.2`
- Network & Storage: OkHttp 4.12.0, DataStore Preferences / PlainSharedPrefsStorage
- Testing: JUnit 4, Kotlinx Coroutines Test 1.8.0, OkHttp MockWebServer 4.12.0

## Phases

| Phase | Name | Status | Progress | Single Comprehensive Test File |
|-------|------|--------|----------|--------------------------------|
| 01 | Socket.io Removal & Detail Loading Performance Optimization | ✅ Completed | 100% | `app/src/test/java/com/evcs/favorites/data/repository/StationDetailStreamlinedTelemetryTest.kt` |
| 02 | Station Photo Gallery, Direct CDN Decoding & Full-Screen Lightbox | ✅ Completed | 100% | `app/src/test/java/com/evcs/favorites/ui/components/StationPhotoGalleryAndDecoderTest.kt` |
| 03 | Firebase Auth & Google Sign-In Integration | ⬜ Pending | 0% | `app/src/test/java/com/evcs/favorites/data/auth/FirebaseAuthAndCredentialManagerTest.kt` |
| 04 | Local-First Firestore Favorites Sync & Profile UI Integration | ⬜ Pending | 0% | `app/src/test/java/com/evcs/favorites/data/repository/LocalFirstFirestoreFavoritesSyncTest.kt` |

## Execution Guidelines
- All phase files are written in English.
- For each phase, add **exactly one** comprehensive file-based test to verify the core functionality of that phase after implementation.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification.
- Stop after each phase verification so the user can review before proceeding.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
