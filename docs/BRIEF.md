# 💡 BRIEF: Loại Trụ 3.5kW & 7kW Ra Khỏi Bộ Lọc AC & Chuẩn Hóa Trạm Sạc Ô Tô

**Ngày tạo:** 05/09/2026  
**Brainstorm cùng:** skul9x  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
- Ứng dụng **EV+** phục vụ tài xế lái xe ô tô điện (VinFast, BYD, Porsche, Hyundai...).
- Hiện tại, bộ lọc `AC` (`QuickChipOption.AC` và `SmartFilterMode.AC`) đang gom cả các cổng có công suất `3.5kW`, `7kW`, `7.4kW` vào danh mục sạc AC.
- **Thực tế bất cập:**
  - **Cổng 3.5kW:** Thực chất là ổ cắm dân dụng / sạc di động cầm tay (Portable EVSE), trạm công cộng không có súng sạc gắn sẵn.
  - **Cổng 7kW / 7.4kW:** Đa số là trạm sạc xe máy điện (e-scooter) hoặc bộ sạc wallbox gia đình.
  - Khi tài xế ô tô bấm lọc cổng AC, app gợi ý các trạm có cổng 3.5kW hoặc 7kW dẫn đến việc tài xế chạy đến nơi nhưng **không sạc được** cho xe ô tô.
  - Các trạm thuần xe máy điện (chỉ có cụm cổng 7kW hoặc 3.5kW) làm loãng danh sách trạm sạc khả dụng của tài xế ô tô.

---

## 2. GIẢI PHÁP ĐỀ XUẤT
1. **Chuẩn hóa bộ lọc AC (Soft Filter):**
   - Chỉ công nhận cổng **11kW** (11_000W) và **22kW** (22_000W) là sạc AC hợp lệ cho ô tô (`AC_STANDARD_WATTS = setOf(11_000L, 22_000L)`).
   - Loại bỏ `3.5kW`, `7kW`, `7.4kW` ra khỏi bộ lọc AC.
   - Cổng trả về `type = 0L` dù có nhãn "AC" hoặc "Type 2" (không có công suất cụ thể) sẽ không được tính vào bộ lọc AC.
2. **Ẩn các trạm thuần xe máy điện:**
   - Nếu một trạm sạc chỉ có toàn cổng sạc công suất thấp (3.5kW, 7kW, 7.4kW) mà không có bất kỳ cổng sạc ô tô nào (không có DC và không có AC ≥ 11kW), trạm này sẽ **bị ẩn hoàn toàn** khỏi danh sách Nearby và Tìm kiếm.
   - Các trạm hỗn hợp (vừa có DC / AC 11kW, vừa có cổng 7kW) vẫn được hiển thị bình thường.
3. **Giữ nguyên dữ liệu thô cho bộ lọc Custom Range:**
   - Dữ liệu `typeWatts` (3500L, 7000L,...) vẫn được lưu trong `PowerPort`.
   - Nếu người dùng chủ động vào Cài đặt và nhập dải công suất thủ công (ví dụ `Min = 3kW, Max = 7kW`), hệ thống vẫn tìm và hiển thị các trạm chứa các cổng này theo đúng yêu cầu.
4. **Chuẩn hóa danh mục công suất (WattageOption):**
   - Xóa bỏ 2 enum `KW_3_5` (3.5kW) và `KW_7` (7kW) khỏi `WattageOption`.
   - Dải công suất chuẩn cho ô tô chỉ còn từ **11kW đến 360kW**.
5. **Cập nhật UI/UX:**
   - Đổi nhãn Quick Chip & Info Pill từ `"Cổng AC từ 3.5kW - 22kW"` thành **`"Cổng AC (11kW, 22kW)"`**.

---

## 3. ĐỐI TƯỢNG SỬ DỤNG
- **Chính:** Chủ sở hữu và tài xế xe ô tô điện cần tìm trạm sạc AC tương thích chuẩn súng Type 2 (11kW / 22kW) một cách chính xác, tránh đi nhầm vào ổ cắm dân dụng hoặc trạm sạc xe máy điện.

---

## 4. CHI TIẾT KỸ THUẬT & QUY TẮC PHÂN LOẠI

| Thành phần | Trước khi sửa | Sau khi sửa |
|---|---|---|
| `AC_STANDARD_WATTS` | `setOf(3_500L, 7_000L, 7_400L, 11_000L, 22_000L)` | `setOf(11_000L, 22_000L)` |
| `PowerPort.isAc()` | Nhận cả 3.5kW, 7kW, 7.4kW và fallback label "AC" | Chỉ `true` nếu `typeWatts in {11_000L, 22_000L}` |
| Trạm thuần 3.5kW / 7kW | Hiển thị trong Nearby / Search | Bị ẩn hoàn toàn (không có cổng ô tô khả dụng) |
| Trạm hỗn hợp (DC + 7kW) | Hiển thị | Vẫn hiển thị bình thường, cổng 7kW vẫn hiển thị trên thẻ |
| `WattageOption` | 15 bậc (360kW xuống 3.5kW) | 13 bậc (360kW xuống 11kW, bỏ `KW_7` và `KW_3_5`) |
| Label QuickChip AC | `"Cổng AC từ 3.5kW - 22kW"` | `"Cổng AC (11kW, 22kW)"` |
| Settings Custom Range | `matchesCustomRange(minKw, maxKw)` | Giữ nguyên: Cho phép match 3.5kW/7kW nếu user tự nhập dải `3-7kW` |

---

## 5. CÁC TẬP TIN SẼ BỊ ẢNH HƯỞNG (CODEBASE IMPACT)
- `app/src/main/java/com/evcs/favorites/domain/model/SmartFilterModels.kt`
- `app/src/main/java/com/evcs/favorites/domain/model/WattageOption.kt`
- `app/src/main/java/com/evcs/favorites/domain/filter/NearbyStationFilter.kt`
- `app/src/main/java/com/evcs/favorites/ui/components/CustomFilterSettingsCard.kt`
- `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt`
- `app/src/main/java/com/evcs/favorites/ui/components/WattageFilterChipsRow.kt`
- Các bộ Unit Test liên quan (`NearbyStationSmartFilterTest`, `CustomFilterSettingsValidationTest`, `NearbyFilteringAndFavoriteSyncTest`, `NearbyUiComponentsTest`...)

---

## 6. ƯỚC TÍNH SƠ BỘ & RỦI RO
- **Độ phức tạp:** 🟢 Thấp - Trung bình (Refactor Domain Model, Filter Logic & Test Cases).
- **Rủi ro:** Một số Unit Test cũ đang assert cố định danh sách 15 enum `WattageOption` hoặc kiểm tra `3.5kW`/`7kW` là AC $\rightarrow$ Cần cập nhật đồng bộ toàn bộ test suite để đảm bảo `./gradlew test` pass 100%.

---

## 7. BƯỚC TIẾP THEO
→ Xác nhận Brief và chuyển sang workflow `/plan` để lập kế hoạch triển khai chi tiết từng phase.
