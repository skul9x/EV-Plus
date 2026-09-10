# Benchmark & So Sánh Telemetry: HERE Maps EV API vs EVCS.vn API

Ngày thực hiện: 2026-09-10  
Mục tiêu: Đánh giá độ trễ dữ liệu realtime, khả năng chịu tải polling liên tục (5s/lần), nguy cơ bị chặn rate limit (HTTP 429), và sự khác biệt giữa trạm Chính hãng VinFast vs Nhượng quyền tư nhân (V-GREEN).

---

## 1. Kết quả kiểm tra Tần suất Polling & Rate Limit (5s/lần trong 10 phút)

| Tiêu chí | HERE Maps EV API | EVCS.vn API |
| :--- | :--- | :--- |
| **Endpoint** | `GET /ev/stations.json` | `POST /search?t={hmac}` |
| **Authentication** | API Key / Bearer Token | Client HMAC-SHA256 Token |
| **Số chu kỳ test (10 phút)** | 97 chu kỳ (đơn trạm) & 85 chu kỳ (6 trạm đồng thời) | 97 chu kỳ (đơn trạm) & 85 chu kỳ (6 trạm đồng thời) |
| **Lỗi HTTP 429 (Rate limit)** | **0 lỗi (0%)** | **0 lỗi (0%)** |
| **Cloudflare WAF Block / Captcha** | Không bị block | Không bị block |
| **Độ trễ phản hồi (Response Latency)** | **Cực kỳ ổn định: 260ms - 450ms** | **Biến thiên lớn: 400ms - 5.4s**, thỉnh thoảng timeout 10s |
| **Kết luận chịu tải** | Cả hai dịch vụ đều an toàn khi polling 5s/lần trong Focus Mode khi xe đang di chuyển. |

---

## 2. So sánh Độ chính xác Realtime theo Loại trạm

### A. Trạm Chính hãng VinFast (Vincom, Vinhomes, Showroom, Trạm dừng nghỉ lớn)
*Ví dụ test:* `C.HNO0050` (Vinhomes Riverside), `C.BNI0012` (Dabaco Mart Quế Võ)
- **HERE Maps EV API:** Nhận dữ liệu trực tiếp qua kết nối phần cứng OCPP với VinFast Cloud. Trạng thái cổng sạc (`AVAILABLE` $\leftrightarrow$ `OCCUPIED`) cập nhật chỉ trong **vài giây** khi người dùng cắm/rút súng sạc.
- **EVCS.vn API:** Có độ trễ đồng bộ từ **1 đến 2 phút** so với thực tế phần cứng.
- **Độ tin cậy:** HERE Maps nhanh và chính xác hơn cho trạm chính hãng.

### B. Trạm Nhượng quyền tư nhân / V-GREEN
*Ví dụ test:* `C.BNI0324` (Sơn Quang Huy), `C.HNO1100` (Gara ô tô Dân Chủ), `C.HNO1215` (Cây xăng Sài Đồng)
- **HERE Maps EV API:** Dữ liệu trạm nhượng quyền qua roaming gateway thường cập nhật theo chu kỳ batch định kỳ (**15 - 30 phút/lần**). Do đó trong các phiên sạc ngắn 10 phút, HERE có thể giữ nguyên trạng thái cũ.
- **EVCS.vn API:** Lấy trực tiếp từ session state của app VinFast nên ghi nhận biến động cắm sạc/rút sạc nhanh hơn HERE Maps đối với các trạm nhượng quyền này.
- **Độ tin cậy:** EVCS.vn nhạy hơn đối với trạm tư nhân nhượng quyền.

---

## 3. Chẩn đoán Phần cứng & Trạng thái Lỗi Súng sạc

- **HERE Maps EV API cung cấp chi tiết cấp cổng vật lý:**
  - Định danh chính xác mã Serial súng: `cpoEvseEMI3Id` (ví dụ `VN*VNF*HNO1100*1*1`, `VN*VNF*HNO1215*1*1`).
  - Phân biệt rõ:
    - `AVAILABLE`: Súng rảnh, sẵn sàng sạc.
    - `OCCUPIED`: Đang sạc xe.
    - `OUT_OF_SERVICE`: Trụ mất điện, đứt kết nối mạng hoặc đang báo lỗi hỏng.
- **EVCS.vn API chỉ cung cấp số liệu tổng hợp:**
  - Chỉ có `total` và `available` dạng số nguyên.
  - Không phân biệt được trụ đang bận hay trụ bị hỏng/mất điện (`total=4, available=0` có thể là do 4 xe đang sạc hoặc trụ hỏng hoàn toàn).

---

## 4. Kiến nghị Tối ưu hóa cho EV-Plus Focus Mode

1. **Chiến lược Hybrid Telemetry:**
   - Với trạm **Chính hãng VinFast**: Dùng HERE Maps EV API làm nguồn chính (ưu tiên tốc độ phản hồi OCPP và chẩn đoán lỗi `OUT_OF_SERVICE`).
   - Với trạm **Nhượng quyền tư nhân (V-GREEN)**: Kết hợp kiểm tra chéo với EVCS API để bù đắp độ trễ batch roaming của HERE Maps.
2. **OSRM Road Distance Matrix:**
   - Thay thế hoàn toàn khoảng cách đường chim bay (Haversine) bằng OSRM matrix khi chọn trạm chuyển hướng (reroute candidate), đặc biệt tại các khu vực ven biển, sông ngòi hoặc đường cao tốc (nơi khoảng cách thực tế gấp 2-3 lần đường chim bay).
