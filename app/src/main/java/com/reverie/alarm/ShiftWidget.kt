package com.reverie.alarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.gson.Gson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ============================================================================
// Android 桌面小工具（4×2）：今日班表、明日班表、下一個鬧鐘
//   - App 每次儲存資料時把近期班表寫進一份小快照（saveSnapshot），再通知小工具更新
//   - 下一個鬧鐘直接讀鬧鐘引擎的排程清單，和實際會響的鬧鐘一致
// ============================================================================

private data class WidgetShift(
    val date: String = "",
    val title: String = "",
    val type: String = "CUSTOM",
    val start: String = "",
    val end: String = ""
)

class ShiftWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, buildViews(context)) }
    }

    companion object {
        private const val PREFS = "reverie_widget"
        private const val KEY_SHIFTS = "shifts"
        private const val KEY_HOLIDAY = "holiday_policy"
        private val gson = Gson()

        /** App 儲存資料時呼叫：保存前後 60 天的班表、國定假日設定，並刷新小工具 */
        fun saveSnapshot(context: Context, events: List<ShiftEvent>, holidayPolicy: HolidayPolicy) {
            val today = LocalDate.now()
            val shifts = events
                .filter { !it.date.isBefore(today.minusDays(1)) && !it.date.isAfter(today.plusDays(60)) }
                .map { WidgetShift(it.date.toString(), it.title, it.type.name, it.startTime.toString(), it.endTime.toString()) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SHIFTS, gson.toJson(shifts.toTypedArray()))
                .putString(KEY_HOLIDAY, gson.toJson(holidayPolicy))
                .apply()
            refresh(context)
        }

        private fun readPolicy(context: Context): HolidayPolicy {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOLIDAY, null) ?: return HolidayPolicy()
            return runCatching { gson.fromJson(raw, HolidayPolicy::class.java) }.getOrNull() ?: HolidayPolicy()
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ShiftWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun readShifts(context: Context): List<WidgetShift> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SHIFTS, null) ?: return emptyList()
            return runCatching { gson.fromJson(raw, Array<WidgetShift>::class.java).toList() }.getOrDefault(emptyList())
        }

        /** 一天的描述；國定假日加上假日名稱（有排班以排班為準，見紅休的空白假日寫「放假」） */
        private fun describe(shift: WidgetShift?, date: LocalDate, policy: HolidayPolicy): String {
            val holiday = policy.holidayOn(date)
            val base = when {
                shift == null && policy.isDayOff(date) -> tr("國定假日放假")
                shift == null -> tr("尚未排班")
                shift.type == ShiftType.OFF.name -> tr("{0} · 全天休假", tr(shift.title))
                else -> "${tr(shift.title)} · ${shift.start.take(5)} - ${shift.end.take(5)}"
            }
            return if (holiday != null) "$base · ${tr(holiday.short)}" else base
        }

        private fun typeColor(shift: WidgetShift?): Int {
            val t = shift?.let { runCatching { ShiftType.valueOf(it.type) }.getOrNull() } ?: return 0xFF94A3B8.toInt()
            return when (t) {
                ShiftType.EARLY -> 0xFFF59E0B
                ShiftType.EVENING -> 0xFF3B82F6
                ShiftType.NIGHT -> 0xFFA855F7
                ShiftType.OFF -> 0xFF10B981
                ShiftType.CUSTOM -> 0xFF94A3B8
            }.toInt()
        }

        private fun buildViews(context: Context): RemoteViews {
            I18n.ensure(context)
            val today = LocalDate.now()
            val shifts = readShifts(context)
            val todayShift = shifts.firstOrNull { it.date == today.toString() }
            val tomorrowShift = shifts.firstOrNull { it.date == today.plusDays(1).toString() }

            val now = LocalDateTime.now()
            val next = AlarmEngine.allSpecs(context)
                .mapNotNull { spec -> runCatching { spec to spec.triggerDateTime() }.getOrNull() }
                .filter { it.second.isAfter(now) }
                .minByOrNull { it.second }

            val views = RemoteViews(context.packageName, R.layout.widget_shift)
            views.setTextViewText(
                R.id.widget_date,
                "${today.format(DateTimeFormatter.ofPattern("M/d"))} ${I18n.weekday(today.dayOfWeek)}"
            )
            val policy = readPolicy(context)
            views.setTextViewText(R.id.widget_today, describe(todayShift, today, policy))
            views.setTextColor(R.id.widget_today_dot, typeColor(todayShift))
            views.setTextViewText(R.id.widget_tomorrow, tr("明日：") + describe(tomorrowShift, today.plusDays(1), policy))

            if (next == null) {
                views.setTextViewText(R.id.widget_alarm_time, "--:--")
                views.setTextViewText(R.id.widget_alarm_label, tr("目前沒有排定的鬧鐘"))
            } else {
                val (spec, at) = next
                val dayWord = when (at.toLocalDate()) {
                    today -> tr("今天")
                    today.plusDays(1) -> tr("明天")
                    else -> at.toLocalDate().format(DateTimeFormatter.ofPattern("M/d"))
                }
                views.setTextViewText(R.id.widget_alarm_time, at.format(DateTimeFormatter.ofPattern("HH:mm")))
                views.setTextViewText(R.id.widget_alarm_label, "$dayWord · ${trLabel(spec.label)}")
            }

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }
    }
}
