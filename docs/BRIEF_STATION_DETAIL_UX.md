# 💡 BRIEF: Tối Ưu Chi Tiết Trạm Sạc (Marquee 1 Dòng & Loại Bỏ Tiêu Đề "Cổng sạc")

**Ngày tạo:** 08/09/2026  
**Ngữ cảnh:** Ứng dụng EV-Plus - Tối ưu trải nghiệm lái xe & Màn hình ngang (Landscape / In-Car)

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
1. **Tên trạm sạc & Địa chỉ chiếm nhiều dòng:**
   - Hiện tại tên trạm cho phép tối đa 3 dòng (`maxLines = 3`) và địa chỉ 2 dòng (`maxLines = 2`).
   - Với các địa chỉ dài (ví dụ: `THÔN THÀNH DỀN , XÃ ĐÀO VIÊN , HUYỆN QUẾ VÕ , T BẮC NINH (Cũ), PHỐ THÀNH DỀN ,...`), nó chiếm từ 36 - 60dp chiều cao.
   - Khi ở chế độ xoay ngang (Landscape trên xe hơi/tablet), chiều cao màn hình rất hạn chế, dẫn đến việc các thành phần quan trọng (nút **⚡ DẪN ĐƯỜNG & THEO DÕI**, danh sách cổng sạc) bị đẩy xuống dưới hoặc bị che khuất.
2. **Dòng tiêu đề "Cổng sạc" gây lãng phí không gian:**
   - Bên dưới địa chỉ có một nhãn văn bản `Text("Cổng sạc")` chiếm thêm khoảng 30dp.
   - Các pill trạng thái bên dưới đã ghi rất rõ công suất và số lượng cổng trống (ví dụ: `🟢 22kW: Trống 1/1 cổng`, `🟢 150kW: Trống 1/2 cổng`), do đó tiêu đề "Cổng sạc" hoàn toàn dư thừa và không mang lại giá trị gia tăng.

---

## 2. GIẢI PHÁP ĐỀ XUẤT
1. **Giới hạn Tên trạm và Địa chỉ chỉ đúng 1 dòng (Single-line) kèm hiệu ứng Marquee:**
   - Cả `station.name` và `station.address` trong thẻ chi tiết trạm sạc (`NativeStationDetailContent`) được cấu hình `maxLines = 1`, `softWrap = false`.
   - Sử dụng `Modifier.basicMarquee()` từ Jetpack Compose Foundation:
     - Nếu chuỗi vừa vặn với chiều ngang: Đứng yên, hiển thị trọn vẹn, không chuyển động.
     - Nếu chuỗi quá dài: Tự động chạy chữ (Marquee) mượt mà sang trái với độ trễ bắt đầu 1.5s - 2s, tốc độ ~35 - 40dp/s, lặp vô hạn để người lái xe dễ dàng quan sát đầy đủ thông tin mà không cần chạm tay vào màn hình.
2. **Loại bỏ hoàn toàn dòng chữ "Cổng sạc":**
   - Xóa bỏ thẻ `Text(text = "Cổng sạc", ...)` trong section Hero của `NativeStationDetailSheet.kt`.
   - Đưa các pill cổng sạc (`PortStatusPill`) lên sát dưới phần Header / Rating với khoảng cách hợp lý (`verticalArrangement = Arrangement.spacedBy(8.dp)`).
   - Tiết kiệm ngay ~32dp không gian dọc, giúp nút bấm chính **⚡ DẪN ĐƯỜNG & THEO DÕI** luôn nằm trong tầm nhìn trực quan đầu tiên (Above the fold).

---

## 3. ĐỐI TƯỢNG VÀ MÔI TRƯỜNG SỬ DỤNG
- **Người dùng chính:** Tài xế xe điện VinFast / xe điện nói chung đang gắn điện thoại nằm ngang trên taplo hoặc sử dụng màn hình Android trên xe hơi.
- **Tiêu chí trải nghiệm (Glanceability):** Mọi thông tin cốt lõi (tên trạm, khoảng cách, trụ sạc trống, nút dẫn đường) phải nằm gọn trong 1 tầm mắt mà không cần cuộn dọc.

---

## 4. PHÂN TÍCH KỸ THUẬT (TECHNICAL REALITY CHECK)

### 4.1. Jetpack Compose `Modifier.basicMarquee`
- **Thư viện:** `androidx.compose.foundation.basicMarquee` (có sẵn trong Compose Foundation 1.6+, project đang dùng BOM `2024.04.01`).
- **Thông số tối ưu cho Automotive:**
  - `iterations = Int.MAX_VALUE`: Chạy liên tục khi màn hình còn hiển thị.
  - `delayMillis = 2000L`: Đợi 2 giây trước khi bắt đầu cuộn giúp người dùng kịp đọc phần đầu văn bản.
  - `velocity = 35.dp`: Tốc độ di chuyển êm dịu, không gây chóng mặt hay mất tập trung khi lái xe.
  - `spacing = MarqueeSpacing.fractionOfContainer(1f / 4f)`: Giãn cách vòng lặp chữ hợp lý.
- **Lưu ý:** Không dùng `overflow = TextOverflow.Ellipsis` kết hợp với `basicMarquee` vì có thể gây cắt cụt chuỗi trước khi đo đạc layout.

### 4.2. File cần can thiệp:
- [`NativeStationDetailSheet.kt`](file:///home/skul9x/Desktop/Code/EV-Plus-main/app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt):
  - Dòng 975 - 998: Tên trạm & Địa chỉ trong `NativeStationDetailContent`.
  - Dòng 1033 - 1039: Bỏ `Text(text = "Cổng sạc", ...)`.
- Kiểm tra lại kích thước và padding tổng thể của chi tiết trạm.

---

## 5. KẾ HOẠCH BÀN GIAO TIẾP THEO
- Chuyển sang `/plan` để lên checklist kiểm thử và thực thi code.
