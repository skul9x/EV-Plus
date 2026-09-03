# Kế Hoạch: Hệ Thống Định Tuyến Lái Xe Thực Tế (ETA) Đa Tầng & Cơ Chế BYOK (Bring Your Own Key)

Status: 📋 In Planning  
Created: 2026-09-03  
Target Platform: Android (Kotlin + Jetpack Compose)  
File Location: `/home/skul9x/Desktop/Code/TramsacEV/plan.md`

---

## 1. Tổng Quan & Tầm Nhìn Sản Phẩm
Hiện tại, ứng dụng tính khoảng cách đến trạm sạc dựa trên công thức **đường chim bay (Haversine)**. Mặc dù chạy nhanh và hoạt động offline 100%, con số này chưa phản ánh được quãng đường ô tô phải lăn bánh thực tế qua các ngõ ngách, cầu cống, đường một chiều, cũng như không hiển thị được thời gian lái xe (ETA) và tình trạng tắc đường.

Kế hoạch này xây dựng hệ thống **Định tuyến Lái xe Đa Tầng (Multi-Tier Routing Engine)** đi kèm cơ chế **BYOK (Bring Your Own Key)**:
1. **Chia sẻ app an toàn cho cộng đồng**: Mọi người cài app đều dùng được ngay lập tức (100% Free) thông qua mạng lưới định tuyến mã nguồn mở **OSRM**.
2. **Kích hoạt tính năng cao cấp cho người dùng nâng cao**: Người dùng có thể tự dán **Google Cloud API Key** của riêng họ trực tiếp trên giao diện Settings để xem thời gian kẹt xe thực tế (Live Traffic) từ Google Maps.
3. **Chủ động kiểm soát chế độ**: Người dùng có quyền tùy chọn dùng Tầng 1, Tầng 2 hay Tầng 3 và bật/tắt cơ chế tự động chuyển dự phòng (Auto-Fallback).

---

## 2. Kiến Trúc 3 Tầng Định Tuyến (3-Tier Multi-Engine Architecture)

```
                     [Vị trí GPS Người Dùng]
                                │
                                ▼
         ┌─────────────────────────────────────────────┐
         │       BỘ ĐIỀU PHỐI ĐỊNH TUYẾN TRUNG TÂM      │
         │          (MultiTierRoutingCoordinator)      │
         └─────────────────────────────────────────────┘
                                │
          ┌─────────────────────┼─────────────────────┐
          ▼                     ▼                     ▼
   [TẦNG 1: GOOGLE]      [TẦNG 2: OSRM]       [TẦNG 3: HAVERSINE]
   • Google Routes v2    • OSRM Table Service • Đường chim bay
   • Kẹt xe realtime     • Chuẩn đường bộ     • Thuần offline 0ms
   • Cần API Key riêng   • Miễn phí 100%      • Không phụ thuộc mạng
   • Độ chính xác số 1   • Không cần tài khoản • Siêu tiết kiệm pin
```

### Chi tiết kỹ thuật từng tầng:

| Đặc tính | Tầng 1: Google Routes API v2 | Tầng 2: OSRM Table Service | Tầng 3: Haversine |
|---|---|---|---|
| **Endpoint** | `POST https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix` | `GET https://router.project-osrm.org/table/v1/driving/{coords}?sources=0` | Tính toán cục bộ bằng CPU điện thoại |
| **Dữ liệu trả về** | Cự ly lái xe (m) + Thời gian lái xe theo kẹt xe thực tế (s) | Cự ly lái xe (m) + Thời gian chạy chuẩn theo đường bộ (s) | Khoảng cách đường thẳng mặt cầu (km) |
| **Yêu cầu Key** | Cần Google API Key (nhập trong UI Settings) | **Không cần Key, không cần tài khoản** | Không cần |
| **Nhận diện tắc đường** | Có (`TRAFFIC_AWARE` phân loại: Xanh / Vàng / Đỏ) | Không (Tính theo giới hạn tốc độ cung đường) | Không |
| **Chi phí** | Miễn phí 10.000 lượt/tháng theo hạn ngạch Google per-SKU (Routes API Essentials) | **100% Miễn phí vĩnh viễn** | Miễn phí 100% |

---

## 3. Thiết Kế Cơ Chế BYOK & Giao Diện Cài Đặt (Settings UI)

### 3.1. Lưu trữ bảo mật (Secure Storage)
- Toàn bộ cài đặt của người dùng được lưu bằng `EncryptedSharedPreferences` qua `RoutingPreferencesManager.kt`.
- API Key được mã hóa chuẩn phần cứng (AES256-GCM / AES256-SIV), không lưu plain text và không bao giờ đồng bộ ra bên ngoài thiết bị.

