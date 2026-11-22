package com.reverie.alarm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ============================================================================
// 週視圖／日視圖（Google 日曆風格）
//   - 左邊 0～24 點時間軸，班別依上下班時間畫成色塊；跨夜班拆成「今天到 24:00」和「隔天 0:00 起」兩段
//   - 鬧鐘標在響鈴時間（起床紅、出門琥珀），今天有一條紅色「現在」線
//   - 休假放在最上面的「全天」列
//   - 左右滑動切換上一週／下一週（上一天／下一天），上下捲動看一整天
//   - 點日期標題切到那天的日視圖；點時間格或色塊打開那天的編輯
// ============================================================================

private const val CENTER_PAGE = 10_000
private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** 時間軸上的一塊班別（跨夜班會拆成兩塊） */
private data class TimeBlock(
    val event: ShiftEvent,
    val startMin: Int,
    val endMin: Int,
    /** 承接前一天的跨夜班 */
    val continued: Boolean,
    /** 會延續到隔天 */
    val continues: Boolean,
    var lane: Int = 0,
    var lanes: Int = 1
)

private fun minutesOf(t: java.time.LocalTime) = t.hour * 60 + t.minute

private fun blocksFor(date: LocalDate, events: List<ShiftEvent>): List<TimeBlock> {
    val out = mutableListOf<TimeBlock>()
    events.filter { it.date == date && it.type != ShiftType.OFF }.forEach { ev ->
        val s = minutesOf(ev.startTime); val e = minutesOf(ev.endTime)
        out += if (e > s) TimeBlock(ev, s, e, continued = false, continues = false)
        else TimeBlock(ev, s, 24 * 60, continued = false, continues = e > 0)
    }
    events.filter { it.date == date.minusDays(1) && it.type != ShiftType.OFF }.forEach { ev ->
        val s = minutesOf(ev.startTime); val e = minutesOf(ev.endTime)
        if (e <= s && e > 0) out += TimeBlock(ev, 0, e, continued = true, continues = false)
    }
    // 重疊的色塊並排（很少見，但資料可能這樣）
    val sorted = out.sortedBy { it.startMin }
    val laneEnds = mutableListOf<Int>()
    sorted.forEach { b ->
        val lane = laneEnds.indexOfFirst { it <= b.startMin }.let { if (it == -1) { laneEnds += 0; laneEnds.lastIndex } else it }
        laneEnds[lane] = b.endMin
        b.lane = lane
    }
    sorted.forEach { it.lanes = laneEnds.size.coerceAtLeast(1) }
    return sorted
}

private fun alarmColor(stage: AlarmStage): Color = when (stage) {
    AlarmStage.WAKE -> ROSE
    AlarmStage.LEAVE -> AMBER
    AlarmStage.CUSTOM -> Color(0xFF38BDF8)
}

/** 每分鐘更新一次的「現在」 */
@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = LocalDateTime.now()
        }
    }
    return now
}

/**
 * @param weekMode true＝週視圖（7 欄）、false＝日視圖（1 欄）
 * @param onDateChange 滑到別的週／日時回報（App 據此更新選取日期與頂部月份）
 * @param onOpenDay 週視圖點日期標題 → 切到那天的日視圖
 * @param onEditDate 點時間格或色塊 → 編輯那天（唯讀時只會選取日期）
 */
