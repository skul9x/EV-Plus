# API Documentation - EV+

Ngày cập nhật: 2026-09-10

---

## ⚡ 1. HERE Maps EV API (Tier 1 Direct VinFast Telemetry)

### GET `/ev/stations.json`
Lấy trạng thái trực tiếp của các trụ và cổng sạc (DC/AC) theo thời gian thực quanh toạ độ GPS.

* **Base URL:** `https://ev-v2.cc.api.here.com`
* **Authentication:** 
  * Header `Authorization: Bearer {token}` (tạo từ OAuth 1.0a HMAC-SHA256 Client Credentials tại `https://account.api.here.com/oauth2/token`).
  * Hoặc Query Parameter `apiKey=q26XVMcERlPYt5JghvY04zwUxpS0Pef0xwnB_c7DE-I`
* **Query Parameters:**
  * `prox`: `{latitude},{longitude},{radiusMeters}` (Ví dụ: `21.154757,106.144707,1000`)
  * `maxresults`: Số lượng trạm trả về tối đa (Focus mode dùng `10`, Nearby scan dùng `50`).
* **Hiệu năng & Rate Limit Benchmark (10 phút polling 5s/lần liên tục):**
  * **0 lỗi HTTP 429** trong suốt quá trình đo đạc.
  * **Độ trễ (Latency):** Rất ổn định, trung bình **260ms - 450ms**.
  * **Chẩn đoán phần cứng:** Báo chi tiết từng cổng `cpoEvseEMI3Id`, phân biệt rõ `AVAILABLE`, `OCCUPIED` và `OUT_OF_SERVICE` (hỏng / mất điện).
  * **Độ trễ cập nhật:** Với trạm Chính hãng (OCPP trực tiếp) cập nhật trong vài giây; với trạm Nhượng quyền V-GREEN roaming cập nhật theo batch 15-30 phút.
* **Response (200 OK):**
```json
{
  "count": 5,
  "evStations": {
    "evStation": [
      {
        "id": "c.BNI0012",
        "name": "Trạm sạc VinFast - TTTM Dabaco Mart Quế Võ",
        "position": { "latitude": 21.154757, "longitude": 106.144707 },
        "connectors": {
          "connector": [
            {
              "maxPowerLevel": 150.0,
              "powerType": "DC",
              "connectorStatuses": {
                "connectorStatus": [
                  { "cpoEvseEMI3Id": "VN*VNF*BNI0012*1", "state": "AVAILABLE" },
                  { "cpoEvseEMI3Id": "VN*VNF*BNI0012*2", "state": "OCCUPIED" }
                ]
              }
            }
          ]
        }
      }
    ]
  }
}
```

---

## 🔍 2. EVCS Search API

### POST `/search?t={hmac_token}`
Tìm kiếm danh sách trạm sạc gần toạ độ yêu cầu.

* **Base URL:** `https://evcs.vn`
* **Hiệu năng & Rate Limit Benchmark (10 phút polling 5s/lần liên tục):**
  * **0 lỗi HTTP 429**, không bị Cloudflare WAF chặn.
  * **Độ trễ (Latency):** Biến thiên lớn từ 400ms đến 5.4s, có lúc timeout 10s.
  * **Độ trễ cập nhật:** Phản ánh nhanh các phiên cắm/rút sạc trên trạm Nhượng quyền V-GREEN vì đồng bộ trực tiếp với VinFast Mobile App.
* **Request Body:**
```json
{
  "latitude": 21.1554157,
  "longitude": 106.1505615
}
```
* **Response (200 OK):**
```json
{
  "code": 200000,
  "data": [
    {
      "stationName": "VinFast - TTTM Dabaco Mart Quế Võ",
      "stationAddress": "Bãi đỗ xe ngoài trời, siêu thị Dabaco Cinema Quế Võ",
      "latitude": 21.154757,
      "longitude": 106.144707,
      "evse": "VinFast"
    }
  ]
}
```

---

## 🗺️ 3. OSRM Driving Distance & Duration Matrix

### GET `/table/v1/driving/{coordinates}`
Tính ma trận khoảng cách và thời gian lái xe thực tế theo mạng lưới đường bộ cho top các trạm sạc gần nhất.

* **Base URL:** `https://router.project-osrm.org`
* **Query Parameters:** `sources=0&annotations=duration,distance`
* **Lợi ích so với khoảng cách đường chim bay (Haversine):**
  * Tránh sai lệch nghiêm trọng ở địa hình ven biển, sông ngòi hoặc đường cao tốc thiếu nút giao. (Ví dụ: tại Hà Tĩnh `18.1885497, 106.0570067`, khoảng cách Haversine đến CHXD Cẩm Lĩnh chỉ 9.5 km nhưng OSRM driving distance thực tế là 26.3 km do phải đi vòng qua cầu).
* **Response (200 OK):**
```json
{
  "code": "Ok",
  "distances": [[0, 796.8, 5240.4, 6594.9]],
  "durations": [[0, 85.2, 420.5, 510.0]]
}
```
