"""
把 app/google-services.json 轉成 Firebase 啟動需要的 Android 資源（res/values/firebase.xml）。
（作用和 Google Services Gradle 外掛一樣，但不受 AGP 版本相容性影響）

執行：python tools/firebase/gen_firebase_values.py
更換 google-services.json 後要重新執行一次。
"""
import json
import os
from xml.sax.saxutils import escape

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SRC = os.path.join(ROOT, "app", "google-services.json")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "values", "firebase.xml")
PACKAGE = "com.reverie.alarm"

cfg = json.load(open(SRC, encoding="utf-8"))
client = next(c for c in cfg["client"] if c["client_info"]["android_client_info"]["package_name"] == PACKAGE)
web_ids = [o["client_id"] for o in client.get("oauth_client", []) if o.get("client_type") == 3]
values = {
    "google_app_id": client["client_info"]["mobilesdk_app_id"],
    "gcm_defaultSenderId": cfg["project_info"]["project_number"],
    "project_id": cfg["project_info"]["project_id"],
    "google_api_key": client["api_key"][0]["current_key"],
    "google_storage_bucket": cfg["project_info"].get("storage_bucket", ""),
    # Google 登入需要的「網頁用戶端 ID」（Firebase 啟用 Google 登入後才會出現在 json 裡）
    "default_web_client_id": web_ids[0] if web_ids else "",
}
lines = ['<?xml version="1.0" encoding="utf-8"?>',
         '<!-- 由 tools/firebase/gen_firebase_values.py 從 google-services.json 產生，請勿手動修改 -->',
         '<resources>']
for k, v in values.items():
    lines.append(f'    <string name="{k}" translatable="false">{escape(v)}</string>')
lines.append('</resources>')
open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("已產生", os.path.normpath(OUT))
print("Google 登入網頁用戶端 ID：", "✓ 已設定" if web_ids else "✗ 缺少（請在 Firebase 啟用 Google 登入並加入 SHA-1 後重新下載 google-services.json）")