@Composable
fun TimeGridCalendar(
    weekMode: Boolean,
    selectedDate: LocalDate,
    weekStartsOnMonday: Boolean,
    events: List<ShiftEvent>,
    alarms: List<ShiftAlarm>,
    holidayPolicy: HolidayPolicy,
    theme: ThemePalette,
    onDateChange: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onEditDate: (LocalDate) -> Unit
) {
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    val today = now.toLocalDate()
    val firstDow = if (weekStartsOnMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    fun weekStartOf(d: LocalDate): LocalDate = d.minusDays(((d.dayOfWeek.value - firstDow.value + 7) % 7).toLong())

    // 每一頁＝一週或一天；以第一次打開時的日期為基準
    val anchor = remember(weekMode, weekStartsOnMonday) { if (weekMode) weekStartOf(selectedDate) else selectedDate }
    fun pageStart(page: Int): LocalDate =
        if (weekMode) anchor.plusWeeks((page - CENTER_PAGE).toLong()) else anchor.plusDays((page - CENTER_PAGE).toLong())
    fun pageOf(d: LocalDate): Int =
        CENTER_PAGE + (if (weekMode) ChronoUnit.WEEKS.between(anchor, weekStartOf(d)) else ChronoUnit.DAYS.between(anchor, d)).toInt()

    val pager = rememberPagerState(initialPage = pageOf(selectedDate)) { CENTER_PAGE * 2 }
    // 滑到別頁 → 回報日期（週視圖保留原本是星期幾）
    LaunchedEffect(pager.settledPage) {
        val start = pageStart(pager.settledPage)
        val d = if (weekMode) start.plusDays(((selectedDate.dayOfWeek.value - firstDow.value + 7) % 7).toLong()) else start
        if (d != selectedDate) onDateChange(d)
    }
    // 外部改了日期（頂部的「今」、月份箭頭）→ 跟著翻頁
    LaunchedEffect(selectedDate) {
        val target = pageOf(selectedDate)
        if (target != pager.currentPage && !pager.isScrollInProgress) pager.scrollToPage(target)
    }

    val hourH: Dp = if (weekMode) 52.dp else 64.dp
    val gutter = 44.dp
    val density = LocalDensity.current
    // 各頁共用同一個上下捲動位置（翻頁時停在同一個時段），一開始捲到現在或最早的班前一小時
    var sharedScroll by remember(weekMode) {
        val days = if (weekMode) (0..6).map { pageStart(pageOf(selectedDate)).plusDays(it.toLong()) } else listOf(selectedDate)
        val startHour = if (today in days) now.hour - 1
        else days.flatMap { blocksFor(it, events) }.minOfOrNull { it.startMin / 60 }?.minus(1) ?: 6
        mutableIntStateOf(with(density) { (hourH * startHour.coerceIn(0, 20)).roundToPx() })
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------- 導覽列：‹ 日期範圍 › 今天 ----------------
        val shownStart = pageStart(pager.currentPage)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Icon(
                Tabler.ChevronLeft, contentDescription = tr("上一頁"), tint = theme.textMain,
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }.padding(8.dp)
            )
            Text(
                if (weekMode) rangeLabel(shownStart, shownStart.plusDays(6)) else dayLabel(shownStart),
                fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Icon(
                Tabler.ChevronRight, contentDescription = tr("下一頁"), tint = theme.textMain,
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }.padding(8.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                tr("今天"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.accentText,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, theme.borderColor, RoundedCornerShape(10.dp))
                    .clickable { scope.launch { pager.animateScrollToPage(pageOf(today)) } }.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val start = pageStart(page)
            val days = if (weekMode) (0..6).map { start.plusDays(it.toLong()) } else listOf(start)
            val scroll = rememberScrollState(initial = sharedScroll)
            LaunchedEffect(scroll.value, pager.currentPage) { if (page == pager.currentPage) sharedScroll = scroll.value }
            Column(Modifier.fillMaxSize()) {
                DayHeaders(days, weekMode, today, selectedDate, events, holidayPolicy, gutter, theme, onOpenDay)
                AllDayRow(days, events, holidayPolicy, gutter, theme, onEditDate)
                HorizontalDivider(color = theme.borderColor)
                Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Row(Modifier.fillMaxWidth().height(hourH * 24)) {
                        HourGutter(hourH, gutter, theme)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            GridLines(days.size, hourH, theme)
                            Row(Modifier.fillMaxSize()) {
                                days.forEach { d ->
                                    DayColumn(
                                        date = d, compact = weekMode, events = events,
                                        alarms = alarms.filter { it.enabled && it.date == d },
                                        now = now, hourH = hourH, theme = theme,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        onEditDate = onEditDate
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun rangeLabel(a: LocalDate, b: LocalDate): String =
    if (I18n.isEn) "${I18n.yearMonth(java.time.YearMonth.from(a))} ${a.dayOfMonth} – ${if (a.month == b.month) "" else b.monthValue.toString() + "/"}${b.dayOfMonth}"
    else "${a.year}年${a.monthValue}月${a.dayOfMonth}日 – ${if (a.month == b.month) "" else "${b.monthValue}月"}${b.dayOfMonth}日"

private fun dayLabel(d: LocalDate): String =
    if (I18n.isEn) "${I18n.weekday(d.dayOfWeek)}, ${I18n.yearMonth(java.time.YearMonth.from(d))} ${d.dayOfMonth}"
    else "${d.year}年${d.monthValue}月${d.dayOfMonth}日 ${I18n.weekday(d.dayOfWeek)}"

@Composable
private fun DayHeaders(
    days: List<LocalDate>, weekMode: Boolean, today: LocalDate, selected: LocalDate, events: List<ShiftEvent>,
    holidayPolicy: HolidayPolicy, gutter: Dp, theme: ThemePalette, onOpenDay: (LocalDate) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(gutter))
        days.forEach { d ->
            val isToday = d == today
            val holiday = holidayPolicy.holidayOn(d)
            val weekendColor = when {
                holiday != null || d.dayOfWeek == DayOfWeek.SUNDAY -> ROSE.readable(4.5)
                d.dayOfWeek == DayOfWeek.SATURDAY -> Color(0xFF3B82F6).readable(4.5)
                else -> theme.textMuted
            }
            Column(
                horizontalAlignment = if (weekMode) Alignment.CenterHorizontally else Alignment.Start,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = weekMode) { onOpenDay(d) }.padding(vertical = 4.dp, horizontal = if (weekMode) 0.dp else 8.dp)
            ) {
                Text(
                    if (weekMode) I18n.weekdayNarrow(d.dayOfWeek) else I18n.weekday(d.dayOfWeek),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isToday) theme.accentText else weekendColor
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(top = 2.dp).size(if (weekMode) 30.dp else 40.dp).clip(CircleShape)
                        .background(if (isToday) theme.accent else Color.Transparent)
                        .border(if (!isToday && d == selected && weekMode) 1.5.dp else 0.dp, if (d == selected) theme.accent else Color.Transparent, CircleShape)
                ) {
                    Text(
                        "${d.dayOfMonth}", fontSize = if (weekMode) 15.sp else 20.sp, fontWeight = FontWeight.Black,
                        color = if (isToday) Color.White else theme.textMain
                    )
                }
                if (!weekMode) {
                    val status = DayResolver.resolve(d, events, holidayPolicy)
                    val ev = status.event
                    val line = when {
                        ev != null && ev.type == ShiftType.OFF -> tr("{0} · 全天休假", tr(ev.title))
                        ev != null -> "${tr(ev.title)} · ${ev.startTime.format(HM)}–${ev.endTime.format(HM)}"
                        status.kind == DayKind.HOLIDAY_OFF -> tr("國定假日放假")
                        else -> tr("尚未排班")
                    }
                    Text(
                        if (holiday != null) "$line · ${tr(holiday.name)}" else line,
                        fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AllDayRow(days: List<LocalDate>, events: List<ShiftEvent>, holidayPolicy: HolidayPolicy, gutter: Dp, theme: ThemePalette, onEditDate: (LocalDate) -> Unit) {
    val offs = days.map { d -> events.firstOrNull { it.date == d && it.type == ShiftType.OFF } }
    // 沒有排休假的國定假日：全天列顯示假日名稱（紅色，只是標示）
    val holidays = days.mapIndexed { i, d -> if (offs[i] == null) holidayPolicy.holidayOn(d) else null }
    if (offs.all { it == null } && holidays.all { it == null }) return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("全天"), fontSize = 10.sp, color = theme.textMuted, modifier = Modifier.width(gutter).padding(start = 6.dp))
        days.forEachIndexed { i, d ->
            Box(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                val ev = offs[i]
                val h = holidays[i]
                if (ev != null || h != null) {
                    Text(
                        if (ev != null) tr(ev.title) else tr(h!!.short),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (ev != null) ShiftType.OFF.accentDot.copy(alpha = 0.9f) else ROSE.darkenForWhiteText().copy(alpha = 0.9f))
                            .clickable { onEditDate(d) }.padding(horizontal = 4.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HourGutter(hourH: Dp, gutter: Dp, theme: ThemePalette) {
    Box(Modifier.width(gutter).fillMaxHeight()) {
        for (h in 1..23) {
            Text(
                "%02d:00".format(h), fontSize = 10.sp, color = theme.textMuted,
                modifier = Modifier.offset(y = hourH * h - 7.dp).padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun GridLines(columns: Int, hourH: Dp, theme: ThemePalette) {
    val line = theme.borderColor
    Canvas(Modifier.fillMaxSize()) {
        val hp = hourH.toPx()
        for (h in 0..24) drawLine(line, Offset(0f, h * hp), Offset(size.width, h * hp), strokeWidth = 1f)
        val cw = size.width / columns
        for (c in 0..columns) drawLine(line, Offset(c * cw, 0f), Offset(c * cw, size.height), strokeWidth = 1f)
    }
}

@Composable
private fun DayColumn(
    date: LocalDate, compact: Boolean, events: List<ShiftEvent>, alarms: List<ShiftAlarm>,
    now: LocalDateTime, hourH: Dp, theme: ThemePalette, modifier: Modifier, onEditDate: (LocalDate) -> Unit
) {
    val blocks = remember(date, events) { blocksFor(date, events) }
    val minH = hourH / 60
    BoxWithConstraints(modifier.clickable { onEditDate(date) }) {
        val colW = maxWidth
        // ---- 班別色塊 ----
        blocks.forEach { b ->
            val w = colW / b.lanes
            val color = b.event.type.accentDot
            Column(
                modifier = Modifier
                    .offset(x = w * b.lane, y = minH * b.startMin)
                    .width(w).height((minH * (b.endMin - b.startMin)).coerceAtLeast(18.dp))
                    .padding(horizontal = 1.5.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
                    .background(color.copy(alpha = if (b.continued) 0.55f else 0.88f))
                    .clickable { onEditDate(b.event.date) }
                    .padding(horizontal = if (compact) 3.dp else 8.dp, vertical = 3.dp)
            ) {
                Text(
                    (if (b.continued) "↳ " else "") + tr(b.event.title), fontSize = if (compact) 11.sp else 13.sp,
                    fontWeight = FontWeight.Black, color = Color.White, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp
                )
                val timeText = when {
                    b.continued -> tr("～{0}", b.event.endTime.format(HM))
                    b.continues -> if (compact) b.event.startTime.format(HM) else tr("{0}–隔日 {1}", b.event.startTime.format(HM), b.event.endTime.format(HM))
                    else -> if (compact) b.event.startTime.format(HM) else "${b.event.startTime.format(HM)}–${b.event.endTime.format(HM)}"
                }
                Text(timeText, fontSize = if (compact) 9.sp else 11.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                if (!compact && !b.continued && b.event.note.isNotBlank()) {
                    Text(b.event.note.lines().first(), fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // ---- 鬧鐘：響鈴時間的一條線＋小鈴鐺 ----
        alarms.forEach { al ->
            val y = minH * minutesOf(al.triggerTime)
            val c = alarmColor(al.stage)
            Box(Modifier.offset(y = y).fillMaxWidth().height(2.dp).background(c))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = 1.dp, y = y - (if (compact) 7.dp else 10.dp))
                    .clip(RoundedCornerShape(50)).background(c).padding(horizontal = if (compact) 3.dp else 7.dp, vertical = 1.dp)
            ) {
                Icon(Tabler.Bell, contentDescription = tr("鬧鐘"), tint = Color.White, modifier = Modifier.size(if (compact) 10.dp else 13.dp))
                if (!compact) {
                    Spacer(Modifier.width(4.dp))
                    Text("${al.triggerTime.format(HM)} ${tr(al.stage.label)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
            }
        }
        // ---- 現在時間 ----
        if (date == now.toLocalDate()) {
            val y = minH * (now.hour * 60 + now.minute)
            Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(ROSE))
            Box(Modifier.offset(x = (-4).dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(ROSE))
        }
    }
}
