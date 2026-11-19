<div align="center">
  <img src="./icon-512.png" width="120" height="120" alt="Reverie Alarm Logo" />

  <h1>Reverie Alarm</h1>

  <p>
    <a href="https://github.com/a097437/Reverie-Alarm/releases">
      <img src="https://img.shields.io/github/v/release/a097437/Reverie-Alarm?label=release&color=2563eb&style=flat-square" alt="Release" />
    </a>
    <img src="https://img.shields.io/badge/Platform-Android%20%7C%20PWA-3ddc84?style=flat-square" alt="Platform" />
    <img src="https://img.shields.io/badge/Offline-離線可用-f59e0b?style=flat-square" alt="Offline" />
  </p>

  <p><strong>專為輪班與通勤族群設計的排班日曆與兩段式強效鬧鐘應用</strong></p>
</div>

---

### ✨ 核心功能

* **📅 智慧排班日曆**：內建早班、晚班、夜班與休假模板，一鍵快速套用並掌握整月班表。
* **⏰ 兩段式強效鬧鐘**：針對不同班別自動對應起床提醒與出門倒數，避免睡過頭。
* **✈️ 離線完整運作**：支援 PWA 與本機資料儲存，無網路環境下依然能查看班表與觸發提醒。

### 📥 下載與使用

* **Android APK 安裝**：前往 [Releases 頁面](https://github.com/a097437/Reverie-Alarm/releases) 下載最新版本的 `.apk` 安裝檔。

---

### 📱 Android 原生版（Kotlin + Jetpack Compose）

本儲存庫同時包含網頁版（`index.html`，PWA）與 Android 原生版原始碼（`app/`）。

**原生版特色**

* **原生鬧鐘引擎**：使用系統精準鬧鐘，鎖屏、App 關閉、重開機後都會準時響鈴；支援貪睡上限與出門倒數禁止貪睡。
* **內建合成鈴聲**：5 首程式合成的鬧鐘音效，無版權疑慮（產生器：`tools/ringtones/generate_ringtones.py`）。
* **跟手月曆與分頁**：左右拖曳月曆即時預覽上／下個月，拖曳空白處切換 5 大分頁。
* **桌面小工具**：4×2 小工具顯示今日班表與下一個鬧鐘。
* **共享帳號（Firebase）**：Google 登入後產生 6 位數邀請碼，可訂閱家人或伴侶的唯讀班表。雲端只存日期、班別與上下班時間；私人備註、圖片、附件與鬧鐘不會上傳。

**自行編譯**

1. 用 Android Studio 開啟專案根目錄。
2. 若要使用自己的 Firebase 專案：把下載的 `google-services.json` 放到 `app/`，執行 `python tools/firebase/gen_firebase_values.py`，並把 `firestore.rules` 發布到 Firestore。
3. 選擇 Build Variant：`debug`（開發）或 `perfTest`（最佳化、可直接覆蓋 debug 版安裝）。

> 簽名金鑰（`*.jks`、`*.keystore`）與 `local.properties` 已在 `.gitignore` 排除，請勿上傳。
