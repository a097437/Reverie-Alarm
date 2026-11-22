package com.reverie.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import java.time.LocalDateTime
import java.time.ZoneId

// ============================================================================
// 原生鬧鐘引擎：負責「時間到一定要響」
//   - 用 setAlarmClock 精準排程（不受省電模式延遲）
//   - 自己保存一份排程清單，手機重開機後自動重新排程
//   - 每個鬧鐘用獨立的 key，不會互相覆蓋
// ============================================================================

/** 一個要排程的鬧鐘（引擎自己保存的格式，和畫面資料分開） */
data class AlarmSpec(
    val key: String,
    val triggerLocal: String,          // LocalDateTime ISO 字串，例如 2026-11-18T06:30
    val label: String,
    val stage: String,                 // WAKE / LEAVE / CUSTOM
    val stageLabel: String,
    val ringtoneUri: String? = null,
    val snoozeMinutes: Int = 5,
    val maxSnooze: Int = 2,
    val snoozeUsed: Int = 0,
    val shiftStartLocal: String? = null,
    val fadeIn: Boolean = true
) {
    fun triggerDateTime(): LocalDateTime = LocalDateTime.parse(triggerLocal)
    fun triggerMillis(): Long = triggerDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val remainingSnooze: Int get() = (maxSnooze - snoozeUsed).coerceAtLeast(0)
    val isSnoozeEntry: Boolean get() = key.startsWith(AlarmEngine.SNOOZE_PREFIX)
    val baseKey: String get() = key.removePrefix(AlarmEngine.SNOOZE_PREFIX)
}

object AlarmEngine {
    const val ACTION_FIRE = "com.reverie.alarm.ACTION_FIRE"
    const val EXTRA_KEY = "alarm_key"
    const val EXTRA_SPEC_JSON = "alarm_spec_json"
    const val SNOOZE_PREFIX = "snooze:"
    /** 計時器的響鈴（不屬於鬧鐘清單，同步鬧鐘時不能被取消） */
    const val TIMER_PREFIX = "timer:"
    const val CHANNEL_RINGING = "reverie_alarm_ringing_v2"

    private const val PREFS = "reverie_alarm_engine"
    private const val PREF_SPECS = "specs"
    private val gson = Gson()
    private val lock = Any()

    // ---------------- 排程清單（存在手機裡，重開機也找得回來） ----------------

