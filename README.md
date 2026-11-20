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

  <p><strong>融合AI快速排班與通勤族群設計的日曆與兩段式強效鬧鐘應用</strong></p>
</div>

---

### 核心功能

* **智慧排班日曆**：內建早班、晚班、夜班與休假模板，一鍵快速套用並掌握整月班表。
* **兩段式強效鬧鐘**：針對不同班別自動對應起床提醒與出門倒數，避免睡過頭。
* **AI 快速排班**：用聊的、用說的，或拍一張班表照片，就能排好整個月。
* **天氣結合班表**：出門與下班時段的天氣和帶傘提醒。
* **離線完整運作**：支援 PWA 與本機資料儲存，無網路環境下依然能查看班表與觸發提醒。
* **中文／English**：介面可切換繁體中文或英文。

### v2.0.0 新功能

* **AI 快速排班**（下方最右邊的分頁）：用聊的、用說的，或拍一張紙本／公司公告的班表照片，Gemini 會找出你那一列並排好整個月。先預覽、確認後才套用，兩段式鬧鐘也會一起排好；哪裡不對可以直接跟它說，例如「15 號是夜班」。
  * 登入 Google 帳號即可使用，不需要設定任何 API 金鑰（透過 Firebase AI Logic）。
  * 沒登入也能用：手機上就能看懂「21 到 25 號早班」「下週一三五晚班」「這個月週六日休假，其他早班」這類說法，不會上傳。
* **天氣分頁**：通勤天氣對照你的班表，顯示出門與下班時段的天氣和帶傘提醒；24 小時與 7 天預報、日出日落、紫外線，以及颱風、豪雨、強風等天氣特報。日曆格子也會顯示未來幾天的天氣小圖示。
* **英文介面**：在「系統與質感主題設定」切換「跟隨系統／繁體中文／English」；Android 13 以上也能在系統設定裡切換。
* **介面改版**：日曆格子顯示班別與天氣、鬧鐘清單用時間軸串起「起床 → 出門」、班別模板即時算出起床與出門時間、所有時間改用滾輪選擇；桌面小工具移到右上角「⋮」選單。
* **修正**：桌面小工具「載入視窗小工具時出現問題」。

### 下載與使用

