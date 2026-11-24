package com.reverie.alarm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime

// ============================================================================
// 檢查與安裝更新：讀 GitHub Releases 上的公開版本，比對目前安裝的版本
//   - 只讀公開的 Release 資訊，不送出任何個人資料；沒網路就安靜跳過
//   - 啟動時（開關有開）一天最多檢查一次；「立即檢查更新」每次都查
//   - 有新版就跳出視窗：版本、發布日期、檔案大小，以及從目前版本到最新版之間每一版的更新內容
//   - 「立即更新」在 App 裡下載安裝檔（顯示進度，可以取消或改成背景下載），
//     下載完確認是同一個套件、簽章和目前安裝的相同，才交給系統安裝程式；
//     系統會再跳出確認畫面，覆蓋安裝後班表、鬧鐘與設定都會保留
// ============================================================================

/** 一個版本的更新內容（Release 說明，Markdown） */
data class ReleaseNotes(val version: String, val date: LocalDate?, val body: String)

data class ReleaseInfo(
    val version: String,              // 例如 v2.7.0
    val title: String,
    val date: LocalDate?,             // 發布日期
    val apkUrl: String?,              // 直接下載 APK 的網址
    val apkSize: Long,                // APK 檔案大小（位元組，不知道是 0）
    val pageUrl: String,              // Release 頁面
    val changes: List<ReleaseNotes>   // 比目前版本新的每一版（新的在前）
)

/** 下載與安裝的進度 */
sealed interface UpdateStage {
    data object Idle : UpdateStage
    data object Downloading : UpdateStage
    data class Ready(val file: File) : UpdateStage
    data class Failed(val message: String) : UpdateStage
}

object UpdateChecker {
    private const val API = "https://api.github.com/repos/a097437/Reverie-Alarm/releases?per_page=20"
    private const val PREFS = "reverie_update"
    private const val DIR = "updates"

