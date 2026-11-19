package com.reverie.alarm

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

// ============================================================================
// 快速排班：把一句話（打字或語音）轉成「哪幾天排什麼班」
//   全部在手機上解析，不會送到任何伺服器。
//   例：「21 到 25 號早班，26 號休假」「下週一三五晚班」「這個月週六日休假，其他早班」
// ============================================================================

data class QuickPlan(
    /** 日期 → 班別名稱（和班別模板的 title 相同） */
    val days: Map<LocalDate, String>,
    /** 看不懂或缺資料的片段，顯示給使用者看 */
    val problems: List<String>
)

object QuickScheduleParser {

    private const val WD = "[一二三四五六日天]"
    private const val WEEK = "(?:週|星期|禮拜|礼拜|周)"
    private const val TO = "(?:到|至|~|-|－|～)"

    /**
     * @param shiftNames 班別模板名稱（例如 早班、晚班、夜班、休假、中班）
     * @param offName 休假模板的名稱，「休息、放假、off」都會對到它
     * @param baseMonth 沒講月份時用哪個月（日曆目前顯示的月份）
     */
    fun parse(input: String, shiftNames: List<String>, offName: String?, today: LocalDate, baseMonth: YearMonth): QuickPlan {
        val aliases = buildAliases(shiftNames, offName)
        val text = normalize(input)
        val explicit = LinkedHashMap<LocalDate, String>()
        val rest = mutableListOf<Pair<YearMonth, String>>() // 「其他」：最後再補沒排到的日子
        val problems = mutableListOf<String>()
        var ctx = baseMonth
        var pendingDates: List<LocalDate> = emptyList()

        val clauses = text.split(Regex("[，,；;。\\n]|然後|另外")).map { it.trim() }.filter { it.isNotEmpty() }
        for (clause in clauses) {
            // 依班別名稱把一句切成幾段：「21早班22晚班」→ [21 早班][22 晚班]
            val hits = findShifts(clause, aliases)
            if (hits.isEmpty()) {
                val r = parseDates(clause, today, ctx)
                ctx = r.ctx
                if (r.dates.isNotEmpty() || r.rest) pendingDates = pendingDates + r.dates
                else if (!r.monthOnly) problems += "看不懂「$clause」"
                continue
            }
            val pieces = mutableListOf<Pair<String, String>>() // 日期文字 to 班別
            var cursor = 0
            hits.forEachIndexed { i, h ->
                var seg = clause.substring(cursor, h.start)
                // 只有一個班別時，班別後面的日期也算（「早班是 21 到 25 號」）
                if (hits.size == 1) seg += " " + clause.substring(h.end)
                else if (i == hits.lastIndex && seg.isBlank()) seg = clause.substring(h.end)
                pieces += seg to h.name
                cursor = h.end
            }
            for ((seg, name) in pieces) {
                val r = parseDates(seg, today, ctx)
                ctx = r.ctx
                val dates = r.dates.ifEmpty { pendingDates }
                pendingDates = emptyList()
                when {
                    r.rest -> rest += ctx to name
                    dates.isEmpty() -> problems += "「$name」沒有說是哪幾天"
                    else -> dates.forEach { explicit.remove(it); explicit[it] = name }
                }
            }
        }
        if (pendingDates.isNotEmpty()) problems += "有日期但沒說要排什麼班"

        val result = LinkedHashMap(explicit)
        for ((month, name) in rest) {
            for (d in 1..month.lengthOfMonth()) {
                val date = month.atDay(d)
                if (date !in result) result[date] = name
            }
        }
        return QuickPlan(result.toSortedMap(), problems)
    }

    // ---------------- 班別名稱 ----------------

    private data class Hit(val start: Int, val end: Int, val name: String)

