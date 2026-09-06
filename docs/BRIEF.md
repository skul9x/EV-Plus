# 💡 BRIEF: Cải Tiến Focus Mode & Trải Nghiệm Bộ Lọc Danh Sách Trạm Sạc

**Ngày tạo:** 2026-09-06  
**Dự án:** EV-Plus (Android App)  
**Mục tiêu:** Nâng cấp Focus Mode cho trụ DC 20kW, sửa lỗi auto-scroll khi đổi bộ lọc, và tối ưu giao diện Sheet chi tiết trạm chỉ có AC.

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
1. **Focus Mode bỏ sót trụ DC 20kW:** Hiện tại hệ thống đang lấy ngưỡng cứng `MIN_DC_POWER_WATTS = 30_000L` (30kW), dẫn đến các trạm có trụ DC 20kW (chuẩn sạc DC của VinFast cho xe VF3, VF5...) bị coi là 0 trụ DC trống, gây sai lệch telemetry và không kích hoạt được tracker.
2. **Không tự động cuộn lên đầu (Auto-scroll to top) khi thay đổi bộ lọc:** Hiện tại màn hình `NearbyScreen` chỉ tự cuộn lên trạm gần nhất khi người dùng ấn nút Refresh lúc đang ở chế độ lọc AC. Khi người dùng bấm chuyển tab lọc (Tất cả, DC, chọn tier công suất, Custom) hoặc làm mới ở các chế độ khác, danh sách giữ nguyên vị trí cũ khiến tài xế không thấy ngay trạm gần nhất vừa lọc ra.
3. **Hiển thị nút "⚡ Focus Mode" thừa thãi ở trạm sạc AC:** Với những trạm chỉ có sạc chậm AC (như trụ AC 7kW, 11kW, 22kW tại chung cư/bãi đỗ xe), việc hiển thị nút "Focus Mode" là không có ý nghĩa vì Focus Mode là tính năng dẫn đường & theo dõi slot sạc nhanh DC thời gian thực.

---

## 2. GIẢI PHÁP ĐÃ THỐNG NHẤT

### 2.1. Hỗ trợ trụ DC 20kW trong Focus Mode & Telemetry Engine
- Cập nhật định nghĩa DC trong `FocusModeDcFilter`:
  - Hạ ngưỡng tối thiểu `MIN_DC_POWER_WATTS = 20_000L` (20kW) và đồng bộ với logic `PowerPort.isDc()` (loại trừ AC 22kW).
  - Cập nhật model `HereConnector.isDcCharging` để nhận diện các trụ 20kW kể cả khi DTO không gắn cờ powerType là DC (`powerKw >= 20.0 && powerKw != 22.0`).
- **Thuật toán Auto-Reroute (Đổi hướng thông minh khi hết slot):**
  - Khi trạm đích là DC 20kW bị đầy (0 slot trống), tự động tìm trạm thay thế gần nhất có trụ DC cùng hạng hoặc cao hơn (`typeWatts >= targetMaxDcWatts` và `availablePlugs > 0`), ưu tiên giữ cho tài xế không bị chuyển sang trạm công suất thấp hơn.
- Giữ nguyên thông báo TTS tiếng Việt thân thiện, không cần đọc riêng con số 20kW.

### 2.2. Khắc phục Auto-Scroll To Top khi Refresh & Thay đổi bộ lọc
- Mở rộng logic trong `NearbyUiHelper.shouldScrollToTop`:
  - Chấp nhận cả `RefreshTriggerType.USER_REFRESH` và `RefreshTriggerType.FILTER_CHANGE`.
- Cập nhật trong `NearbyViewModel`:
  - Khi người dùng tương tác thay đổi bộ lọc (`toggleAcFilter`, `enterDcMode`, `exitDcMode`, `selectDcTier`, `applyCustomFilter`, `saveAndApplyCustomFilter`, `clearFilters`):
    - Đặt `triggerType = RefreshTriggerType.FILTER_CHANGE`.
    - Cập nhật `lastRefreshTimestamp = System.currentTimeMillis()`.
  - Đảm bảo khi bấm nút Refresh vật lý ở bất kỳ chế độ lọc nào, `lastRefreshTimestamp` đều được kích hoạt để cuộn mượt về vị trí index 0.

### 2.3. Tối ưu Sheet thông tin trạm sạc (NativeStationDetailSheet)
- Kiểm tra danh sách cổng sạc của trạm: `val hasDc = station.powers.any { it.isDc() }`.
- **Nếu trạm có cổng DC (`hasDc == true`):**
  - Giữ nguyên bố cục hiện tại: Hàng 1 gồm 2 nút `[Chỉ đường]` (50%) và `[⚡ Focus Mode]` (50%).
- **Nếu trạm chỉ có cổng AC (`hasDc == false`):**
  - Ẩn hoàn toàn nút `⚡ Focus Mode`.
  - Nút `[Chỉ đường]` tự động trải rộng toàn màn hình (`fillMaxWidth()`) ở hàng 1.

---

## 3. PHẠM VI ẢNH HƯỞNG & CÁC FILE LIÊN QUAN
1. `app/src/main/java/com/evcs/favorites/focus/FocusModeTelemetryEngine.kt`:
   - `FocusModeDcFilter`: Cập nhật `MIN_DC_POWER_WATTS = 20_000L` và `isDcPort(port)`.
   - `findAlternativeStation`: Điều chỉnh so sánh `port.typeWatts >= targetMaxDcWatts`.
2. `app/src/main/java/com/evcs/favorites/data/network/here/model/HereEvModels.kt`:
   - `HereConnector.isDcCharging`: Cập nhật chuẩn hóa nhận diện trụ 20kW.
3. `app/src/main/java/com/evcs/favorites/ui/components/NearbyUiHelper.kt`:
   - `shouldScrollToTop`: Cho phép trigger khi có `FILTER_CHANGE`.
4. `app/src/main/java/com/evcs/favorites/ui/viewmodel/NearbyViewModel.kt`:
   - Cập nhật các action thay đổi bộ lọc truyền `triggerType = RefreshTriggerType.FILTER_CHANGE` và timestamp mới.
5. `app/src/main/java/com/evcs/favorites/ui/components/NativeStationDetailSheet.kt`:
   - Điều kiện ẩn hiện nút `Focus Mode` và mở rộng nút `Chỉ đường` khi `hasDc == false`.
6. Unit Tests:
   - Cập nhật/bổ sung test cases trong `FocusModeTelemetryEngineTest`, `NearbyUiHelperTest`, `NearbyViewModelTest`.

---

## 4. BƯỚC TIẾP THEO
→ Chuyển sang workflow `/plan` để lập kế hoạch chi tiết từng bước, phân tích edge case và tiến hành implement.
