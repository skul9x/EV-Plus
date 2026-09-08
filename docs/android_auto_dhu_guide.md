# Android Auto Desktop Head Unit (DHU) Operational Guide & Android 15 Verification

This guide outlines the complete operational workflow to simulate, run, and verify **EV-Plus** on Android Auto using Google's Desktop Head Unit (DHU) and real Android 15 devices without requiring a Google Play Store release.

---

## 1. Prerequisites & SDK Installation

### 1.1. Install Desktop Head Unit via Android SDK
Install the Desktop Head Unit package using `sdkmanager` included in Android command-line tools:

```bash
sdkmanager "extras;google;auto"
```

The DHU binary will be installed to:
- **Linux/macOS:** `$ANDROID_HOME/extras/google/auto/desktop-head-unit`
- **Windows:** `%ANDROID_HOME%\extras\google\auto\desktop-head-unit.exe`

Ensure execute permissions on Linux/macOS:
```bash
chmod +x $ANDROID_HOME/extras/google/auto/desktop-head-unit
```

### 1.2. Verify ADB Setup
Ensure ADB is available in your PATH:
```bash
adb version
```
Connect your Android 15 device via USB and ensure it appears in `adb devices`:
```bash
adb devices
```

---

## 2. Android 15 Phone Configuration (Sideload & Head Unit Server)

To allow the sideloaded EV-Plus APK to run on the car display without Google Play signing restrictions, follow these steps on your Android 15 phone:

### Step-by-Step Developer Activation:
1. **Open Settings**:
   - Go to phone `Settings` -> Search for `Android Auto` (or navigate to `Connected devices` -> `Android Auto`).
2. **Locate Version Section**:
   - Scroll down to the bottom of the Android Auto settings screen to find the **Version** (`Phiên bản`) section.
3. **Unlock Developer Mode**:
   - Tap continuously **10 times** on the "Version" field until a dialog prompt asks to allow development settings. Tap **OK** (`Đồng ý`).
4. **Access Developer Settings**:
   - Tap the **three-dot overflow menu** in the top-right corner of the screen.
   - Select **Developer settings** (`Cài đặt cho nhà phát triển`).
5. **Enable Unknown Sources**:
   - Check the checkbox for **"Unknown sources"** (`Nguồn không xác định`).
   - *CRITICAL:* This flag permits sideloaded debug/release APKs built with Android Auto Car App Library (`androidx.car.app`) to appear in the car launcher.
6. **Start Head Unit Server**:
   - Return to the Android Auto main screen.
   - Tap the **three-dot overflow menu** again.
   - Select **"Start head unit server"** (`Khởi động máy chủ đầu phát`).
   - A persistent foreground notification *"Android Auto - Head unit server is running"* will appear in the Android notification shade.

---

## 3. Desktop Head Unit (DHU) Simulation Workflow

### 3.1. Configure ADB Port Forwarding
Google DHU communicates with the Android Auto Head Unit Server on the phone via TCP port `5277`:

```bash
adb forward tcp:5277 tcp:5277
```

To verify active port forwards:
```bash
adb forward --list
```
Output must contain:
```text
<device_serial> tcp:5277 tcp:5277
```

### 3.2. Launch Google DHU on PC/Linux
Run the DHU executable from your Android SDK location:

```bash
# Default resolution (800x480 or widescreen 1920x720 depending on default.ini):
$ANDROID_HOME/extras/google/auto/desktop-head-unit

# Or specify a custom resolution configuration:
$ANDROID_HOME/extras/google/auto/desktop-head-unit --config config/default.ini
```

Available screen presets in `config/`:
- `config/default.ini` (Standard 800x480 touch)
- `config/1080p.ini` (1920x1080 high-resolution widescreen)
- `config/portrait.ini` (768x1024 vertical automotive screen)

---

## 4. End-to-End Verification Checklist

Once DHU is launched, perform the following verification steps:

| Step | Action | Expected Result |
|---|---|---|
| **1. App Launcher** | Open car app launcher on DHU | The **EV-Plus** icon and label appear in the car launcher. |
| **2. Launch App** | Click the EV-Plus icon | Opens `MainCarScreen` using `PlaceListMapTemplate`. |
| **3. Live List** | View station items | Displays station name, status badge (`🟢 4/8 TRỐNG` or `🔴 0 TRỐNG`), distance (`km`), and address. |
| **4. Station Details** | Tap a station item | Navigates to `StationDetailCarScreen` showing DC fast-charge socket breakdown (`250kW`, `150kW`, `60kW`, `30kW`) and "⚡ DẪN ĐƯỜNG & THEO DÕI" action button. |
| **5. Navigation Intent** | Tap "Dẫn đường & Theo dõi" | Head unit dispatches `CarContext.ACTION_NAVIGATE` with `geo:0,0?q=lat,lng(Name)`. Google Maps on the DHU immediately starts turn-by-turn guidance to the selected EV station. |
| **6. Focus Mode Bridge** | Check mobile phone screen | `FocusModeForegroundService` starts concurrently, showing floating HUD widget and starting real-time station availability telemetry. |
| **7. Voice Ducking** | Trigger full station scenario | If destination station becomes full, Audio Ducking transiently lowers car audio volume, and Vietnamese TTS announces: *"Cảnh báo: Trạm sạc vừa hết chỗ! Đã tìm thấy trạm thay thế..."*. |

---

## 5. Troubleshooting & Common Pitfalls

### Problem 1: EV-Plus does not appear in DHU Car App Launcher
- **Cause:** "Unknown sources" is not checked in Android Auto Developer Settings, or manifest declaration is missing.
- **Fix:**
  1. Confirm step 2.5 ("Unknown sources") is enabled.
  2. Ensure `<service android:name=".car.EvPlusCarAppService" android:exported="true">` with `<category android:name="androidx.car.app.category.POI" />` is declared.
  3. Ensure `<uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />` is declared in `AndroidManifest.xml`.

### Problem 2: DHU displays "Waiting for connection..."
- **Cause:** TCP port `5277` is not forwarded or Head Unit Server was stopped.
- **Fix:**
  1. Verify Android Auto notification shows *"Head unit server is running"*.
  2. Re-run: `adb forward tcp:5277 tcp:5277`.
  3. Check device connection: `adb devices`.

### Problem 3: Port 5277 already in use on PC
- **Cause:** A previous DHU instance is still running in background.
- **Fix:**
  ```bash
  fuser -k 5277/tcp
  # Or kill lingering DHU process:
  killall desktop-head-unit
  ```

### Problem 4: Android 15 Notification or Location Permission Missing
- **Cause:** Android 15 requires explicit runtime grants for `POST_NOTIFICATIONS` and foreground service types (`FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_DATA_SYNC`).
- **Fix:**
  ```bash
  adb shell pm grant com.evcs.favorites android.permission.POST_NOTIFICATIONS
  adb shell pm grant com.evcs.favorites android.permission.ACCESS_FINE_LOCATION
  adb shell pm grant com.evcs.favorites android.permission.ACCESS_COARSE_LOCATION
  ```

---

## 6. Continuous Integration & Headless Verification

Automated contract validation is executed in CI via headless unit tests without requiring a physical car or GUI:

```bash
./gradlew testDebugUnitTest --tests "com.evcs.favorites.car.AndroidAutoContractVerificationTest"
```

This verifies XML descriptors, Android Auto intent schema adherence, screen backstack limitations (<= 12), and mobile focus synchronization.
