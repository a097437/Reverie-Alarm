package com.reverie.alarm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

// ============================================================================
// 國定假日與休假防呆：判斷規則（純邏輯、不碰畫面，用單元測試鎖住）
//
// 兩層資料：
//   1. 個人排班（events）：只有使用者自己的操作會寫入（手動、批次、AI 排班、還原備份）
//   2. 國定假日（TaiwanHolidays）：唯讀的日曆標示，不寫進班表、不產生也不刪除鬧鐘，也不會被分享成你的休假
//
// 兩種上班方式（HolidayMode），同一套規則：
//   - 照班表（輪班、排班制）：國定假日只標示紅字，班表和鬧鐘完全照你排的
//   - 見紅休（一般上班族）：沒排班的國定假日算放假；假日排了上班會先問你、批次排班會略過
//
// 三條鐵律：
//   1. 國定假日只能「填空白」：那天只要有排班，就完全以排班為準
//   2. 鬧鐘看「歸屬」不看響鈴日期：班別的起床／出門鬧鐘跟著它所屬的班（跨午夜的大夜班也一樣）
//   3. 系統永遠不自己關鬧鐘：這裡只產生「建議」，暫停要使用者勾選確認，而且可以復原
// ============================================================================

/** 一個放假日（資料來源：行政院人事行政總處「政府行政機關辦公日曆表」） */
data class Holiday(
    val date: LocalDate,
    /** 完整名稱，例如「國慶日」「國慶日補假」 */
    val name: String,
    /** 日曆格子裡的短名稱 */
    val short: String,
    /** 補假：節日遇到週末，政府行政機關改在平日放的那一天（民間公司依勞資協商，不一定同一天） */
    val makeUp: Boolean = false
)

object TaiwanHolidays {
    /** 有內建資料的年份（每年隨 App 更新） */
    val years: IntRange = 2026..2027

    private fun h(y: Int, m: Int, d: Int, name: String, short: String = name) = Holiday(LocalDate.of(y, m, d), name, short)
    private fun makeUp(y: Int, m: Int, d: Int, of: String) = Holiday(LocalDate.of(y, m, d), "${of}補假", "補假", makeUp = true)

    /**
     * 所有放假的節日與補假（週末遇到的節日本身也列出來，日曆才標得出來）。
     * 2026、2027 兩年都沒有補班日，所以只需要記放假的日子。
     */
    val all: List<Holiday> = listOf(
        // ---- 2026（民國 115 年）：全年放假 120 天 ----
        h(2026, 1, 1, "開國紀念日", "元旦"),
        h(2026, 2, 15, "小年夜"),
        h(2026, 2, 16, "農曆除夕", "除夕"),
        h(2026, 2, 17, "春節（初一）", "初一"),
        h(2026, 2, 18, "春節（初二）", "初二"),
        h(2026, 2, 19, "春節（初三）", "初三"),
        makeUp(2026, 2, 20, "春節"),
        makeUp(2026, 2, 27, "和平紀念日"),
        h(2026, 2, 28, "和平紀念日", "228"),
        makeUp(2026, 4, 3, "兒童節"),
        h(2026, 4, 4, "兒童節"),
        h(2026, 4, 5, "清明節"),
        makeUp(2026, 4, 6, "清明節"),
        h(2026, 5, 1, "勞動節"),
        h(2026, 6, 19, "端午節"),
        h(2026, 9, 25, "中秋節"),
        h(2026, 9, 28, "教師節"),
        makeUp(2026, 10, 9, "國慶日"),
        h(2026, 10, 10, "國慶日"),
        h(2026, 10, 25, "臺灣光復節", "光復節"),
        makeUp(2026, 10, 26, "臺灣光復節"),
        h(2026, 12, 25, "行憲紀念日", "行憲"),
        // ---- 2027（民國 116 年）：全年放假 121 天 ----
        h(2027, 1, 1, "開國紀念日", "元旦"),
        h(2027, 2, 4, "小年夜"),
        h(2027, 2, 5, "農曆除夕", "除夕"),
        h(2027, 2, 6, "春節（初一）", "初一"),
        h(2027, 2, 7, "春節（初二）", "初二"),
        h(2027, 2, 8, "春節（初三）", "初三"),
        makeUp(2027, 2, 9, "春節"),
        makeUp(2027, 2, 10, "春節"),
        h(2027, 2, 28, "和平紀念日", "228"),
        makeUp(2027, 3, 1, "和平紀念日"),
        h(2027, 4, 4, "兒童節"),
        h(2027, 4, 5, "清明節"),
        makeUp(2027, 4, 6, "兒童節"),
        makeUp(2027, 4, 30, "勞動節"),
        h(2027, 5, 1, "勞動節"),
        h(2027, 6, 9, "端午節"),
        h(2027, 9, 15, "中秋節"),
        h(2027, 9, 28, "教師節"),
        h(2027, 10, 10, "國慶日"),
        makeUp(2027, 10, 11, "國慶日"),
        h(2027, 10, 25, "臺灣光復節", "光復節"),
        makeUp(2027, 12, 24, "行憲紀念日"),
        h(2027, 12, 25, "行憲紀念日", "行憲"),
        makeUp(2027, 12, 31, "開國紀念日") // 2028 元旦是星期六，提前在 2027/12/31 補假
    )

