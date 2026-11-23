package com.reverie.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.google.gson.Gson
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ============================================================================
// Android 桌面小工具（四種大小，共用同一份資料）
//   - 4×2（ShiftWidgetProvider）：今日班表、明日班表、下一個鬧鐘
//   - 2×2（ShiftWidgetSmallProvider）：今天的班（大字、班別色）＋下一個鬧鐘
//   - 5×2（ShiftWidgetWeeksProvider）：兩週班表，每天標班別色與國定假日，
//          右上角可以往前／往後翻兩週，點月份回到本週，標題旁是這兩週上班／休假天數
//   - 5×5（ShiftWidgetMonthProvider）：整月班表，可以翻月，底下是今天／明天與這個月每種班的天數
//   - 日期格的版面由 tools/widgets/gen_widget_layouts.py 產生（id 在 WidgetIds.kt）
//   - App 每次儲存資料時把近期班表寫進一份小快照（saveSnapshot），再通知所有小工具更新
//   - 過了午夜自動更新一次（scheduleMidnight），「今天」才不會停在昨天
//   - 下一個鬧鐘直接讀鬧鐘引擎的排程清單，和實際會響的鬧鐘一致（計時器不算）
// ============================================================================

private data class WidgetShift(
    val date: String = "",
    val title: String = "",
    val type: String = "CUSTOM",
    val start: String = "",
    val end: String = ""
)

/** 小工具用到的外觀設定 */
private data class WidgetStyle(val accentHex: String = "#5B7C99", val weekStartsOnMonday: Boolean = false)

/**
 * 小工具的畫面在這條背景執行緒組（讀快照、組 RemoteViews、通知系統）：
 * 系統定時更新、午夜更新、翻頁、鬧鐘響時的更新都不佔用 App 的主執行緒（你正在滑 App 時不會頓一下）。
 * 只有一條執行緒，更新依序進行。
 */
object WidgetWork {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ReverieWidget").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** App 裡呼叫：丟到背景做 */
    fun post(block: () -> Unit) {
        executor.execute { runCatching(block).onFailure { AppLog.w("Widget", "更新小工具失敗", it) } }
    }

    /** 廣播裡呼叫：goAsync 撐住這次廣播，背景做完才結束 */
    fun inBroadcast(receiver: android.content.BroadcastReceiver, block: () -> Unit) {
        val pending = receiver.goAsync()
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                AppLog.w("Widget", "更新小工具失敗", e)
            } finally {
                pending?.finish()
            }
        }
    }
}

/** 所有小工具共用：讀資料、組畫面 */
private object WidgetData {
    const val PREFS = "reverie_widget"
    const val KEY_SHIFTS = "shifts"
    const val KEY_HOLIDAY = "holiday_policy"
    const val KEY_STYLE = "style"
    val gson = Gson()
    val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shifts(context: Context): Map<LocalDate, WidgetShift> {
        val raw = prefs(context).getString(KEY_SHIFTS, null) ?: return emptyMap()
        val list = runCatching { gson.fromJson(raw, Array<WidgetShift>::class.java).toList() }.getOrDefault(emptyList())
        return list.mapNotNull { s -> runCatching { LocalDate.parse(s.date) to s }.getOrNull() }.toMap()
    }

    fun policy(context: Context): HolidayPolicy {
        val raw = prefs(context).getString(KEY_HOLIDAY, null) ?: return HolidayPolicy()
        return runCatching { gson.fromJson(raw, HolidayPolicy::class.java) }.getOrNull() ?: HolidayPolicy()
    }

    fun style(context: Context): WidgetStyle {
        val raw = prefs(context).getString(KEY_STYLE, null) ?: return WidgetStyle()
        return runCatching { gson.fromJson(raw, WidgetStyle::class.java) }.getOrNull() ?: WidgetStyle()
    }

    fun type(s: WidgetShift?): ShiftType? = s?.let { runCatching { ShiftType.valueOf(it.type) }.getOrNull() }

