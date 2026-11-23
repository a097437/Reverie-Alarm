package com.reverie.alarm

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ============================================================================
// 工具 → 小工具：四種桌面小工具（2×2、4×2、5×2、5×5）的預覽（樣子和桌面上的一樣）與一鍵加到桌面
// 實際的小工具在 ShiftWidget.kt（RemoteViews）；這裡用 Compose 畫出相同的樣子
// ============================================================================

private enum class WidgetKind(val size: String, val title: String, val sub: String) {
    MONTH("5×5", "整月班表", "一整個月的班、今天和明天，還有這個月每種班幾天"),
    WEEKS("5×2", "兩週班表", "兩週的班一眼看完，可以往前、往後翻頁"),
    MEDIUM("4×2", "今日班表", "今天和明天的班，加上下一個鬧鐘"),
    SMALL("2×2", "今天的班", "小小一格：今天的班與下一個鬧鐘");

    fun providerClass(): Class<*> = when (this) {
        MONTH -> ShiftWidgetMonthProvider::class.java
        WEEKS -> ShiftWidgetWeeksProvider::class.java
        MEDIUM -> ShiftWidgetProvider::class.java
        SMALL -> ShiftWidgetSmallProvider::class.java
    }
}

private val W_TEXT = Color(0xFFF1F5F9)
private val W_MUTED = Color(0xFF94A3B8)
private val W_ROSE = Color(0xFFFB7185)
private val W_BLUE = Color(0xFF60A5FA)
private val W_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun WidgetsPreviewScreen(
    events: List<ShiftEvent>,
    holidayPolicy: HolidayPolicy,
    weekStartsOnMonday: Boolean,
    nextAlarm: ShiftAlarm?,
    theme: ThemePalette,
    effectiveAccent: Color,
    showTitle: Boolean = true
) {
    val context = LocalContext.current
    val today = LocalDate.now()

    // 桌面上各有幾個（回到這頁時重新檢查）
    var pinned by remember { mutableStateOf(mapOf<WidgetKind, Int>()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        fun check() {
            val mgr = AppWidgetManager.getInstance(context)
            pinned = WidgetKind.entries.associateWith { k ->
                runCatching { mgr.getAppWidgetIds(ComponentName(context, k.providerClass())).size }.getOrDefault(0)
            }
        }
        check()
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) check() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // 小米／紅米的桌面還要另外開「桌面快捷方式」權限，沒開的話按了不會有任何反應
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val isXiaomi = remember { Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") } }

    fun countOf(kind: WidgetKind) = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, kind.providerClass())).size
    }.getOrDefault(0)

    fun requestPin(kind: WidgetKind) {
        val mgr = AppWidgetManager.getInstance(context)
        if (mgr.isRequestPinAppWidgetSupported) {
            val before = countOf(kind)
            runCatching { mgr.requestPinAppWidget(ComponentName(context, kind.providerClass()), null, null) }
                .onSuccess {
                    // 小米沒開「桌面快捷方式」權限時，按了完全沒反應：3 秒後還沒加上去才提醒（成功時不打擾）
                    if (isXiaomi) scope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (countOf(kind) <= before) AppToast.show(tr("沒看到詢問視窗？小米手機要先開「桌面快捷方式」權限，見下方說明"))
                    }
                }
                .onFailure { AppToast.show(tr("這個桌面不支援一鍵新增，請照下方步驟手動加入")) }
        } else {
            AppToast.show(tr("這個桌面不支援一鍵新增，請照下方步驟手動加入"))
        }
    }

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showTitle) Text(tr("桌面小工具"), fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                Text(tr("不用打開 App，在手機桌面就看得到班表和下一個鬧鐘。有四種大小可以選。"), fontSize = 11.sp, color = theme.textMuted, lineHeight = 16.sp)
            }
        }

        WidgetKind.entries.forEach { kind ->
            item(key = kind.name) {
                WebCard(theme, radius = 26.dp, padding = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 預覽：放在像桌布的背景上
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(Brush.linearGradient(listOf(effectiveAccent.copy(alpha = 0.55f), Color(0xFF1E293B), Color(0xFF0F172A))))
                                .padding(horizontal = 14.dp, vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (kind) {
                                WidgetKind.MONTH -> PreviewMonth(events, holidayPolicy, weekStartsOnMonday, nextAlarm, effectiveAccent, today)
                                WidgetKind.WEEKS -> PreviewWeeks(events, holidayPolicy, weekStartsOnMonday, nextAlarm, effectiveAccent, today)
                                WidgetKind.MEDIUM -> PreviewMedium(events, holidayPolicy, nextAlarm, today)
                                WidgetKind.SMALL -> PreviewSmall(events, holidayPolicy, nextAlarm, today)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr(kind.title), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                                    Spacer(Modifier.width(6.dp))
                                    WebPill(kind.size, theme.accent, fontSize = 10.sp)
                                }
                                val n = pinned[kind] ?: 0
                                Text(
                                    if (n > 0) tr("桌面上有 {0} 個・會自動更新", n) else tr(kind.sub),
                                    fontSize = 11.sp, color = if (n > 0) EMERALD.readable() else theme.textMuted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            WebButton(
                                if ((pinned[kind] ?: 0) > 0) tr("再加一個") else tr("加到桌面"), theme,
                                filled = (pinned[kind] ?: 0) == 0, icon = Tabler.Pin, fontSize = 12.sp, vertical = 9.dp
                            ) { requestPin(kind) }
                        }
                    }
                }
            }
        }

        // 手動加入的步驟
        item {
            WebCard(theme, radius = 24.dp, padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("按了沒反應？也可以手動加入"), fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                    listOf(
                        tr("回到手機桌面，長按空白的地方"),
                        tr("點選「小工具」（有些手機叫「Widgets」）"),
                        tr("找到 Reverie Alarm，選 2×2、4×2、5×2 或 5×5 拖到桌面")
                    ).forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(24.dp).clip(CircleShape).background(theme.bgElevated).border(1.dp, theme.borderColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Black, color = theme.accentText) }
                            Spacer(Modifier.width(10.dp))
                            Text(step, fontSize = 13.sp, color = theme.textMain.copy(alpha = 0.9f), lineHeight = 18.sp)
                        }
                    }
                    if (isXiaomi) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(AMBER.copy(alpha = 0.10f))
                                .border(1.dp, AMBER.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconText(
                                tr("⚠️ 小米／紅米手機：按「加到桌面」沒反應時，到「應用程式資訊 → 其他權限」開啟「桌面快捷方式」"),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AMBER.readable(), lineHeight = 17.sp
                            )
                            WebButton(tr("打開應用程式資訊"), theme, filled = false, icon = Tabler.Settings, fontSize = 12.sp, vertical = 8.dp) { openAppSettings() }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 預覽（和 ShiftWidget.kt 的版面、文字一致）
// ---------------------------------------------------------------------------

private fun describe(s: ShiftEvent?, d: LocalDate, policy: HolidayPolicy): String {
    val holiday = policy.holidayOn(d)
    val base = when {
        s == null && policy.isDayOff(d) -> tr("國定假日放假")
        s == null -> tr("尚未排班")
        s.type == ShiftType.OFF -> tr("{0} · 全天休假", tr(s.title))
        else -> "${tr(s.title)} · ${s.startTime.format(W_HM)} - ${s.endTime.format(W_HM)}"
    }
    return if (holiday != null) "$base · ${tr(holiday.short)}" else base
}

private fun dayWord(d: LocalDate, today: LocalDate) = when (d) {
    today -> tr("今天")
    today.plusDays(1) -> tr("明天")
    else -> "${d.monthValue}/${d.dayOfMonth}"
}

private fun alarmShort(a: ShiftAlarm) = when (a.stage) {
    AlarmStage.WAKE -> tr("起床")
    AlarmStage.LEAVE -> tr("出門")
    AlarmStage.CUSTOM -> trLabel(a.label)
}

@Composable
private fun WidgetFrame(width: Dp?, height: Dp, glass: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(22.dp))
            .background(if (glass) Color(0xD9131A2A) else Color(0xF0131A2A))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
            .padding(13.dp)
    ) { content() }
}