### 3.2. Cấu trúc thiết lập người dùng (`RoutingSettings`):
```kotlin
data class RoutingSettings(
    val googleApiKey: String = "",
    val preferredEngine: RoutingEngineMode = RoutingEngineMode.AUTO,
    val autoFallbackEnabled: Boolean = true
)

enum class RoutingEngineMode {
    AUTO,          // Tự động: Google (nếu có key) -> OSRM -> Haversine
    GOOGLE_ONLY,   // Chỉ dùng Google Maps (bắt buộc có dữ liệu kẹt xe)
    OSRM_ONLY,     // Chỉ dùng OSRM (100% Free, không tốn quota Google)
    HAVERSINE_ONLY // Chỉ dùng đường chim bay (Siêu nhẹ, thuần offline)
}
```

### 3.3. Giao diện Cài Đặt (`RoutingSettingsModal.kt`):
- **Nút mở Settings**: Icon bánh răng `⚙️` trên thanh TopAppBar của màn hình chính.
- **Form cấu hình**:
  1. **Google Maps API Key**:
     - Ô nhập dạng password có icon ẩn/hiện mắt.
     - Nút **"Kiểm tra kết nối" (Test Connection)**: gửi thử 1 request test nhỏ, hiển thị tick xanh `✅ Hợp lệ` hoặc báo lỗi cụ thể nếu key sai/chưa kích hoạt billing.
  2. **Chế độ định tuyến (Radio Group)**:
     - 🔘 **Tự động (Khuyên dùng)**: Có key Google thì tận dụng traffic xịn, lỗi/hết quota thì tự trôi về OSRM.
     - 🔘 **Chỉ Google Maps**: Dành cho người muốn số liệu kẹt xe chính xác tuyệt đối.
     - 🔘 **Chỉ OSRM (Miễn phí)**: Dành cho người không muốn dùng Google Cloud.
     - 🔘 **Chỉ Đường chim bay**: Dành cho máy yếu hoặc tiết kiệm dữ liệu di động tối đa.
  3. **Công tắc (Switch)**:
     - `[Bật/Tắt] Tự động chuyển dự phòng (Auto-Fallback)`: Cho phép tự động chuyển sang tầng kế tiếp khi tầng ưu tiên mất mạng hoặc gặp lỗi.

---

## 4. Trải Nghiệm Hiển Thị Thẻ Trạm Sạc (`StationCard.kt`)

Thẻ trạm sạc sẽ hiển thị thông số hành trình sinh động và trực quan:
1. **Định dạng Pill**:
   - Khi có dữ liệu **Tầng 1 (Google)**: `🚗 8 phút • 3.2 km • Thông thoáng` (hoặc `Kẹt xe vừa`, `Ùn tắc`).
   - Khi có dữ liệu **Tầng 2 (OSRM)**: `🚗 9 phút • 3.4 km • Đường bộ`.
   - Khi ở **Tầng 3 (Haversine)**: `⚡ 2.5 km • Đường thẳng`.
2. **Quy tắc phối màu giao thông (Traffic Color Coding)**:
   - 🟢 Xanh lá (`#10B981`): Di chuyển thông thoáng ($v \ge 30 \text{ km/h}$).
   - 🟡 Vàng hổ phách (`#F59E0B`): Lưu thông chậm, kẹt xe nhẹ ($15 \le v < 30 \text{ km/h}$).
   - 🔴 Đỏ cảnh báo (`#EF4444`): Ùn tắc nghiêm trọng ($v < 15 \text{ km/h}$).
3. **Sắp xếp danh sách thông minh**:
   - Tự động sắp xếp các trạm theo **Thời gian di chuyển ngắn nhất (ETA)** lên đầu danh sách.

---

## 5. Kế Hoạch Triển Khai Chi Tiết (5 Giai Đoạn)

### 📌 Giai Đoạn 01: Xây Dựng Các Client Định Tuyến & Data Models
- Tạo model dữ liệu chuẩn `DrivingMetrics(distanceMeters, durationSeconds, trafficCondition, engineUsed)`.
- Xây dựng `GoogleRoutesClient.kt`: Gọi Google Routes API v2 `computeRouteMatrix` với headers `X-Goog-Api-Key` và `X-Goog-FieldMask`.
- Xây dựng `OsrmRoutingClient.kt`: Gọi OSRM Table Service API `table/v1/driving/` không cần key.
- Xây dựng `MultiTierRoutingCoordinator.kt`: Nhận danh sách trạm và điều phối gọi tầng tương ứng theo `RoutingSettings`.

### 📌 Giai Đoạn 02: Quản Lý Cấu Hình Bảo Mật (BYOK) & Giao Diện Settings
- Tạo `RoutingPreferencesManager.kt`: Quản lý đọc/ghi API Key, Engine Mode và Fallback Flag vào `EncryptedSharedPreferences`.
- Tạo Composable `RoutingSettingsModal.kt`: Giao diện Material 3 BottomSheet với ô nhập API Key, nút test kết nối, các nút radio chọn chế độ và toggle switch.
- Gắn nút icon `⚙️ Cài đặt` vào header của `FavoritesScreen.kt` và nối trạng thái vào `FavoritesViewModel`.

