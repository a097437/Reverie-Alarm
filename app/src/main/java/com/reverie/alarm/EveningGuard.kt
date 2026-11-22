package com.reverie.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ============================================================================
// 休假防呆：前一晚自動提醒
//   - 每天晚上固定時間（預設 21:00）檢查「明天」，規則和 App 裡的防呆視窗完全一樣（HolidayGuard）
//   - 只有要你決定的事才通知：忘了關的上班鬧鐘、見紅休的國定假日排了班、明天有班卻沒有鬧鐘
//   - 「暫停」只關通知上列出、而且按下去時仍然是衝突的鬧鐘，之後可以按「復原」
//   - 「保留／照常上班／知道了」記住這組問題，App 裡也不會再問
//   - 點通知打開 App 的防呆視窗
//   - App 開著時交給畫面改資料（GuardBridge），畫面上的舊資料才不會又把改動蓋回去
// ============================================================================

/** App 畫面開著時，由畫面提供資料、負責修改（只在主執行緒使用） */
object GuardBridge {
    interface Host {
        fun events(): List<ShiftEvent>
        fun alarms(): List<ShiftAlarm>
        fun prefs(): AppPreferences
        /** 開／關這些鬧鐘並存檔，回傳實際改了幾個 */
        fun setAlarmsEnabled(ids: Collection<String>, enabled: Boolean): Int
        /** 這組問題不再追問 */
        fun markDismissed(key: String)
        fun toast(text: String)
    }

    var host: Host? = null

    const val EXTRA_OPEN = "open_guard"

    /** 從通知點進來：打開防呆視窗 */
    var openRequested by mutableStateOf(false)

    fun fromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN, false) == true) openRequested = true
    }
}

object EveningGuard {
    private const val ACTION_CHECK = "com.reverie.alarm.guard.CHECK"
    private const val ACTION_PAUSE = "com.reverie.alarm.guard.PAUSE"
    private const val ACTION_KEEP = "com.reverie.alarm.guard.KEEP"
    private const val ACTION_UNDO = "com.reverie.alarm.guard.UNDO"
    private const val EXTRA_IDS = "ids"
    private const val EXTRA_DATE = "date"
    private const val EXTRA_KEY = "attention_key"
    private const val EXTRA_NO_ALARM_KEY = "no_alarm_key"
    private const val CHANNEL = "reverie_guard_v1"
    private const val NOTIFY_ID = 7501
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val gson = Gson()
    private val KEY_EVENTS = stringPreferencesKey("v2_events")
    private val KEY_ALARMS = stringPreferencesKey("v2_alarms")
    private val KEY_PREFS = stringPreferencesKey("saved_app_preferences")

    // ------------------------------------------------------------------ 排程

