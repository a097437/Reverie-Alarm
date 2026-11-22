package com.reverie.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class QuickScheduleParserTest {
    // 2026-11-20 是星期五
    private val today = LocalDate.of(2026, 11, 20)
    private val base = YearMonth.of(2026, 11)
    private val names = listOf("早班", "晚班", "夜班", "休假", "中班")

    private fun parse(s: String) = QuickScheduleParser.parse(s, names, "休假", today, base)
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private fun nov(vararg days: Int) = days.map { d(11, it) }

    @Test fun rangeAndSingle() {
        val p = parse("21到25號早班，26號休假")
        assertEquals(nov(21, 22, 23, 24, 25).associateWith { "早班" } + mapOf(d(11, 26) to "休假"), p.days)
        assertTrue(p.problems.isEmpty())
    }

    @Test fun nextWeekList() {
        assertEquals(nov(23, 25, 27).associateWith { "晚班" }, parse("下週一三五晚班").days)
        assertEquals(nov(23, 25, 27).associateWith { "晚班" }, parse("下禮拜一、三、五 晚班").days)
    }

    @Test fun weekendsThenRest() {
        val p = parse("這個月週六日休假，其他早班")
        val weekend = nov(1, 7, 8, 14, 15, 21, 22, 28, 29)
        assertEquals(30, p.days.size)
        weekend.forEach { assertEquals("休假", p.days[it]) }
        assertEquals("早班", p.days[d(11, 2)])
        assertEquals("早班", p.days[d(11, 30)])
    }

    @Test fun monthPrefixAppliesToWeekdays() {
        val p = parse("12月：每週六日休假，其他晚班")
        assertEquals(31, p.days.size)
        assertEquals("休假", p.days[d(12, 5)])
        assertEquals("休假", p.days[d(12, 6)])
        assertEquals("晚班", p.days[d(12, 7)])
        assertTrue(p.problems.toString(), p.problems.isEmpty())
    }

    @Test fun explicitMonthDay() {
        assertEquals((1..5).map { d(12, it) }.associateWith { "夜班" }, parse("12月1號到5號夜班").days)
        assertEquals((28..30).map { d(11, it) }.plus((1..2).map { d(12, it) }).associateWith { "夜班" }, parse("11/28-12/2 大夜").days)
    }

    @Test fun chineseNumerals() {
        assertEquals(nov(21, 22, 23).associateWith { "中班" }, parse("二十一號到二十三號中班").days)
        assertEquals(mapOf(d(12, 3) to "早班"), parse("十二月三號早班").days)
    }

    @Test fun relativeDays() {
        assertEquals(mapOf(d(11, 21) to "休假"), parse("明天休息").days)
        assertEquals(mapOf(d(11, 22) to "夜班"), parse("後天夜班").days)
    }

    @Test fun severalShiftsInOneSentence() {
        assertEquals(mapOf(d(11, 21) to "早班", d(11, 22) to "晚班", d(11, 23) to "夜班"), parse("21早班22晚班23夜班").days)
    }

    @Test fun datesThenShiftInNextClause() {
        assertEquals(nov(21, 22, 23).associateWith { "早班" }, parse("21、22、23，早班").days)
        assertEquals(nov(21, 22, 23, 24, 25).associateWith { "早班" }, parse("早班是 21 到 25 號").days)
    }

    @Test fun nextYearMonth() {
        assertEquals(mapOf(d(1, 5, 2027) to "早班"), parse("1/5 早班").days)
    }

    @Test fun timesAreNotDays() {
        assertEquals(mapOf(d(11, 25) to "早班"), parse("25號早班 8點上班").days)
    }

    @Test fun laterOverridesEarlier() {
        val p = parse("21到25早班，23休假")
        assertEquals("休假", p.days[d(11, 23)])
        assertEquals("早班", p.days[d(11, 24)])
    }

    @Test fun untilMonthEnd() {
        assertEquals((25..30).map { d(11, it) }.associateWith { "晚班" }, parse("25號到月底晚班").days)
    }

    @Test fun problems() {
        assertTrue(parse("早班").problems.single().contains("早班"))
        assertTrue(parse("哈囉").problems.single().contains("看不懂"))
        assertTrue(parse("21號").problems.isNotEmpty())
    }

    @Test fun clearDates() {
        val p = parse("取消25號的夜班")
        assertEquals(setOf(d(11, 25)), p.clears)
        assertTrue(p.days.isEmpty())
        assertTrue(p.problems.isEmpty())
        assertEquals(nov(21, 22, 23).toSet(), parse("清除21到23號").clears)
    }

    @Test fun clearWholeMonth() {
        assertEquals(30, parse("清除這個月的班").clears.size)
        assertEquals(31, parse("刪除12月的排班").clears.size)
    }

    @Test fun clearThenSet() {
        val p = parse("取消25號，26號早班")
        assertEquals(setOf(d(11, 25)), p.clears)
        assertEquals(mapOf(d(11, 26) to "早班"), p.days)
        // 後面又排班的日子不清除
        val q = parse("清除25到26號，26號晚班")
        assertEquals(setOf(d(11, 25)), q.clears)
        assertEquals(mapOf(d(11, 26) to "晚班"), q.days)
    }

    @Test fun clearNeedsDates() {
        assertTrue(parse("取消").problems.isNotEmpty())
    }
}