* **Android APK 安裝**：前往 [Releases 頁面](https://github.com/a097437/Reverie-Alarm/releases) 下載最新版本的 `.apk` 安裝檔。

---

###  Android 原生版（Kotlin + Jetpack Compose）

本儲存庫同時包含網頁版（`index.html`，PWA）與 Android 原生版原始碼（`app/`）。

**原生版特色**

* **原生鬧鐘引擎**：使用系統精準鬧鐘，鎖屏、App 關閉、重開機後都會準時響鈴；支援貪睡上限與出門倒數禁止貪睡。
* **內建柔和鈴聲**：11 首授權明確（CC0／CC BY 4.0）的鋼琴、木吉他與八音盒音樂，可無縫循環、音量統一，不刺耳也不會忽大忽小（處理工具：`tools/ringtones/make_tones.py`）。
* **跟手月曆與分頁**：左右拖曳月曆即時預覽上／下個月，拖曳空白處切換 5 大分頁。
* **桌面小工具**：4×2 小工具顯示今日班表與下一個鬧鐘。
* **天氣**：預報來自 Open-Meteo（CC BY 4.0），特報來自國家災害防救科技中心 NCDR 公開資料；只送出地區經緯度。
* **AI 快速排班（Firebase AI Logic）**：Gemini 的金鑰留在 Firebase，App 裡沒有任何 AI 金鑰；只有使用者按下送出時，才會把該則文字或照片交給 Gemini。AI 回覆可以在 App 內回報。
* **共享帳號（Firebase）**：預設不共享；打開後產生 8 碼邀請碼，家人或伴侶輸入後可以看唯讀班表。看得到「誰訂閱了我」，可以個別移除或更換邀請碼。雲端只存日期、班別與上下班時間；私人備註、圖片、附件與鬧鐘不會上傳。

**安全設計**

* **AI 防濫用**：每天 30 次、照片 10 張、送出間隔 3 秒；每則文字 500 字、回覆長度有上限；只帶最近 10 輪對話與最新一張照片。系統指示要求 AI 只做排班，並把訊息與照片裡的文字當資料、不當指令。
* **AI 輸出檢查**：只收今天前 60 天到後 400 天內的日期、一次最多 120 天、班別必須對得上自己的班別模板，而且一定要使用者看過預覽、按「套用」才會寫入。
* **App Check**：已接上 Firebase App Check（正式版 Play Integrity，測試版偵錯提供者）。目前主控台是「未強制執行」，上架 Google Play 後再改成「強制執行」，才能擋住拆 APK 直接打 API 的人。
* **存取控制**：見 `firestore.rules`。班表只有訂閱者名單上的人讀得到，加入名單必須知道目前的邀請碼；所有欄位都有型別與長度限制。
* **存取控制（續）**：讀取別人的班表要同時符合「對方開著共享」「我在訂閱者名單上」「我用的邀請碼是對方目前的邀請碼」，只知道對方的 UID 讀不到；舊的 6 位數邀請碼一律查不到。
* **XSS**：Android 版用 Jetpack Compose 顯示純文字，沒有 WebView，也不解析 HTML。網頁版（PWA）的所有文字經過 `escapeHtml`、事件參數經過 `jsArg`，圖片只接受 `data:image`、附件網址擋掉 `javascript:`；匯入的備份檔、localStorage 與雲端資料都先經過 `sanitizeAppData` 驗證格式與長度。
* **網頁版登入**：使用真正的 Google 登入（Firebase Authentication 彈出視窗），和 Android 版共用同一套共享與安全規則；權限一律由 Firestore 安全規則在伺服器端檢查。網頁上的排班預設不會上傳，要手動開啟「用這個瀏覽器的班表更新分享內容」。

**鈴聲音樂來源**

以下 2 首依 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 授權使用，已剪輯成循環片段並調整音量（App 內「關於應用」也有標註）：

* 「破曉時分」："Dawn's Break" by Shane Ivers (https://www.silvermansound.com), CC BY 4.0
* 「鳥鳴晨光」："Wake up in the morning sun" by 東月 (https://dongnguyet.itch.io/morning-sun), CC BY 4.0

其餘 9 首為 CC0（公眾領域），免標註，出處供參考：

* 「晨空漫步」「鋼琴晨空」：*Morning Sky* by Centurion_of_war（[OpenGameArt](https://opengameart.org/node/179462)）
* 「微光粒子」：*First Light Particles* by Yoiyami（[OpenGameArt](https://opengameart.org/node/182244)）
* 「緩緩甦醒」：*The Budding of Consciousness* by Yoiyami（[OpenGameArt](https://opengameart.org/content/the-budding-of-consciousness-%E2%80%93-cc0-ambient-minimalist-theme-yoiyami-blue-series-%E2%80%93-no4)）
* 「草原微風」：*Sunset Plains* by Yoiyami（[OpenGameArt](https://opengameart.org/node/182253)）
* 「營火吉他」：*A Small Fire Will Do* by Trex0n（[OpenGameArt](https://opengameart.org/content/a-small-fire-will-do-calming-loop)）
* 「晨間吉他」：*Etirwer* by Kistol（[OpenGameArt](https://opengameart.org/content/etirwer)）
* 「清脆八音盒」：*Music Box Cute Tune* by Aureolus_Omicron（[OpenGameArt](https://opengameart.org/node/136996)）
* 「晨鈴琶音」：*Happy Lullaby* by cynicmusic（[OpenGameArt](https://opengameart.org/content/happy-lullaby-song17)）

**自行編譯**

1. 用 Android Studio 開啟專案根目錄。
2. 若要使用自己的 Firebase 專案：把下載的 `google-services.json` 放到 `app/`，執行 `python tools/firebase/gen_firebase_values.py`，並把 `firestore.rules` 發布到 Firestore。
   * 要使用 AI 快速排班：在 Firebase 主控台 → **AI Logic** 按「開始使用」並選擇 **Gemini Developer API**。App 不是從 Play 商店安裝時，請在 **App Check → APIs** 把 Firebase AI Logic 設為 **Unenforced**，否則請求會被擋下。
   * 網頁版要能登入：在 Firebase 主控台 → **Authentication → 設定 → 已授權網域** 加入網頁版的網域（例如 `a097437.github.io`）。
   * 測試版（debug／perfTest）使用 App Check 偵錯提供者：第一次開啟時 logcat 會印出偵錯權杖（搜尋 `DebugAppCheckProvider`），App Check 改成強制執行前，要把它加到 **App Check → 應用程式 → 管理偵錯權杖**。
3. 選擇 Build Variant：`debug`（開發）或 `perfTest`（最佳化、可直接覆蓋 debug 版安裝）。

> 簽名金鑰（`*.jks`、`*.keystore`）與 `local.properties` 已在 `.gitignore` 排除，請勿上傳。