    /** 班別的代表色（圓點、格子底色） */
    fun typeColor(s: WidgetShift?): Int = when (type(s)) {
        ShiftType.EARLY -> 0xFFF59E0B
        ShiftType.EVENING -> 0xFF3B82F6
        ShiftType.NIGHT -> 0xFFA855F7
        ShiftType.OFF -> 0xFF10B981
        ShiftType.CUSTOM -> 0xFF94A3B8
        null -> 0xFF94A3B8
    }.toInt()

    /** 深色底上的班別文字色（和 App 日曆一樣的淺色版） */
    fun typeTextColor(s: WidgetShift?): Int = when (type(s)) {
        ShiftType.EARLY -> 0xFFFCD34D
        ShiftType.EVENING -> 0xFF93C5FD
        ShiftType.NIGHT -> 0xFFD8B4FE
        ShiftType.OFF -> 0xFF6EE7B7
        else -> 0xFFCBD5E1
    }.toInt()

    /** 一天的描述；國定假日加上假日名稱（有排班以排班為準，見紅休的空白假日寫「放假」） */
    fun describe(shift: WidgetShift?, date: LocalDate, policy: HolidayPolicy): String {
        val holiday = policy.holidayOn(date)
        val base = when {
            shift == null && policy.isDayOff(date) -> tr("國定假日放假")
            shift == null -> tr("尚未排班")
            shift.type == ShiftType.OFF.name -> tr("{0} · 全天休假", tr(shift.title))
            else -> "${tr(shift.title)} · ${shift.start.take(5)} - ${shift.end.take(5)}"
        }
        return if (holiday != null) "$base · ${tr(holiday.short)}" else base
    }

    /** 下一個會響的鬧鐘（計時器不算） */
    fun nextAlarm(context: Context): Pair<AlarmSpec, LocalDateTime>? {
        val now = LocalDateTime.now()
        return AlarmEngine.allSpecs(context)
            .filter { !it.key.startsWith(AlarmEngine.TIMER_PREFIX) }
            .mapNotNull { spec -> runCatching { spec to spec.triggerDateTime() }.getOrNull() }
            .filter { it.second.isAfter(now) }
            .minByOrNull { it.second }
    }

    fun dayWord(d: LocalDate, today: LocalDate): String = when (d) {
        today -> tr("今天")
        today.plusDays(1) -> tr("明天")
        else -> d.format(DateTimeFormatter.ofPattern("M/d"))
    }