### 📌 Giai Đoạn 03: Tích Hợp Lọc 2 Bước & Làm Giàu Dữ Liệu Vào ViewModel
- Áp dụng thuật toán Hybrid 2 bước:
  - **Bước 1 (Local 0ms)**: Lấy Top 5 - 8 trạm gần nhất theo Haversine.
  - **Bước 2 (Async Coordinator)**: Gửi danh sách đã lọc vào `MultiTierRoutingCoordinator` chạy ngầm trong `Dispatchers.IO`.
- Cache kết quả ETA trong 3 phút hoặc khi vị trí GPS dịch chuyển quá 200m để hạn chế gọi mạng thừa thãi.
- Sắp xếp trạm theo thời gian di chuyển (`drivingDurationSeconds`) ngắn nhất.

### 📌 Giai Đoạn 04: Cải Tiến Giao Diện Thẻ Trạm Sạc (`StationCard.kt`)
- Nâng cấp `DistanceBadge` hiển thị số phút lái xe, cự ly và trạng thái giao thông tương ứng.
- Tích hợp điều hướng 1 chạm sang ứng dụng Google Maps Navigation với đúng tọa độ đích đã chọn.

### 📌 Giai Đoạn 05: Kiểm Thử Toàn Diện Bằng 1 File Test Duy Nhất
- Tạo `MultiTierRoutingIntegrationTest.kt` sử dụng `MockWebServer` xác thực:
  1. Gọi đúng Google Routes API v2 khi có API Key và parse đúng traffic.
  2. Tự động fallback sang OSRM khi Google API trả về lỗi 403/429/500.
  3. Hoạt động độc lập với OSRM khi không có API Key.
  4. Cơ chế bật/tắt Auto-Fallback hoạt động chính xác theo cài đặt của user.
  5. Sắp xếp danh sách trạm theo đúng thời gian lái xe ETA.

---

## 6. Danh Mục File Sẽ Tạo & Sửa Đổi

| File | Thao tác | Chức năng chính |
|---|---|---|
| `app/src/main/java/com/evcs/favorites/data/routing/DrivingMetrics.kt` | [Tạo mới] | Model dữ liệu hành trình lái xe & trạng thái kẹt xe |
| `app/src/main/java/com/evcs/favorites/data/routing/GoogleRoutesClient.kt` | [Tạo mới] | Client gọi Google Routes API v2 với live traffic |
| `app/src/main/java/com/evcs/favorites/data/routing/OsrmRoutingClient.kt` | [Tạo mới] | Client gọi OSRM Table Service hoàn toàn miễn phí |
| `app/src/main/java/com/evcs/favorites/data/routing/MultiTierRoutingCoordinator.kt` | [Tạo mới] | Điều phối định tuyến 3 tầng và tự động fallback |
| `app/src/main/java/com/evcs/favorites/data/routing/RoutingPreferencesManager.kt` | [Tạo mới] | Quản lý lưu trữ bảo mật BYOK (EncryptedSharedPreferences) |
| `app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt` | [Tạo mới] | BottomSheet cài đặt API Key, chọn Engine & Fallback |
| `app/src/main/java/com/evcs/favorites/ui/screens/FavoritesScreen.kt` | [Chỉnh sửa] | Thêm nút icon Cài đặt `⚙️` trên TopAppBar |
| `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` | [Chỉnh sửa] | Quản lý trạng thái cài đặt & kích hoạt định tuyến |
| `app/src/main/java/com/evcs/favorites/ui/components/StationCard.kt` | [Chỉnh sửa] | Hiển thị badge ETA, cự ly và màu sắc giao thông |
| `app/src/main/java/com/evcs/favorites/data/model/StationModels.kt` | [Chỉnh sửa] | Bổ sung trường drivingMetrics vào domain Station |
| `app/src/test/java/com/evcs/favorites/MultiTierRoutingIntegrationTest.kt` | [Tạo mới] | File kiểm thử tự động duy nhất xác thực toàn bộ tính năng |

---

## 7. Tiêu Chí Nghiệm Thu (Acceptance Criteria)
1. **An toàn bảo mật (BYOK)**: Không hardcode bất kỳ API Key nào trong mã nguồn APK. Người dùng tự nhập Key cá nhân an toàn.
2. **Hoạt động Out-of-the-box**: Người dùng mới cài app không cần nhập Key vẫn có thể tính toán lộ trình đường bộ chính xác thông qua OSRM (Tầng 2).
3. **Hiển thị mượt mà**: App hiển thị ngay cự ly ban đầu, không giật lag. Badge ETA cập nhật mượt mà sau khi có kết quả định tuyến.
4. **Độ tin cậy cao**: 100% vượt qua kiểm thử tự động trong `MultiTierRoutingIntegrationTest.kt`.
