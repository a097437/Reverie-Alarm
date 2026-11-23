package com.reverie.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

// ============================================================================
// 各品牌手機的「背景執行／自動啟動」設定
//
// 很多廠牌在 Android 原本的「電池最佳化」之外，還有自己的省電機制，會把背景的 App 殺掉，
// 鬧鐘就可能不響。這些設定沒有統一的 API 可以查，只能告訴使用者怎麼開、幫他打開對應的設定頁。
//
//   - 已知會殺背景的品牌：列出步驟、優先打開該品牌的設定頁（打不開就退回「應用程式資訊」）
//   - 原生 Android（Google、Motorola、Sony、Nokia…）：只要「不限制電池用量」就夠，不用額外設定
//   - 每個品牌都附上 dontkillmyapp.com 的教學頁（社群維護，各型號／系統版本的詳細步驟）
// ============================================================================

data class OemGuide(
    /** 畫面上顯示的品牌名稱 */
    val brand: String,
    /** 要使用者做的事（一兩句話） */
    val steps: String,
    /** 依序嘗試打開的設定頁（品牌自己的頁面在前） */
    val intents: List<Intent>,
    /** dontkillmyapp.com 上的頁面 */
    val helpUrl: String
)

object OemBackground {

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    /** 手機品牌（Build.MANUFACTURER 與 Build.BRAND 都看，POCO、iQOO、realme 等子品牌才認得出來） */
    private val brandKey: String by lazy {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        fun has(vararg k: String) = k.any { m.contains(it) || b.contains(it) }
        when {
            has("xiaomi", "redmi", "poco") -> "xiaomi"
            has("honor") -> "honor"
            has("huawei") -> "huawei"
            has("oneplus") -> "oneplus"
            has("realme") -> "realme"
            has("oppo") -> "oppo"
            has("vivo", "iqoo") -> "vivo"
            has("samsung") -> "samsung"
            has("asus") -> "asus"
            has("meizu") -> "meizu"
            has("lenovo") -> "lenovo"
            else -> "other"
        }
    }

    /** 這支手機需要額外設定嗎？（原生 Android 不需要） */
    val needsExtraStep: Boolean get() = brandKey != "other"

    /** 這支手機的設定方式；原生 Android 回傳 null */
    fun guide(context: Context): OemGuide? {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        fun g(brand: String, steps: String, slug: String, vararg own: Intent) =
            OemGuide(brand, steps, own.toList() + details, "https://dontkillmyapp.com/$slug")
        return when (brandKey) {
            "xiaomi" -> g(
                tr("小米／紅米／POCO"),
                tr("打開「自啟動」，「省電策略」選「無限制」"),
                "xiaomi",
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            "huawei", "honor" -> g(
                if (brandKey == "honor") tr("榮耀") else tr("華為"),
                tr("「應用程式啟動管理」關閉自動管理，改成手動並打開「自動啟動、關聯啟動、在背景執行」"),
                "huawei",
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            "oppo", "realme", "oneplus" -> g(
                when (brandKey) { "realme" -> "realme"; "oneplus" -> "OnePlus"; else -> "OPPO" },
                tr("允許「自動啟動」，「耗電管理」允許在背景執行"),
                if (brandKey == "oppo") "oppo" else brandKey,
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            )
            "vivo" -> g(
                tr("vivo／iQOO"),
                tr("「背景耗電管理」選「允許高耗電」，並打開「自動啟動」"),
                "vivo",
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            )
            "samsung" -> g(
                tr("三星"),
                tr("「電池 → 背景使用限制」把 Reverie Alarm 加到「永不休眠的應用程式」"),
                "samsung",
                component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
            "asus" -> g(
                tr("華碩"),
                tr("「行動管理員 → 自動啟動管理」允許 Reverie Alarm"),
                "asus",
                component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                component("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity")
            )
            "meizu" -> g(
                tr("魅族"),
                tr("「背景管理」允許 Reverie Alarm 在背景執行"),
                "meizu",
                component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            )
            "lenovo" -> g(
                tr("聯想"),
                tr("「電池 → 背景管理」允許 Reverie Alarm 在背景執行"),
                "lenovo"
            )
            else -> null
        }
    }

    /** 原生 Android 也附一個通用教學頁 */
    const val GENERIC_HELP = "https://dontkillmyapp.com/"
}
