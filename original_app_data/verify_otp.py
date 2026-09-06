import urllib.request, json, sys

if len(sys.argv) < 2:
    print("Usage: python3 verify_otp.py <OTP_CODE>")
    sys.exit(1)

otp = sys.argv[1].strip()

with open("auth_state.json", "r", encoding="utf-8") as f:
    state = json.load(f)

email = state["email"]
cookie = state["cookie"]
csrf = state["csrf"]

headers_post = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 EVCS/A1.57 Mobile",
    "Referer": "https://evcs.vn/reward.html",
    "Origin": "https://evcs.vn",
    "Content-Type": "application/json",
    "Cookie": cookie,
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "same-origin"
}

body = json.dumps({"action": "verify_otp", "csrf": csrf, "email": email, "otp": otp}).encode("utf-8")
req = urllib.request.Request("https://evcs.vn/reward.html", data=body, headers=headers_post, method="POST")

try:
    with urllib.request.urlopen(req) as resp:
        print(f"HTTP Status: {resp.status}")
        raw = resp.read().decode("utf-8")
        print(f"Response Body: {raw}")
        
        # Check set-cookie
        new_cookies = []
        for k, v in resp.headers.items():
            if "set-cookie" in k.lower():
                new_cookies.append(v)
                print(f"New Cookie: {v}")
                
        state["verify_response"] = raw
        if new_cookies:
            state["new_cookies"] = new_cookies
        with open("auth_state.json", "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2)
            
        data = json.loads(raw)
        if data.get("ok"):
            print("\n>>> DANG NHAP THANH CONG! <<<")
            print("Dang thu tai danh sach yeu thich...")
            
            # Fetch favorites
            fav_headers = {
                "User-Agent": "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 EVCS/A1.57 Mobile",
                "Referer": "https://evcs.vn/favorite.html",
                "Origin": "https://evcs.vn",
                "X-Partial": "fav",
                "Cookie": cookie,
                "Sec-Fetch-Dest": "empty",
                "Sec-Fetch-Mode": "cors",
                "Sec-Fetch-Site": "same-origin"
            }
            fav_req = urllib.request.Request("https://evcs.vn/favorite.html", headers=fav_headers, method="POST")
            with urllib.request.urlopen(fav_req) as fav_resp:
                fav_body = fav_resp.read().decode("utf-8")
                print(f"Favorites response: {fav_body}")
                state["favorites"] = json.loads(fav_body)
                with open("auth_state.json", "w", encoding="utf-8") as f:
                    json.dump(state, f, indent=2)
except urllib.error.HTTPError as e:
    print(f"HTTP Error: {e.code} - {e.read().decode('utf-8')}")
