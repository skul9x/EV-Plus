# 💡 BRIEF: Nâng Cấp Station Detail (Photo Gallery & Viewer, Gỡ Socket.io) & Firebase Firestore Favorites

**Ngày tạo:** 05/09/2026  
**Brainstorm cùng:** skul9x  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT

1. **Hiệu năng & Trải nghiệm Bottom Sheet chi tiết trạm:**
   - App đang phụ thuộc vào `io.socket:socket.io-client` để kết nối WebSocket tới `https://www2.evcs.vn/` chỉ nhằm mục đích lấy dữ liệu lịch sử sử dụng 24h (tính toán Peak, Rush Hour, Fill Rate).
   - Quá trình kết nối socket ngầm này tốn từ 500ms đến 4000ms (timeout 4s), tốn pin và dữ liệu 4G.
   - Đối với tài xế thực tế, thông tin "Thống kê sử dụng 24h" ít có giá trị thực tiễn bằng **Hình ảnh thực tế của trạm sạc** (vị trí lối vào hầm hay mặt đất, vị trí đặt trụ sạc, biển chỉ dẫn).

2. **Hạn chế của hệ sinh thái EVCS Favorites cũ:**
   - Phụ thuộc vào server bên thứ 3 (`POST /favorite.html`), đăng nhập bằng mã Email OTP chậm chạp, cookie phiên (`PHPSESSID`) dễ hết hạn hoặc bị Cloudflare chặn.
   - Bị giới hạn số lượng trạm yêu thích khắt khe (`favLimit: 10`).
   - Khả năng hoạt động offline hạn chế nếu chưa cache đầy đủ.

---

## 2. GIẢI PHÁP ĐỀ XUẤT

### 🚀 Giai đoạn A: Tối Ưu Hiệu Năng & Gỡ Bỏ Socket.io
- Gỡ bỏ hoàn toàn thư viện `io.socket:socket.io-client:2.1.1`.
- Loại bỏ Stage 2 (tải Socket.io 24h) và tác vụ tính toán thống kê trong `StationDetailCoordinator` và `StationDetailViewModel`.
- Giảm thời gian hiển thị đầy đủ Bottom Sheet từ ~2s xuống còn **~150ms - 200ms**.

### 🖼️ Giai đoạn B: Station Photo Gallery & Full-Screen Image Viewer
1. **Tích hợp Coil Compose (`io.coil-kt:coil-compose`):**
   - Hỗ trợ Image Caching tự động (Disk Cache + Memory Bitmap Pooling).
2. **Giải mã Direct VinFast S3 CDN URL:**
   - Trong dữ liệu trạm (`POST /search`), trường `media` chứa chuỗi dạng `media?file=...`.
   - Chuỗi này là **Double Base64 URL-encoded**. App sẽ giải mã 2 lớp để lấy URL gốc trực tiếp từ **CloudFront S3 CDN của VinFast** (`https://cpo-prod-s3.vinfastauto.com/charging-station-car/depot/images/...`).
   - Tải trực tiếp qua CloudFront: Tốc độ tối đa, không bị Cloudflare 403 Challenge, không phụ thuộc proxy evcs.vn.
3. **Hiển thị Carousel ảnh trên Bottom Sheet:**
   - Nếu trạm có ảnh: Hiển thị `HorizontalPager` (tỷ lệ 16:9, góc bo tròn Material 3).
   - Hiển thị pill badge chỉ số ở góc dưới phải: `1 / 3`, `2 / 3` kèm dot indicators.
   - Nếu trạm không có ảnh (`media` rỗng): **Tự động ẩn hoàn toàn khu vực ảnh**, không để khoảng trống thừa.
4. **Trình xem ảnh toàn màn hình (LightBox Modal):**
   - Bấm vào ảnh mở màn hình xem ảnh toàn màn hình trên nền đen 95%.
   - Hỗ trợ cử chỉ cảm ứng cao cấp:
     - **Pinch-to-zoom:** Phóng to/thu nhỏ 1x - 4x.
     - **Double-tap to zoom:** Chạm 2 lần để phóng to nhanh vào điểm chạm và chạm lại để về 1x.
     - **Pan / Drag:** Kéo rê tự do khi đang phóng to.
     - **Swipe Horizontal:** Vuốt qua lại giữa các ảnh.
     - **Swipe-to-Dismiss:** Vuốt nhẹ ảnh xuống dưới để đóng trình xem ảnh.
   - Thanh điều khiển trên cùng: Đếm ảnh `2 / 3` và nút `X` đóng.

