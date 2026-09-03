# 💡 BRIEF: EVCS Favorites - Ứng dụng Trạm Sạc EV Yêu Thích

**Ngày tạo:** 03/09/2026  
**Brainstorm cùng:** skul9x  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
- Ứng dụng **Trạm Sạc EV** chính thức (`com.evcs.vn`) hiện tại là dạng **Hybrid WebView** bọc một trang web.
- Trải nghiệm người dùng bị chậm, giật lag, giao diện rườm rà (nhiều banner, trang tin tức, giới thiệu, tải chậm do Cloudflare và mã web nặng).
- Người dùng chỉ có một nhu cầu cốt lõi, thường xuyên nhất: **Mở app lên là thấy ngay danh sách các trạm sạc mình đã lưu yêu thích trước đó trên tài khoản EVCS**, kèm khoảng cách, trạng thái và nút chỉ đường nhanh đến trạm.

---

## 2. GIẢI PHÁP ĐỀ XUẤT
Xây dựng một ứng dụng Android **100% Native bằng Kotlin & Jetpack Compose** siêu nhẹ, khởi động tức thì:
- Xác thực tài khoản EVCS bằng **Email + OTP**.
- Lưu phiên đăng nhập an toàn, mở app là vào thẳng danh sách trạm sạc yêu thích.
- Hiển thị thông tin trực quan: Khoảng cách theo vị trí GPS thực tế, công suất sạc (Sạc nhanh DC / Sạc thường AC), tình trạng đỗ xe, giờ mở cửa.
- Nút bấm 1-chạm để kích hoạt chỉ đường (Google Maps / Apple Maps).

---

## 3. ĐỐI TƯỢNG SỬ DỤNG
- **Chính:** Chủ xe ô tô điện (EV) tại Việt Nam (VinFast, BYD, Porsche, Hyundai...) thường xuyên sử dụng hệ sinh thái trạm sạc EVCS và đã có danh sách trạm quen thuộc.
- **Mục tiêu:** Tiết kiệm thời gian thao tác khi đang lái xe hoặc sắp hết pin, cần tìm ngay trạm quen gần nhất.

---

## 4. PHÂN TÍCH KỸ THUẬT & ĐIỂM KHÁC BIỆT

| Tiêu chí | App Gốc (`com.evcs.vn`) | App Mới (Kotlin Jetpack Compose) |
|---|---|---|
| **Công nghệ** | WebView bọc web HTML/JS | 100% Kotlin Native + Jetpack Compose Material 3 |
| **Tốc độ mở** | Chậm (load webview, bypass cloudflare, tải scripts) | Tức thì (< 0.5s), mượt mà 120Hz |
| **Thao tác xem trạm** | Phức tạp (mở app -> load web -> vào menu -> bấm trạm yêu thích) | 0 thao tác: Mở app là thấy ngay danh sách |
| **Định vị & Khoảng cách** | Gọi qua Javascript Bridge | FusedLocationProviderClient Native, tính toán khoảng cách tức thời |
| **Dung lượng & RAM** | Chiếm nhiều RAM do chạy Web engine | Cực kỳ nhẹ, tiết kiệm pin |

---

## 5. TÍNH NĂNG CHI TIẾT

### 🚀 MVP (Bắt buộc có trong phiên bản đầu tiên):
- [ ] **Xác thực Email OTP:**
  - Nhập email đăng ký EVCS $\rightarrow$ Nhận mã OTP qua email $\rightarrow$ Xác thực thành công.
  - Lưu trữ Token / Session an toàn bằng `EncryptedSharedPreferences` / Jetpack `DataStore`.
- [ ] **Lấy & Hiển thị Danh sách Trạm Sạc Yêu Thích:**
  - Đồng bộ danh sách trạm mà tài khoản đã lưu trên server EVCS.
  - Tự động lấy vị trí GPS hiện tại của điện thoại và tính khoảng cách (km) đến từng trạm.
  - Sắp xếp trạm gần người dùng nhất lên đầu tiên.
- [ ] **Thẻ thông tin trạm sạc chi tiết:**
  - Tên trạm & Địa chỉ.
  - Huy hiệu loại sạc: Sạc nhanh DC (60kW, 120kW, 180kW, 250kW), Sạc chậm AC (7kW, 11kW).
  - Giờ hoạt động (VD: 24/7) và trạng thái đỗ xe (Miễn phí / Có phí).
- [ ] **Chỉ đường 1-Chạm:**
  - Nút "Dẫn đường" tự động mở ứng dụng bản đồ mặc định của máy (Google Maps) với tọa độ chính xác của trạm.

### 🎁 Phase 2 (Nâng cấp tiếp theo):
- [ ] **Home Screen Widget:** Tiện ích nhỏ ngoài màn hình chính Android, nhìn là thấy ngay trạm yêu thích gần nhất và dung lượng cổng sạc còn trống mà không cần mở app.
- [ ] **Hỗ trợ thêm/bỏ trạm yêu thích:** Tìm trạm mới quanh đây và đánh dấu sao để đồng bộ ngược lên tài khoản.
- [ ] **Android Auto:** Tích hợp POI đơn giản lên màn hình xe ô tô.

---

## 6. PHÂN TÍCH RỦI RO & GIẢI PHÁP KỸ THUẬT

1. **Rào cản Cloudflare trên `evcs.vn`:**
   - Server `evcs.vn` bật Cloudflare bot protection đối với các request cURL/HTTP thông thường.
   - **Giải pháp tối ưu:** Thiết kế luồng đăng nhập Email OTP qua một **Embedded Authentication Component** (chỉ mở một WebSheet/WebView nhẹ trong lần đăng nhập đầu tiên để giải quyết Cloudflare và nhập OTP). Sau khi xác thực thành công, app lưu session cookie/token và **đóng vĩnh viễn** webview. Mọi thao tác sau đó chạy hoàn toàn bằng Native API client.
2. **Cơ chế ký số `X-App-Signature`:**
   - Đã dịch ngược thành công thuật toán HMAC-SHA256 với Secret Key tại lớp `y/a.java`, sẵn sàng dùng trong Retrofit/OkHttp Interceptor để gọi trực tiếp các API tìm kiếm & dữ liệu trạm.

---

## 7. BƯỚC TIẾP THEO
→ Xác nhận Brief và chuyển sang giai đoạn `/plan` để thiết kế kiến trúc chi tiết (Gradle dependencies, ViewModel, State, UI Component).
