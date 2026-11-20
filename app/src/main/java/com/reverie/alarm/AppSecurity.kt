package com.reverie.alarm

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

// ============================================================================
// Firebase App Check：讓 Firebase（AI Logic、Firestore）確認請求真的來自這個 App，
// 擋掉「拆 APK 拿到設定檔後，自己寫程式狂打 API」的濫用。
//
//   - 正式版：Play Integrity（需要從 Google Play 安裝才拿得到有效憑證）
//   - 測試版（debug／perfTest）：偵錯提供者。第一次執行時 logcat 會印出偵錯權杖，
//     要加到 Firebase 主控台 → App Check → 應用程式 → 管理偵錯權杖
//
// 主控台還是「未強制執行（Unenforced）」時只會統計，不會擋人；
// 上架 Google Play、確認指標裡大多是「已驗證」的請求後，再改成「強制執行」。
// ============================================================================
object AppSecurity {
    private var installed = false

    fun installAppCheck(context: Context) {
        if (installed) return
        installed = true
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) return
            val factory = (if (BuildConfig.APP_CHECK_DEBUG) debugFactory() else null)
                ?: PlayIntegrityAppCheckProviderFactory.getInstance()
            Firebase.appCheck.installAppCheckProviderFactory(factory)
        }.onFailure { android.util.Log.w("AppSecurity", "App Check 安裝失敗", it) }
    }

    /** 偵錯提供者只打包在測試版裡，正式版沒有這個類別，所以用名稱載入 */
    private fun debugFactory(): AppCheckProviderFactory? = runCatching {
        Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
            .getMethod("getInstance").invoke(null) as AppCheckProviderFactory
    }.getOrNull()
}
