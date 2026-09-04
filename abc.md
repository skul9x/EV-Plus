# 📋 BẢN TÓM TẮT THIẾT KẾ BỘ LỌC THÔNG MINH (THE BRIEF)

### 1. Màn hình chính (NearbyScreen)
* **Giao diện mặc định:** 
  * Gồm 3 button lọc được sắp xếp: `[ Custom ]` (bên trái) — `[ DC ]` (ở giữa) — `[ AC ]` (bên phải).
  * Lưu lại trạng thái lọc của lần gần nhất (nếu trước đó chọn AC thì mở app lên vẫn là AC).
* **Khi chọn `[ AC ]`:**
  * Lọc hiển thị ngay các trạm có cổng AC khả dụng (từ 3.5kW - 22kW).
  * Bấm lại `[ AC ]` một lần nữa hoặc bấm nút `[ ✕ ]`: Hủy lọc, quay về hiển thị tất cả các trạm.
* **Khi chọn `[ DC ]`:**
  * Animation chuyển đổi mượt mà: Ẩn 3 nút ban đầu, xuất hiện thanh lọc DC gồm:
    * Nút **`[ ← Quay lại ]`** ở đầu bên trái (bấm vào sẽ animation ngược lại 3 nút ban đầu và hủy lọc DC).
    * Các chip dải công suất (Single-select): `[ ≤ 30kW ]`, `[ 30 - 60kW ]`, `[ ≥ 60kW ]`, `[ ≥ 120kW ]`.
  * Khi vừa bấm DC: Chưa lọc vội, giữ danh sách và đợi user chọn một dải công suất cụ thể. Luôn giữ chọn 1 chip (muốn thoát hẳn thì bấm `[ ← Quay lại ]`).
* **Khi chọn `[ Custom ]`:**
  * **Nếu chưa từng cấu hình trong Setting:** Hiển thị popup / thông báo: *"Bạn chưa cấu hình bộ lọc tùy chỉnh. Đi đến Cài đặt?"* kèm nút bấm mở ngay màn hình Setting.
  * **Nếu đã cấu hình:** Áp dụng bộ lọc tùy chỉnh đã lưu.
* **Quy tắc lọc trạm hỗn hợp:** Trạm chỉ được coi là khả dụng nếu cổng đáp ứng đúng tiêu chí đang lọc còn chỗ trống (ví dụ: đang lọc `DC ≥ 60kW` mà cổng DC này hết chỗ thì trạm sẽ bị ẩn, dù cổng AC của trạm đó vẫn còn trống).

---

### 2. Màn hình Cài đặt (Settings Modal)
* Tích hợp thêm mục **"Bộ lọc tùy chỉnh (Custom Filter)"** vào màn hình Setting (mở từ nút Setting ở góc trên cùng bên phải).
* Đảm bảo giao diện có **Scrollbar** mượt mà, trực quan.
* Hỗ trợ 2 chế độ chọn thay thế nhau (Mutual Exclusive):
  1. **Chọn nhanh bằng Chip:** `[Tất cả]`, `[🔌 AC]`, `[⚡ DC ≤ 30kW]`, `[⚡ DC 30-60kW]`, `[⚡ DC ≥ 60kW]`, `[⚡ DC ≥ 120kW]`.
  2. **Hoặc nhập giới hạn công suất bằng ô Text Input:**
     - Ô nhập `Min kW` và `Max kW` (chỉ cho phép số nguyên dương `1` - `500` kW).
     - Chỉ nhập Min (Max rỗng) ➔ `X ≥ Min kW`.
     - Chỉ nhập Max (Min rỗng) ➔ `X ≤ Max kW`.
     - Nhập cả hai ➔ `Min kW ≤ X ≤ Max kW`.
     - **Validation:** Nếu nhập `Min > Max` ➔ hiển thị cảnh báo đỏ bên dưới và disable nút **Save**.
* Có nút **Save** để lưu cấu hình vào bộ nhớ mã hóa (`Encrypted Preferences`).