    private val byDate: Map<LocalDate, Holiday> = all.associateBy { it.date }

    fun on(date: LocalDate): Holiday? = byDate[date]

    fun between(from: LocalDate, to: LocalDate): List<Holiday> = all.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
}

/** 國定假日怎麼影響「這天算不算放假」 */
enum class HolidayMode(val key: String) {
    /** 照班表上班（輪班、排班制）：假日只標示，班表和鬧鐘完全照你排的 */
    ROSTER("roster"),
    /** 見紅就休（一般上班族）：沒排班的假日算放假；假日排了上班會先問你 */
    OFFICE("office");

    companion object {
        fun of(key: String?): HolidayMode = entries.firstOrNull { it.key == key } ?: ROSTER
    }
}

/** 從設定整理出來的假日規則 */
data class HolidayPolicy(
    /** 日曆標示國定假日（關掉就和沒有這個功能一樣） */
    val enabled: Boolean = true,
    val mode: HolidayMode = HolidayMode.ROSTER,
    /** 見紅休：政府的補假日也放假 */
    val includeMakeUp: Boolean = true,
    /** 見紅休：批次／AI 排班時，上班的班別略過放假的國定假日 */
    val skipInBatch: Boolean = true
) {
    /** 這天要標示的假日（關掉國定假日就一律 null） */
    fun holidayOn(date: LocalDate): Holiday? = if (enabled) TaiwanHolidays.on(date) else null

    /** 依你的上班方式，這天是不是放假的國定假日：只有見紅休才算，補假可以排除 */
    fun isDayOff(date: LocalDate): Boolean {
        if (mode != HolidayMode.OFFICE) return false
        val h = holidayOn(date) ?: return false
        return includeMakeUp || !h.makeUp
    }

    /** 批次／AI 排班時，這天的上班班別要不要略過 */
    fun skipsShiftOn(date: LocalDate): Boolean = skipInBatch && isDayOff(date)
}

fun AppPreferences.holidayPolicy(): HolidayPolicy =
    HolidayPolicy(holidayLabels, HolidayMode.of(holidayMode), holidayIncludeMakeUp, holidaySkipInBatch)

/** 上班的班別（早、晚、夜）；「日程」是行程，不算上班 */
val ShiftType.isWorkShift: Boolean
    get() = this == ShiftType.EARLY || this == ShiftType.EVENING || this == ShiftType.NIGHT

// ---------------------------------------------------------------------------
// 每一天的判斷
// ---------------------------------------------------------------------------

enum class DayKind {
    /** 有上班的班（早／晚／夜班） */
    SHIFT,
    /** 有行程（日程） */
    PLAN,
    /** 你自己排的休假 */
    OFF,
    /** 沒排班的國定假日，而且你是「見紅就休」：推定放假 */
    HOLIDAY_OFF,
    /** 沒排班 */
    NONE
}

data class DayStatus(val date: LocalDate, val kind: DayKind, val event: ShiftEvent?, val holiday: Holiday?) {
    /** 這天放假（你排的休假，或見紅休的國定假日） */
    val isOff: Boolean get() = kind == DayKind.OFF || kind == DayKind.HOLIDAY_OFF
    /** 國定假日照常上班 */
    val isHolidayWork: Boolean get() = kind == DayKind.SHIFT && holiday != null
}

object DayResolver {
    /**
     * 一天的狀態：由上往下，第一條符合的就是答案。
     * 日曆、防呆檢測、統計、共同休假對照、桌面小工具都用這一個函式，不會各說各話。
     */
    fun resolve(date: LocalDate, events: List<ShiftEvent>, policy: HolidayPolicy): DayStatus =
        resolveDay(date, events.firstOrNull { it.date == date }, policy)

    /** 已經查到那天的排班（例如月曆用的查表）時直接用，不必再掃一次整份清單 */
    fun resolveDay(date: LocalDate, ev: ShiftEvent?, policy: HolidayPolicy): DayStatus {
        val kind = when {
            ev != null && ev.type.isWorkShift -> DayKind.SHIFT    // 1. 有班：以排班為準（國定假日也一樣）
            ev != null && ev.type != ShiftType.OFF -> DayKind.PLAN // 1. 有行程：以排班為準
            ev != null -> DayKind.OFF                              // 2. 你排的休假
            policy.isDayOff(date) -> DayKind.HOLIDAY_OFF           // 3. 沒排班＋國定假日＋見紅休
            else -> DayKind.NONE                                   // 4、5. 沒排班（照班表時假日只標示）
        }
        return DayStatus(date, kind, ev, policy.holidayOn(date))
    }

