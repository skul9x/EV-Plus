# 💡 BRIEF: Tích Hợp VinFast CAPP Public API Trực Tiếp & Cơ Chế Fallback (Giảm Phụ Thuộc EVCS.vn)

**Ngày tạo:** 06/09/2026  
**Brainstorm cùng:** skul9x  
**Dự án:** EV Plus (D:\skul9x\EV-Plus-main)  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT
1. **Phụ thuộc rủi ro vào bên thứ 3 (evcs.vn):**
   - Hiện tại EV-Plus dựa hoàn toàn vào evcs.vn thông qua chữ ký HMAC-SHA256 với token tĩnh (eepe5dp9zpipl102). Nếu trang này thay đổi thuật toán, đổi token, bảo trì hoặc bị sập, app sẽ ngưng hoạt động.
   - Thường xuyên đối mặt với nguy cơ Rate Limit, Cloudflare Challenge 403, và độ trễ mạng do đi qua proxy trung gian.
2. **Nhu cầu dữ liệu gốc, thời gian thực:**
   - Dữ liệu trạm sạc từ chính backend VinFast luôn là dữ liệu nguồn (chính xác 100%, độ trễ thấp <200ms, hình ảnh CDN gốc không bị chặn).

---

## 2. GIẢI PHÁP ĐỀ XUẤT
1. **Phương án A - Direct from Client (Zero-Cost Architecture):**
   - App Android trên máy người dùng gọi trực tiếp đến API Public của VinFast (https://mobile.connected-car.vinfast.vn/).
   - Không cần tài khoản đăng nhập (chế độ Khách / Public CAPP).
   - Đính kèm đầy đủ bộ headers chuẩn của VinFast CAPP Android App (như X-APP-VERSION: 2.25.7, X-SERVICE-NAME: CAPP, X-Device-Platform: android...).
2. **Kiến trúc Dự Phòng 2 Tầng (Dual-Tier Fallback):**
   - **Tier 1 (Ưu tiên số 1):** Gọi trực tiếp VinFast CAPP API. Nhanh nhất, dữ liệu chuẩn nhất.
   - **Tier 2 (Fallback tự động):** Nếu máy chủ VinFast bảo trì hoặc timeout/không phản hồi, tự động trượt về evcs.vn. Người dùng không nhận thấy gián đoạn.
3. **Bảo toàn 100% dữ liệu Firestore:**
   - Vì mã định danh trạm locationId của VinFast và evcs.vn trùng khớp 100%, danh sách Yêu thích của người dùng trên Firebase Firestore không cần migrate, hoạt động liền mạch ngay lập tức.
4. **Bộ lọc Trụ Sạc Ô tô Điện:**
   - Tiếp tục duy trì cơ chế lọc cứng: Chỉ lấy các trụ sạc dành cho ô tô (từ 11kW trở lên: 11kW, 30kW, 60kW, 120kW, 150kW, 180kW, 250kW, 300kW, 360kW).
   - Tự động lọc bỏ các trụ sạc xe máy AC 3.5kW/7kW.

---

## 3. ĐỐI TƯỢNG SỬ DỤNG
- **Chủ xe & Tài xế Ô tô điện VinFast** (VF 3, VF 5, VF e34, VF 6, VF 7, VF 8, VF 9, Taxi Xanh SM).
- Cần một ứng dụng tra cứu trạm sạc cực nhanh, mượt mà, chính xác số trụ trống/đang sạc mà không lo bị lỗi mạng hay sập server trung gian.

---

## 4. PHÂN KỲ TÍNH NĂNG

### 🚀 MVP (Giai đoạn triển khai ngay):
- [ ] Xây dựng VinFastCAppApiClient: Gọi POST /ccarcharging/api/v1/stations/search và /stations/location-info.
- [ ] Thiết lập VinFastHeaderInterceptor: Tự động sinh device headers chuẩn CAPP.
- [ ] Mapper DTO: Chuyển đổi RemoteChargingStationsStatus của VinFast sang Station & PowerPort của EV-Plus.
- [ ] Tích hợp StationRepositoryCoordinator: Điều phối gọi Tier 1 (VinFast) -> nếu fail tự động gọi Tier 2 (EVCS).
- [ ] Tận dụng trực tiếp link ảnh CDN gốc VinFast (cpo-prod-s3.vinfastauto.com / d1aza9v8tzxrkt.cloudfront.net).

### 🎁 Phase 2 (Nâng cao):
- [ ] Remote Config cho X-APP-VERSION: Cập nhật số phiên bản app VinFast từ xa qua Firebase, tránh nguy cơ backend chặn app cũ.
- [ ] Tích hợp HERE Maps EV API làm đường lùi quốc tế khi ra nước ngoài.

---

## 5. ƯỚC TÍNH SƠ BỘ
- **Độ phức tạp:** Trung bình (1 - 2 ngày code và test).
- **Rủi ro:** Cực thấp (Do có hệ thống Fallback 2 tầng bảo hiểm: nếu VinFast API lỗi thì app lập tức chạy bằng EVCS như cũ).

---

## 6. BƯỚC TIẾP THEO
→ Tiến hành chạy /plan để lập bản thiết kế kỹ thuật, sơ đồ lớp và danh sách task cụ thể.
