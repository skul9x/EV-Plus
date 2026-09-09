# Phase 02: Android 12+ Data Extraction & Automotive Car Host Resource Hardening

Status: ✅ Completed
Dependencies: Phase 01

## Objective

Remediate clinical findings FIND-04 and FIND-05 identified in the system audit:
1. Eradicate reference to private resource `androidx.car.app.R.array.hosts_allowlist_sample` in [CarServiceConfig.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt) by defining a dedicated local resource array in `res/values/car_hosts.xml`.
2. Add [data_extraction_rules.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/res/xml/data_extraction_rules.xml) for Android 12+ (API 31+) to prevent leakage of local cache and unencrypted shared preferences during device-to-device transfers, and bind it in `AndroidManifest.xml`.

## Requirements

### Functional
- Create `res/values/car_hosts.xml` containing `<string-array name="car_hosts_allowlist">` populated with official Android Auto hosts (`com.google.android.projection.gearhead`).
- Update `CarServiceConfig.createHostValidator` to reference `R.array.car_hosts_allowlist` rather than private internal AAR resource `androidx.car.app.R.array.hosts_allowlist_sample`.
- Create `res/xml/data_extraction_rules.xml` configuring cloud-backup encryption requirements and device-transfer exclusions.
- Update `<application>` in [AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml) with `android:dataExtractionRules="@xml/data_extraction_rules"`.

### Non-Functional
- Eliminate Android Lint `PrivateResource` and `DataExtractionRules` warnings.
- Preserve full compatibility with Desktop Head Unit (DHU) simulation (`isDebuggable == true`) and Android 15 sideloaded distribution.

## Implementation Steps
1. Create [app/src/main/res/values/car_hosts.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/res/values/car_hosts.xml):
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <resources>
       <string-array name="car_hosts_allowlist">
           <item>com.google.android.projection.gearhead</item>
       </string-array>
   </resources>
   ```
2. In [app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt):
   Update `createHostValidator`:
   ```kotlin
   fun createHostValidator(
       context: Context,
       isDebuggable: Boolean = isAppDebuggable(context)
   ): HostValidator {
       return if (isDebuggable) {
           HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
       } else {
           HostValidator.Builder(context)
               .addAllowedHosts(com.evcs.favorites.R.array.car_hosts_allowlist)
               .build()
       }
   }
   ```
3. Create [app/src/main/res/xml/data_extraction_rules.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/res/xml/data_extraction_rules.xml):
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <data-extraction-rules>
       <cloud-backup disableIfNoEncryptionCapabilities="true">
           <exclude path="." />
       </cloud-backup>
       <device-transfer>
           <exclude path="." />
       </device-transfer>
   </data-extraction-rules>
   ```
4. In [app/src/main/AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml):
   Add attribute `android:dataExtractionRules="@xml/data_extraction_rules"` to `<application>`.
5. Create single verification test:
   [app/src/test/java/com/evcs/favorites/config/DataExtractionAndCarHostConfigTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/config/DataExtractionAndCarHostConfigTest.kt)

## Files to Create/Modify
- [app/src/main/res/values/car_hosts.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/res/values/car_hosts.xml) - [NEW] Local car host allowlist array
- [app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt) - [MODIFY] Point to local resource
- [app/src/main/res/xml/data_extraction_rules.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/res/xml/data_extraction_rules.xml) - [NEW] Android 12+ backup configuration
- [app/src/main/AndroidManifest.xml](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/AndroidManifest.xml) - [MODIFY] Add `android:dataExtractionRules`
- [app/src/test/java/com/evcs/favorites/config/DataExtractionAndCarHostConfigTest.kt](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/test/java/com/evcs/favorites/config/DataExtractionAndCarHostConfigTest.kt) - [NEW] Single comprehensive verification test

## Test Criteria
- Verify `R.array.car_hosts_allowlist` exists in resources and contains `com.google.android.projection.gearhead`.
- Verify `CarServiceConfig.createHostValidator` does not reference `androidx.car.app.R.array.hosts_allowlist_sample`.
- Verify `res/xml/data_extraction_rules.xml` is valid XML with cloud-backup and device-transfer rules.
- Verify `AndroidManifest.xml` binds `@xml/data_extraction_rules`.

## Verification Execution
Run only the single test for this phase:
```bash
./gradlew testDebugUnitTest --tests "com.evcs.favorites.config.DataExtractionAndCarHostConfigTest"
```

---
Next Phase: [phase-03-compose-performance-and-code-cleanup.md](file:///home/skul9x/Desktop/Code/EV-Plus-main/plans/260909-1006-audit-findings-remediation/phase-03-compose-performance-and-code-cleanup.md)