### ⭐️ Giai đoạn C: Google Firebase Firestore Favorites & Google Sign-In
1. **Loại bỏ EVCS Favorites:**
   - Xóa bỏ toàn bộ logic OTP Email, Cookie, CSRF và API `POST /favorite.html` của EVCS.
2. **Kiến trúc Local-First + Firestore Sync:**
   - Hỗ trợ chế độ khách (Anonymous / Local-First): Sử dụng ngay khi mở app không cần đăng nhập.
   - Đăng nhập 1 chạm bằng **Google Sign-In**.
   - Cấu trúc dữ liệu Firestore tối ưu chi phí (Free Tier: 1 Document = 1 Read / 1 Write) tại `/users/{userId}/userdata/favorites`:
     ```json
     {
       "favorites": {
         "C.BNI0012": 1788597858,
         "C.BNI0320": 1788600120
       },
       "updated_at": 1788600500
     }
     ```
   - Thêm trạm: `update("favorites.C.BNI0012", timestamp)`
   - Xóa trạm: `update("favorites.C.BNI0012", FieldValue.delete())` (an toàn tuyệt đối, không lỗi lệch kiểu dữ liệu).
3. **Local Cache đầy đủ cho Offline 0ms:**
   - Bộ nhớ máy lưu snapshot metadata trạm (`id`, `name`, `address`, `lat`, `lon`, `connectors`, `images`, `added_at`).
   - Mở tab Yêu thích hiển thị tức thì trong 0ms kể cả khi mất mạng.
4. **UI Quản lý Tài khoản (Profile):**
   - Banner nhỏ gọn đầu tab Yêu thích: *"Đăng nhập để đồng bộ đám mây"*.
   - Đã đăng nhập: Avatar Google, Tên tài khoản, nút *"Đồng bộ"* và nút *"Đăng xuất"*.

---

## 3. CÁC TẬP TIN SẼ BỊ ẢNH HƯỞNG (CODEBASE IMPACT)

- **Cấu hình & Dependencies:**
  - `app/build.gradle.kts`: Gỡ bỏ `io.socket:socket.io-client`, thêm `io.coil-kt:coil-compose`, Firebase BOM, Firebase Firestore, Firebase Auth, Google Play Services Auth / Credential Manager.
- **Data & Repository Layer:**
  - `EvcsTelemetryDataSource.kt` & `EvcsTelemetryRepository.kt`: Loại bỏ Socket.io và 24h history.
  - `FavoritesRepository.kt` / `FirebaseFavoritesRepository.kt`: Triển khai Firestore Sync và Local Cache.
  - `StationModels.kt` / `Station.kt`: Thêm trường `images: List<String>`, mapper giải mã CloudFront CDN.
- **UI Layer:**
  - `NativeStationDetailSheet.kt`: Bỏ 2x2 stats grid; thêm Photo Carousel và logic ẩn khi rỗng ảnh.
  - `StationPhotoViewerModal.kt` (mới): Trình xem ảnh toàn màn hình (zoom, pan, dismiss).
  - `StationDetailCoordinator.kt`: Bỏ Stage 2 Socket.io.
  - `FavoritesScreen.kt`: Bổ sung banner Google Sign-in / Profile bar, kết nối Firestore Flow.
- **Test Suites:**
  - Cập nhật các bộ Unit Test liên quan đến Detail Sheet, Telemetry và Favorites.

---

## 4. ƯỚC TÍNH SƠ BỘ & RỦI RO

- **Độ phức tạp:** 🟡 Trung bình - Đầy đủ (Tích hợp Firebase, Coil, Gesture Zoom trong Compose).
- **Rủi ro:**
  - Cần đảm bảo cử chỉ Pinch-to-zoom và Swipe-to-dismiss không bị xung đột với hành vi vuốt đóng BottomSheet.
  - Cần file cấu hình `google-services.json` để Firebase SDK hoạt động trên thiết bị thật.

---

## 5. BƯỚC TIẾP THEO
→ Chuyển sang workflow `/plan` để chia các giai đoạn (Phases) thực thi chi tiết.