    private fun checkIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 7510, Intent(context, GuardReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 排好下一次檢查（關掉就取消）；App 每次存檔、開機、更新後都會呼叫 */
    fun schedule(context: Context, prefs: AppPreferences) {
        val am = AlarmEngine.alarmManager(context)
        val pi = checkIntent(context)
        if (!prefs.eveningGuard) {
            am.cancel(pi)
            return
        }
        val at = nextEveningCheck(LocalDateTime.now(), parseEveningTime(prefs.eveningGuardTime))
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (AlarmEngine.canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    /** 開機、更新後：讀存檔裡的設定再排 */
    suspend fun scheduleFromStore(context: Context) = schedule(context, readPrefs(context))

    // ------------------------------------------------------------------ 資料

    private class Snapshot(val events: List<ShiftEvent>, val alarms: List<ShiftAlarm>, val prefs: AppPreferences)

    private suspend fun readPrefs(context: Context): AppPreferences {
        val raw = context.dataStore.data.first()[KEY_PREFS] ?: return AppPreferences()
        return runCatching { gson.fromJson(raw, AppPreferences::class.java) }.getOrNull() ?: AppPreferences()
    }

    private fun parseAlarms(raw: String?): List<ShiftAlarm>? =
        raw?.let { runCatching { gson.fromJson(it, Array<AlarmDto>::class.java).mapNotNull { a -> a.toModel() } }.getOrNull() }

    /** 目前的資料：App 開著就拿畫面上的（最新），否則讀存檔 */
    private suspend fun load(context: Context): Snapshot? {
        withContext(Dispatchers.Main) {
            GuardBridge.host?.let { Snapshot(it.events(), it.alarms(), it.prefs()) }
        }?.let { return it }
        val p = context.dataStore.data.first()
        val events = p[KEY_EVENTS]?.let { raw ->
            runCatching { gson.fromJson(raw, Array<EventDto>::class.java).mapNotNull { it.toModel() } }.getOrNull()
        } ?: return null
        val alarms = parseAlarms(p[KEY_ALARMS]) ?: return null
        val prefs = p[KEY_PREFS]?.let { raw -> runCatching { gson.fromJson(raw, AppPreferences::class.java) }.getOrNull() } ?: AppPreferences()
        return Snapshot(events, alarms, prefs)
    }

    /** 開／關鬧鐘：App 開著交給畫面；沒開就改存檔並同步系統排程 */
    private suspend fun setAlarmsEnabled(context: Context, ids: Collection<String>, enabled: Boolean, prefs: AppPreferences): Int {
        if (ids.isEmpty()) return 0
        withContext(Dispatchers.Main) { GuardBridge.host?.setAlarmsEnabled(ids, enabled) }?.let { return it }
        val idSet = ids.toSet()
        val changed = mutableListOf<ShiftAlarm>()
        context.dataStore.edit { p ->
            val list = parseAlarms(p[KEY_ALARMS]) ?: return@edit
            val updated = list.map { a ->
                if (a.id in idSet && a.enabled != enabled) a.copy(enabled = enabled).also { changed += it } else a
            }
            if (changed.isNotEmpty()) p[KEY_ALARMS] = gson.toJson(updated.map { it.toDto() })
        }
        NativeAlarmScheduler.rules = prefs
        changed.forEach { NativeAlarmScheduler.scheduleAlarm(context, it) } // 關掉＝取消排程，打開＝重新排入
        runCatching { ShiftWidgetProvider.refresh(context) }
        return changed.size
    }

    private suspend fun markDismissed(context: Context, key: String) {
        if (key.isEmpty()) return
        val handled = withContext(Dispatchers.Main) { GuardBridge.host?.let { it.markDismissed(key); true } } ?: false
        if (!handled) GuardPrefs.setDismissed(context, key)
    }

    private suspend fun toast(text: String) {
        withContext(Dispatchers.Main) { GuardBridge.host?.toast(text) }
    }

    // ------------------------------------------------------------------ 動作

    /** 通知與排程的所有動作（背景執行緒） */
    suspend fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK -> runCheck(context)
            ACTION_KEEP -> {
                markDismissed(context, intent.getStringExtra(EXTRA_KEY).orEmpty())
                intent.getStringExtra(EXTRA_NO_ALARM_KEY)?.takeIf { it.isNotEmpty() }?.let { GuardPrefs.setNoAlarmDismissed(context, it) }
                cancelNotice(context)
                AppLog.i("Guard", "前一晚提醒：保留")
            }
            ACTION_PAUSE -> pause(
                context,
                intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return,
                intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
            )
            ACTION_UNDO -> undo(context, intent.getStringArrayListExtra(EXTRA_IDS).orEmpty(), intent.getStringExtra(EXTRA_KEY).orEmpty())
        }
    }

    /** 檢查明天，需要就發通知；回傳有沒有發 */
    suspend fun runCheck(context: Context): Boolean {
        val s = load(context)
        val prefs = s?.prefs ?: readPrefs(context)
        schedule(context, prefs) // 先排好下一次
        if (s == null || !prefs.eveningGuard) return false
        val tomorrow = LocalDate.now().plusDays(1)
        val r = HolidayGuard.check(tomorrow, LocalDateTime.now(), s.events, s.alarms, prefs.holidayPolicy())
        val notify = r.needsEveningNotice(GuardPrefs.dismissed(context), GuardPrefs.noAlarmDismissed(context))
        AppLog.i(
            "Guard",
            "前一晚檢查 $tomorrow：要確認 ${r.attention.size} 項、有班沒鬧鐘=${r.shiftWithoutAlarm != null} → ${if (notify) "發通知" else "不用提醒"}"
        )
        if (notify) postNotice(context, r) else cancelNotice(context)
        return notify && canPost(context)
    }

    /** 「暫停」：只關通知上列出、而且現在仍然是衝突的鬧鐘 */
    private suspend fun pause(context: Context, date: LocalDate, listed: List<String>) {
        val s = load(context) ?: return
        val r = HolidayGuard.check(date, LocalDateTime.now(), s.events, s.alarms, s.prefs.holidayPolicy())
        val ids = r.preCheckedAlarmIds.filter { it in listed }
        val n = setAlarmsEnabled(context, ids, false, s.prefs)
        // 剩下的（例如見紅休的假日排班）＝不在通知上處理，不再追問
        val handled = r.items.filter { it.defaultChecked && it.alarms.any { a -> a.id in ids } }
        markDismissed(context, r.attentionKeyWithout(handled))
        AppLog.i("Guard", "前一晚提醒：暫停 $n 個鬧鐘")
        if (n == 0) {
            cancelNotice(context)
            return
        }
        ensureChannel(context)
        val undo = action(context, ACTION_UNDO, 7513) {
            putStringArrayListExtra(EXTRA_IDS, ArrayList(ids))
            putExtra(EXTRA_KEY, r.attentionKey)
        }
        post(
            context,
            base(context)
                .setContentTitle(tr("已暫停 {0} 個鬧鐘", n))
                .setContentText(tr("明天好好休息；按錯了可以復原"))
                .setSilent(true)
                .setTimeoutAfter(6 * 3_600_000L)
                .addAction(0, tr("復原"), undo)
        )
        toast(tr("🛡️ 已暫停 {0} 個鬧鐘", n))
    }

    private suspend fun undo(context: Context, ids: List<String>, fullKey: String) {
        val prefs = load(context)?.prefs ?: readPrefs(context)
        val n = setAlarmsEnabled(context, ids, true, prefs)
        markDismissed(context, fullKey) // 復原＝決定保留，不要再問
        cancelNotice(context)
        AppLog.i("Guard", "前一晚提醒：復原 $n 個鬧鐘")
        toast(tr("✅ 已復原"))
    }

    // ------------------------------------------------------------------ 通知

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("休假防呆提醒"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr("前一晚檢查明天的鬧鐘，有衝突才通知")
            }
        )
    }

    private fun canPost(context: Context) =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    private fun action(context: Context, action: String, code: Int, fill: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context, code, Intent(context, GuardReceiver::class.java).setAction(action).apply(fill),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7520,
            Intent(context, MainActivity::class.java)
                .putExtra(GuardBridge.EXTRA_OPEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun base(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentIntent(openApp(context))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    @SuppressLint("MissingPermission") // canPost 已經檢查過
    private fun post(context: Context, b: NotificationCompat.Builder) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, b.build()) }
            .onFailure { AppLog.w("Guard", "前一晚提醒通知顯示失敗", it) }
    }

    fun cancelNotice(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFY_ID) }
    }

    /** 鬧鐘什麼時候響：明天的只寫時間，今晚響的（大夜班）特別標出來 */
    private fun whenLabel(a: ShiftAlarm, day: LocalDate): String = when (a.date) {
        day -> a.triggerTime.format(HM)
        day.minusDays(1) -> tr("今晚 {0}", a.triggerTime.format(HM))
        else -> "${a.date.monthValue}/${a.date.dayOfMonth} ${a.triggerTime.format(HM)}"
    }

    /** 明天要確認的通知 */
    private fun postNotice(context: Context, r: GuardReport) {
        ensureChannel(context)
        val conflicts = r.items.filter { it.defaultChecked }
        val holidayShift = r.items.firstOrNull { it.kind == GuardKind.HOLIDAY_SHIFT }
        val noAlarm = r.shiftWithoutAlarm
        val pauseIds = r.preCheckedAlarmIds
        val noAlarmLine = noAlarm?.let { tr("明天{0} {1} 上班，但沒有開著的鬧鐘", tr(it.title), it.startTime.format(HM)) }
        val title = when {
            conflicts.isNotEmpty() -> tr("明天休假，還有 {0} 個上班鬧鐘開著", pauseIds.size)
            holidayShift != null -> tr("明天是{0}，你排了{1}", tr(r.day.holiday?.name ?: ""), tr(holidayShift.event?.title ?: ""))
            noAlarmLine != null -> noAlarmLine
            else -> return
        }
        val lines = buildList {
            conflicts.flatMap { it.alarms }.forEach { add("${whenLabel(it, r.date)}  ${trLabel(it.label)}") }
            if (holidayShift != null) add(tr("照常上班不用做任何事；要放假的話，點這裡改成休假"))
            if (noAlarmLine != null) {
                if (conflicts.isNotEmpty() || holidayShift != null) add(tr("另外：{0}", noAlarmLine))
                else add(tr("點這裡打開 App 設定鬧鐘"))
            }
        }
        val keep = action(context, ACTION_KEEP, 7511) {
            putExtra(EXTRA_KEY, r.attentionKey)
            putExtra(EXTRA_NO_ALARM_KEY, r.noAlarmKey)
        }
        val b = base(context)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        if (pauseIds.isNotEmpty()) {
            b.addAction(0, tr("暫停 {0} 個鬧鐘", pauseIds.size), action(context, ACTION_PAUSE, 7512) {
                putExtra(EXTRA_DATE, r.date.toString())
                putStringArrayListExtra(EXTRA_IDS, ArrayList(pauseIds))
            })
            b.addAction(0, tr("保留"), keep)
        } else {
            b.addAction(0, if (holidayShift != null) tr("照常上班") else tr("知道了"), keep)
        }
        post(context, b)
        AppLog.i("Guard", "前一晚提醒通知：$title（可暫停 ${pauseIds.size} 個）")
    }
}

/** 前一晚提醒：排程時間到、通知上的按鈕 */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        I18n.ensure(app)
        val pending = goAsync()
        scope.launch {
            try {
                EveningGuard.handle(app, intent)
            } catch (e: Exception) {
                AppLog.w("Guard", "前一晚提醒處理失敗：${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
