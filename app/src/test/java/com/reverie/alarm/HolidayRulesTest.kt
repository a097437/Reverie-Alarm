package com.reverie.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/**
 * 國定假日與休假防呆的規則。
 * 最重要的兩件事：休假日不會亂響、國定假日上班的鬧鐘絕對不會被誤關。
 */
class HolidayRulesTest {

    private val roster = HolidayPolicy(enabled = true, mode = HolidayMode.ROSTER)
    private val office = HolidayPolicy(enabled = true, mode = HolidayMode.OFFICE)

    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private fun shift(date: LocalDate, type: ShiftType, start: LocalTime = t(8), end: LocalTime = t(17), id: String = "ev-$date") =
        ShiftEvent(id = id, date = date, title = type.displayName, type = type, startTime = start, endTime = end)

    private fun alarm(
        date: LocalDate, time: LocalTime, eventId: String?, stage: AlarmStage = AlarmStage.WAKE,
        enabled: Boolean = true, id: String = "al-$date-$time-$stage"
    ) = ShiftAlarm(id = id, eventId = eventId, date = date, triggerTime = time, stage = stage, label = "測試", enabled = enabled)

    /** 前一天早上 9 點檢查明天 */
    private fun nowBefore(date: LocalDate) = LocalDateTime.of(date.minusDays(1), t(9))

    // ------------------------------------------------------------------ 假日資料