    private fun readRegistry(context: Context): MutableMap<String, AlarmSpec> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_SPECS, null)
            ?: return mutableMapOf()
        return runCatching {
            gson.fromJson(raw, Array<AlarmSpec>::class.java).associateBy { it.key }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun writeRegistry(context: Context, map: Map<String, AlarmSpec>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SPECS, gson.toJson(map.values.toTypedArray()))
            .apply()
    }

    fun getSpec(context: Context, key: String): AlarmSpec? = synchronized(lock) { readRegistry(context)[key] }

    /** 目前所有排定中的鬧鐘（桌面小工具用來找「下一個鬧鐘」） */
    fun allSpecs(context: Context): List<AlarmSpec> = synchronized(lock) { readRegistry(context).values.toList() }

    fun removeSpec(context: Context, key: String) = synchronized(lock) {
        val map = readRegistry(context)
        if (map.remove(key) != null) writeRegistry(context, map)
    }

    fun specToJson(spec: AlarmSpec): String = gson.toJson(spec)
    fun specFromJson(json: String?): AlarmSpec? =
        if (json.isNullOrBlank()) null else runCatching { gson.fromJson(json, AlarmSpec::class.java) }.getOrNull()

    // ---------------- 排程 / 取消 ----------------

    private fun firePendingIntent(context: Context, key: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            // 用 data 讓每個鬧鐘的 PendingIntent 都獨一無二，不會互相覆蓋
            data = Uri.parse("reverie-alarm://fire/" + Uri.encode(key))
            putExtra(EXTRA_KEY, key)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, key.hashCode(), intent, flags)
    }

    private fun showAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** 排程一個鬧鐘；時間已過的鬧鐘會直接從清單移除。回傳是否成功排入。 */
    fun schedule(context: Context, spec: AlarmSpec): Boolean {
        synchronized(lock) {
            val map = readRegistry(context)
            val millis = runCatching { spec.triggerMillis() }.getOrNull()
            if (millis == null || millis <= System.currentTimeMillis()) {
                if (map.remove(spec.key) != null) {
                    writeRegistry(context, map)
                    AppLog.i("Alarm", "時間已過、從排程移除：${spec.triggerLocal} ${spec.label}")
                }
                firePendingIntent(context, spec.key, create = false)?.let { pi ->
                    alarmManager(context).cancel(pi)
                    pi.cancel()
                }
                return false
            }
            map[spec.key] = spec
            writeRegistry(context, map)
            setSystemAlarm(context, spec.key, millis)
            return true
        }
    }

    private fun setSystemAlarm(context: Context, key: String, millis: Long) {
        val am = alarmManager(context)
        val pi = firePendingIntent(context, key, create = true) ?: return
        try {
            if (canScheduleExact(context)) {
                // setAlarmClock：系統當成真正的鬧鐘，省電模式也會準時，狀態列會出現鬧鐘圖示
                am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, showAppPendingIntent(context)), pi)
            } else {
                // 沒有精準鬧鐘權限時的備援（可能延遲幾分鐘）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                AppLog.w("Alarm", "沒有精準鬧鐘權限，改用一般排程（可能延遲）：$key")
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            AppLog.w("Alarm", "排程被系統拒絕，改用一般排程：$key", e)
        } catch (e: Exception) {
            AppLog.e("Alarm", "排程失敗：$key", e)
        }
    }

    fun cancel(context: Context, key: String) = synchronized(lock) {
        firePendingIntent(context, key, create = false)?.let { pi ->
            alarmManager(context).cancel(pi)
            pi.cancel()
        }
        val map = readRegistry(context)
        if (map.remove(key) != null) writeRegistry(context, map)
    }

    /**
     * 讓系統排程和 App 裡的鬧鐘清單完全一致：
     * 清單裡沒有的（被刪掉、被關閉）就取消，有的就（重新）排程。
     * 貪睡中的鬧鐘不受影響，除非它的原鬧鐘已被刪除或關閉。
     */
    fun syncAll(context: Context, enabledSpecs: List<AlarmSpec>) {
        val wantedKeys = enabledSpecs.map { it.key }.toSet()
        val existing = synchronized(lock) { readRegistry(context).values.toList() }
        existing.forEach { old ->
            if (old.key.startsWith(TIMER_PREFIX)) return@forEach
            val stillWanted = if (old.isSnoozeEntry) old.baseKey in wantedKeys else old.key in wantedKeys
            if (!stillWanted) cancel(context, old.key)
        }
        var ok = 0
        enabledSpecs.forEach { spec ->
            // 已在清單裡的鬧鐘保留它的貪睡次數紀錄
            if (schedule(context, spec)) ok++
        }
        val next = enabledSpecs.mapNotNull { runCatching { it.triggerDateTime() }.getOrNull() }.filter { it.isAfter(LocalDateTime.now()) }.minOrNull()
        AppLog.i("Alarm", "同步排程：開啟中 ${enabledSpecs.size} 個，排入 $ok 個（其餘時間已過），下一個 ${next ?: "無"}，精準鬧鐘權限=${canScheduleExact(context)}")
    }

    /** 開機、更新 App、改時間或時區之後，把清單裡的鬧鐘全部重新排程 */
    fun rescheduleAll(context: Context) {
        val all = synchronized(lock) { readRegistry(context).values.toList() }
        val ok = all.count { schedule(context, it) }
        AppLog.i("Alarm", "重新排程全部鬧鐘：$ok / ${all.size} 個")
    }

    /** 貪睡：N 分鐘後用同一個鬧鐘再響一次 */
    fun scheduleSnooze(context: Context, ringing: AlarmSpec): AlarmSpec {
        val next = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(ringing.snoozeMinutes.toLong().coerceAtLeast(1))
        val snoozeSpec = ringing.copy(
            key = SNOOZE_PREFIX + ringing.baseKey,
            triggerLocal = next.toString(),
            snoozeUsed = ringing.snoozeUsed + 1
        )
        schedule(context, snoozeSpec)
        AppLog.i("Alarm", "貪睡：${snoozeSpec.triggerLocal} 再響（第 ${snoozeSpec.snoozeUsed} 次，共 ${snoozeSpec.maxSnooze} 次）")
        return snoozeSpec
    }

    /** 「試響」：馬上用這個鬧鐘的鈴聲與規則響一次（網頁版 triggerAlarmSimulation） */
    fun ringNow(context: Context, spec: AlarmSpec) {
        val preview = spec.copy(
            key = "preview-" + System.currentTimeMillis(),
            triggerLocal = LocalDateTime.now().withNano(0).toString()
        )
        val serviceIntent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_START
            putExtra(EXTRA_SPEC_JSON, specToJson(preview))
        }
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        // App 在前景時全螢幕通知只會變成橫幅，直接打開響鈴畫面
        context.startActivity(
            Intent(context, AlarmRingingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_SPEC_JSON, specToJson(preview))
            }
        )
    }

    /** 測試用：1 分鐘後響一次 */
    fun scheduleTestAlarm(context: Context, minutesLater: Long = 1): AlarmSpec {
        val t = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(minutesLater)
        val spec = AlarmSpec(
            key = "test-" + System.currentTimeMillis(),
            triggerLocal = t.toString(),
            label = tr("測試鬧鐘（確認鎖屏也會響）"),
            stage = "CUSTOM",
            stageLabel = tr("測試"),
            maxSnooze = 1
        )
        schedule(context, spec)
        return spec
    }

    // ---------------- 權限與通知頻道 ----------------

    fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_RINGING) != null) return
        val channel = NotificationChannel(CHANNEL_RINGING, tr("鬧鐘響鈴"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = tr("時間到時跳出全螢幕響鈴畫面")
            // 聲音和震動由響鈴服務自己控制（才能做漸進音量）
            setSound(null, null as AudioAttributes?)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)
    }
}