    /** 鬧鐘的簡短名稱：起床／出門，自己設的用原本的名稱 */
    fun alarmShort(spec: AlarmSpec): String = when (spec.stage) {
        AlarmStage.WAKE.name -> tr("起床")
        AlarmStage.LEAVE.name -> tr("出門")
        else -> trLabel(spec.label)
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

// ----------------------------------------------------------------------------
// 4×2：今日班表、明日班表、下一個鬧鐘
// ----------------------------------------------------------------------------

class ShiftWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        WidgetWork.inBroadcast(this) {
            appWidgetIds.forEach { id -> manager.updateAppWidget(id, buildViews(app)) }
            scheduleMidnight(app, true)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 過了午夜：所有小工具換到新的一天
        if (intent.action == ACTION_MIDNIGHT) {
            val app = context.applicationContext
            WidgetWork.inBroadcast(this) { refresh(app) }
        }
    }

    companion object {
        /** App 儲存資料時呼叫：保存近期班表、國定假日與外觀設定，並刷新所有小工具 */
        fun saveSnapshot(
            context: Context,
            events: List<ShiftEvent>,
            holidayPolicy: HolidayPolicy,
            accentHex: String = "",
            weekStartsOnMonday: Boolean = false
        ) {
            val today = LocalDate.now()
            // 兩週／整月班表可以往前、往後翻，快照要涵蓋翻得到的日子（整月：前 3 個月到後 6 個月）
            val from = YearMonth.from(today).minusMonths(3).atDay(1).minusDays(7)
            val to = YearMonth.from(today).plusMonths(6).atEndOfMonth().plusDays(14)
            val shifts = events
                .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
                .map { WidgetShift(it.date.toString(), it.title, it.type.name, it.startTime.toString(), it.endTime.toString()) }
            WidgetData.prefs(context).edit()
                .putString(WidgetData.KEY_SHIFTS, WidgetData.gson.toJson(shifts.toTypedArray()))
                .putString(WidgetData.KEY_HOLIDAY, WidgetData.gson.toJson(holidayPolicy))
                .putString(WidgetData.KEY_STYLE, WidgetData.gson.toJson(WidgetStyle(accentHex.ifBlank { "#5B7C99" }, weekStartsOnMonday)))
                .apply()
            refresh(context)
        }

        /** 更新桌面上所有 Reverie Alarm 小工具（四種大小），並排好午夜的自動更新 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            fun ids(cls: Class<*>) = runCatching { manager.getAppWidgetIds(ComponentName(context, cls)) }.getOrDefault(IntArray(0))
            var any = false
            ids(ShiftWidgetProvider::class.java).takeIf { it.isNotEmpty() }?.let { list ->
                any = true
                val v = buildViews(context)
                list.forEach { manager.updateAppWidget(it, v) }
            }
            ids(ShiftWidgetSmallProvider::class.java).takeIf { it.isNotEmpty() }?.let { list ->
                any = true
                val v = ShiftWidgetSmallProvider.buildViews(context)
                list.forEach { manager.updateAppWidget(it, v) }
            }
            ids(ShiftWidgetWeeksProvider::class.java).forEach { id ->
                any = true
                manager.updateAppWidget(id, ShiftWidgetWeeksProvider.buildViews(context, id))
            }
            ids(ShiftWidgetMonthProvider::class.java).forEach { id ->
                any = true
                manager.updateAppWidget(id, ShiftWidgetMonthProvider.buildViews(context, id))
            }
            scheduleMidnight(context, any)
        }

        private const val ACTION_MIDNIGHT = "com.reverie.alarm.widget.MIDNIGHT"

        /**
         * 過了午夜（00:00:30）自動更新一次，「今天」的格子與今天的班才會換到新的一天。
         * 系統的定時更新最快 30 分鐘一次，跨日時可能停在昨天好一陣子。
         * 不叫醒手機（RTC）：手機睡著時不用更新，醒來時系統會補送。桌面上沒有小工具就取消。
         */
        fun scheduleMidnight(context: Context, enabled: Boolean) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 7600,
                Intent(context, ShiftWidgetProvider::class.java).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!enabled) {
                am.cancel(pi)
                return
            }
            val at = LocalDate.now().plusDays(1).atTime(0, 0, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            runCatching { am.setWindow(AlarmManager.RTC, at, 60_000L, pi) }
        }

        private fun buildViews(context: Context): RemoteViews {
            I18n.ensure(context)
            val today = LocalDate.now()
            val shifts = WidgetData.shifts(context)
            val policy = WidgetData.policy(context)
            val todayShift = shifts[today]
            val tomorrowShift = shifts[today.plusDays(1)]

            val views = RemoteViews(context.packageName, R.layout.widget_shift)
            views.setTextViewText(R.id.widget_date, "${today.format(DateTimeFormatter.ofPattern("M/d"))} ${I18n.weekday(today.dayOfWeek)}")
            views.setTextViewText(R.id.widget_today, WidgetData.describe(todayShift, today, policy))
            views.setTextColor(R.id.widget_today_dot, WidgetData.typeColor(todayShift))
            views.setTextViewText(R.id.widget_tomorrow, tr("明日：") + WidgetData.describe(tomorrowShift, today.plusDays(1), policy))

            val next = WidgetData.nextAlarm(context)
            if (next == null) {
                views.setTextViewText(R.id.widget_alarm_time, "--:--")
                views.setTextViewText(R.id.widget_alarm_label, tr("目前沒有排定的鬧鐘"))
            } else {
                val (spec, at) = next
                views.setTextViewText(R.id.widget_alarm_time, at.format(WidgetData.HM))
                views.setTextViewText(R.id.widget_alarm_label, "${WidgetData.dayWord(at.toLocalDate(), today)} · ${trLabel(spec.label)}")
            }
            views.setOnClickPendingIntent(R.id.widget_root, WidgetData.openApp(context))
            return views
        }
    }
}