    @Test
    fun officialCalendar_2026_and_2027_matchTheGovernmentTotals() {
        // 人事行政總處：2026 放假 120 天（平日放假 16 天）、2027 放假 121 天（平日放假 17 天），都沒有補班
        for ((year, weekdayOff, total) in listOf(Triple(2026, 16, 120), Triple(2027, 17, 121))) {
            val inYear = TaiwanHolidays.all.filter { it.date.year == year }
            val weekdays = inYear.filter { it.date.dayOfWeek != DayOfWeek.SATURDAY && it.date.dayOfWeek != DayOfWeek.SUNDAY }
            assertEquals("$year 平日放假天數", weekdayOff, weekdays.size)
            var weekends = 0
            var day = LocalDate.of(year, 1, 1)
            while (day.year == year) {
                if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) weekends++
                day = day.plusDays(1)
            }
            assertEquals("$year 全年放假天數", total, weekends + weekdays.size)
        }
        assertEquals(TaiwanHolidays.all.size, TaiwanHolidays.all.map { it.date }.toSet().size)
    }

    @Test
    fun officialCalendar_spotChecks() {
        assertEquals("國慶日補假", TaiwanHolidays.on(d(10, 9))?.name)
        assertTrue(TaiwanHolidays.on(d(10, 9))!!.makeUp)
        assertEquals("國慶日", TaiwanHolidays.on(d(10, 10))?.name)
        assertFalse(TaiwanHolidays.on(d(10, 10))!!.makeUp)
        assertEquals("行憲紀念日", TaiwanHolidays.on(d(12, 25))?.name)
        assertEquals("小年夜", TaiwanHolidays.on(d(2, 4, 2027))?.name)
        assertEquals("開國紀念日補假", TaiwanHolidays.on(d(12, 31, 2027))?.name)
        assertNull(TaiwanHolidays.on(d(10, 12)))
    }

    // ------------------------------------------------------------------ 每天的判斷：有排班一律以排班為準

    @Test
    fun scheduledShiftAlwaysWinsOverHoliday_inBothModes() {
        val events = listOf(shift(d(10, 9), ShiftType.EARLY))
        for (policy in listOf(roster, office)) {
            val s = DayResolver.resolve(d(10, 9), events, policy)
            assertEquals(DayKind.SHIFT, s.kind)
            assertFalse(s.isOff)
            assertTrue(s.isHolidayWork)
        }
    }

    @Test
    fun scheduledOffAndPlansStayAsScheduled() {
        val events = listOf(shift(d(10, 10), ShiftType.OFF), shift(d(12, 25), ShiftType.CUSTOM))
        assertEquals(DayKind.OFF, DayResolver.resolve(d(10, 10), events, office).kind)
        assertEquals(DayKind.PLAN, DayResolver.resolve(d(12, 25), events, office).kind)
    }

    @Test
    fun unscheduledHoliday_onlyMeansOffForOfficeWorkers() {
        assertEquals(DayKind.NONE, DayResolver.resolve(d(10, 9), emptyList(), roster).kind)
        assertEquals(DayKind.HOLIDAY_OFF, DayResolver.resolve(d(10, 9), emptyList(), office).kind)
        // 補假可以排除（民間公司的補假日由勞資協商）
        val noMakeUp = office.copy(includeMakeUp = false)
        assertEquals(DayKind.NONE, DayResolver.resolve(d(10, 9), emptyList(), noMakeUp).kind)
        assertEquals(DayKind.HOLIDAY_OFF, DayResolver.resolve(d(10, 10), emptyList(), noMakeUp).kind)
        // 一般日子沒排班就是沒排班
        assertEquals(DayKind.NONE, DayResolver.resolve(d(10, 12), emptyList(), office).kind)
    }

    @Test
    fun turningHolidaysOff_removesEveryHolidayEffect() {
        val off = office.copy(enabled = false)
        val s = DayResolver.resolve(d(10, 9), emptyList(), off)
        assertEquals(DayKind.NONE, s.kind)
        assertNull(s.holiday)
        assertFalse(off.skipsShiftOn(d(10, 9)))
    }

    @Test
    fun batchSkipping_onlyForOfficeWorkersWhoWantIt() {
        assertTrue(office.skipsShiftOn(d(10, 9)))
        assertFalse(office.skipsShiftOn(d(10, 12)))
        assertFalse(roster.skipsShiftOn(d(10, 9)))
        assertFalse(office.copy(skipInBatch = false).skipsShiftOn(d(10, 9)))
    }

    // ------------------------------------------------------------------ 防呆：絕對不誤關上班鬧鐘

    @Test
    fun nightShiftWakeAlarm_theEveningBefore_isNeverFlagged_evenWhenThatEveningIsADayOff() {
        // 10/11 00:00 大夜班：起床 10/10 22:30、出門 10/10 23:20；10/10 是國慶日，而且你排了休假
        val night = shift(d(10, 11), ShiftType.NIGHT, t(0), t(8))
        val dayOff = shift(d(10, 10), ShiftType.OFF)
        val wake = alarm(d(10, 10), t(22, 30), night.id)
        val leave = alarm(d(10, 10), t(23, 20), night.id, AlarmStage.LEAVE)
        for (policy in listOf(roster, office)) {
            val r = HolidayGuard.check(d(10, 10), nowBefore(d(10, 10)), listOf(night, dayOff), listOf(wake, leave), policy)
            assertTrue("大夜班的鬧鐘不能被列成衝突（$policy）", r.items.isEmpty())
            assertEquals(listOf(wake, leave), r.ringing)
            assertTrue(r.day.isOff)
        }
    }

    @Test
    fun holidayWork_alarmsAlwaysRing_andRosterModeNeverAsks() {
        // 國慶日補假當天排早班
        val early = shift(d(10, 9), ShiftType.EARLY, t(8))
        val wake = alarm(d(10, 9), t(6, 30), early.id)
        val leave = alarm(d(10, 9), t(7, 20), early.id, AlarmStage.LEAVE)
        val r = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), listOf(early), listOf(wake, leave), roster)
        assertTrue(r.items.isEmpty())
        assertEquals(listOf(wake, leave), r.ringing)
        assertTrue(r.day.isHolidayWork)
        assertEquals("", r.attentionKey)
    }

    @Test
    fun holidayWork_officeModeAsks_butNothingIsPreChecked_andAlarmsStillRing() {
        val early = shift(d(10, 9), ShiftType.EARLY, t(8))
        val wake = alarm(d(10, 9), t(6, 30), early.id)
        val r = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), listOf(early), listOf(wake), office)
        assertEquals(1, r.items.size)
        val item = r.items[0]
        assertEquals(GuardKind.HOLIDAY_SHIFT, item.kind)
        assertFalse("預設照常上班，不能預先勾選", item.defaultChecked)
        assertEquals(listOf(wake), item.alarms)
        assertEquals(listOf(wake), r.ringing)
        assertEquals(1, r.attention.size)
        // 補假排除時，補假日排班就是一般上班日，不用問
        val r2 = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), listOf(early), listOf(wake), office.copy(includeMakeUp = false))
        assertTrue(r2.items.isEmpty())
    }

    @Test
    fun plansOnHolidays_areNeverQuestioned() {
        val plan = shift(d(10, 9), ShiftType.CUSTOM, t(10))
        val a = alarm(d(10, 9), t(9), plan.id)
        val r = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), listOf(plan), listOf(a), office)
        assertTrue(r.items.isEmpty())
        assertEquals(listOf(a), r.ringing)
    }

    // ------------------------------------------------------------------ 防呆：休假日不亂響

    @Test
    fun shiftChangedToOff_leftoverWorkAlarmsArePreChecked() {
        // 10/12 原本早班，改成休假時沒有重設鬧鐘：鬧鐘還掛在這個班上
        val off = shift(d(10, 12), ShiftType.OFF)
        val wake = alarm(d(10, 12), t(6, 30), off.id)
        val leave = alarm(d(10, 12), t(7, 20), off.id, AlarmStage.LEAVE)
        val r = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(wake, leave), roster)
        assertEquals(listOf(GuardKind.SHIFT_NOW_OFF, GuardKind.SHIFT_NOW_OFF), r.items.map { it.kind })
        assertTrue(r.items.all { it.defaultChecked })
        assertTrue(r.ringing.isEmpty())
        assertEquals(2, r.attention.size)
    }

    @Test
    fun leftoverNightAlarmRingingTonight_isCaughtThroughItsOwner() {
        // 明天（10/13）的大夜班改成休假，但今晚 22:30 的起床鬧鐘還開著：用日期找會漏掉，要看歸屬
        val off = shift(d(10, 13), ShiftType.OFF, id = "night-now-off")
        val tonight = alarm(d(10, 12), t(22, 30), off.id)
        val now = LocalDateTime.of(d(10, 12), t(20))
        val r = HolidayGuard.check(d(10, 13), now, listOf(off), listOf(tonight), roster)
        assertEquals(1, r.items.size)
        assertEquals(GuardKind.SHIFT_NOW_OFF, r.items[0].kind)
    }

    @Test
    fun alarmWhoseShiftWasDeleted_isPreChecked() {
        val orphan = alarm(d(10, 12), t(6, 30), "deleted-shift")
        val r = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), emptyList(), listOf(orphan), roster)
        assertEquals(GuardKind.SHIFT_REMOVED, r.items.single().kind)
        assertTrue(r.items.single().defaultChecked)
    }

    @Test
    fun ownAlarmOnADayOff_isOnlyAGentleReminder() {
        // 休假日自己設的鬧鐘（例如趕飛機）：列出來，但預設不勾，也不會主動跳出來
        val off = shift(d(10, 12), ShiftType.OFF)
        val flight = alarm(d(10, 12), t(4, 30), null, AlarmStage.CUSTOM)
        val r = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(flight), roster)
        assertEquals(GuardKind.OWN_ALARM_DAY_OFF, r.items.single().kind)
        assertFalse(r.items.single().defaultChecked)
        assertTrue(r.attention.isEmpty())
        assertEquals("", r.attentionKey)
    }

    @Test
    fun holidayAloneNeverCreatesAConflict() {
        // 沒排班的國定假日＋自己設的鬧鐘
        val own = alarm(d(10, 9), t(7), null, AlarmStage.CUSTOM)
        val r1 = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), emptyList(), listOf(own), roster)
        assertTrue("照班表：國定假日只是標示", r1.items.isEmpty())
        assertEquals(listOf(own), r1.ringing)
        val r2 = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), emptyList(), listOf(own), office)
        assertEquals(GuardKind.OWN_ALARM_DAY_OFF, r2.items.single().kind)
        assertTrue("見紅休也只溫和提醒，不會主動跳出", r2.attention.isEmpty())
    }

    @Test
    fun pastAndDisabledAlarmsAreIgnored() {
        val off = shift(d(10, 12), ShiftType.OFF)
        val rang = alarm(d(10, 12), t(6, 30), off.id)
        val paused = alarm(d(10, 12), t(7, 20), off.id, AlarmStage.LEAVE, enabled = false)
        val now = LocalDateTime.of(d(10, 12), t(7))
        val r = HolidayGuard.check(d(10, 12), now, listOf(off), listOf(rang, paused), roster)
        assertTrue(r.items.isEmpty())
        assertTrue(r.ringing.isEmpty())
    }

    @Test
    fun legacyAlarmsWithoutOwner_beforeANightShift_areTreatedAsThatShifts() {
        // 舊版資料的鬧鐘沒有 eventId：10/10 22:30 起床，10/11 00:00 大夜班
        val night = shift(d(10, 11), ShiftType.NIGHT, t(0), t(8))
        val dayOff = shift(d(10, 10), ShiftType.OFF)
        val legacy = alarm(d(10, 10), t(22, 30), null, AlarmStage.WAKE)
        val r = HolidayGuard.check(d(10, 10), nowBefore(d(10, 10)), listOf(night, dayOff), listOf(legacy), roster)
        assertTrue(r.items.isEmpty())
        assertEquals(listOf(legacy), r.ringing)
    }

    @Test
    fun workShiftWithoutAnyAlarm_isPointedOut() {
        val early = shift(d(10, 9), ShiftType.EARLY, t(8))
        val r = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), listOf(early), emptyList(), roster)
        assertEquals(early, r.shiftWithoutAlarm)
        // 自己設的鬧鐘在上班前幾小時響也算有鬧鐘
        val own = alarm(d(10, 9), t(6), null, AlarmStage.CUSTOM)
        val r2 = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), listOf(early), listOf(own), roster)
        assertNull(r2.shiftWithoutAlarm)
    }

    @Test
    fun attentionKey_isStableAndChangesWhenTheConflictsChange() {
        val off = shift(d(10, 12), ShiftType.OFF)
        val wake = alarm(d(10, 12), t(6, 30), off.id, id = "a1")
        val leave = alarm(d(10, 12), t(7, 20), off.id, AlarmStage.LEAVE, id = "a2")
        val k1 = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(wake, leave), roster).attentionKey
        val k2 = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(leave, wake), roster).attentionKey
        val k3 = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(wake), roster).attentionKey
        assertEquals(k1, k2)
        assertNotNull(k3)
        assertTrue(k1 != k3)
    }

    // ------------------------------------------------------------------ 統計與設定面板

    @Test
    fun holidayWorkDays_countsRealHolidaysOnly() {
        val events = listOf(
            shift(d(10, 9), ShiftType.EARLY),   // 補假：不算
            shift(d(10, 10), ShiftType.NIGHT),  // 國慶日：算
            shift(d(10, 25), ShiftType.OFF),    // 休假：不算
            shift(d(10, 26), ShiftType.EVENING) // 補假：不算
        )
        assertEquals(1, DayResolver.holidayWorkDays(YearMonth.of(2026, 10), events, roster))
        assertEquals(0, DayResolver.holidayWorkDays(YearMonth.of(2026, 10), events, roster.copy(enabled = false)))
    }

    @Test
    fun upcomingHolidayShifts_listsOnlyFutureWorkOnDaysOff() {
        val events = listOf(
            shift(d(10, 9), ShiftType.EARLY),
            shift(d(12, 25), ShiftType.EARLY),
            shift(d(12, 24), ShiftType.EARLY),   // 平日
            shift(d(1, 1, 2027), ShiftType.CUSTOM)
        )
        val list = DayResolver.upcomingHolidayShifts(d(11, 23), events, office)
        assertEquals(listOf(d(12, 25)), list.map { it.first.date })
        assertTrue(DayResolver.upcomingHolidayShifts(d(11, 23), events, roster).isEmpty())
    }

    // ------------------------------------------------------------------ 前一晚自動提醒

    @Test
    fun eveningCheck_runsTodayIfNotYetPassed_otherwiseTomorrow() {
        val nine = LocalTime.of(21, 0)
        assertEquals(LocalDateTime.of(d(11, 23), nine), nextEveningCheck(LocalDateTime.of(d(11, 23), t(20, 59)), nine))
        assertEquals(LocalDateTime.of(d(11, 24), nine), nextEveningCheck(LocalDateTime.of(d(11, 23), t(21, 0)), nine))
        assertEquals(LocalDateTime.of(d(11, 24), nine), nextEveningCheck(LocalDateTime.of(d(11, 23), t(23, 30)), nine))
        assertEquals(LocalDateTime.of(d(1, 1, 2027), nine), nextEveningCheck(LocalDateTime.of(d(12, 31), t(22)), nine))
        assertEquals(LocalTime.of(22, 0), parseEveningTime("22:00"))
        assertEquals(LocalTime.of(21, 0), parseEveningTime("亂打"))
        assertEquals(LocalTime.of(21, 0), parseEveningTime(null))
    }

    @Test
    fun eveningPause_onlyTouchesPreCheckedConflicts() {
        // 明天休假：忘了關的上班鬧鐘（預設勾）＋自己設的鬧鐘（不勾）
        val off = shift(d(10, 12), ShiftType.OFF)
        val leftover = alarm(d(10, 12), t(6, 30), off.id, id = "leftover")
        val own = alarm(d(10, 12), t(4, 30), null, AlarmStage.CUSTOM, id = "own")
        val r = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(leftover, own), roster)
        assertEquals(listOf("leftover"), r.preCheckedAlarmIds)
        // 見紅休的假日排班不會被通知上的「暫停」動到
        val early = shift(d(10, 9), ShiftType.EARLY, t(8))
        val wake = alarm(d(10, 9), t(6, 30), early.id)
        val r2 = HolidayGuard.check(d(10, 9), nowBefore(d(10, 9)), listOf(early), listOf(wake), office)
        assertTrue(r2.preCheckedAlarmIds.isEmpty())
        assertTrue(r2.needsEveningNotice("", ""))
    }

    @Test
    fun eveningNotice_respectsWhatYouAlreadyDecided() {
        val off = shift(d(10, 12), ShiftType.OFF)
        val leftover = alarm(d(10, 12), t(6, 30), off.id, id = "leftover")
        val r = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(leftover), roster)
        assertTrue(r.needsEveningNotice("", ""))
        assertFalse("按過保留就不再提醒", r.needsEveningNotice(r.attentionKey, ""))
        // 沒有要確認的事就不提醒（自己設的鬧鐘不算）
        val own = alarm(d(10, 12), t(4, 30), null, AlarmStage.CUSTOM)
        val quiet = HolidayGuard.check(d(10, 12), nowBefore(d(10, 12)), listOf(off), listOf(own), roster)
        assertFalse(quiet.needsEveningNotice("", ""))
        // 明天有班卻沒鬧鐘：提醒一次，按過「知道了」就不再提醒
        val early = shift(d(10, 13), ShiftType.EARLY, t(8))
        val none = HolidayGuard.check(d(10, 13), nowBefore(d(10, 13)), listOf(early), emptyList(), roster)
        assertTrue(none.needsEveningNotice("", ""))
        assertFalse(none.needsEveningNotice("", none.noAlarmKey))
        assertEquals("", quiet.noAlarmKey)
    }

    @Test
    fun preferencesMapToPolicy_withSafeFallback() {
        val p = AppPreferences(holidayMode = "office", holidayIncludeMakeUp = false).holidayPolicy()
        assertEquals(HolidayMode.OFFICE, p.mode)
        assertFalse(p.includeMakeUp)
        assertEquals(HolidayMode.ROSTER, AppPreferences(holidayMode = "???").holidayPolicy().mode)
        assertEquals(HolidayMode.ROSTER, AppPreferences().holidayPolicy().mode)
    }
}
