━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 HANDOVER DOCUMENT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📍 Đang làm: Đánh giá & Khảo sát Telemetry Thực tế (HERE vs EVCS), Rate-limit, Phân loại trạm & Định tuyến OSRM
🔢 Đến bước: Hoàn thành 100% các cuộc thử nghiệm thực nghiệm, đã phân tích log và cập nhật toàn bộ tài liệu kiến trúc.

✅ ĐÃ XONG:
   1. **Kiểm tra chịu tải & Rate Limit (HTTP 429) trong 10 phút**:
      * Chạy polling tần suất cao 5s/lần liên tục (~100 chu kỳ) trên cả HERE Maps EV API và EVCS.vn API (test đơn trạm `C.BNI0012`, `C.BNI0324` và đa trạm 6 trạm đồng thời tại Long Biên).
      * **Kết quả:** 0 lỗi HTTP 429, không bị Cloudflare WAF chặn hoặc yêu cầu Captcha.
      * **Độ ổn định:** HERE Maps phản hồi đều đặn 260-450ms; EVCS có jitter biến thiên (400ms đến 5.4s, đôi khi timeout 10s).

   2. **So sánh độ trễ Realtime & Phân biệt Trạm Chính Hãng vs Trạm Nhượng Quyền**:
      * **Thực tế tại Việt Nam:** EVCS.vn cập nhật biến động cắm/rút sạc **nhanh hơn HERE Maps** (vì kết nối thẳng VinFast app session trong nước).
      * **Nguyên nhân HERE Maps cập nhật muộn hơn (3 - 15 phút):**
        1. Pipeline quốc tế: Trạm (VN) -> VinFast Cloud -> Hubject/HERE (Châu Âu) -> CDN Châu Á.
        2. Edge Cache TTL: HERE cache kết quả 3-5 phút ở máy chủ biên để tải hàng triệu xe.
        3. V-GREEN nhượng quyền: Dữ liệu roaming sync định kỳ theo batch 15-30 phút/lần.
      * **Ưu điểm bù lại của HERE Maps:** Nhận diện chuẩn xác từng cổng bị hỏng/mất điện (`OUT_OF_SERVICE`) và độ trễ mạng cực thấp (260ms), không bị chập chờn như EVCS.

   3. **Khảo sát trạm sạc & Định tuyến đường bộ thực tế (OSRM Driving Matrix)**:
      * Khảo sát các trạm sạc quanh Quế Võ (Bắc Ninh), Long Biên (Hà Nội), Mai Sơn (Thanh Hóa), và Cẩm Xuyên / Kỳ Anh (Hà Tĩnh).
      * Chứng minh khoảng cách đường chim bay (Haversine) gây sai lệch nghiêm trọng ở các khu vực ven biển / sông ngòi / đường cao tốc (ví dụ tại Kỳ Anh, trạm cách 9.5 km chim bay nhưng đường lái thực tế OSRM là 26.3 km / 31 phút do phải đi vòng qua cầu).

   4. **Cập nhật Tài liệu Kỹ thuật & Bộ nhớ Dự án**:
      * `docs/benchmarks/telemetry_benchmark_here_vs_evcs.md`: Báo cáo chi tiết benchmark 10 phút.
      * `docs/business/station_classification_rules.md`: Quy tắc phân biệt trạm chính hãng vs nhượng quyền.
      * `docs/api/endpoints.md`: Bổ sung kết quả benchmark rate-limit và OSRM matrix.
      * `.brain/brain.json` & `.brain/session.json`: Đồng bộ kiến thức mới vào bộ nhớ vĩnh viễn.

⏳ CÒN LẠI / ĐỀ XUẤT TIẾP THEO:
   - Tích hợp logic Hybrid Telemetry vào `FocusModeTelemetryEngine`: Dùng HERE Maps cho trạm chính hãng và EVCS cho trạm nhượng quyền.
   - Tích hợp OSRM matrix vào thuật toán tìm trạm dự phòng (reroute) trong Focus Mode thay vì Haversine.
   - Dọn dẹp các file log benchmark tạm thời ở thư mục gốc (`BNI0012.txt`, `C.BNI0324.txt`, `6_STATIONS_LOG.txt`) khi không còn cần đối soát.

🔧 QUYẾT ĐỊNH QUAN TRỌNG:
   - Dùng HERE Maps EV API làm nguồn dữ liệu sạc số 1 cho các trạm chính hãng VinFast vì độ trễ OCPP chỉ vài giây và nhận biết được trụ hỏng `OUT_OF_SERVICE`.
   - Với trạm nhượng quyền V-GREEN, cần cơ chế cross-reference với EVCS API để không bị trễ theo chu kỳ batch 15-30 phút của HERE roaming.
   - Ưu tiên tính khoảng cách chuyển hướng lái xe bằng OSRM Table API thay vì Haversine để tránh dẫn xe vào ngõ cụt hoặc đường vòng ven sông/cao tốc.

📁 FILES QUAN TRỌNG:
   - `docs/benchmarks/telemetry_benchmark_here_vs_evcs.md`
   - `docs/business/station_classification_rules.md`
   - `docs/api/endpoints.md`
   - `.brain/brain.json`
   - `.brain/session.json`
   - `.brain/handover.md`

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📍 Đã lưu! Để tiếp tục: Gõ /recap
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