// ----------------------------------------------------------------------------
// 2×2：今天的班＋下一個鬧鐘
// ----------------------------------------------------------------------------

class ShiftWidgetSmallProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        WidgetWork.inBroadcast(this) {
            val v = buildViews(app)
            appWidgetIds.forEach { id -> manager.updateAppWidget(id, v) }
            ShiftWidgetProvider.scheduleMidnight(app, true)
        }
    }

    companion object {
        fun buildViews(context: Context): RemoteViews {
            I18n.ensure(context)
            val today = LocalDate.now()
            val shift = WidgetData.shifts(context)[today]
            val policy = WidgetData.policy(context)
            val holiday = policy.holidayOn(today)
            val v = RemoteViews(context.packageName, R.layout.widget_small)

            v.setTextViewText(R.id.sm_date, "${today.format(DateTimeFormatter.ofPattern("M/d"))} ${I18n.weekday(today.dayOfWeek)}")
            v.setTextColor(R.id.sm_dot, WidgetData.typeColor(shift))
            when {
                shift != null -> {
                    v.setTextViewText(R.id.sm_title, shortShiftName(shift.title))
                    v.setTextColor(R.id.sm_title, WidgetData.typeTextColor(shift))
                    v.setTextViewText(
                        R.id.sm_time,
                        if (shift.type == ShiftType.OFF.name) (holiday?.let { tr(it.name) } ?: tr("全天休假"))
                        else "${shift.start.take(5)} – ${shift.end.take(5)}"
                    )
                }
                holiday != null -> {
                    v.setTextViewText(R.id.sm_title, tr(holiday.short))
                    v.setTextColor(R.id.sm_title, 0xFFFDA4AF.toInt())
                    v.setTextViewText(R.id.sm_time, if (policy.isDayOff(today)) tr("國定假日放假") else tr("國定假日"))
                }
                else -> {
                    v.setTextViewText(R.id.sm_title, tr("未排班"))
                    v.setTextColor(R.id.sm_title, 0xFFCBD5E1.toInt())
                    v.setTextViewText(R.id.sm_time, tr("點一下打開 App 排班"))
                }
            }

            val next = WidgetData.nextAlarm(context)
            if (next == null) {
                v.setTextViewText(R.id.sm_alarm_day, tr("沒有排定的鬧鐘"))
                v.setTextViewText(R.id.sm_alarm_time, "--:--")
            } else {
                val (spec, at) = next
                v.setTextViewText(R.id.sm_alarm_day, "${WidgetData.dayWord(at.toLocalDate(), today)} · ${WidgetData.alarmShort(spec)}")
                v.setTextViewText(R.id.sm_alarm_time, at.format(WidgetData.HM))
            }
            v.setOnClickPendingIntent(R.id.sm_root, WidgetData.openApp(context))
            return v
        }
    }
}

// ----------------------------------------------------------------------------
// 有日期格的小工具（5×2 兩週、5×5 整月）共用：翻頁、日期格上色
// ----------------------------------------------------------------------------

/**
 * 翻頁：每個小工具各自記住翻到第幾頁，翻頁後 30 分鐘沒再動就回到第 0 頁（本週／本月），
 * 免得一直停在別的日子。按鈕的 request code 依小工具 id 與種類分開，不會互相覆蓋。
 */
