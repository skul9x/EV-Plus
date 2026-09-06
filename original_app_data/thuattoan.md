# Thuật Toán & Luồng Hoạt Động

Em đã nắm rất rõ và trọn vẹn 100% toàn bộ yêu cầu cũng như các ràng buộc kỹ thuật của anh. Em chưa chỉnh sửa bất kỳ dòng code nào và xin tổng hợp lại bản kiến trúc/flow chốt để anh kiểm tra:

## 📋 Tóm Tắt Phương Án & Luồng Hoạt Động

### 1. Màn hình & Điều hướng (Navigation)
- **Bottom Navigation Bar** ở mép dưới gồm 2 mục chính:
  - 🌟 **Yêu thích** (Favorites - Màn hình mặc định khi mở app).
  - 📍 **Tìm quanh đây** (Nearby - Màn hình tìm kiếm mới).

### 2. Dữ liệu & Phạm vi trạm sạc
- Chỉ quét và hiển thị **Trạm sạc ô tô điện VinFast**.
- Bỏ qua các loại trạm khác và trạm đổi pin xe máy để app tập trung cao độ, mượt mà và nhẹ nhàng.

### 3. Bộ lọc công suất sạc & Logic Cổng trống
- **Danh sách các mức công suất hỗ trợ lọc:** 
  `360kW`, `300kW`, `250kW`, `180kW`, `150kW`, `120kW`, `80kW`, `60kW`, `40kW`, `30kW`, `22kW`, `20kW`, `11kW`, `7kW`, `3.5kW`.
- **Quy tắc lọc:**
  - **OR:** Trạm chỉ cần có ít nhất một trụ sạc đạt mức công suất đã chọn. Nếu không chọn mức nào $\rightarrow$ hiển thị tất cả các trạm hợp lệ.
  - **Ẩn trạm không khả dụng / Hết cổng:** Chỉ hiển thị những trụ sạc đang hoạt động và còn cổng trống (`numberOfAvailableEvse > 0`). Nếu tất cả các trụ sạc thuộc công suất đang lọc đều đang bận (0 cổng trống) hoặc bảo trì thì trạm đó sẽ bị ẩn đi.

### 4. Chiến lược Định tuyến Đa Tầng (Multi-Tier Routing cho Top 10)
- **Bước 1 (Lấy dữ liệu thô):** Người dùng bấm nút "Tìm trạm quanh đây" $\rightarrow$ lấy GPS hiện tại $\rightarrow$ gọi `POST /search?t=...` lấy danh sách trạm VinFast trong khu vực về máy.
- **Bước 2 (Lọc & Sắp xếp sơ bộ):** Áp dụng bộ lọc công suất & cổng trống ở client $\rightarrow$ tính khoảng cách đường chim bay (Haversine) cho các trạm vượt qua bộ lọc $\rightarrow$ sắp xếp từ gần đến xa $\rightarrow$ cắt lấy đúng **10 trạm gần nhất**.
- **Bước 3 (Routing thực tế & ETA):** Gửi đúng danh sách 10 trạm này vào `MultiTierRoutingCoordinator` (Google Routes API v2 / OSRM Table Service) để lấy khoảng cách đường bộ thực tế và thời gian lái xe theo lưu lượng giao thông.
- Mỗi lần người dùng thay đổi bộ lọc chip công suất, app sẽ lập tức lọc lại, lấy Top 10 mới và request lại routing cho 10 trạm đó!

### 5. Tương tác "Thả tim / Lưu vào Yêu thích" (Cross-Screen Favorite)
- Ngay trên mỗi thẻ trạm của tab "Quanh đây" sẽ có icon trái tim ❤️:
  - Trạm nào đã có trong danh sách Favorites $\rightarrow$ hiển thị tim sáng (đỏ/cam).
  - Trạm nào chưa có $\rightarrow$ hiển thị icon viền tim trống.
- **Khi nhấn vào icon tim:**
  - **Đã đăng nhập:** Thêm trạm vào danh sách yêu thích và đồng bộ lên cloud EVCS qua API `POST /favorite.html` với action `save`.
  - **Chưa đăng nhập:** Mở Dialog thông báo: *"Vui lòng đăng nhập để lưu trạm vào danh sách yêu thích"* kèm nút chuyển sang màn hình đăng nhập.

### 6. Hành vi GPS
- **Kích hoạt thủ công 100%:** Chỉ lấy vị trí và tìm kiếm khi người dùng chủ động nhấn nút "Tìm trạm quanh đây" (hoặc nút "Làm mới").
- Khi xe lăn bánh di chuyển, app không tự động quét ngầm hay tự reload để tiết kiệm tối đa pin và dung lượng 4G/5G của người dùng.
