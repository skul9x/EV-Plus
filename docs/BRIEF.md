# 💡 BRIEF: Tối ưu UI Bộ lọc AC và Nút Focus Trạm Sạc

**Ngày tạo:** 2026-09-13  
**Brainstorm cùng:** skul9x  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
- Chế độ **Focus Mode** (dẫn đường + hiển thị telemetry overlay nổi theo dõi cổng sạc theo thời gian thực) chủ yếu phục vụ các trạm sạc nhanh DC khi tài xế đang di chuyển cần sạc gấp. Đối với các trạm thuần sạc chậm AC (hoặc khi người dùng đang chủ động lọc sạc AC), nút **Focus** là thừa thãi và gây chật chội trên giao diện.
- Ở màn hình dọc, dòng tóm tắt bộ lọc (`filterSummaryPillText`, ví dụ *"Tìm thấy 10 trạm có cổng AC khả dụng"*, *"Top 10 trạm sạc VinFast gần nhất..."*) chiếm diện tích theo chiều dọc không cần thiết, làm giảm số lượng trạm hiển thị trên màn hình.

## 2. GIẢI PHÁP ĐỀ XUẤT
1. **Ẩn nút Focus cho trạm AC:**
   - Trạm sạc **không có cổng DC** (thuần AC) sẽ ẩn hoàn toàn nút Focus trên toàn bộ các màn hình (Nearby, Favorites, Search).
   - Khi đang ở chế độ lọc AC trên màn hình (bao gồm cả Quick Chip AC trong Custom Filter), nếu xem chi tiết trạm thì nút Focus cũng được ẩn.
   - Khi nút Focus bị ẩn, nút `[ Chỉ Đường ]` tự động mở rộng chiếm toàn bộ chiều ngang (`fillMaxWidth()`) để người lái xe dễ thao tác bằng một tay.
   - Đồng bộ hoàn toàn giữa giao diện Dọc (Portrait Bottom Sheet) và giao diện Ngang (Landscape Right Pane).
   - Ở màn hình ngang, nếu người dùng chuyển tab bộ lọc từ AC sang DC/Tất cả, các trạm có hỗ trợ DC sẽ lập tức hiển thị lại nút Focus.

2. **Loại bỏ dòng tóm tắt trên màn hình dọc:**
   - Ẩn hoàn toàn dòng summary pill trên màn hình dọc đối với tất cả các chế độ lọc (NONE, AC, DC, CUSTOM) để thu hồi 100% diện tích, giúp danh sách trạm sạc được đẩy lên sát tab lọc.
   - Khi đang tính toán lộ trình (`isRoutingLoading == true`), hiển thị loading spinner căn giữa màn hình (hoặc overlay tinh tế) thay vì nằm trong pill row cũ.

---

## 3. PHẠM VI & TÍNH NĂNG (SCOPE)

### 🚀 MVP:
- [ ] **Domain Helper:** Thêm `Station.hasDcPorts()` / `Station.isPureAcStation()` sử dụng logic kiểm tra `PowerPort.isDc()` và fallback parse connectors.
- [ ] **UI Detail Sheet & Content:** 
  - Thêm điều kiện ẩn nút Focus trong `NativeStationDetailContent`: `canShowFocus = station.hasDcPorts() && !isAcFilterActive`.
  - Nút `[ Chỉ Đường ]` dùng `Modifier.fillMaxWidth()` khi `!canShowFocus`, và `Modifier.weight(1f)` khi có `canShowFocus`.
  - Truyền trạng thái `isAcFilterActive` từ `NearbyScreen` và `NearbyLandscapeScreen` vào component chi tiết.
- [ ] **Portrait Screen Cleanup:**
  - Bỏ Row chứa `filterSummaryPillText` trên màn hình dọc `NearbyScreen.kt`.
  - Căn giữa `CircularProgressIndicator` cho `isRoutingLoading` trên danh sách trạm.
- [ ] **Unit Tests:** Cập nhật và bổ sung test cases kiểm thử logic ẩn Focus button, dãn nút Chỉ đường và loại bỏ pill row.

---

## 4. BƯỚC TIẾP THEO
→ Chạy `/plan` để lập kế hoạch triển khai chi tiết các phases kỹ thuật.
