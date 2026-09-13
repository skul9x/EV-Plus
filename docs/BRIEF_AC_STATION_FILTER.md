# 💡 BRIEF: Bộ Lọc Trạm Sạc AC (11kW & 22kW) - Tab Quanh Đây

**Ngày tạo:** 2026-09-13  
**Tính năng:** Tối ưu hóa bộ lọc trạm sạc AC trong Tab Quanh Đây (Nearby Screen)  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
- Khi người dùng chọn bộ lọc **AC**, hiện tại app vẫn hiển thị cả các trạm AC đã hết cổng trống (`0/1` hoặc `0/2`), gây lãng phí thời gian lái xe đến trạm mà không sạc được.
- Với các trạm hỗn hợp (vừa có trụ DC công suất lớn 60kW - 120kW, vừa có trụ AC 11kW/22kW), người dùng khó nhận biết trụ AC nằm ở đâu giữa danh sách chip công suất hiển thị.
- Cần đảm bảo quy tắc lọc AC chuẩn xác: thu thập đầy đủ cả trạm hãng lẫn trạm tư nhân/nhượng quyền có trụ 11kW hoặc 22kW.

---

## 2. QUY TẮC NGHIỆP VỤ & QUYẾT ĐỊNH (BUSINESS RULES)

1. **Phạm vi công suất AC:**
   - Chỉ tính duy nhất **11kW** và **22kW** (chuẩn AC ô tô).
   - Bỏ qua các mức công suất sạc chậm khác (3.5kW, 7kW).

2. **Tiêu chí thu thập trạm (Trường hợp A):**
   - Lấy **tất cả** các trạm có trụ 11kW hoặc 22kW.
   - Bao gồm cả trạm VinFast chính hãng (Showroom, Vincom, TTTM...) và trạm Nhượng quyền / Tư nhân ngoài nhà dân.
   - Kể cả trạm hỗn hợp lớn (ví dụ có trụ 120kW + 11kW) đều được lấy vào danh sách.
   - **Không** gắn thêm nhãn/badge "Tư nhân" lên UI để giữ giao diện thẻ trạm tối giản và sạch sẽ.

3. **Điều kiện khả dụng (Cổng trống):**
   - Khi bật bộ lọc AC: **Ẩn luôn** các trạm không còn cổng AC trống.
   - Chỉ hiển thị các trạm có ít nhất 1 cổng AC (11kW hoặc 22kW) đang **khả dụng** (`availablePlugs > 0`).

4. **Trải nghiệm hiển thị (UI/UX):**
   - Khi đang kích hoạt bộ lọc AC, trên mỗi thẻ trạm (`StationCard`), **làm nổi bật (highlight viền xanh Emerald / chữ đậm hơn)** riêng cho chip công suất **11kW / 22kW** để người dùng nhận diện ngay lập tức.

---

## 3. CÁC THÀNH PHẦN KỸ THUẬT LIÊN QUAN

* **`NearbyStationFilter.kt`**:
  * Điều chỉnh nhánh `SmartFilterMode.AC`: Chỉ chấp nhận trạm có `power.isAc() && power.availablePlugs > 0` (bỏ qua `includeFullStations` đối với chế độ AC).
* **`StationCard.kt` / `WattageChip`**:
  * Nhận biết trạng thái `activeFilterMode == SmartFilterMode.AC`.
  * Highlight chip 11kW/22kW với viền màu Emerald Primary (`#10B981` / `EmeraldPrimary`), độ dày viền 1.5dp, và font chữ đậm hơn.
* **`NearbyUiState.kt` / `NearbyViewModel.kt`**:
  * Truyền ngữ cảnh filter AC xuống danh sách trạm hiển thị.

---

## 4. BƯỚC TIẾP THEO
→ Chạy `/plan` để lên kế hoạch chi tiết và tiến hành triển khai code.