    /** 要顯示的新版本（null 代表不用跳視窗） */
    var available by mutableStateOf<ReleaseInfo?>(null)
    /** 已知 GitHub 上有比目前新的版本（側邊欄顯示「有新版本」用） */
    var newer by mutableStateOf<ReleaseInfo?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    /** 下載進度：stage 是目前階段；downloaded / total 是位元組（total 不知道時是 0） */
    var stage by mutableStateOf<UpdateStage>(UpdateStage.Idle)
        private set
    var downloaded by mutableLongStateOf(0L)
        private set
    var total by mutableLongStateOf(0L)
        private set
    /** 正在下載（或已下載好）的是哪一版（也是 State：視窗要跟著它重組） */
    private var stageVersion by mutableStateOf<String?>(null)
    /** 關掉視窗改成背景下載：下載完再把視窗叫出來 */
    private var inBackground = false
    /** 去系統設定開「允許安裝」：回到 App 時自動繼續安裝 */
    var waitingInstallPermission by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** App 啟動時呼叫：一天最多一次，略過的版本不再提醒 */
    suspend fun checkOnLaunch(context: Context, enabled: Boolean) {
        cleanup(context)
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

    /** 關掉視窗：正在下載就繼續在背景下載，好了再跳出來 */
    fun dismiss() {
        if (stage == UpdateStage.Downloading) inBackground = true
        available = null
    }

    /** 這個版本目前的下載階段（別的版本一律當作還沒開始） */
    fun stageFor(info: ReleaseInfo): UpdateStage {
        val s = stage // 一定要先讀：畫面才會在階段改變時更新
        return if (stageVersion == info.version) s else UpdateStage.Idle
    }

    // ---------------------------------------------------------------- 下載

    /** 立即更新：在 App 裡下載安裝檔；沒有 APK 的版本就開 Release 頁面 */
    fun startDownload(context: Context, info: ReleaseInfo) {
        val app = context.applicationContext
        val url = info.apkUrl ?: return openInBrowser(context, info)
        if (stageVersion == info.version && (stage == UpdateStage.Downloading || stage is UpdateStage.Ready)) return
        job?.cancel()
        stageVersion = info.version
        inBackground = false
        val dir = File(app.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, "Reverie-Alarm-${info.version}.apk")
        val part = File(dir, target.name + ".part")
        // 只留這一版的檔案（舊版本、下載到一半的別的版本都刪掉）
        dir.listFiles()?.forEach { if (it != target && it != part) it.delete() }

        job = scope.launch {
            try {
                // 已經下載好（例如之前背景下載完、還沒安裝）就不用再下載
                if (!(target.exists() && (info.apkSize <= 0 || target.length() == info.apkSize) && verify(app, target, info) == null)) {
                    target.delete()
                    stage = UpdateStage.Downloading
                    downloaded = part.length()
                    total = info.apkSize
                    withContext(Dispatchers.IO) { download(url, part, info.apkSize) }
                    if (info.apkSize > 0 && part.length() != info.apkSize) error(tr("下載的檔案不完整，請再試一次"))
                    verify(app, part, info)?.let { problem -> part.delete(); error(problem) }
                    if (!part.renameTo(target)) error(tr("無法儲存安裝檔"))
                }
                stage = UpdateStage.Ready(target)
                AppLog.i("Update", "已下載 ${info.version}（${target.length()} bytes）")
                if (inBackground) {
                    // 背景下載完成：把視窗叫回來，按一下就能安裝
                    inBackground = false
                    available = info
                }
            } catch (e: CancellationException) {
                stage = UpdateStage.Idle
                throw e
            } catch (e: Exception) {
                AppLog.w("Update", "下載更新失敗", e)
                stage = UpdateStage.Failed(e.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP") } ?: tr("下載失敗，請確認網路連線後再試一次"))
                if (inBackground) { inBackground = false; available = info }
            }
        }
    }

    /** 取消下載（下載到一半的檔案保留，下次從中斷的地方接著下載） */
    fun cancelDownload() {
        job?.cancel()
        job = null
        stage = UpdateStage.Idle
        inBackground = false
    }

    /** 下載到 part 檔；已經有一部分就用 Range 接著下載。進度每 64KB 更新一次（避免畫面一直重組） */
    private suspend fun download(url: String, part: File, expected: Long) = withContext(Dispatchers.IO) {
        var have = if (part.exists()) part.length() else 0L
        if (expected > 0 && have > expected) { part.delete(); have = 0L }
        if (expected > 0 && have == expected) return@withContext
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ReverieAlarm-Android")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            val append = when (code) {
                206 -> true
                200 -> { have = 0L; false } // 伺服器不支援接續下載：從頭來
                else -> error("HTTP $code")
            }
            val size = if (expected > 0) expected else (conn.contentLengthLong.takeIf { it > 0 }?.plus(have) ?: 0L)
            withContext(Dispatchers.Main) { total = size; downloaded = have }
            var done = have
            var lastShown = done
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastShown >= 64 * 1024) {
                            lastShown = done
                            val now = done
                            withContext(Dispatchers.Main) { downloaded = now }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { downloaded = done }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 確認下載的安裝檔可以放心安裝：是這個 App、版本是要更新的那一版、簽章和目前安裝的相同。
     * 回傳 null 代表沒問題，否則回傳原因
     */
    private suspend fun verify(context: Context, file: File, info: ReleaseInfo): String? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = runCatching { pm.getPackageArchiveInfo(file.path, flags) }.getOrNull()
            ?: return@withContext tr("下載的安裝檔無法讀取，請再試一次")
        if (archive.packageName != context.packageName) return@withContext tr("下載的不是 Reverie Alarm 的安裝檔")
        val archiveVersion = "v" + (archive.versionName ?: "")
        if (isNewer(info.version, archiveVersion) || isNewer(archiveVersion, info.version)) {
            return@withContext tr("下載的安裝檔版本不符（{0}）", archiveVersion)
        }
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
            ?: return@withContext tr("無法讀取目前安裝的版本")
        if (signers(archive) != signers(installed)) return@withContext tr("安裝檔的簽章和目前安裝的不同，為了安全不會安裝")
        null
    }

    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = p.signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") p.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** 更新完成後（或下次啟動時）清掉已經用不到的安裝檔 */
    private fun cleanup(context: Context) {
        if (stage == UpdateStage.Downloading) return
        File(context.applicationContext.cacheDir, DIR).listFiles()?.forEach { f ->
            val v = Regex("""(v\d+(?:\.\d+)*)""").find(f.name)?.value
            if (v == null || !isNewer(v, APP_VERSION)) f.delete()
        }
    }

    // ---------------------------------------------------------------- 安裝

    /** 交給系統安裝程式。還沒允許「安裝不明應用程式」就先帶到系統設定，回來後自動繼續 */
    fun install(context: Context) {
        val file = (stage as? UpdateStage.Ready)?.file ?: return
        if (!file.exists()) { stage = UpdateStage.Idle; return }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            waitingInstallPermission = true
            AppToast.show(tr("請允許 Reverie Alarm 安裝應用程式，回來後會自動繼續"))
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { waitingInstallPermission = false; AppToast.show(tr("無法開啟系統設定")) }
            return
        }
        waitingInstallPermission = false
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            AppLog.w("Update", "開啟安裝程式失敗", it)
            AppToast.show(tr("無法開啟系統安裝程式"))
        }
    }

    /** 從系統設定回到 App：已經允許就繼續安裝 */
    fun onResume(context: Context) {
        if (!waitingInstallPermission) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            waitingInstallPermission = false
            return
        }
        install(context)
    }

    /** 用瀏覽器開下載連結（App 內下載失敗時的備用方法）；沒有 APK 就開 Release 頁面 */
    fun openInBrowser(context: Context, info: ReleaseInfo) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl ?: info.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppToast.show(tr("無法開啟下載連結")) }
    }

    // ---------------------------------------------------------------- 讀 GitHub Releases

    /** 讀最近的 Release：最新的公開版本，加上比目前版本新的每一版更新內容 */
    private suspend fun fetch(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ReverieAlarm-Android") // GitHub API 要求要有 User-Agent
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val releases = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && it.optString("tag_name").isNotBlank() }
            val latest = releases.reduceOrNull { a, b -> if (isNewer(b.getString("tag_name"), a.getString("tag_name"))) b else a }
                ?: error("沒有公開的版本")
            val changes = releases
                .filter { isNewer(it.getString("tag_name"), APP_VERSION) }
                .sortedWith { a, b -> if (isNewer(a.getString("tag_name"), b.getString("tag_name"))) -1 else 1 }
                .map { ReleaseNotes(it.getString("tag_name"), dateOf(it), it.optString("body")) }
            val apk = latest.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", true) }
            }
            ReleaseInfo(
                version = latest.getString("tag_name"),
                title = latest.optString("name"),
                date = dateOf(latest),
                apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                apkSize = apk?.optLong("size") ?: 0L,
                pageUrl = latest.optString("html_url", "https://github.com/a097437/Reverie-Alarm/releases"),
                changes = changes.ifEmpty { listOf(ReleaseNotes(latest.getString("tag_name"), dateOf(latest), latest.optString("body"))) }
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun dateOf(o: JSONObject): LocalDate? = runCatching {
        OffsetDateTime.parse(o.optString("published_at")).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
    }.getOrNull()

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
}

