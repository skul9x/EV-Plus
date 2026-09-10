# Quy Tắc Phân Loại Trạm Sạc: Chính Hãng VinFast vs Nhượng Quyền Tư Nhân (V-GREEN)

Ngày cập nhật: 2026-09-10  
Tài liệu định nghĩa các tiêu chí nhận diện và phân loại trạm sạc phục vụ thuật toán lọc, hiển thị UI và lựa chọn nguồn telemetry tối ưu cho EV-Plus.

---

## 1. Bảng Tiêu Chí So Sánh Tổng Quan

| Tiêu chí | Trạm Chính Hãng VinFast | Trạm Nhượng Quyền Tư Nhân (V-GREEN) |
| :--- | :--- | :--- |
| **Mã trạm (Station Code)** | Số thứ tự nhỏ, triển khai đời đầu (Ví dụ: `C.HNO0050`, `C.BNI0012`, `C.SLA0004`...) | Số thứ tự lớn, mở rộng gần đây (Ví dụ: `C.HNO1100`, `C.HNO1215`, `C.BNI0324`...) |
| **Vị trí lắp đặt** | Hệ sinh thái Vingroup (Vinhomes, Vincom Mega Mall), Showroom VinFast 3S, Trạm dừng nghỉ cao tốc lớn | Cây xăng tư nhân (PVOIL, Petrolimex, CHXD tư nhân), xưởng sửa chữa/gara ô tô, nhà hàng, quán cafe, bãi giữ xe tư nhân |
| **Thời gian mở cửa** | **24/7** không rào cản | Thường phụ thuộc giờ kinh doanh của đối tác (ví dụ gara 07:00 - 18:00, cây xăng đến 22:00) |
| **Chính sách phí phụ thu** | Theo quy định chuẩn VinFast (miễn phí sạc/gửi xe theo chính sách hoặc giá vé gửi xe chuẩn) | Có thể có phụ phí bãi đỗ riêng của chủ mặt bằng tư nhân |
| **Quy mô công suất** | Thường là cụm lớn: Nhiều tủ sạc 60kW, 120kW, 150kW đến siêu nhanh 250kW | Thường quy mô nhỏ gọn: 1-2 trụ 30kW hoặc 60kW (tận dụng trạm biến áp có sẵn của cơ sở) |
| **Nguồn Telemetry nhanh nhất** | **HERE Maps EV API** (nhận OCPP tức thì trong vài giây) | **EVCS.vn API** (nhận sự kiện cắm/rút sạc qua phiên sạc VinFast App nhanh hơn batch roaming của HERE) |

---

## 2. Dấu Hiệu Nhận Biết Trên Dữ Liệu API

### Dấu hiệu 1: Tên địa điểm & Địa chỉ
- Trạm nhượng quyền thường có tên chứa: `CHXD` (Cửa hàng xăng dầu), `Gara`, `Auto`, `Xưởng`, `Cà phê`, `Nhà hàng`, hoặc tên công ty TNHH tư nhân (ví dụ: *Gara Dân Chủ*, *CHXD Sơn Quang Huy*, *CHXD Sài Đồng*).
- Trạm chính hãng thường mang tên: *Vincom Plaza*, *Vinhomes Ocean Park*, *Showroom VinFast*, *TTTM Dabaco*...

### Dấu hiệu 2: Mã EVSE trên HERE Maps API
- Súng sạc của trạm chính hãng thường có định dạng chuẩn: `VN*VNF*{MãTrạmNhỏ}*...`
- Trạm nhượng quyền thường mang mã số mở rộng từ `HNO1000` trở lên, `BNI0300` trở lên.

### Dấu hiệu 3: Tần suất biến động trên Telemetry
- Trạm chính hãng: Trạng thái trên HERE API chuyển `AVAILABLE` $\leftrightarrow$ `OCCUPIED` đồng bộ ngay khi xe cắm/rút súng.
- Trạm nhượng quyền: HERE API có thể giữ trạng thái tĩnh suốt 15-30 phút (do chu kỳ roaming sync), trong khi EVCS thay đổi ngay.
