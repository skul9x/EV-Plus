# API Documentation - EV+

Ngày cập nhật: 2026-09-06

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
Tính ma trận khoảng cách và thời gian lái xe thực tế theo mạng lưới đường bộ cho 10 trạm sạc gần nhất.

* **Base URL:** `https://router.project-osrm.org`
* **Query Parameters:** `sources=0&annotations=duration,distance`
* **Response (200 OK):**
```json
{
  "code": "Ok",
  "distances": [[0, 796.8, 5240.4, 6594.9, ...]],
  "durations": [[0, 85.2, 420.5, 510.0, ...]]
}
```
