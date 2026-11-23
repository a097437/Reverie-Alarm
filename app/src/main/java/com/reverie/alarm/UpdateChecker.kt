package com.reverie.alarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

// ============================================================================
// 檢查更新：讀 GitHub Releases 上最新的公開版本，比對目前安裝的版本
//   - 只讀公開的 Release 資訊，不送出任何個人資料；沒網路就安靜跳過
//   - 啟動時（開關有開）一天最多檢查一次；「立即檢查更新」每次都查
//   - 有新版就跳出視窗：版本號、更新內容、下載更新／稍後／略過這一版
// ============================================================================

data class ReleaseInfo(
    val version: String,   // 例如 v2.0.1
    val title: String,
    val notes: String,     // 已整理成純文字的更新內容
    val apkUrl: String?,   // 直接下載 APK 的網址
    val pageUrl: String    // Release 頁面
)

object UpdateChecker {
    private const val API = "https://api.github.com/repos/a097437/Reverie-Alarm/releases/latest"
    private const val PREFS = "reverie_update"

    /** 要顯示的新版本（null 代表不用跳視窗） */
    var available by mutableStateOf<ReleaseInfo?>(null)
    /** 已知 GitHub 上有比目前新的版本（側邊欄顯示「有新版本」用） */
    var newer by mutableStateOf<ReleaseInfo?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    /** App 啟動時呼叫：一天最多一次，略過的版本不再提醒 */
    suspend fun checkOnLaunch(context: Context, enabled: Boolean) {
        if (!enabled) return
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (sp.getString("last_check", null) == today) return
        val info = runCatching { fetch() }.onFailure { AppLog.w("Update", "檢查更新失敗", it) }.getOrNull() ?: return // 沒網路就下次再說
        sp.edit().putString("last_check", today).apply()
        if (isNewer(info.version, APP_VERSION)) {
            newer = info
            if (sp.getString("skipped", null) != info.version) available = info
        }
    }

    /** 「立即檢查更新」：每次都查。回傳 true＝有新版（已跳視窗）、false＝已是最新、null＝查不到 */
    suspend fun checkNow(context: Context): Boolean? {
        if (checking) return null
        checking = true
        try {
            val info = runCatching { fetch() }.getOrNull() ?: return null
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last_check", LocalDate.now().toString()).apply()
            return if (isNewer(info.version, APP_VERSION)) {
                newer = info; available = info; true
            } else {
                newer = null; false
            }
        } finally {
            checking = false
        }
    }

    fun skip(context: Context, info: ReleaseInfo) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skipped", info.version).apply()
        available = null
    }

    /** 下載更新：用瀏覽器下載 APK（簽名相同，可以直接覆蓋安裝）；沒有 APK 就開 Release 頁面 */
    fun download(context: Context, info: ReleaseInfo) {
        available = null
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl ?: info.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppToast.show(tr("無法開啟下載連結")) }
    }

    private suspend fun fetch(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ReverieAlarm-Android") // GitHub API 要求要有 User-Agent
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }?.optString("browser_download_url")
            ReleaseInfo(
                version = o.getString("tag_name"),
                title = o.optString("name"),
                notes = plainNotes(o.optString("body")),
                apkUrl = apk?.takeIf { it.isNotBlank() },
                pageUrl = o.optString("html_url", "https://github.com/a097437/Reverie-Alarm/releases")
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 「v2.0.1」比「v2.0.0」新嗎？逐段比較數字 */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Release 說明是 Markdown：整理成手機上好讀的純文字，去掉安裝說明與檔案雜湊 */
    fun plainNotes(md: String): String = md.replace("\r", "")
        .substringBefore("## 安裝說明")
        .lines()
        .filterNot { it.trim().startsWith("SHA-256") }
        .joinToString("\n") { line ->
            line.trim()
                .replace(Regex("^#+\\s*"), "")
                .replace(Regex("^[-*]\\s+"), "• ")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                .replace("**", "").replace("`", "")
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** 有新版本的視窗 */
@Composable
fun UpdateDialog(info: ReleaseInfo, theme: ThemePalette, context: Context) {
    MotionAlertDialog(
        onDismissRequest = { UpdateChecker.available = null },
        containerColor = theme.bgCard,
        title = {
            Column {
                IconText(tr("🎉 有新版本 {0}", info.version), fontWeight = FontWeight.Black, color = theme.textMain, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                Text(tr("目前使用 {0}", APP_VERSION), fontSize = 12.sp, color = theme.textMuted)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    info.notes.ifBlank { info.title }, fontSize = 13.sp, color = theme.textMain, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                )
                Text(tr("直接覆蓋安裝就好，班表、鬧鐘與設定都會保留。"), fontSize = 11.sp, color = theme.textMuted)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { UpdateChecker.skip(context, info) }) { Text(tr("略過這一版"), color = theme.textMuted) }
                TextButton(onClick = { UpdateChecker.available = null }) { Text(tr("稍後"), color = theme.textMuted) }
            }
        },
        confirmButton = {
            TextButton(onClick = { UpdateChecker.download(context, info) }) {
                Text(tr("下載更新"), color = theme.accentText, fontWeight = FontWeight.Black)
            }
        }
    )
}