    private fun buildAliases(names: List<String>, offName: String?): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        names.forEach { list += it.lowercase() to it }
        if (offName != null) {
            listOf("休假", "休息", "放假", "排休", "公休", "例假", "補休", "off", "休").forEach { list += it to offName }
        }
        fun alias(alias: String, target: String) { if (target in names && names.none { it == alias }) list += alias to target }
        alias("小夜班", "晚班"); alias("小夜", "晚班")
        alias("大夜班", "夜班"); alias("大夜", "夜班")
        alias("白班", "早班")
        return list.sortedByDescending { it.first.length }
    }

    private fun findShifts(s: String, aliases: List<Pair<String, String>>): List<Hit> {
        val hits = mutableListOf<Hit>()
        var i = 0
        while (i < s.length) {
            val m = aliases.firstOrNull { (a, _) -> s.startsWith(a, i) }
            if (m != null) {
                hits += Hit(i, i + m.first.length, m.second)
                i += m.first.length
            } else i++
        }
        return hits
    }

    // ---------------- 日期 ----------------

    private data class DateResult(val dates: List<LocalDate>, val ctx: YearMonth, val rest: Boolean, val monthOnly: Boolean = false)

    private fun parseDates(segment: String, today: LocalDate, startCtx: YearMonth): DateResult {
        var s = segment
        var ctx = startCtx
        var monthMentioned = false
        val out = LinkedHashSet<LocalDate>()

        // 年份（2026年、2026/）不是日期；時間（8點、08:30）也不是
        s = s.replace(Regex("\\d{4}\\s*[年/-]\\s*"), "")
        s = stripTimes(s)

        // 這個月／下個月
        if (Regex("下個?月").containsMatchIn(s)) { ctx = YearMonth.from(today).plusMonths(1); monthMentioned = true; s = s.replace(Regex("下個?月"), " ") }
        if (Regex("這個?月|本月").containsMatchIn(s)) { ctx = YearMonth.from(today); monthMentioned = true; s = s.replace(Regex("這個?月|本月"), " ") }

        // 只講月份（12月、十二月份）要先處理，後面的「週六日」才知道是哪個月
        s = Regex("(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*月份?(?!\\s*[\\d一二三四五六七八九十])").replace(s) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: cn(m.groupValues[1])
            val ym = n?.let { yearMonthFor(it, today) }
            if (ym != null) { ctx = ym; monthMentioned = true; " " } else m.value
        }

        // 星期：先處理範圍（週一到週五），再處理清單（週一三五），週末、平日
        s = Regex("(每個?|這個?|本|下下個?|下個?)?$WEEK($WD)\\s*$TO\\s*$WEEK?($WD)").replace(s) { m ->
            val a = wd(m.groupValues[2]); val b = wd(m.groupValues[3])
            val days = if (a <= b) (a..b).toList() else ((a..7) + (1..b)).toList()
            out += weekdays(m.groupValues[1], days, today, ctx, monthMentioned || segment.contains("每")); " "
        }
        s = Regex("(每個?|這個?|本|下下個?|下個?)?(週末|平日)").replace(s) { m ->
            val days = if (m.groupValues[2] == "週末") listOf(6, 7) else (1..5).toList()
            out += weekdays(m.groupValues[1].ifEmpty { "每" }, days, today, ctx, true); " "
        }
        s = Regex("(每個?|這個?|本|下下個?|下個?)?$WEEK($WD(?:\\s*[、和跟及與]?\\s*$WEEK?$WD)*)").replace(s) { m ->
            val days = Regex(WD).findAll(m.groupValues[2]).map { wd(it.value) }.toList()
            out += weekdays(m.groupValues[1], days, today, ctx, monthMentioned); " "
        }

        // 剩下的中文數字轉阿拉伯數字（二十一號 → 21號、十二月 → 12月）
        s = Regex("[零〇一二兩三四五六七八九十]+").replace(s) { cn(it.value)?.toString() ?: it.value }
        s = stripTimes(s)

        // 今天、明天、後天
        listOf("大後天" to 3L, "後天" to 2L, "明天" to 1L, "今天" to 0L).forEach { (w, n) ->
            if (s.contains(w)) { out += today.plusDays(n); s = s.replace(w, " ") }
        }

        // 月/日（可接範圍）：11/21、11月21號、11/21-25、11/21 到 11/25、11/21 到月底
        s = Regex("(\\d{1,2})\\s*[/月]\\s*(\\d{1,2})\\s*[號日]?(?:\\s*$TO\\s*(?:(\\d{1,2})\\s*[/月]\\s*)?(\\d{1,2}|月底)\\s*[號日]?)?").replace(s) { m ->
            val ym = yearMonthFor(m.groupValues[1].toInt(), today) ?: return@replace " "
            ctx = ym
            val from = ym.atDayOrNull(m.groupValues[2].toInt())
            val endRaw = m.groupValues[4]
            if (from != null) {
                if (endRaw.isEmpty()) out += from
                else {
                    val endYm = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { yearMonthFor(it.toInt(), today) } ?: ym
                    val to = if (endRaw == "月底") endYm.atEndOfMonth() else endYm.atDayOrNull(endRaw.toInt())
                    if (to != null && !to.isBefore(from)) out += dateRange(from, to)
                }
            }
            " "
        }
        // 只講月份：「12月」
        s = Regex("(\\d{1,2})\\s*月").replace(s) { m ->
            yearMonthFor(m.groupValues[1].toInt(), today)?.let { ctx = it; monthMentioned = true }; " "
        }
        // 日的範圍：21 到 25 號、21-25、21 到月底
        s = Regex("(\\d{1,2})\\s*[號日]?\\s*$TO\\s*(\\d{1,2}|月底)\\s*[號日]?").replace(s) { m ->
            val from = ctx.atDayOrNull(m.groupValues[1].toInt())
            val to = if (m.groupValues[2] == "月底") ctx.atEndOfMonth() else ctx.atDayOrNull(m.groupValues[2].toInt())
            if (from != null && to != null && !to.isBefore(from)) out += dateRange(from, to)
            " "
        }
        // 單一的日：21號、21、22、23
        s = Regex("(\\d{1,2})\\s*[號日]?").replace(s) { m ->
            ctx.atDayOrNull(m.groupValues[1].toInt())?.let { out += it }; " "
        }
        // 整個月、每天
        if (Regex("整個?月|整月|每天|全部|天天").containsMatchIn(s) || (monthMentioned && out.isEmpty() && Regex("都").containsMatchIn(s))) {
            for (d in 1..ctx.lengthOfMonth()) out += ctx.atDay(d)
        }
        val isRest = Regex("其他|其餘|其它|剩下|其他天|剩餘").containsMatchIn(s)
        return DateResult(out.toList(), ctx, isRest && out.isEmpty(), monthOnly = monthMentioned && out.isEmpty())
    }

    private fun stripTimes(s: String) = s
        .replace(Regex("\\d{1,2}:\\d{2}"), " ")
        .replace(Regex("\\d{1,2}\\s*點(\\s*(\\d{1,2}\\s*分|半))?"), " ")

    /** 星期幾 → 日期。前綴：這週、下週、下下週；「每」或講了月份 → 這個月的每一個 */
    private fun weekdays(prefix: String, days: List<Int>, today: LocalDate, ctx: YearMonth, wholeMonth: Boolean): List<LocalDate> {
        val p = prefix.replace("個", "")
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when {
            p == "這" || p == "本" -> days.map { weekStart.plusDays(it - 1L) }
            p == "下" -> days.map { weekStart.plusWeeks(1).plusDays(it - 1L) }
            p == "下下" -> days.map { weekStart.plusWeeks(2).plusDays(it - 1L) }
            p == "每" || wholeMonth -> (1..ctx.lengthOfMonth()).map { ctx.atDay(it) }.filter { it.dayOfWeek.value in days }
            // 沒講哪一週：接下來最近的那一天（今天也算）
            else -> days.map { d -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(d))) }
        }.sorted()
    }

    private fun wd(c: String): Int = when (c) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; else -> 7
    }

    /** 中文數字（1～99）；看不懂回傳 null */
    private fun cn(s: String): Int? {
        val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (s.contains('十')) {
            val (a, b) = s.split('十', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val tens = if (a.isEmpty()) 1 else digit[a.singleOrNull() ?: return null] ?: return null
            val ones = if (b.isEmpty()) 0 else digit[b.singleOrNull() ?: return null] ?: return null
            return tens * 10 + ones
        }
        return s.map { digit[it] ?: return null }.joinToString("").toIntOrNull()
    }

    /** 全形轉半形、統一大小寫 */
    private fun normalize(s: String): String = buildString {
        s.forEach { c ->
            append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    in 'Ａ'..'Ｚ' -> 'a' + (c - 'Ａ')
                    in 'ａ'..'ｚ' -> 'a' + (c - 'ａ')
                    '／' -> '/'
                    '－', '—', '–' -> '-'
                    '　' -> ' '
                    '号' -> '號'
                    '礼' -> '禮'
                    else -> c.lowercaseChar()
                }
            )
        }
    }

    /** 月份 → 年月：已經過了兩個月以上的月份，當作明年 */
    private fun yearMonthFor(month: Int, today: LocalDate): YearMonth? {
        if (month !in 1..12) return null
        val ym = YearMonth.of(today.year, month)
        return if (ym.isBefore(YearMonth.from(today).minusMonths(2))) ym.plusYears(1) else ym
    }

    private fun YearMonth.atDayOrNull(d: Int): LocalDate? = if (d in 1..lengthOfMonth()) atDay(d) else null

    private fun dateRange(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.take(62).toList()
}