@Composable
private fun PreviewSmall(events: List<ShiftEvent>, policy: HolidayPolicy, next: ShiftAlarm?, today: LocalDate) {
    val s = events.firstOrNull { it.date == today }
    val holiday = policy.holidayOn(today)
    WidgetFrame(156.dp, 156.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${today.monthValue}/${today.dayOfMonth} ${I18n.weekday(today.dayOfWeek)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = W_MUTED, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(s?.type?.accentDot ?: W_MUTED))
        }
        Text(
            when { s != null -> shortShiftName(s.title); holiday != null -> tr(holiday.short); else -> tr("未排班") },
            fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, lineHeight = 24.sp,
            color = when { s != null -> s.type.badgeText; holiday != null -> Color(0xFFFDA4AF); else -> Color(0xFFCBD5E1) },
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            when {
                s != null && s.type == ShiftType.OFF -> holiday?.let { tr(it.name) } ?: tr("全天休假")
                s != null -> "${s.startTime.format(W_HM)} – ${s.endTime.format(W_HM)}"
                holiday != null -> if (policy.isDayOff(today)) tr("國定假日放假") else tr("國定假日")
                else -> tr("點一下打開 App 排班")
            },
            fontSize = 12.sp, color = Color(0xFFCBD5E1), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        Text(next?.let { "${dayWord(it.date, today)} · ${alarmShort(it)}" } ?: tr("沒有排定的鬧鐘"), fontSize = 10.sp, color = W_MUTED, maxLines = 1)
        Text(next?.triggerTime?.format(W_HM) ?: "--:--", fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = W_TEXT)
    }
}

