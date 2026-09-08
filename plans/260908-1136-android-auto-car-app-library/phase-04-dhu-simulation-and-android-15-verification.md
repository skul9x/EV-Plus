# Phase 04: Desktop Head Unit (DHU) Simulation & Android 15 Automotive Verification
Status: ✅ Completed
Dependencies: Phase 01, Phase 02, Phase 03

## Objective
Establish the Desktop Head Unit (DHU) simulation workflow for local testing, provide step-by-step instructions for Android 15 developer settings (enabling "Unknown Sources" and starting Head Unit Server in Android Auto), and implement end-to-end automotive protocol contract verification to ensure seamless deployment and compliance with Google Automotive App Quality standards.

## Requirements

### Functional
1. **Desktop Head Unit (DHU) Operational Workflow (`docs/android_auto_dhu_guide.md`)**:
   - Create comprehensive developer guide documenting:
     - SDK installation: Install Desktop Head Unit package via Android SDK:
       `sdkmanager "extras;google;auto"`
     - ADB port forwarding setup:
       `adb forward tcp:5277 tcp:5277`
     - Android 15 configuration (Step-by-step per `1.md` Section 3.3):
       1. On phone, open `Settings` -> Search for `Android Auto` (or go to `Connected devices` -> `Android Auto`).
       2. Scroll down to the bottom to find the **Version** section.
       3. Tap continuously **10 times** on the "Version" field until a prompt appears confirming Developer Mode is activated. Tap **OK**.
       4. Tap the **three-dot overflow menu** (top-right corner) -> select **Developer settings** (`Cài đặt cho nhà phát triển`).
       5. Check the box for **"Unknown sources"** (`Nguồn không xác định`) - *Critical: Allows sideloaded APK to appear on the in-car display*.
       6. Return to the three-dot menu and tap **"Start head unit server"** (`Khởi động máy chủ đầu phát`).
     - Starting Google DHU on PC/Linux:
       - Run from Android SDK extras:
         `$ANDROID_HOME/extras/google/auto/desktop-head-unit`
       - Or specify resolution preset:
         `./desktop-head-unit --config config/default.ini`
     - Verifying EV-Plus icon appears in the car app launcher, opens `MainCarScreen`, displays live trạm sạc list, and launches 1-tap navigation to Google Maps.

2. **Automotive Contract Validator (`AndroidAutoContractValidator.kt`)**:
   - Implement runtime verification helper that validates:
     - Manifest configuration conforms to Google Automotive requirements.
     - Mandatory permission `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES"/>` is present.
     - Service declaration uses `android:name=".car.EvPlusCarAppService"` and `android:exported="true"`.
     - Category `androidx.car.app.category.POI` is properly bound.
     - Descriptor XML exists and specifies `<uses name="template" />`.
     - Screen backstack depth does not exceed Car App limits (12 screens maximum).
     - Navigation intents use `CarContext.ACTION_NAVIGATE` with valid `geo:` schema.
     - Mobile fallback uses `Intent.ACTION_VIEW` with `google.navigation:` schema.
     - Android 15 permissions (`ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_DATA_SYNC`) are compliant.
     - Voice TTS and Audio Ducking integration flags are verified.

3. **End-to-End Automotive Contract Verification Test (`AndroidAutoContractVerificationTest.kt`)**:
   - Single test validating the complete Android Auto subsystem contract:
     - Service registration and intent filtering (`POI` category).
     - XML automotive app descriptor parsing (`template` usage).
     - Manifest permissions (`MAP_TEMPLATES`, location, foreground services).
     - Screen navigation and backstack safety (depth <= 12).
     - Intent handoff from car screen to Google Maps (`CarContext.ACTION_NAVIGATE`).
     - Mobile fallback intent (`google.navigation:`).
     - Simultaneous Focus Mode background synchronization (`ACTION_START` with JSON payload) and Voice TTS policy integration.

### Non-Functional
- Clear, reproducible testing commands and steps.
- Headless execution capability for automated continuous integration.

## Implementation Steps
1. Create `docs/android_auto_dhu_guide.md` with step-by-step DHU and Android 15 instructions.
2. Implement `AndroidAutoContractValidator.kt` in `app/src/main/java/com/evcs/favorites/car/`.
3. Create single verification test `AndroidAutoContractVerificationTest.kt` in `app/src/test/java/com/evcs/favorites/car/`.
4. Run the single verification test:
   `./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.AndroidAutoContractVerificationTest"`
5. Stop execution and await user review.

## Files to Create/Modify
- `docs/android_auto_dhu_guide.md` - [New] Operational guide for DHU simulation and Android 15 testing.
- `app/src/main/java/com/evcs/favorites/car/AndroidAutoContractValidator.kt` - [New] Runtime validator for automotive contracts.
- `app/src/test/java/com/evcs/favorites/car/AndroidAutoContractVerificationTest.kt` - [New] Single verification test for Phase 04.

## Test Criteria
- Single test: `com.evcs.favorites.car.AndroidAutoContractVerificationTest`
  - Verifies complete manifest contract (service, intent filters, automotive metadata, `MAP_TEMPLATES`).
  - Verifies automotive descriptor XML schema (`<uses name="template" />`).
  - Verifies screen backstack enforcement (depth <= 12).
  - Verifies end-to-end flow from session creation -> main screen -> detail screen -> navigation intent dispatch (`CarContext.ACTION_NAVIGATE`) -> Focus Mode trigger.
  - Verifies DHU communication port protocol contracts.

---
Phase Complete: Android Auto Integration Plan Ready for Execution