    /** 這個月在國定假日（不含補假）排了上班的天數：方便核對加倍工資（勞基法第 39 條） */
    fun holidayWorkDays(month: YearMonth, events: List<ShiftEvent>, policy: HolidayPolicy): Int {
        if (!policy.enabled) return 0
        return events.count { ev ->
            YearMonth.from(ev.date) == month && ev.type.isWorkShift && TaiwanHolidays.on(ev.date)?.makeUp == false
        }
    }

    /** 見紅休：今天以後、排了上班的放假國定假日（設定面板裡一次改成休假用） */
    fun upcomingHolidayShifts(from: LocalDate, events: List<ShiftEvent>, policy: HolidayPolicy): List<Pair<ShiftEvent, Holiday>> =
        events.filter { !it.date.isBefore(from) && it.type.isWorkShift && policy.isDayOff(it.date) }
            .sortedBy { it.date }
            .mapNotNull { ev -> policy.holidayOn(ev.date)?.let { ev to it } }
}

// ---------------------------------------------------------------------------
// 休假日鬧鐘防呆
// ---------------------------------------------------------------------------

enum class GuardKind {
    /** 班改成休假了，原本的上班鬧鐘還開著（建議暫停，預設勾） */
    SHIFT_NOW_OFF,
    /** 鬧鐘原本屬於的班已經不在了（建議暫停，預設勾） */
    SHIFT_REMOVED,
    /** 放假那天，你自己設的鬧鐘（只提醒，預設不勾） */
    OWN_ALARM_DAY_OFF,
    /** 見紅休：放假的國定假日排了上班（問要不要改成休假，預設不勾＝照常上班） */
    HOLIDAY_SHIFT
}

data class GuardItem(
    val kind: GuardKind,
    /** 勾選後要暫停的鬧鐘（HOLIDAY_SHIFT：那個班的鬧鐘，改成休假時一起移除） */
    val alarms: List<ShiftAlarm>,
    /** 相關的班（SHIFT_NOW_OFF：鬧鐘所屬、已改成休假的班；HOLIDAY_SHIFT：要不要改成休假的班） */
    val event: ShiftEvent?,
    val defaultChecked: Boolean
) {
    val key: String
        get() = if (kind == GuardKind.HOLIDAY_SHIFT) "shift:${event?.id}" else "alarm:${alarms.firstOrNull()?.id}"
}

data class GuardReport(
    /** 檢查的是哪一天（明天） */
    val date: LocalDate,
    val day: DayStatus,
    val items: List<GuardItem>,
    /** 會照常響的鬧鐘（上班或行程用的；國定假日上班也照響） */
    val ringing: List<ShiftAlarm>,
    /** 那天有上班的班，卻沒有任何會響的鬧鐘 */
    val shiftWithoutAlarm: ShiftEvent?
) {
    /** 需要你決定、會主動跳出來問的：預設勾選的衝突，加上見紅休的假日排班 */
    val attention: List<GuardItem> get() = items.filter { it.defaultChecked || it.kind == GuardKind.HOLIDAY_SHIFT }

    /** 同一組問題只問一次（按過「全部保留」就記住這把鑰匙） */
    val attentionKey: String get() = attentionKeyWithout(emptyList())

    /** 處理掉一部分之後剩下的那組問題（沒勾的＝你決定保留，不再追問） */
    fun attentionKeyWithout(handled: Collection<GuardItem>): String {
        val handledKeys = handled.map { it.key }.toSet()
        val rest = attention.filter { it.key !in handledKeys }
        return if (rest.isEmpty()) "" else "$date|" + rest.map { it.key }.sorted().joinToString(",")
    }

    /** 前一晚提醒的「暫停」按鈕會關的鬧鐘：只有預設勾選的衝突（見紅休的假日排班要在 App 裡決定） */
    val preCheckedAlarmIds: List<String> get() = items.filter { it.defaultChecked }.flatMap { it.alarms }.map { it.id }

    /** 「明天有班卻沒鬧鐘」這件事的鑰匙（按過「知道了」就不再提醒） */
    val noAlarmKey: String get() = shiftWithoutAlarm?.let { "$date|noalarm:${it.id}" } ?: ""

    /** 前一晚要不要發通知：還有沒處理、也沒按過「保留／知道了」的事 */
    fun needsEveningNotice(dismissedAttention: String, dismissedNoAlarm: String): Boolean =
        (attention.isNotEmpty() && attentionKey != dismissedAttention) ||
            (noAlarmKey.isNotEmpty() && noAlarmKey != dismissedNoAlarm)
}

