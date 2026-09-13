# 💡 BRIEF: Hiển thị hình ảnh trạm / trụ sạc khi xem chi tiết trạm AC

**Ngày tạo:** 2026-09-13  
**Brainstorm cùng:** skul9x  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
- Khi người dùng xem chi tiết các trạm AC (được lấy qua pipeline hybrid HERE EV API hoặc bộ lọc AC), danh sách ảnh `station.images` hiện đang rỗng (`emptyList()`), dẫn đến việc Photo Carousel ở đáy màn hình chi tiết (`NativeStationDetailSheet`) bị ẩn hoàn toàn (chiều cao = 0).
- Người dùng không quan sát được hình ảnh thực tế của trạm sạc / trụ sạc để mường tượng khu vực sạc và vị trí cắm sạc trông như thế nào khi đến nơi.

## 2. GIẢI PHÁP ĐỀ XUẤT
1. **Vị trí hiển thị đồng nhất 100% với trạm DC:**
   - Ảnh của trạm/trụ AC sẽ hiển thị tại chính xác vị trí như trạm DC: khu vực **Photo Carousel (`StationPhotoCarousel`)** ở cuối màn hình chi tiết (`NativeStationDetailContent`).
   - Giữ nguyên 100% các hành vi tương tác: vuốt ngang danh sách ảnh (`HorizontalPager`), dot indicators, badge đếm số trang (`x/y`), và bấm vào ảnh để mở modal phóng to toàn màn hình (`StationPhotoViewerModal`).
   - Hoạt động đồng bộ trên cả 2 giao diện: Màn hình dọc (Portrait Bottom Sheet) và Màn hình ngang trên xe hơi (Landscape Right Pane).

2. **Cơ chế Resolve ảnh tự động (Tầng Dữ liệu):**
   - Khi mở xem chi tiết trạm AC (hoặc trong pipeline tải chi tiết trạm của `StationDetailCoordinator`), hệ thống tự động đối chiếu toạ độ / `locationId` với API VinFast hoặc bóc tách hình ảnh từ trang chi tiết HTML của EVCS (`tram-sac-vinfast-{id}.html`).
   - Toàn bộ ảnh được server trả về (ảnh trụ sạc, ảnh hiện trường trạm...) sẽ được hiển thị theo đúng thứ tự mặc định từ server để tài xế có cái nhìn trực quan nhất.
   - Không cần lọc bớt hay ép theo công suất 11kW/22kW, có bao nhiêu ảnh từ hệ thống sẽ hiển thị bấy nhiêu.

3. **Xử lý Network & Error Fallback:**
   - Nếu xảy ra lỗi mạng hoặc server không trả về ảnh, hiển thị khung placeholder xám chuẩn (không làm vỡ layout).

---

## 3. PHẠM VI & TÍNH NĂNG (SCOPE)

### 🚀 MVP:
- [ ] **Data Pipeline (AC Photo Resolution):**
  - Mở rộng `EvcsStationNameResolver` hoặc `StationDetailCoordinator` / `EvcsApiClient` để khi mở chi tiết trạm AC (từ HERE API hoặc trạm thiếu ảnh), hệ thống đối chiếu toạ độ / `locationId` lấy `media` từ VinFast search API hoặc bóc tách từ trang chi tiết HTML.
  - Cập nhật `images` và `image` trong domain model `Station` của `StationDetailUiState`.
- [ ] **UI Presentation Consistency:**
  - Đảm bảo `StationPhotoCarousel` hiển thị mượt mà khi `station.images` được resolve cho trạm AC.
  - Hiển thị khung placeholder xám khi đang tải hoặc khi không tải được ảnh.
  - Giữ nguyên tương tác mở Lightbox modal phóng to toàn màn hình.
- [ ] **Automated Tests:**
  - Viết test case kiểm thử việc resolve ảnh cho trạm AC, cập nhật `stationDetailState.station.images`, và hiển thị Carousel.

---

## 4. BƯỚC TIẾP THEO
→ Chạy `/plan` để lên kế hoạch triển khai chi tiết các phases kỹ thuật.
