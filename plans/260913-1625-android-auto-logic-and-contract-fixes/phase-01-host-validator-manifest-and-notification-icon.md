# Phase 01: Host Validation, Manifest Compliance & Notification SmallIcon

Status: ✅ Completed
Dependencies: None

## Objective
Remediate release mode crash caused by malformed `car_hosts_allowlist` string-array format in `HostValidator.Builder.addAllowedHosts()`, and replace the invalid adaptive mipmap notification icon with an automotive-compliant monochrome drawable.

## Requirements
### Functional
- [x] Ensure `HostValidator` can be successfully instantiated in release/non-debuggable mode without throwing `IllegalArgumentException: Invalid allowed host entry`.
- [x] Support both official Gearhead signature digest validation and developer sideload mode without crashes.
- [x] Provide an automotive-compliant monochrome notification icon in `AndroidManifest.xml` meta-data `com.google.android.gms.car.notification.SmallIcon`.

### Non-Functional
- [x] Automotive Quality: Pass Android Auto HostValidator checks across all Android OS versions (API 26-34+).
- [x] Zero crash policy on service instantiation (`EvPlusCarAppService.createHostValidator()`).

## Implementation Steps
1. [x] Inspect `HostValidator.Builder.addAllowedHosts` requirements: each item must be formatted as `"<digest>,<package_name>"`.
2. [x] Update `app/src/main/res/values/car_hosts.xml` with valid entry format containing package name and signature digest (or configure `CarServiceConfig.createHostValidator` to provide a fallback allowing developer sideloading).
3. [x] Create a dedicated monochrome automotive notification drawable `res/drawable/ic_car_notification.xml` (24dp vector, white fill on transparent background) or bind an existing monochrome drawable.
4. [x] Update `AndroidManifest.xml` meta-data `com.google.android.gms.car.notification.SmallIcon` to reference the monochrome vector drawable.
5. [x] Update `AndroidAutoContractValidator` to verify both release host validator parsing and monochrome notification icon resource type.

## Files to Create/Modify
- `app/src/main/res/values/car_hosts.xml` - Format allowlist entries with valid signature digests
- `app/src/main/res/drawable/ic_car_notification.xml` - Monochrome vector drawable for head-unit notifications
- `app/src/main/AndroidManifest.xml` - Point SmallIcon meta-data to monochrome drawable
- `app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt` - Safe host validator builder for debug and release
- `app/src/main/java/com/evcs/favorites/car/AndroidAutoContractValidator.kt` - Add validation for host entries and icon
- `app/src/test/java/com/evcs/favorites/car/CarHostAndManifestContractTest.kt` - Comprehensive single verification test for Phase 01

## Single Verification Test
- **Test Class:** `com.evcs.favorites.car.CarHostAndManifestContractTest`
- **Execution Command:**
  `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarHostAndManifestContractTest"`
- **Verifications:**
  - Host validator creation in debuggable mode (`ALLOW_ALL_HOSTS_VALIDATOR`).
  - Host validator creation in release mode without `IllegalArgumentException`.
  - Manifest SmallIcon is non-null, valid resource, and points to a monochrome drawable.
  - Manifest meta-data `com.google.android.gms.car.application` points to valid `@xml/automotive_app_desc`.

---
Next Phase: [Phase 02: Car Screen Contracts, DistanceSpan Enforcement & Permission Safety](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260913-1625-android-auto-logic-and-contract-fixes/phase-02-car-screen-contracts-distancespan-and-location-permission.md)
