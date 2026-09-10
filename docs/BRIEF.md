# 💡 BRIEF: Focus Mode EVCS Telemetry & OSRM Driving Matrix Reroute

**Ngày tạo:** 2026-09-10  
**Tác giả:** Antigravity Brainstorm Partner & skul9x  
**Trạng thái:** Brainstorming Completed -> Ready for /plan  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
1. **Khoảng cách chim bay (Haversine) gây sai lệch khi tìm trạm dự phòng:**
   - Khi trạm đích hết cổng sạc, app gợi ý trạm thay thế theo đường chim bay. Trên thực tế (đặc biệt khi đi trên cao tốc hoặc khu vực có sông ngòi/cầu cống), trạm đo chim bay 1.5 - 9.5 km nhưng đường ô tô thực tế phải đi vòng 9 - 26 km, gây nguy cơ hết pin giữa đường.
2. **Chu kỳ polling động (Dynamic Interval) phức tạp không cần thiết:**
   - Hiện tại app thay đổi chu kỳ polling theo khoảng cách (15s > 3km, 10s 1.5-3km, 5s < 1.5km). Cần cố định 1 chu kỳ duy nhất đơn giản, ổn định cho Floating Window.
3. **Nguồn dữ liệu Floating Window:**
   - Cần dữ liệu phản ánh nhanh tình trạng cắm/rút thực tế tại Việt Nam cùng tên trạm tiếng Việt chuẩn xác từ EVCS.vn.

---

## 2. GIẢI PHÁP ĐỀ XUẤT
1. **EVCS Telemetry Engine cho Floating Window:**
   - Lấy dữ liệu trực tiếp từ EVCS.vn API cho Floating Window.
   - Chu kỳ polling cố định: **10 giây / lần** từ đầu đến cuối phiên Focus Mode (không phụ thuộc khoảng cách).
   - Nếu EVCS phản hồi chậm hoặc timeout: Giữ nguyên dữ liệu hiển thị gần nhất, không fallback sang bên thứ ba làm nhiễu UI.
   - Tin tưởng trạng thái trạm (bao gồm bảo trì/ngừng hoạt động) từ EVCS.
2. **Kích hoạt OSRM Driving Matrix Reroute có điều kiện (On-Demand):**
   - Chỉ kích hoạt tìm trạm thay thế với OSRM khi **thỏa mãn đồng thời cả 2 điều kiện**:
     1. Trạm đích **thực sự hết cổng DC** (`availableDcSlots == 0`).
     2. Người dùng **chủ động bấm đổi trạm sạc** trên Floating Window / UI.
   - Quy trình tìm trạm 2 bước:
     - **Bước 1 (Lọc thô):** Lấy Top 5–8 trạm gần nhất theo Haversine còn cổng DC khả dụng với công suất $\ge$ trạm đích.
     - **Bước 2 (Sắp xếp tinh):** Gửi Top 5–8 trạm vào `OsrmRoutingClient` (OSRM Table Matrix) để sắp xếp lại theo **khoảng cách lái xe thực tế (km) gần nhất**.
   - **Fallback:** Nếu OSRM lỗi mạng hoặc timeout, tự động giữ nguyên thứ tự sắp xếp theo Haversine.
3. **UX & Voice Announcements (TTS):**
   - Nút đổi trạm hiển thị tên trạm và khoảng cách lái xe OSRM.
   - Mẫu câu TTS chuẩn:
     > *"Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang [Tên trạm B], cách [X] cây số, đi mất [Y] phút, còn [Z] cổng [P]kW"*

---

## 3. ĐỐI TƯỢNG SỬ DỤNG
- **Tài xế xe điện VinFast (VF3, VF5, VF8, VF9, e34...)** sử dụng điện thoại hoặc Android Box / Carlinkit TBox trên màn hình ô tô khi đang dẫn đường (Google Maps) kết hợp Floating Capsule HUD của EV-Plus.

---

## 4. TÍNH NĂNG CHI TIẾT

### 🚀 MVP (Bắt buộc có):
- [ ] **Fixed 10s Telemetry Polling**: Chu kỳ cập nhật cố định 10s cho Floating Window từ EVCS.
- [ ] **UI State Retention on Network Lag**: Giữ nguyên dữ liệu EVCS gần nhất khi mạng chập chờn / timeout.
- [ ] **Conditional OSRM Reroute Engine**:
  - Chỉ tính OSRM khi: `availableDcSlots == 0` VÀ `user_clicked_reroute == true`.
  - Lọc thô 5–8 ứng viên qua Haversine -> Tính OSRM Table Matrix -> Sắp xếp theo `distanceMeters` tăng dần.
  - Fallback về Haversine khi OSRM gặp sự cố.
- [ ] **Voice Alert Scripting**: Định dạng đúng mẫu câu tiếng Việt: *"Trạm bạn muốn tới hiện tại đã hết cổng. Gợi ý đổi sang [Tên trạm B], cách [X] cây số, đi mất [Y] phút, còn [Z] cổng [P]kW"*.
- [ ] **Unit Tests**: Kiểm thử đầy đủ các điều kiện kích hoạt, logic fallback OSRM, và format câu TTS.

### 🎁 Phase 2 (Cân nhắc sau):
- [ ] Cấu hình tùy chọn máy chủ OSRM riêng (Self-hosted OSRM endpoint) nếu server công cộng quá tải.
- [ ] Lưu cache tuyến đường OSRM ngắn hạn để tái sử dụng nếu tài xế bấm đổi trạm nhiều lần.

---

## 5. ƯỚC TÍNH SƠ BỘ & RỦI RO
- **Độ phức tạp:** Trung bình (Clean Architecture đã có sẵn `OsrmRoutingClient` và `FocusModeTelemetryEngine`, chỉ cần cập nhật điều kiện kích hoạt, tích hợp OSRM matrix vào engine reroute và chỉnh sửa polling interval).
- **Rủi ro:** 
  - Server OSRM công cộng (`router.project-osrm.org`) có thể thỉnh thoảng phản hồi chậm hoặc `NoRoute` -> Đã có giải pháp fallback về Haversine.
  - EVCS API có độ trễ biến thiên -> Đã có giải pháp giữ nguyên snapshot data gần nhất.

---

## 6. BƯỚC TIẾP THEO
→ Chuyển sang workflow `/plan` để lên bản thiết kế kỹ thuật chi tiết và danh sách task code.
