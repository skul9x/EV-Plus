# ⚡ EV+

> **Native Android App** tra cứu trạm sạc xe điện thông minh, giám sát cổng sạc khả dụng thời gian thực và dẫn đường tối ưu dành cho tài xế xe điện.

[![Platform](https://img.shields.io/badge/Platform-Android_8.0+_(API_26--34)-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-1.9.23-purple.svg)](https://kotlinlang.org)
[![UI Framework](https://img.shields.io/badge/UI-Jetpack_Compose_Material3-blue.svg)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Clean_Architecture_+_MVVM-orange.svg)](#-kiến-trúc-hệ-thống)
[![Build](https://img.shields.io/badge/Build-Gradle_Kotlin_DSL-teal.svg)](https://gradle.org)
[![Security](https://img.shields.io/badge/Security-AES--256_GCM_Encrypted-red.svg)](#-bảo-mật--an-toàn-thông-tin)

---

## 📖 Giới thiệu (Overview)

**EV+** được phát triển nhằm giải quyết triệt để các hạn chế về hiệu năng, độ trễ và sự phụ thuộc vào webview cồng kềnh của ứng dụng gốc. Ứng dụng mang lại trải nghiệm Android Native thuần khiết: khởi động tức thì, định vị trạm sạc lân cận trong 0ms, tra cứu số cổng sạc trống và dẫn đường chính xác cho các tài xế xe điện (EV).

### 🌟 Điểm nổi bật:
- 🚀 **Khám phá tức thì không cần đăng nhập**: Mở app là thấy ngay toàn bộ trạm sạc quanh vị trí hiện tại kèm khoảng cách và trạng thái súng sạc.
- ⚡ **Theo dõi cổng sạc trực tiếp (Live Connectors)**: Hiển thị rõ số cổng sạc đang rảnh / tổng số cổng theo từng loại công suất (11kW, 20kW, 60kW, 120kW, 180kW, v.v.).
- 🎯 **Bộ lọc công suất thông minh & Ghi nhớ liên phiên (Filter Persistence)**: Dễ dàng lọc trạm theo công suất sạc phù hợp với xe; trạng thái bộ lọc được lưu tự động vào `SessionStorage` và khôi phục khi mở lại app.
- 🗺️ **Hệ thống Định tuyến 3 Tầng (3-Tier Multi-Engine Routing)**: Kết hợp linh hoạt giữa Google Routes API v2 (ETA thực tế kèm Live Traffic), OSRM Table Service (định tuyến mã nguồn mở) và Haversine baseline (khoảng cách đường thẳng offline 0ms).
- 📍 **Dẫn đường 1-Chạm (1-Tap Turn-by-Turn Navigation)**: Mở trực tiếp Google Maps với tọa độ chính xác của trạm sạc.
- ⭐️ **Đồng bộ Trạm Yêu thích**: Đăng nhập an toàn qua Email OTP, đồng bộ 2 chiều danh sách trạm yêu thích từ tài khoản EVCS.

---

## 🛠️ Tech Stack Chi Tiết

Dự án tuân thủ tiêu chuẩn **Modern Android Development (MAD)** với các công nghệ cập nhật nhất:

| Tầng / Thành phần | Công nghệ / Thư viện | Phiên bản | Vai trò & Mục đích |
|---|---|---|---|
| **Ngôn ngữ** | [Kotlin](https://kotlinlang.org/) | `1.9.23` | Ngôn ngữ phát triển toàn bộ dự án, type-safe & null-safe |
| **Hệ điều hành hỗ trợ** | Android SDK | `minSdk 26` / `targetSdk 34` | Tương thích từ Android 8.0 đến Android 14+ |
| **JVM Target** | OpenJDK | `Java 17` | Môi trường biên dịch chuẩn cho Gradle 8.7 và Kotlin |
| **Giao diện (UI)** | [Jetpack Compose](https://developer.android.com/jetpack/compose) | `BOM 2024.04.01` | Khung giao diện Declarative UI hiện đại, mượt mà |
| **Design System** | [Material Design 3](https://m3.material.io/) | `1.2.1` | Hệ thống thiết kế Material You với tone màu EV Emerald chủ đạo |
| **Biểu tượng (Icons)** | Compose Material Icons Extended | Đi kèm BOM | Cung cấp hệ thống icon phong phú (Bolt, Navigation, Place, Car, Time) |
| **Kiến trúc (Architecture)** | MVVM + Clean Architecture | AndroidX Lifecycle `2.7.0` | Tách biệt rành mạch Data Layer, Domain Model và UI State qua ViewModel Compose |
| **Bất đồng bộ & Phản ứng** | Kotlin Coroutines & Flow | `1.8.0` | Xử lý đa luồng ngầm, StateFlow và Unidirectional Data Flow (UDF) |
| **Mạng (Networking)** | [OkHttp](https://square.github.io/okhttp/) | `4.12.0` | Xử lý HTTP request, cookie jar, custom headers và connection pooling |
| **Chuyển đổi dữ liệu** | Kotlinx Serialization JSON | `1.6.3` | Parse JSON tốc độ cao, không cần reflection |
| **Phân tích Telemetry** | Server-Side HTML Parser | Tự phát triển | Phân tích SSR HTML từ trạm sạc EVCS để trích xuất dự báo hoàn thành sạc thời gian thực |
| **Lưu trữ & Bảo mật** | Jetpack Security Crypto & DataStore | `1.1.0-alpha06` / `1.0.0` | `EncryptedSharedPreferences` (AES-256 GCM) và `Preferences DataStore` |
| **Định vị (Location)** | Google Play Services Location | `21.2.0` | `FusedLocationProviderClient` lấy tọa độ GPS chính xác và tiết kiệm pin |
| **Định tuyến (Routing)** | Multi-Tier Engine | Tự phát triển | Phối hợp Google Routes API v2, OSRM Table Service và Haversine |
| **Bộ nhớ đệm (Caching)** | In-Memory TTL Cache | Tự phát triển | `ForecastCache` (TTL 30s) và `TrafficCache` (TTL 60s) chống spam request |
| **Kiểm thử (Testing)** | JUnit 4, MockWebServer, Coroutines Test | `4.13.2` / `4.12.0` | Unit test cho toàn bộ Repository, ViewModel, Router, Parser và Preferences |

---

## 📂 Cấu Trúc Thư Mục (Project Structure)

```
TramsacEV/
├── app/                                    # Mã nguồn Android chính
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml         # Manifest cấu hình permissions và app_name "Trạm Sạc EV+"
│   │   │   ├── java/com/evcs/favorites/
│   │   │   │   ├── data/                   # Data Layer (Api, Models, Preferences, Repository, Storage)
│   │   │   │   ├── domain/                 # Domain Layer (Models, Routing Engines, Location Services)
│   │   │   │   ├── navigation/             # AppTab (Nearby trái, Favorites phải)
│   │   │   │   ├── ui/                     # UI Layer (Screens, Components, Theme, ViewModels)
│   │   │   │   └── util/                   # Tiện ích (StationNameSanitizer, TokenGenerator, v.v.)
│   │   │   └── res/
│   │   │       └── values/strings.xml      # Định nghĩa chuỗi tài nguyên ("Trạm Sạc EV+")
│   │   └── test/                           # Bộ Unit Test toàn diện (JVM-based)
│   └── build.gradle.kts                    # Cấu hình build module app
│
├── original_app_data/                      # Dữ liệu & Tài liệu phân tích ứng dụng gốc EVCS
│   ├── Tramsac-EV.xapk                     # Gói ứng dụng gốc Android
│   ├── extracted_xapk/                     # Nội dung giải nén từ xapk
│   ├── apktool_out/                        # Tài nguyên và bytecode smali đã reverse
│   ├── src_code/                           # Mã nguồn Java/resources trích xuất qua JADX
│   ├── *.html, web_*.js                    # Web templates và client scripts gốc
│   ├── auth_state.json, verify_otp.py      # Session dump & script test OTP
│   └── thuattoan.md                        # Tài liệu phân tích thuật toán CSRF / Chữ ký bảo mật
│
├── plans/                                  # Kế hoạch phát triển tính năng (AWF Workflows)
│   ├── 260903-station-display-filter-persistence-and-tab-reorder/
│   ├── 260903-nearby-charging-stations-and-multi-tier-routing/
│   ├── 260903-station-detail-and-live-ports/
│   ├── 260903-google-maps-api-key-guide-and-settings-ux/
│   ├── 260903-multi-tier-routing-and-byok/
│   └── 260903-evcs-favorites-mvp/
│
├── docs/                                   # Tài liệu kỹ thuật chi tiết & Hướng dẫn sử dụng
├── .brain/                                 # Hệ thống lưu trữ ngữ cảnh vĩnh viễn (Eternal Context AWF)
├── .gitignore                              # Quy tắc loại trừ file rác, build outputs & heavy binaries
├── build.gradle.kts                        # Root build script
├── settings.gradle.kts                     # Project settings
└── README.md                               # Tài liệu tổng quan dự án
```

---

## 🚀 Hướng Dẫn Cài Đặt & Chạy (Getting Started)

### 1. Yêu cầu môi trường:
- **JDK:** OpenJDK 17 trở lên.
- **Android SDK:** Hỗ trợ compile SDK 34 (`Android 14`).
- **Thiết bị:** Thiết bị Android thật (bật USB Debugging) hoặc Android Emulator chạy Android 8.0 (API 26) trở lên.

### 2. Biên dịch & Chạy kiểm thử:
```bash
# Cấp quyền thực thi cho gradle wrapper (nếu cần)
chmod +x gradlew

# Chạy toàn bộ Unit Test trên JVM
./gradlew test

# Biên dịch APK Debug
./gradlew assembleDebug
```
File APK kết quả sẽ nằm tại: `app/build/outputs/apk/debug/app-debug.apk`.

### 3. Cài đặt trực tiếp lên thiết bị Android qua ADB:
```bash
# Kiểm tra thiết bị đã kết nối
adb devices

# Cài đặt APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Khởi chạy ứng dụng
adb shell am start -n com.evcs.favorites/.MainActivity
```

---

## 🗺️ Cơ Chế Định Tuyến Đa Tầng (3-Tier Multi-Engine Routing)

Để tối ưu hóa trải nghiệm dẫn đường cho tài xế xe điện, ứng dụng thiết kế cơ chế điều phối định tuyến 3 tầng:

1. **Tier 1 - Google Routes API v2 (BYOK - Bring Your Own Key):**
   - Cung cấp thời gian di chuyển (ETA) và tình trạng kẹt xe thời gian thực (Live Traffic).
   - Người dùng có thể tự nhập Google Cloud API Key cá nhân trong màn hình cài đặt mà không bị chia sẻ ra ngoài.
2. **Tier 2 - OSRM Table Service (Open Source Routing Machine):**
   - Tính toán khoảng cách lái xe thực tế qua hệ thống đường sá hoàn toàn miễn phí, chất lượng cao.
3. **Tier 3 - Haversine Baseline (Offline 100%):**
   - Tính toán khoảng cách đường thẳng ngay lập tức (0ms) khi chưa có kết nối mạng hoặc chưa nhận diện được tuyến đường.

---

## 🔒 Bảo Mật & An Toàn Thông Tin

- **Không lưu mật khẩu tĩnh:** Đăng nhập sử dụng cơ chế Email OTP 6 chữ số có giới hạn thời gian thực thi.
- **Mã hóa phần cứng AES-256 GCM:** Cookie phiên đăng nhập (`evcs`, `PHPSESSID`) và các tùy chọn bảo mật được lưu trong `EncryptedSharedPreferences` quản lý bởi Android KeyStore.
- **Không gửi telemetry bên thứ ba:** Mọi dữ liệu về tọa độ vị trí người dùng chỉ được sử dụng cục bộ trên máy để tính toán khoảng cách đến trạm sạc.

---

## 📄 Bản Quyền & Miễn Trừ Trách Nhiệm

- Dự án được phát triển phục vụ mục đích học tập, nghiên cứu kỹ thuật và nâng cao trải nghiệm cho cộng đồng sử dụng xe điện.
- Toàn bộ thương hiệu và API backend thuộc về đơn vị cung cấp dịch vụ sạc tương ứng.
