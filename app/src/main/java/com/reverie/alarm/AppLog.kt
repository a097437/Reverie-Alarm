package com.reverie.alarm

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ============================================================================
// 診斷日誌：寫進手機檔案（App 關掉、重開機都還在），匯出時整理成一份完整報告
//
//   - app.log：重要事件（鬧鐘排程／觸發／響鈴／貪睡／關閉、鈴聲播放失敗改用備援、
//              AI 與雲端錯誤、匯入結果…），超過 512 KB 自動輪替成 app.log.1
//   - crash-*.txt：App 閃退時的完整錯誤堆疊（最多留 10 份）
//   - 匯出報告另外附上：手機與系統資訊、權限狀態、鬧鐘音量與勿擾、排程中的鬧鐘、
//              以及這次執行期間系統 logcat 裡本 App 的紀錄
//   - 匯出時會把 Email 遮掉
// ============================================================================
object AppLog {
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_CRASHES = 10
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var dir: File? = null
    private val lock = Any()

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        installCrashHandler()
        i("App", "啟動 ${APP_VERSION}（Android ${Build.VERSION.RELEASE}／API ${Build.VERSION.SDK_INT}，${Build.MANUFACTURER} ${Build.MODEL}）")
    }

    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    /**
     * 寫檔交給背景的單一執行緒排隊做：呼叫的地方（多半是主執行緒）不必等硬碟，
     * 順序也和呼叫順序一樣。閃退時改成直接寫（見 installCrashHandler）
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ReverieAppLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private fun write(level: Char, tag: String, msg: String, t: Throwable?, sync: Boolean = false) {
        when (level) {
            'E' -> android.util.Log.e("Reverie/$tag", msg, t)
            'W' -> android.util.Log.w("Reverie/$tag", msg, t)
            else -> android.util.Log.i("Reverie/$tag", msg)
        }
        val d = dir ?: return
        val line = buildString {
            append(LocalDateTime.now().format(stamp)).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append("\n    ").append(t.javaClass.name).append(": ").append(t.message.orEmpty().take(300))
            append('\n')
        }
        if (sync) appendLine(d, line)
        else runCatching { io.execute { appendLine(d, line) } }.onFailure { appendLine(d, line) }
    }

    private fun appendLine(d: File, line: String) {
        synchronized(lock) {
            runCatching {
                val f = File(d, "app.log")
                if (f.length() > MAX_BYTES) {
                    File(d, "app.log.1").delete()
                    f.renameTo(File(d, "app.log.1"))
                }
                File(d, "app.log").appendText(line)
            }
        }
    }

    /** 等背景排隊中的紀錄都寫進檔案（匯出報告前用，最多等 2 秒） */
    private fun drain() {
        runCatching { io.submit { }.get(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val d = dir ?: return@runCatching
                val sw = StringWriter().also { ex.printStackTrace(PrintWriter(it)) }
                val name = "crash-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"
                File(d, name).writeText("App ${APP_VERSION}  執行緒：${thread.name}\n$sw")
                // 程式馬上就要結束：直接寫，不排隊
                write('E', "Crash", "閃退：${ex.javaClass.name}: ${ex.message.orEmpty().take(200)}（詳細見 $name）", null, sync = true)
                d.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(MAX_CRASHES)?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, ex)
        }
    }

    // ------------------------------------------------------------------ 匯出報告

    /** 整理成一份完整報告（appState：App 目前的資料摘要，由畫面提供） */
    fun buildReport(context: Context, appState: String): String {
        drain()
        val d = dir
        val report = buildString {
            appendLine("=== Reverie Alarm 診斷報告 ===")
            appendLine("匯出時間：${LocalDateTime.now().format(stamp)}")
            appendLine()
            appendLine("## 版本與裝置")
            appendLine("App：$APP_VERSION（建置 $APP_BUILD_DATE）")
            appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）")
            appendLine("系統：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），${Build.DISPLAY}")
            appendLine("語言／時區：${java.util.Locale.getDefault()}／${java.util.TimeZone.getDefault().id}")
            appendLine()
            appendLine("## 鬧鐘能不能準時響（權限與系統設定）")
            append(systemStatus(context))
            appendLine()
            appendLine("## App 資料摘要")
            appendLine(appState.trimEnd())
            appendLine()
            appendLine("## 系統排程中的鬧鐘（AlarmEngine）")
            append(scheduledAlarms(context))
            appendLine()
            val crashes = d?.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()
            appendLine("## 閃退紀錄（${crashes.size} 份）")
            if (crashes.isEmpty()) appendLine("（沒有閃退紀錄）")
            crashes.take(5).forEach { f ->
                appendLine("--- ${f.name} ---")
                appendLine(runCatching { f.readText().take(12_000) }.getOrDefault("（讀不到）"))
            }
            appendLine()
            appendLine("## App 事件紀錄（最近的在最下面）")
            val old = d?.let { File(it, "app.log.1") }?.takeIf { it.exists() }
            val cur = d?.let { File(it, "app.log") }?.takeIf { it.exists() }
            val lines = (listOfNotNull(old, cur).flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }).takeLast(3000)
            if (lines.isEmpty()) appendLine("（還沒有紀錄）") else lines.forEach { appendLine(it) }
            appendLine()
            appendLine("## 系統 logcat（這次開啟 App 之後，本 App 的警告與錯誤）")
            appendLine(logcat())
        }
        return maskPrivate(report)
    }

    private fun systemStatus(context: Context): String = buildString {
        fun line(name: String, ok: Boolean?, note: String = "") =
            appendLine("- $name：${when (ok) { true -> "正常"; false -> "⚠️ 有問題"; null -> "無法判斷" }}${if (note.isNotEmpty()) "（$note）" else ""}")
        line("通知權限", runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrNull())
        line("精準鬧鐘權限", runCatching { AlarmEngine.canScheduleExact(context) }.getOrNull(), "沒有的話鬧鐘可能延遲幾分鐘")
        line("全螢幕響鈴畫面權限", runCatching { AlarmEngine.canUseFullScreen(context) }.getOrNull(), "沒有的話鎖屏時只會跳通知")
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        line("略過電池最佳化", runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(), "被省電限制時鬧鐘可能不準")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            val v = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            line("鬧鐘音量", v > 0, "$v / $max")
        }
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val f = nm.currentInterruptionFilter
            val name = when (f) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "關閉"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "僅限優先"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "僅限鬧鐘"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "完全靜音"
                else -> "未知"
            }
            line("勿擾模式", f != NotificationManager.INTERRUPTION_FILTER_NONE, name)
        }
        runCatching {
            val next = AlarmEngine.alarmManager(context).nextAlarmClock
            appendLine("- 系統的下一個鬧鐘：${next?.let { java.time.Instant.ofEpochMilli(it.triggerTime).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() } ?: "（沒有）"}" +
                (next?.showIntent?.creatorPackage?.let { "（$it）" } ?: ""))
        }
        val miui = context.getSharedPreferences("reverie_alarm_engine", Context.MODE_PRIVATE)
        if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) || Build.MANUFACTURER.equals("POCO", true)) {
            appendLine("- 小米自啟動：${if (miui.getBoolean("xiaomi_autostart_acked", false)) "使用者已確認開啟" else "⚠️ 使用者還沒確認"}")
        }
    }

    private fun scheduledAlarms(context: Context): String = buildString {
        val specs = runCatching { AlarmEngine.allSpecs(context) }.getOrDefault(emptyList())
            .sortedBy { runCatching { it.triggerDateTime() }.getOrNull() }
        appendLine("共 ${specs.size} 個")
        specs.take(30).forEach { s ->
            appendLine("- ${s.triggerLocal}  ${s.stage}  ${s.label.take(30)}  貪睡 ${s.snoozeUsed}/${s.maxSnooze}  淡入=${s.fadeIn}  鈴聲=${s.ringtoneUri?.substringAfterLast('/')?.take(40) ?: "預設"}")
        }
        if (specs.size > 30) appendLine("…（其餘 ${specs.size - 30} 個省略）")
    }

    /** 本 App 這次執行期間的系統紀錄（App 只讀得到自己的） */
    private fun logcat(): String = runCatching {
        // 只留警告、錯誤，以及本 App 自己的紀錄（系統的一般訊息太多，沒有參考價值）
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "2000", "--pid", android.os.Process.myPid().toString(), "*:W", "Reverie/*:V"))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.ifBlank { "（沒有內容）" }
    }.getOrElse { "（讀不到 logcat：${it.message}）" }

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private fun maskPrivate(s: String) = EMAIL.replace(s) { m ->
        val (user, domain) = m.value.split("@", limit = 2)
        user.take(1) + "***@" + domain.substringAfterLast('.').let { "***.$it" }
    }
}

/** 整個 App（包含鬧鐘服務、開機接收器）都會先經過這裡 */
class ReverieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 開發版（debug）：主執行緒讀寫硬碟、網路時在 logcat 記一筆（標籤 StrictMode），方便找出卡頓來源
        if (BuildConfig.STRICT_MODE) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
        }
        AppLog.init(this)
        // 先在背景把畫面一開始就會用到的小設定檔讀進記憶體：第一次用到時不必在主執行緒等硬碟
        val app = this
        Thread({
            PREWARM_PREFS.forEach { runCatching { app.getSharedPreferences(it, MODE_PRIVATE).all } }
        }, "ReveriePrefsWarmup").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private companion object {
        val PREWARM_PREFS = listOf(
            "reverie_lang", "reverie_tools", "reverie_guard", "reverie_alarm_engine",
            "reverie_weather", "reverie_update", "reverie_stopwatch", "reverie_timer"
        )
    }
}
