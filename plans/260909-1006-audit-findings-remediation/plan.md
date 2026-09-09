# Plan: Audit Findings Remediation & Hardening

Created: 2026-09-09 10:06
Status: ✅ Completed

## Overview

Remediate all clinical findings identified during the comprehensive system and architecture audit ([docs/reports/audit_20260909_0948.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/docs/reports/audit_20260909_0948.md)). The remediation covers package alignment for Google Cloud API key restrictions, permission minimization in AndroidManifest, Cloud Firestore security rules specification, elimination of private Car App Library internal resource references, Android 12+ data extraction backup configuration, and Jetpack Compose autoboxing optimization.

The implementation is structured into 3 sequential phases:
1. **Core Security, Google Routes Package Alignment & Manifest Hardening:** Align `GoogleRoutesClient` package header (`X-Android-Package`) with `applicationId` (`com.evplus.app`), eliminate unused `ACCESS_BACKGROUND_LOCATION` from `AndroidManifest.xml` to prevent Play Store rejection, and establish version-controlled `firestore.rules` for per-user data isolation.
2. **Android 12+ Data Extraction & Automotive Car Host Resource Hardening:** Define custom public resource `car_hosts_allowlist` to eradicate private resource dependency in `CarServiceConfig`, create `res/xml/data_extraction_rules.xml` to protect local data from unencrypted device transfers, and wire into `AndroidManifest.xml`.
3. **Compose Performance & UI Code Cleanup:** Eliminate `Long` autoboxing via `mutableLongStateOf` in `NearbyScreen.kt`, update Car App `PaneTemplate` builder calls to eliminate deprecations in `StationDetailCarScreen.kt`, and clean up unused legacy parameters in screen composables.

## Tech Stack
- Platform: Android 8.0+ (API 26-34) & Android Auto (Car App Library Level 7)
- Language: Kotlin 1.9.23 (JVM 17)
- UI: Jetpack Compose (Material 3 BOM 2024.04.01)
- Security: Cloud Firestore Rules, AndroidX Security Crypto, Android KeyStore
- Testing: JUnit 4 + MockWebServer + kotlinx-coroutines-test

## Execution Rules
- **Phase isolation:** Complete each phase sequentially.
- **Single test rule:** For each phase, add exactly one comprehensive file-based test to verify the core functionality of that phase after implementation. Do not create or run more than one test per phase.
- **Verification step:** After completing each phase, run only that single test for verification:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- **Stop for review:** Halt after test execution and await user review before proceeding to the next phase.

## Phases

| Phase | Name | Status | Single Verification Test |
|---|---|---|---|
| 01 | Core Security, Google Routes Package Alignment & Manifest Hardening | ✅ Completed | `com.evcs.favorites.security.SecurityAndPackageConfigAuditTest` |
| 02 | Android 12+ Data Extraction & Automotive Car Host Resource Hardening | ✅ Completed | `com.evcs.favorites.config.DataExtractionAndCarHostConfigTest` |
| 03 | Compose Performance & UI Code Cleanup | ✅ Completed | `com.evcs.favorites.ui.ComposeOptimizationAndCleanupTest` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Run only that single test after completing each phase via:
  `./gradlew testDebugUnitTest --tests "<TestClass>"`
- Stop after each phase for user review and validation.
