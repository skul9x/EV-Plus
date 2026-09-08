# Phase 01: Gradle, Manifest & CarAppService Architecture
Status: ✅ Completed
Dependencies: None

## Objective
Configure build dependencies and AndroidManifest declarations for Android Auto Car App Library (`androidx.car.app:app:1.7.0` and `androidx.car.app:app-projected:1.7.0`), declare mandatory `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES"/>`, establish the automotive application descriptor (`automotive_app_desc.xml`), declare `<service android:name=".car.EvPlusCarAppService">` with Point of Interest (`POI`) category filter, implement host validation policies supporting Desktop Head Unit (DHU) and Android 15 release sideloading without Google Play Store signing restrictions, and wire dependency injection via `AppContainer`.

## Requirements

### Functional
1. **Dependency Configuration (`build.gradle.kts`)**:
   - Add core Car App Library: `implementation("androidx.car.app:app:1.7.0")`.
   - Add projection runtime: `implementation("androidx.car.app:app-projected:1.7.0")` (mandatory for Android Auto projection; without this, head unit cannot bind to CarAppService).
   - Add testing artifact: `testImplementation("androidx.car.app:app-testing:1.7.0")` for Car App unit testing.

2. **Automotive App Descriptor (`automotive_app_desc.xml`)**:
   - Create `app/src/main/res/xml/automotive_app_desc.xml`:
     ```xml
     <?xml version="1.0" encoding="utf-8"?>
     <automotiveApp>
         <uses name="template" />
     </automotiveApp>
     ```

3. **Manifest Integration (`AndroidManifest.xml`)**:
   - Declare required map template permission:
     - `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />` (strictly required by Car App Host to render `PlaceListMapTemplate`).
   - Declare optional automotive features:
     - `<uses-feature android:name="android.hardware.type.automotive" android:required="false" />`
     - `<uses-feature android:name="android.software.car.templates_host" android:required="false" />`
   - Declare Android Auto metadata inside `<application>`:
     - `<meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1" />`
     - `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />`
     - `<meta-data android:name="com.google.android.gms.car.notification.SmallIcon" android:resource="@mipmap/ic_launcher" />`
   - Declare `EvPlusCarAppService`:
     - `android:name=".car.EvPlusCarAppService"`
     - `android:exported="true"`
     - Intent filter action: `androidx.car.app.CarAppService`
     - Intent filter category: `androidx.car.app.category.POI` (standard POI category per Google specs; older `CHARGING` category is deprecated since API 1.3).

4. **Service & Host Validation Architecture (`EvPlusCarAppService.kt` & `CarServiceConfig.kt`)**:
   - Implement `EvPlusCarAppService` extending `CarAppService`:
     - Implements `createHostValidator(): HostValidator`.
     - Supports both development/DHU and standalone APK distribution on Android 15 (without requiring Google Play Store signing per `1.md` Section 3):
       - When debuggable: returns `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` for seamless DHU and local device testing.
       - In release builds with Android 15 Unknown Sources: uses `HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()` to validate official Android Auto projection hosts (`com.google.android.projection.gearhead`) while supporting non-Play-Store distribution.
     - Implements `onCreateSession(): Session` returning `EvPlusCarSession`.
   - Implement `CarServiceConfig` to encapsulate API level checks, host validation configurations, and permission assertions in a decoupled, testable structure.

5. **Application Container Integration (`AppContainer.kt`)**:
   - Ensure `AppContainer` exposes `EvcsRepository` and `SessionManager` so `EvPlusCarAppService` and its screens can access cached station data and favorites without creating duplicate network/storage instances.

### Non-Functional
- 100% pure JVM unit testable without requiring an active Android Auto head unit or Android emulator.
- Thread-safe service initialization and graceful fallback on non-supported hosts.
- Full compatibility with Android 15 background service launch restrictions.

## Implementation Steps
1. Add `androidx.car.app:app:1.7.0`, `androidx.car.app:app-projected:1.7.0`, and `androidx.car.app:app-testing:1.7.0` to `app/build.gradle.kts`.
2. Create `app/src/main/res/xml/automotive_app_desc.xml`.
3. Update `app/src/main/AndroidManifest.xml` with `MAP_TEMPLATES` permission, automotive metadata, service declaration, and intent filters.
4. Implement `CarServiceConfig.kt` and `EvPlusCarAppService.kt` in `app/src/main/java/com/evcs/favorites/car/`.
5. Update `AppContainer.kt` to expose repository singleton for the automotive subsystem.
6. Create single verification test `CarAppServiceConfigurationTest.kt` in `app/src/test/java/com/evcs/favorites/car/`.
7. Run the single verification test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.CarAppServiceConfigurationTest"`
8. Stop execution and await user review.

## Files to Create/Modify
- `app/build.gradle.kts` - [Modify] Add Car App Library 1.7.0 dependencies (`app`, `app-projected`, `app-testing`).
- `app/src/main/res/xml/automotive_app_desc.xml` - [New] Automotive app descriptor XML.
- `app/src/main/AndroidManifest.xml` - [Modify] Declare `MAP_TEMPLATES` permission, service, intent filter, and metadata.
- `app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt` - [New] Configuration, host validation logic, and constants.
- `app/src/main/java/com/evcs/favorites/car/EvPlusCarAppService.kt` - [New] Entry service for Android Auto.
- `app/src/main/java/com/evcs/favorites/di/AppContainer.kt` - [Modify] Expose repository singleton for car subsystem.
- `app/src/test/java/com/evcs/favorites/car/CarAppServiceConfigurationTest.kt` - [New] Single verification test for Phase 01.

## Test Criteria
- Single test: `com.evcs.favorites.car.CarAppServiceConfigurationTest`
  - Verifies automotive descriptor XML contains `<uses name="template" />`.
  - Verifies manifest contains `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES"/>`.
  - Verifies manifest metadata for `minCarApiLevel` (value "1") and `com.google.android.gms.car.application`.
  - Verifies `EvPlusCarAppService` intent filter action `androidx.car.app.CarAppService` and category `androidx.car.app.category.POI`.
  - Verifies `CarServiceConfig.createHostValidator()` safely returns `ALLOW_ALL_HOSTS_VALIDATOR` when debuggable.
  - Verifies session lifecycle and configuration parameters in a decoupled JVM test.

---
Next Phase: [Phase 02: Car Screens Architecture & Automotive PlaceListMapTemplate UI](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260908-1136-android-auto-car-app-library/phase-02-car-screens-and-placelistmap-template.md)