@Composable
private fun PreviewMedium(events: List<ShiftEvent>, policy: HolidayPolicy, next: ShiftAlarm?, today: LocalDate) {
    val s = events.firstOrNull { it.date == today }
    val tm = events.firstOrNull { it.date == today.plusDays(1) }
    WidgetFrame(null, 150.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reverie Alarm", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = W_MUTED, modifier = Modifier.weight(1f))
            Text("${today.monthValue}/${today.dayOfMonth} ${I18n.weekday(today.dayOfWeek)}", fontSize = 11.sp, color = W_MUTED)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(s?.type?.accentDot ?: W_MUTED))
            Spacer(Modifier.width(7.dp))
            Text(describe(s, today, policy), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = W_TEXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(tr("明日：") + describe(tm, today.plusDays(1), policy), fontSize = 11.sp, color = W_MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(next?.triggerTime?.format(W_HM) ?: "--:--", fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = W_TEXT)
            Spacer(Modifier.width(10.dp))
            Text(
                next?.let { "${dayWord(it.date, today)} · ${trLabel(it.label)}" } ?: tr("目前沒有排定的鬧鐘"),
                fontSize = 11.sp, color = Color(0xFFCBD5E1), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp
            )
        }
    }
}

/** 5×2、5×5 共用的標題列：月份、兩行副標、下一個鬧鐘、上一頁／下一頁 */
@Composable
private fun PreviewGridHeader(month: String, sub1: String, sub2: String, next: ShiftAlarm?, today: LocalDate, big: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            month, fontSize = if (big) 26.sp else 22.sp, fontWeight = FontWeight.Black, color = Color(0xFFF8FAFC),
            lineHeight = if (big) 28.sp else 24.sp, maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(sub1, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE2E8F0), lineHeight = 14.sp, maxLines = 1)
            Text(sub2, fontSize = 10.sp, color = W_MUTED, lineHeight = 12.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 10.dp)) {
            Text(
                next?.triggerTime?.format(W_HM) ?: "--:--", fontSize = if (big) 16.sp else 15.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = Color(0xFFF8FAFC), lineHeight = 18.sp
            )
            Text(next?.let { "${dayWord(it.date, today)} · ${alarmShort(it)}" } ?: tr("沒有排定的鬧鐘"), fontSize = 9.sp, color = W_MUTED, lineHeight = 11.sp)
        }
        listOf("‹", "›").forEachIndexed { i, ch ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(if (big) 30.dp else 28.dp).clip(CircleShape).background(Color(0x14FFFFFF)).border(1.dp, Color(0x40FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text(ch, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = W_TEXT) }
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 5.dp).height(1.dp).background(Color(0x26FFFFFF)))
}

@Composable
private fun PreviewWeekHeads(firstDow: DayOfWeek) {
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until 7) {
            val dow = firstDow.plus(i.toLong())
            Text(
                I18n.weekdayNarrow(dow), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = when (dow) { DayOfWeek.SUNDAY -> W_ROSE; DayOfWeek.SATURDAY -> W_BLUE; else -> W_MUTED },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 一個日期格（和 ShiftWidget.kt 的 DayCells.draw 規則相同） */
@Composable
private fun RowScope.PreviewDayCell(
    d: LocalDate, s: ShiftEvent?, holiday: Holiday?, today: LocalDate, todayBg: Color, inScope: Boolean, markNewMonth: Boolean
) {
    val isToday = d == today
    val past = d.isBefore(today)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isToday -> todayBg
                    s != null && inScope -> s.type.accentDot.copy(alpha = if (past) 0.13f else 0.24f)
                    else -> Color.Transparent
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "${d.dayOfMonth}", fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp,
            color = when {
                isToday -> Color.White
                !inScope -> Color(0xFF475569)
                past -> Color(0xFF64748B)
                holiday != null || d.dayOfWeek == DayOfWeek.SUNDAY -> W_ROSE
                d.dayOfWeek == DayOfWeek.SATURDAY -> W_BLUE
                else -> W_TEXT
            }
        )
        val newMonth = markNewMonth && d.dayOfMonth == 1 && s == null && holiday == null
        val label = when {
            s != null -> shortShiftName(s.title)
            holiday != null -> tr(holiday.short)
            newMonth -> tr("{0}月", d.monthValue)
            else -> ""
        }
        if (label.isNotEmpty()) {
            val c = when {
                isToday -> Color.White
                s != null -> s.type.badgeText
                newMonth -> W_MUTED
                else -> Color(0xFFFDA4AF)
            }
            Text(
                label, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (inScope || isToday) c else c.copy(alpha = 0.5f)
            )
        }
    }
}