private class WidgetPager(
    private val kind: String,
    private val maxBack: Int,
    private val maxForward: Int,
    private val requestBase: Int,
    private val receiver: Class<*>
) {
    private val prefix = "com.reverie.alarm.widget.$kind."
    private fun keyPage(id: Int) = "${kind}_page_$id"
    private fun keyAt(id: Int) = "${kind}_page_at_$id"

    fun page(context: Context, id: Int): Int {
        val p = WidgetData.prefs(context)
        return if (System.currentTimeMillis() - p.getLong(keyAt(id), 0L) > RESET_MS) 0 else p.getInt(keyPage(id), 0)
    }

    /** 這個廣播是不是這種小工具的翻頁按鈕 */
    fun owns(intent: Intent): Boolean = intent.action?.startsWith(prefix) == true

    /** 上一頁／下一頁／回到本週（本月）：是這種小工具的按鈕就回傳（小工具 id, 新的頁數），否則 null */
    fun handle(context: Context, intent: Intent): Pair<Int, Int>? {
        val action = intent.action ?: return null
        if (!action.startsWith(prefix)) return null
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        val now = page(context, id)
        val target = when (action.removePrefix(prefix)) {
            "PREV" -> now - 1
            "NEXT" -> now + 1
            "HOME" -> 0
            else -> return null
        }.coerceIn(-maxBack, maxForward)
        WidgetData.prefs(context).edit().putInt(keyPage(id), target).putLong(keyAt(id), System.currentTimeMillis()).apply()
        return id to target
    }

    fun buttons(context: Context, v: RemoteViews, id: Int, prev: Int, next: Int, home: Int) {
        fun pi(what: String, code: Int) = PendingIntent.getBroadcast(
            context, id * 100 + requestBase + code,
            Intent(context, receiver).setAction(prefix + what).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(prev, pi("PREV", 1))
        v.setOnClickPendingIntent(next, pi("NEXT", 2))
        v.setOnClickPendingIntent(home, pi("HOME", 3))
        v.setContentDescription(prev, tr("上一頁"))
        v.setContentDescription(next, tr("下一頁"))
    }

    /** 小工具從桌面移除：清掉它的翻頁紀錄 */
    fun forget(context: Context, ids: IntArray) {
        val e = WidgetData.prefs(context).edit()
        ids.forEach { e.remove(keyPage(it)).remove(keyAt(it)) }
        e.apply()
    }

    companion object {
        const val RESET_MS = 30 * 60_000L
    }
}

/** 日期格的上色規則（5×2、5×5 一樣） */
private object DayCells {
    const val ROSE = 0xFFFB7185.toInt()
    const val BLUE = 0xFF60A5FA.toInt()
    const val TEXT = 0xFFF1F5F9.toInt()
    const val MUTED = 0xFF94A3B8.toInt()
    const val PAST = 0xFF64748B.toInt()
    const val OUTSIDE = 0xFF475569.toInt()
    const val HOLIDAY = 0xFFFDA4AF.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /** 今天的格子用 App 的主題色（壓暗到白字看得清楚） */
    fun accentOf(style: WidgetStyle): Int =
        (parseHexColorOrNull(style.accentHex) ?: androidx.compose.ui.graphics.Color(0xFF5B7C99)).darkenForWhiteText(3.0).toArgb()

    fun firstDow(style: WidgetStyle): DayOfWeek = if (style.weekStartsOnMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY

    /** 這一週的第一天 */
    fun weekStart(d: LocalDate, firstDow: DayOfWeek): LocalDate = d.minusDays(((d.dayOfWeek.value - firstDow.value + 7) % 7).toLong())

    fun heads(v: RemoteViews, grid: WidgetGrid, firstDow: DayOfWeek) {
        for (i in 0 until 7) {
            val dow = firstDow.plus(i.toLong())
            v.setTextViewText(grid.head[i], I18n.weekdayNarrow(dow))
            v.setTextColor(grid.head[i], when (dow) { DayOfWeek.SUNDAY -> ROSE; DayOfWeek.SATURDAY -> BLUE; else -> MUTED })
        }
    }

    /** 有顏色的格子底：Android 12 以上才能幫圓角底上色，舊版就不畫底（班別名稱照樣有顏色） */
    private fun tint(v: RemoteViews, id: Int, color: Int?) {
        if (color == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            v.setInt(id, "setBackgroundResource", 0)
            return
        }
        v.setInt(id, "setBackgroundResource", R.drawable.widget_cell)
        v.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
    }

    /**
     * 畫一個日期格
     * @param inScope 是不是這一頁「主要」的日子（整月班表裡，上／下個月的日子會變淡）
     * @param markNewMonth 1 號沒排班也不是假日時，班別位置寫「12月」提示換月了
     */
    fun draw(
        v: RemoteViews, grid: WidgetGrid, i: Int, d: LocalDate, s: WidgetShift?, holiday: Holiday?,
        today: LocalDate, accent: Int, inScope: Boolean, markNewMonth: Boolean
    ) {
        val isToday = d == today
        val newMonth = markNewMonth && d.dayOfMonth == 1 && s == null && holiday == null
        v.setTextViewText(grid.day[i], d.dayOfMonth.toString())
        v.setTextColor(
            grid.day[i],
            when {
                isToday -> WHITE
                !inScope -> OUTSIDE
                d.isBefore(today) -> PAST
                holiday != null || d.dayOfWeek == DayOfWeek.SUNDAY -> ROSE
                d.dayOfWeek == DayOfWeek.SATURDAY -> BLUE
                else -> TEXT
            }
        )
        v.setTextViewText(
            grid.label[i],
            when {
                s != null -> shortShiftName(s.title)
                holiday != null -> tr(holiday.short)
                newMonth -> tr("{0}月", d.monthValue)
                else -> ""
            }
        )
        v.setTextColor(
            grid.label[i],
            when {
                isToday -> WHITE
                !inScope -> withAlpha(if (s != null) WidgetData.typeTextColor(s) else HOLIDAY, 0x80)
                s != null -> WidgetData.typeTextColor(s)
                newMonth -> MUTED
                else -> HOLIDAY
            }
        )
        tint(
            v, grid.cell[i],
            when {
                isToday -> accent
                s == null || !inScope -> null
                else -> withAlpha(WidgetData.typeColor(s), if (d.isBefore(today)) 0x22 else 0x3D)
            }
        )
    }

    /** 這段日子裡上班、休假各幾天（見紅休的空白國定假日也算休假） */
    fun count(days: List<LocalDate>, shifts: Map<LocalDate, WidgetShift>, policy: HolidayPolicy): Pair<Int, Int> {
        var work = 0
        var off = 0
        for (d in days) {
            val s = shifts[d]
            when {
                s == null -> if (policy.isDayOff(d)) off++
                s.type == ShiftType.OFF.name -> off++
                WidgetData.type(s)?.isWorkShift == true -> work++
            }
        }
        return work to off
    }

    /** 下一個鬧鐘（兩種小工具的標題列一樣） */
    fun nextAlarm(context: Context, v: RemoteViews, timeId: Int, labelId: Int, today: LocalDate) {
        val next = WidgetData.nextAlarm(context)
        if (next == null) {
            v.setTextViewText(timeId, "--:--")
            v.setTextViewText(labelId, tr("沒有排定的鬧鐘"))
        } else {
            val (spec, at) = next
            v.setTextViewText(timeId, at.format(WidgetData.HM))
            v.setTextViewText(labelId, "${WidgetData.dayWord(at.toLocalDate(), today)} · ${WidgetData.alarmShort(spec)}")
        }
    }
}

// ----------------------------------------------------------------------------
// 5×2：兩週班表
// ----------------------------------------------------------------------------

class ShiftWidgetWeeksProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        WidgetWork.inBroadcast(this) {
            appWidgetIds.forEach { id -> manager.updateAppWidget(id, buildViews(app, id)) }
            ShiftWidgetProvider.scheduleMidnight(app, true)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (!PAGER.owns(intent)) return
        val app = context.applicationContext
        WidgetWork.inBroadcast(this) {
            PAGER.handle(app, intent)?.let { (id, page) ->
                AppWidgetManager.getInstance(app).updateAppWidget(id, render(app, id, page))
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = PAGER.forget(context, appWidgetIds)

    companion object {
        /** 一頁兩週：往前 8 週、往後 16 週 */
        private val PAGER = WidgetPager("weeks", 4, 8, 10, ShiftWidgetWeeksProvider::class.java)

        /** 目前那一頁的畫面（App 存檔、系統定時更新時用） */
        fun buildViews(context: Context, id: Int): RemoteViews = render(context, id, PAGER.page(context, id))

        private fun render(context: Context, id: Int, pageNo: Int): RemoteViews {
            I18n.ensure(context)
            val today = LocalDate.now()
            val shifts = WidgetData.shifts(context)
            val policy = WidgetData.policy(context)
            val style = WidgetData.style(context)
            val accent = DayCells.accentOf(style)
            val firstDow = DayCells.firstDow(style)
            val start = DayCells.weekStart(today, firstDow).plusWeeks(pageNo * 2L)
            val end = start.plusDays(13)
            val days = (0 until 14).map { start.plusDays(it.toLong()) }
            val grid = WidgetIds.WEEKS
            val v = RemoteViews(context.packageName, R.layout.widget_weeks)

            // 標題：月份（本週那頁是今天的月份，其他頁是中間那天的月份；跨月時新月份的 1 號格子會標「12月」）
            v.setTextViewText(R.id.wk_month, tr("{0}月", (if (pageNo == 0) today else start.plusDays(7)).monthValue))
            v.setTextViewText(
                R.id.wk_sub1,
                if (pageNo == 0) I18n.weekday(today.dayOfWeek) else "${start.monthValue}/${start.dayOfMonth} – ${end.monthValue}/${end.dayOfMonth}"
            )
            // 第二行：這兩週上班／休假天數（桌面只給 2 格高，日期格要留給班別名稱，所以不放底部資訊列）
            val (work, off) = DayCells.count(days, shifts, policy)
            v.setTextViewText(R.id.wk_sub2, tr("上班 {0}・休 {1}", work, off))
            DayCells.nextAlarm(context, v, R.id.wk_alarm_time, R.id.wk_alarm_label, today)

            DayCells.heads(v, grid, firstDow)
            days.forEachIndexed { i, d ->
                DayCells.draw(v, grid, i, d, shifts[d], policy.holidayOn(d), today, accent, inScope = true, markNewMonth = true)
            }

            v.setOnClickPendingIntent(R.id.wk_root, WidgetData.openApp(context))
            PAGER.buttons(context, v, id, R.id.wk_prev, R.id.wk_next, R.id.wk_title)
            return v
        }
    }
}

// ----------------------------------------------------------------------------
// 5×5：整月班表
// ----------------------------------------------------------------------------

class ShiftWidgetMonthProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        WidgetWork.inBroadcast(this) {
            appWidgetIds.forEach { id -> manager.updateAppWidget(id, buildViews(app, id)) }
            ShiftWidgetProvider.scheduleMidnight(app, true)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (!PAGER.owns(intent)) return
        val app = context.applicationContext
        WidgetWork.inBroadcast(this) {
            PAGER.handle(app, intent)?.let { (id, page) ->
                AppWidgetManager.getInstance(app).updateAppWidget(id, render(app, id, page))
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = PAGER.forget(context, appWidgetIds)

    companion object {
        /** 往前 3 個月、往後 6 個月 */
        private val PAGER = WidgetPager("month", 3, 6, 20, ShiftWidgetMonthProvider::class.java)

        /** 目前那一頁的畫面（App 存檔、系統定時更新時用） */
        fun buildViews(context: Context, id: Int): RemoteViews = render(context, id, PAGER.page(context, id))

        private fun render(context: Context, id: Int, pageNo: Int): RemoteViews {
            I18n.ensure(context)
            val today = LocalDate.now()
            val shifts = WidgetData.shifts(context)
            val policy = WidgetData.policy(context)
            val style = WidgetData.style(context)
            val accent = DayCells.accentOf(style)
            val firstDow = DayCells.firstDow(style)
            val ym = YearMonth.from(today).plusMonths(pageNo.toLong())
            val gridStart = DayCells.weekStart(ym.atDay(1), firstDow)
            val lead = ym.atDay(1).toEpochDay() - gridStart.toEpochDay()
            val rows = ((lead + ym.lengthOfMonth() + 6) / 7).toInt() // 4～6 列
            val grid = WidgetIds.MONTH
            val v = RemoteViews(context.packageName, R.layout.widget_month)

            // 標題：月份、星期（本月）或年份（其他月）、本月上班／休假天數
            val monthDays = (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            val (work, off) = DayCells.count(monthDays, shifts, policy)
            v.setTextViewText(R.id.mo_month, tr("{0}月", ym.monthValue))
            v.setTextViewText(R.id.mo_sub1, if (pageNo == 0) I18n.weekday(today.dayOfWeek) else tr("{0}年", ym.year))
            v.setTextViewText(R.id.mo_sub2, tr("上班 {0}・休 {1}", work, off))
            DayCells.nextAlarm(context, v, R.id.mo_alarm_time, R.id.mo_alarm_label, today)

            // 日期格：只需要 4、5 週的月份把多出來的列藏起來，其他列自動長高
            DayCells.heads(v, grid, firstDow)
            v.setViewVisibility(R.id.mo_row4, if (rows >= 5) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.mo_row5, if (rows >= 6) View.VISIBLE else View.GONE)
            for (i in 0 until rows * 7) {
                val d = gridStart.plusDays(i.toLong())
                DayCells.draw(
                    v, grid, i, d, shifts[d], policy.holidayOn(d), today, accent,
                    inScope = YearMonth.from(d) == ym, markNewMonth = false
                )
            }

            // 底部：今天、明天
            val todayShift = shifts[today]
            v.setTextColor(R.id.mo_today_dot, WidgetData.typeColor(todayShift))
            v.setTextViewText(R.id.mo_today, tr("今天 ") + WidgetData.describe(todayShift, today, policy))
            val tm = today.plusDays(1)
            val tmShift = shifts[tm]
            v.setTextViewText(
                R.id.mo_tomorrow,
                tr("明天 ") + when {
                    tmShift == null && policy.isDayOff(tm) -> tr("放假")
                    tmShift == null -> tr("未排班")
                    tmShift.type == ShiftType.OFF.name -> tr(tmShift.title)
                    else -> "${tr(tmShift.title)} ${tmShift.start.take(5)}"
                }
            )

            // 底部：這個月每種班幾天、國定假日上班幾天（核對加倍工資）
            val byType = monthDays.mapNotNull { WidgetData.type(shifts[it]) }.groupingBy { it }.eachCount()
            v.setTextViewText(
                R.id.mo_stats,
                listOf(ShiftType.EARLY, ShiftType.EVENING, ShiftType.NIGHT, ShiftType.OFF)
                    .filter { (byType[it] ?: 0) > 0 }
                    .joinToString("・") { "${tr(it.displayName)} ${byType[it]}" }
                    .ifEmpty { tr("這個月還沒排班") }
            )
            val holidayWork = if (!policy.enabled) 0 else monthDays.count { d ->
                WidgetData.type(shifts[d])?.isWorkShift == true && TaiwanHolidays.on(d)?.makeUp == false
            }
            v.setViewVisibility(R.id.mo_holiday, if (holidayWork > 0) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.mo_holiday, tr("國定假日上班 {0} 天", holidayWork))

            v.setOnClickPendingIntent(R.id.mo_root, WidgetData.openApp(context))
            PAGER.buttons(context, v, id, R.id.mo_prev, R.id.mo_next, R.id.mo_title)
            return v
        }
    }
}
