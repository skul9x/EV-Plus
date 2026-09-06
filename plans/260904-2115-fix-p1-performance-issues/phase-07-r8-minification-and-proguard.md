# Phase 07: R8 Code/Resource Shrinking & Proguard Rules Configuration

Status:  Completed
Issue ID: PERF-BUILD-01
Dependencies: Phase 06

## Objective
Reduce release APK size by up to 50%, accelerate DEX bytecode loading into memory, and protect against runtime reflection crashes by enabling R8 minification (`isMinifyEnabled = true`), resource shrinking (`isShrinkResources = true`), and creating a production-grade `proguard-rules.pro`.

---

## Requirements

### Functional
- [x] Create `app/proguard-rules.pro` with explicit keep rules for:
  - **Kotlinx Serialization**:
    - Preserve `@kotlinx.serialization.Serializable` classes, fields, and constructors.
    - Preserve static `Companion` fields and `serializer()` methods on default and named companions.
    - Preserve `KSerializer` implementations.
  - **OkHttp & Okio**:
    - `-dontwarn okhttp3.**`, `-dontwarn okio.**`.
    - Preserve `PublicSuffixDatabase` and reflective access members.
  - **AndroidX Security Crypto**:
    - `-keepclassmembers class androidx.security.crypto.** { *; }`
    - `-dontwarn androidx.security.crypto.**`
  - **Google Play Services Location**:
    - Preserve `com.google.android.gms.location.**` interfaces and callback methods.
  - **Jetpack Compose**:
    - Preserve Compose runtime attributes and tooling preview annotations.
  - **Coroutines**:
    - `-dontwarn kotlinx.coroutines.**`
- [x] In `app/build.gradle.kts`, configure `release` build type:
  - `isMinifyEnabled = true`
  - `isShrinkResources = true`
  - `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`
- [x] Verify that serialized models (`Station`, `PowerPort`, `StationForecast`, `DrivingMetrics`) serialize and deserialize cleanly without missing field or serializer exceptions.

### Non-Functional
- [x] Binary size: Substantial reduction in APK and DEX file size.
- [x] Stability: Zero `ClassNotFoundException`, `NoSuchMethodException`, or serialization reflection errors in minified builds.

---

## Implementation Steps
1. **Create `app/proguard-rules.pro`**:
   - Write comprehensive rules covering Serialization, OkHttp, Security Crypto, Play Services Location, and Coroutines.
2. **Update `app/build.gradle.kts`**:
   - Enable `isMinifyEnabled = true` and `isShrinkResources = true` under `buildTypes.getByName("release")`.
3. **Verify Configuration**:
   - Run verification test ensuring Proguard rules exist, are structurally sound, and cover all required serialization targets.

---

## Files to Modify/Create
- [NEW] `app/proguard-rules.pro` - Production Proguard / R8 optimization rules.
- [MODIFY] `app/build.gradle.kts` - Enable minification and resource shrinking for release builds.
- [NEW] `app/src/test/java/com/evcs/favorites/R8ProGuardConfigurationVerificationTest.kt` - Exactly one comprehensive test for Phase 07.

---

## Test Criteria (Single Comprehensive Test)
- **Test File**: `app/src/test/java/com/evcs/favorites/R8ProGuardConfigurationVerificationTest.kt`
- **Core Verifications**:
  1. `app/proguard-rules.pro` exists and defines valid R8 rules for all key libraries.
  2. Proguard rules contain explicit protection for `@kotlinx.serialization.Serializable` classes and companions.
  3. `build.gradle.kts` contains active `isMinifyEnabled = true` and `isShrinkResources = true` configurations.
  4. Kotlinx Serialization json engine parses core domain models (`Station`, `StationForecast`, `PowerPort`, `DrivingMetrics`) cleanly.

---

## Verification Command
```bash
./gradlew testDebugUnitTest --tests com.evcs.favorites.R8ProGuardConfigurationVerificationTest
```