private fun weekStartOf(d: LocalDate, firstDow: DayOfWeek) = d.minusDays(((d.dayOfWeek.value - firstDow.value + 7) % 7).toLong())

private fun countWorkOff(days: List<LocalDate>, byDate: Map<LocalDate, ShiftEvent>, policy: HolidayPolicy): Pair<Int, Int> =
    days.count { byDate[it]?.type?.isWorkShift == true } to
        days.count { d -> byDate[d]?.type == ShiftType.OFF || (byDate[d] == null && policy.isDayOff(d)) }

@Composable
private fun PreviewWeeks(
    events: List<ShiftEvent>, policy: HolidayPolicy, weekMonday: Boolean, next: ShiftAlarm?, accent: Color, today: LocalDate
) {
    val firstDow = if (weekMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val start = weekStartOf(today, firstDow)
    val byDate = events.associateBy { it.date }
    val days = (0 until 14).map { start.plusDays(it.toLong()) }
    val (work, off) = countWorkOff(days, byDate, policy)
    val todayBg = accent.darkenForWhiteText(3.0)

    WidgetFrame(null, 190.dp, glass = true) {
        PreviewGridHeader(tr("{0}月", today.monthValue), I18n.weekday(today.dayOfWeek), tr("上班 {0}・休 {1}", work, off), next, today, big = false)
        PreviewWeekHeads(firstDow)
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0 until 7) {
                    val d = days[row * 7 + col]
                    PreviewDayCell(d, byDate[d], policy.holidayOn(d), today, todayBg, inScope = true, markNewMonth = true)
                }
            }
        }
    }
}

@Composable
private fun PreviewMonth(
    events: List<ShiftEvent>, policy: HolidayPolicy, weekMonday: Boolean, next: ShiftAlarm?, accent: Color, today: LocalDate
) {
    val firstDow = if (weekMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val ym = java.time.YearMonth.from(today)
    val gridStart = weekStartOf(ym.atDay(1), firstDow)
    val lead = (ym.atDay(1).toEpochDay() - gridStart.toEpochDay()).toInt()
    val rows = (lead + ym.lengthOfMonth() + 6) / 7
    val byDate = events.associateBy { it.date }
    val monthDays = (1..ym.lengthOfMonth()).map { ym.atDay(it) }
    val (work, off) = countWorkOff(monthDays, byDate, policy)
    val todayBg = accent.darkenForWhiteText(3.0)
    val tm = today.plusDays(1)
    val byType = monthDays.mapNotNull { byDate[it]?.type }.groupingBy { it }.eachCount()
    val holidayWork = DayResolver.holidayWorkDays(ym, events, policy)

    WidgetFrame(null, 400.dp, glass = true) {
        PreviewGridHeader(tr("{0}月", ym.monthValue), I18n.weekday(today.dayOfWeek), tr("上班 {0}・休 {1}", work, off), next, today, big = true)
        PreviewWeekHeads(firstDow)
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0 until 7) {
                    val d = gridStart.plusDays((row * 7 + col).toLong())
                    PreviewDayCell(d, byDate[d], policy.holidayOn(d), today, todayBg, inScope = java.time.YearMonth.from(d) == ym, markNewMonth = false)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 6.dp).height(1.dp).background(Color(0x26FFFFFF)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(byDate[today]?.type?.accentDot ?: W_MUTED))
            Spacer(Modifier.width(6.dp))
            Text(
                tr("今天 ") + describe(byDate[today], today, policy), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = W_TEXT,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            val t = byDate[tm]
            Text(
                tr("明天 ") + when {
                    t == null && policy.isDayOff(tm) -> tr("放假")
                    t == null -> tr("未排班")
                    t.type == ShiftType.OFF -> tr(t.title)
                    else -> "${tr(t.title)} ${t.startTime.format(W_HM)}"
                },
                fontSize = 11.sp, color = W_MUTED, maxLines = 1
            )
        }
        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(ShiftType.EARLY, ShiftType.EVENING, ShiftType.NIGHT, ShiftType.OFF)
                    .filter { (byType[it] ?: 0) > 0 }
                    .joinToString("・") { "${tr(it.displayName)} ${byType[it]}" }
                    .ifEmpty { tr("這個月還沒排班") },
                fontSize = 11.sp, color = Color(0xFFCBD5E1), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (holidayWork > 0) {
                Spacer(Modifier.width(8.dp))
                Text(tr("國定假日上班 {0} 天", holidayWork), fontSize = 10.sp, color = Color(0xFFFDA4AF), maxLines = 1)
            }
        }
    }
}