// ============================================================================
// 更新內容排版：Release 說明是 Markdown，整理成「分類標題＋項目」
// ============================================================================

private sealed interface NoteLine {
    data class Heading(val text: String) : NoteLine
    data class Bullet(val text: AnnotatedString, val level: Int) : NoteLine
    data class Paragraph(val text: AnnotatedString) : NoteLine
}

/** 去掉安裝說明、檔案雜湊；「**粗體**」保留成粗體，連結只留文字 */
private fun parseNotes(md: String, bold: Color): List<NoteLine> {
    fun inline(s: String): AnnotatedString = buildAnnotatedString {
        val clean = s.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1").replace("`", "")
        val parts = clean.split("**")
        parts.forEachIndexed { i, p ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Black, color = bold)) { append(p) } else append(p)
        }
    }
    return md.replace("\r", "").substringBefore("## 安裝說明").lines()
        .filterNot { it.isBlank() || it.trim().startsWith("SHA-256") }
        .map { raw ->
            val indent = raw.length - raw.trimStart().length
            val t = raw.trim()
            when {
                t.startsWith("#") -> NoteLine.Heading(t.trimStart('#').trim().replace("**", ""))
                t.startsWith("- ") || t.startsWith("* ") -> NoteLine.Bullet(inline(t.drop(2)), if (indent >= 2) 1 else 0)
                else -> NoteLine.Paragraph(inline(t))
            }
        }
}

private fun sizeText(bytes: Long): String = if (bytes <= 0) "" else "%.1f MB".format(bytes / 1024.0 / 1024.0)

// ============================================================================
// 有新版本的視窗
// ============================================================================

@Composable
fun UpdateDialog(info: ReleaseInfo, theme: ThemePalette, context: Context) {
    val stage = UpdateChecker.stageFor(info)
    val accent = theme.accent

    // 從系統設定（允許安裝）回來時繼續安裝
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) UpdateChecker.onResume(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    MotionAlertDialog(
        onDismissRequest = { UpdateChecker.dismiss() },
        containerColor = theme.bgCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Tabler.Download, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("發現新版本"), fontSize = 19.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                    Text(tr("目前：{0}", APP_VERSION), fontSize = 12.sp, color = theme.textMuted)
                }
                Icon(
                    Tabler.X, contentDescription = tr("關閉"), tint = theme.textMuted,
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(theme.textMain.copy(alpha = 0.06f))
                        .clickable { UpdateChecker.dismiss() }.padding(8.dp)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 版本、發布日期、檔案大小
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(theme.textMain.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(info.version, fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                        info.date?.let { Text(tr("發布於 {0}", it.toString()), fontSize = 12.sp, color = theme.textMuted) }
                    }
                    if (info.apkSize > 0) {
                        Text(
                            sizeText(info.apkSize), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.accentText,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    if (info.changes.size > 1) tr("更新內容（{0} 個版本）", info.changes.size) else tr("更新內容"),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.textMuted
                )
                ReleaseNotesBox(info, theme)
                Text(tr("直接覆蓋安裝，班表、鬧鐘與設定都會保留。"), fontSize = 11.sp, color = theme.textMuted)
                // 下載進度／結果
                when (stage) {
                    UpdateStage.Downloading -> DownloadProgress(theme)
                    is UpdateStage.Ready -> Text(
                        tr("下載完成，已確認是官方簽章的安裝檔"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF22C55E).readable()
                    )
                    is UpdateStage.Failed -> Text(stage.message, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ROSE.readable())
                    UpdateStage.Idle -> {}
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (stage) {
                    UpdateStage.Idle -> {
                        Text(
                            tr("略過這一版"), fontSize = 12.sp, color = theme.textMuted,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { UpdateChecker.skip(context, info) }.padding(6.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        DialogButton(tr("稍後"), theme, filled = false) { UpdateChecker.dismiss() }
                        DialogButton(tr("立即更新"), theme, filled = true, icon = Tabler.Download) { UpdateChecker.startDownload(context, info) }
                    }
                    UpdateStage.Downloading -> {
                        Spacer(Modifier.weight(1f))
                        DialogButton(tr("取消"), theme, filled = false) { UpdateChecker.cancelDownload() }
                        DialogButton(tr("背景下載"), theme, filled = false) { UpdateChecker.dismiss() }
                    }
                    is UpdateStage.Ready -> {
                        Spacer(Modifier.weight(1f))
                        DialogButton(tr("稍後"), theme, filled = false) { UpdateChecker.dismiss() }
                        DialogButton(tr("立即安裝"), theme, filled = true, icon = Tabler.Download) { UpdateChecker.install(context) }
                    }
                    is UpdateStage.Failed -> {
                        Text(
                            tr("用瀏覽器下載"), fontSize = 12.sp, color = theme.textMuted,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { UpdateChecker.dismiss(); UpdateChecker.openInBrowser(context, info) }.padding(6.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        DialogButton(tr("稍後"), theme, filled = false) { UpdateChecker.dismiss() }
                        DialogButton(tr("重試"), theme, filled = true, icon = Tabler.Refresh) { UpdateChecker.startDownload(context, info) }
                    }
                }
            }
        }
    )
}

/** 更新內容：每一版一段，分類標題前面有一條色塊，項目的粗體開頭保留 */
@Composable
private fun ReleaseNotesBox(info: ReleaseInfo, theme: ThemePalette) {
    val multi = info.changes.size > 1
    val parsed = remember(info) { info.changes.map { it to parseNotes(it.body, theme.textMain) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.textMain.copy(alpha = 0.05f))
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        parsed.forEachIndexed { vi, (notes, lines) ->
            if (multi) {
                if (vi > 0) Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(notes.version, notes.date?.toString()).joinToString("・"),
                    fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.accentText
                )
            }
            if (lines.isEmpty()) Text(tr("這一版沒有更新說明"), fontSize = 13.sp, color = theme.textMuted)
            lines.forEach { line ->
                when (line) {
                    is NoteLine.Heading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Box(Modifier.width(3.dp).height(15.dp).clip(RoundedCornerShape(2.dp)).background(theme.accent))
                        Spacer(Modifier.width(8.dp))
                        Text(line.text, fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                    }
                    is NoteLine.Bullet -> Row(Modifier.padding(start = if (line.level > 0) 18.dp else 2.dp)) {
                        Text(if (line.level > 0) "◦" else "•", fontSize = 13.sp, color = theme.accentText, modifier = Modifier.width(12.dp))
                        Text(line.text, fontSize = 13.sp, color = theme.textMain.copy(alpha = 0.82f), lineHeight = 19.sp)
                    }
                    is NoteLine.Paragraph -> Text(line.text, fontSize = 13.sp, color = theme.textMain.copy(alpha = 0.82f), lineHeight = 19.sp)
                }
            }
        }
    }
}

/** 下載進度條：「正在下載更新　29%（6.6 MB / 22.7 MB）」 */
@Composable
private fun DownloadProgress(theme: ThemePalette) {
    val done = UpdateChecker.downloaded
    val all = UpdateChecker.total
    val fraction = if (all > 0) (done.toFloat() / all).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("正在下載更新"), fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
            Text(
                if (all > 0) "${(fraction * 100).toInt()}%（${sizeText(done)} / ${sizeText(all)}）" else sizeText(done),
                fontSize = 12.sp, color = theme.textMuted
            )
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(theme.textMain.copy(alpha = 0.10f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(theme.accent))
        }
    }
}

@Composable
private fun DialogButton(text: String, theme: ThemePalette, filled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) theme.accent.darkenForWhiteText() else theme.textMain.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (filled) Color.White else theme.textMain, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Black, color = if (filled) Color.White else theme.textMain)
    }
}
