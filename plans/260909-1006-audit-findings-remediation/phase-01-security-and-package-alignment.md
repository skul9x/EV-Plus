# Phase 01: Core Security, Google Routes Package Alignment & Manifest Hardening

Status: ✅ Completed
Dependencies: None

## Objective

Remediate clinical findings FIND-01, FIND-02, and FIND-03 identified in the system audit:
1. Fix package mismatch in `GoogleRoutesClient.kt` by updating `ANDROID_PACKAGE_VALUE` from `"com.evcs.favorites"` to `"com.evplus.app"` (matching `applicationId`).
2. Remove unused high-risk permission `android.permission.ACCESS_BACKGROUND_LOCATION` from `AndroidManifest.xml`.
3. Create version-controlled `firestore.rules` in the repository root enforcing strict per-user authorization for `/users/{userId}/userdata/favorites`.

## Requirements

### Functional
- Align `GoogleRoutesClient.ANDROID_PACKAGE_VALUE` with `com.evplus.app` so that BYOK Google Cloud API keys restricted to the official package name do not fail with HTTP 403 Forbidden.
- Remove `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />` from [app/src/main/AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml).
- Create [firestore.rules](file:///home/skul9x/Desktop/Code/EV-Plus-main/firestore.rules) restricting reads and writes under `/users/{userId}/userdata/favorites` to authenticated requests where `request.auth.uid == userId`.

### Non-Functional
- Maintain 100% backward compatibility for all existing unit tests in `MultiTierRoutingCoordinatorTest` and `GoogleRoutesClientTest`.
- Ensure zero degradation in Foreground Service behavior (`FocusModeForegroundService` continues to function normally with `ACCESS_FINE_LOCATION`).

## Implementation Steps
1. In [GoogleRoutesClient.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt):
   - Update `ANDROID_PACKAGE_VALUE` constant:
     ```kotlin
     const val ANDROID_PACKAGE_VALUE = "com.evplus.app"
     ```
2. In [app/src/main/AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml):
   - Delete line 8: `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />`.
3. In [firestore.rules](file:///home/skul9x/Desktop/Code/EV-Plus-main/firestore.rules):
   - Create root rules file:
     ```javascript
     rules_version = '2';
     service cloud.firestore {
       match /databases/{database}/documents {
         match /users/{userId}/userdata/favorites {
           allow read, write: if request.auth != null && request.auth.uid == userId;
         }
       }
     }
     ```
4. Create single verification test:
   [app/src/test/java/com/evcs/favorites/security/SecurityAndPackageConfigAuditTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/security/SecurityAndPackageConfigAuditTest.kt)

## Files to Create/Modify
- [GoogleRoutesClient.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt) - [MODIFY] Update `ANDROID_PACKAGE_VALUE` to `"com.evplus.app"`
- [app/src/main/AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml) - [MODIFY] Remove `ACCESS_BACKGROUND_LOCATION`
- [firestore.rules](file:///home/skul9x/Desktop/Code/EV-Plus-main/firestore.rules) - [NEW] Add Firestore security rules
- [app/src/test/java/com/evcs/favorites/security/SecurityAndPackageConfigAuditTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/security/SecurityAndPackageConfigAuditTest.kt) - [NEW] Single comprehensive verification test

## Test Criteria
- Verify `GoogleRoutesClient.ANDROID_PACKAGE_VALUE == "com.evplus.app"`.
- Verify `X-Android-Package` header generated in HTTP requests equals `"com.evplus.app"`.
- Verify `AndroidManifest.xml` does NOT contain `android.permission.ACCESS_BACKGROUND_LOCATION`.
- Verify `firestore.rules` exists and contains correct pattern `request.auth.uid == userId`.

## Verification Execution
Run only the single test for this phase:
```bash
./gradlew testDebugUnitTest --tests "com.evcs.favorites.security.SecurityAndPackageConfigAuditTest"
```

---
Next Phase: [phase-02-data-extraction-and-car-host-hardening.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-1006-audit-findings-remediation/phase-02-data-extraction-and-car-host-hardening.md)
