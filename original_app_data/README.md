# Original App Data (Dữ liệu Ứng dụng Gốc EVCS)

Thư mục này lưu trữ toàn bộ dữ liệu, gói cài đặt và tài liệu phân tích kỹ thuật đảo ngược (Reverse Engineering) từ ứng dụng gốc **Tramsac-EV** để phục vụ công tác nghiên cứu, đối chiếu API và thuật toán xác thực:

- **`Tramsac-EV.xapk`**: File gói ứng dụng gốc Android.
- **`extracted_xapk/`**: Nội dung giải nén từ file .xapk.
- **`apktool_out/`**: Kết quả dịch ngược tài nguyên và bytecode smali qua Apktool.
- **`src_code/`**: Mã nguồn Java và tài nguyên trích xuất qua JADX (`sources/`, `resources/`).
- **`*.html`**: Các trang web template/hybrid view lưu lại từ server EVCS (`app_home.html`, `favorite_page.html`, `index_page.html`, `reward_login_form.html`, `reward_page.html`, `station_detail.html`).
- **`web_*.js`**: Các file JavaScript client logic gốc từ hệ thống webview của EVCS.
- **`auth_state.json`**: Phiên đăng nhập mẫu thu thập trong quá trình reverse engineering.
- **`verify_otp.py`**: Script Python mẫu kiểm thử tương tác luồng gửi/nhận OTP.
- **`thuattoan.md`**: Ghi chép tài liệu phân tích thuật toán CSRF token và chữ ký bảo mật.