/** 前一晚提醒的時間（設定是 "21:00" 這樣的字串，讀不懂就用 21:00） */
fun parseEveningTime(s: String?): LocalTime =
    runCatching { LocalTime.parse(s ?: "") }.getOrNull() ?: LocalTime.of(21, 0)

/** 下一次前一晚檢查：今天還沒到就今天，已經過了就明天 */
fun nextEveningCheck(now: LocalDateTime, time: LocalTime): LocalDateTime {
    val today = now.toLocalDate().atTime(time)
    return if (today.isAfter(now)) today else today.plusDays(1)
}

object HolidayGuard {
    /** 舊資料沒有 eventId 的起床／出門鬧鐘：之後這麼多小時內開始的班，就當作它的主人 */
    private const val LOOKAHEAD_HOURS = 12L

    private fun at(a: ShiftAlarm): LocalDateTime = LocalDateTime.of(a.date, a.triggerTime)

    /**
     * 檢查某一天（通常是明天）的鬧鐘。只產生建議，不改任何資料。
     * @param now 已經響過的鬧鐘不算
     */
    fun check(
        date: LocalDate,
        now: LocalDateTime,
        events: List<ShiftEvent>,
        alarms: List<ShiftAlarm>,
        policy: HolidayPolicy
    ): GuardReport {
        val day = DayResolver.resolve(date, events, policy)
        val byId = events.associateBy { it.id }
        val upcoming = alarms.filter { it.enabled && at(it).isAfter(now) }.sortedBy { at(it) }
        val dayEvent = day.event
        // 要看的鬧鐘：那天響的，以及屬於那天的班的（例如大夜班前一晚的起床鬧鐘）
        val candidates = upcoming.filter { a -> a.date == date || (dayEvent != null && a.eventId == dayEvent.id) }

        val items = mutableListOf<GuardItem>()
        val ringing = mutableListOf<ShiftAlarm>()
        for (a in candidates) {
            val ownerId = a.eventId
            if (ownerId != null) {
                val owner = byId[ownerId]
                when {
                    owner == null -> items += GuardItem(GuardKind.SHIFT_REMOVED, listOf(a), null, defaultChecked = true)
                    owner.type == ShiftType.OFF -> items += GuardItem(GuardKind.SHIFT_NOW_OFF, listOf(a), owner, defaultChecked = true)
                    else -> ringing += a // 屬於上班或行程：照常響（鐵律 2），國定假日也一樣（鐵律 1）
                }
            } else if (a.stage != AlarmStage.CUSTOM && legacyOwner(a, events) != null) {
                ringing += a // 舊資料：之後 12 小時內有班，當作那個班的鬧鐘
            } else {
                // 你自己設的鬧鐘：看它響的那一天放不放假，放假也只提醒、預設不勾
                val d = DayResolver.resolve(a.date, events, policy)
                if (d.isOff) items += GuardItem(GuardKind.OWN_ALARM_DAY_OFF, listOf(a), d.event, defaultChecked = false)
                else ringing += a
            }
        }

        // 見紅休：放假的國定假日排了上班 → 問一下（預設照常上班，鬧鐘照響）
        if (dayEvent != null && dayEvent.type.isWorkShift && policy.isDayOff(date)) {
            items += GuardItem(GuardKind.HOLIDAY_SHIFT, upcoming.filter { it.eventId == dayEvent.id }, dayEvent, defaultChecked = false)
        }

        // 有上班的班，卻沒有任何會響的鬧鐘（屬於它的，或上班前 12 小時內的都算）
        val noAlarm = dayEvent?.takeIf { ev ->
            ev.type.isWorkShift && run {
                val start = LocalDateTime.of(ev.date, ev.startTime)
                start.isAfter(now) && upcoming.none { a ->
                    a.eventId == ev.id || (at(a).isBefore(start) && !at(a).isBefore(start.minusHours(LOOKAHEAD_HOURS)))
                }
            }
        }
        return GuardReport(date, day, items, ringing, noAlarm)
    }

    /** 舊資料沒有 eventId 的起床／出門鬧鐘：找之後 12 小時內開始的上班或行程 */
    private fun legacyOwner(a: ShiftAlarm, events: List<ShiftEvent>): ShiftEvent? {
        val t = at(a)
        return events.firstOrNull { ev ->
            ev.type != ShiftType.OFF && LocalDateTime.of(ev.date, ev.startTime).let { start ->
                !start.isBefore(t) && !start.isAfter(t.plusHours(LOOKAHEAD_HOURS))
            }
        }
    }
}
