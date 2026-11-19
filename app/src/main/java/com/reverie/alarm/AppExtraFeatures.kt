package com.reverie.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID

// ============================================================================
// 1. 檢視模式與莫蘭迪多主題色盤定義
// ============================================================================
enum class CalendarViewMode(val label: String) {
    MONTH("月曆"),
    WEEK("週視圖"),
    DAY("日時間軸"),
    YEAR("年總覽")
}

enum class MorandiTheme(
    val title: String,
    val bgBase: Color,
    val bgCard: Color,
    val bgElevated: Color,
    val borderColor: Color,
    val accent: Color,
    val textMain: Color,
    val textMuted: Color
) {
    SLATE_NIGHT(
        tr("靜謐岩藍 (預設深色)"),
        Color(0xFF0B0F19), Color(0xFF131A2A), Color(0xFF1B2438),
        Color(0xFF232E47), Color(0xFF5B7C99), Color(0xFFF1F5F9), Color(0xFF94A3B8)
    ),
    SAGE_FOREST(
        tr("鼠尾草深林"),
        Color(0xFF0D1412), Color(0xFF15201D), Color(0xFF1E2D29),
        Color(0xFF2A3E38), Color(0xFF6B9080), Color(0xFFF1F5F3), Color(0xFF94A8A0)
    ),
    WARM_ESPRESSO(
        tr("暖栗可可夜"),
        Color(0xFF161210), Color(0xFF211B18), Color(0xFF2C2420),
        Color(0xFF3E332D), Color(0xFFB08968), Color(0xFFF5F0EC), Color(0xFFA89990)
    ),
    MORANDI_MIST(
        tr("晨霧莫蘭迪 (淺色護眼)"),
        Color(0xFFF4F1EA), Color(0xFFFFFFFF), Color(0xFFEAE5DC),
        Color(0xFFD6CFC2), Color(0xFF5B7C99), Color(0xFF1E293B), Color(0xFF64748B)
    )
}

// ============================================================================
// 3. 週視圖 / 日時間軸 / 年總覽 補充視圖元件
// ============================================================================
@Composable
fun ExtraCalendarViews(
    viewMode: CalendarViewMode,
    selectedDate: LocalDate,
    yearMonth: YearMonth,
    events: List<ShiftEvent>,
    alarms: List<ShiftAlarm>,
    theme: ThemePalette,
    onSelectDateAndSwitchMonth: (LocalDate) -> Unit
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    when (viewMode) {
        CalendarViewMode.WEEK -> {
            // 顯示選取日期所屬的一週 7 天排班卡片
            val startOfWeek = selectedDate.minusDays((selectedDate.dayOfWeek.value % 7).toLong())
            val weekDays = (0..6).map { startOfWeek.plusDays(it.toLong()) }
            val weekNames = listOf(tr("週日"), tr("週一"), tr("週二"), tr("週三"), tr("週四"), tr("週五"), tr("週六"))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(tr("週視圖 ({0} ~ {1})", weekDays.first(), weekDays.last()), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
                }
                items(weekDays) { day ->
                    val ev = events.firstOrNull { it.date == day }
                    val dayAlarms = alarms.filter { it.date == day && it.enabled }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = theme.bgCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (day == selectedDate) theme.accent else theme.borderColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDateAndSwitchMonth(day) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${day} (${weekNames[day.dayOfWeek.value % 7]})", fontSize = 12.sp, color = theme.textMuted)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (ev != null) "${ev.title} · ${ev.startTime.format(timeFormatter)}-${ev.endTime.format(timeFormatter)}" else tr("未排班"),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textMain
                                )
                                if (!ev?.note.isNullOrBlank()) {
                                    Text(ev!!.note, fontSize = 11.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (dayAlarms.isNotEmpty()) {
                                IconText(tr("⏰ {0}個", dayAlarms.size), fontSize = 11.sp, color = theme.accentText, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        CalendarViewMode.DAY -> {
            // 24 小時垂直時間軸檢視
            val ev = events.firstOrNull { it.date == selectedDate }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Text(tr("日 24H 時間軸 · {0} ({1})", selectedDate, ev?.title?.let { tr(it) } ?: tr("無排班")), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items((0..23).toList()) { hour ->
                    val hourTime = LocalTime.of(hour, 0)
                    val isWorkingHour = if (ev != null && ev.type != ShiftType.OFF) {
                        if (ev.endTime.isAfter(ev.startTime)) {
                            !hourTime.isBefore(ev.startTime) && hourTime.isBefore(ev.endTime)
                        } else {
                            !hourTime.isBefore(ev.startTime) || hourTime.isBefore(ev.endTime)
                        }
                    } else false
                    val hourAlarms = alarms.filter { it.date == selectedDate && it.triggerTime.hour == hour }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isWorkingHour) ev!!.type.chipBg() else theme.bgCard)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(String.format("%02d:00", hour), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = theme.textMuted)
                        if (isWorkingHour) {
                            Text(tr(ev!!.title), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ev.type.chipText())
                        }
                        if (hourAlarms.isNotEmpty()) {
                            IconText("⏰ ${hourAlarms.first().triggerTime.format(timeFormatter)} ${hourAlarms.first().label}", fontSize = 11.sp, color = theme.accentText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        CalendarViewMode.YEAR -> {
            // 全年 12 個月出勤總覽網格
            val year = yearMonth.year
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(tr("{0}年 全年排班總覽 (點擊月份跳轉)", year), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                }
                items((1..12).chunked(3)) { rowMonths ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowMonths.forEach { m ->
                            val ym = YearMonth.of(year, m)
                            val mCount = events.count { YearMonth.from(it.date) == ym && it.type != ShiftType.OFF }
                            val oCount = events.count { YearMonth.from(it.date) == ym && it.type == ShiftType.OFF }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = theme.bgCard,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (ym == yearMonth) theme.accent else theme.borderColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSelectDateAndSwitchMonth(ym.atDay(1)) }
                            ) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(tr("{0}月", m), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(tr("班 {0} 天", mCount), fontSize = 11.sp, color = theme.accentText)
                                    Text(tr("休 {0} 天", oCount), fontSize = 11.sp, color = Color(0xFF6EE7B7).readable())
                                }
                            }
                        }
                    }
                }
            }
        }

        CalendarViewMode.MONTH -> Unit
    }
}

// ============================================================================
// 4. 全域搜尋抽屜對話框 (搜尋排班、備註與鬧鐘)
// ============================================================================
@Composable
fun GlobalSearchDialog(
    events: List<ShiftEvent>,
    alarms: List<ShiftAlarm>,
    theme: ThemePalette,
    onJumpToDate: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onEditDate: ((LocalDate) -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    // 網頁版 searchFilterType：all / 各班別 / alarm
    var filter by remember { mutableStateOf("all") }
    val filters = listOf(
        "all" to tr("全部項目"), ShiftType.EARLY.name to tr("☀️ 早班"), ShiftType.EVENING.name to tr("🌆 晚班"),
        ShiftType.NIGHT.name to tr("🌙 夜班"), ShiftType.OFF.name to tr("🌿 休假"), ShiftType.CUSTOM.name to tr("📌 日程"),
        "alarm" to tr("⏰ 鬧鐘")
    )
    val q = query.trim()
    val idle = q.isBlank() && filter == "all"
    val matchedEvents = remember(q, filter, events.toList()) {
        if (filter == "alarm" || idle) emptyList()
        else events.filter { ev ->
            (filter == "all" || ev.type.name == filter) && (q.isBlank() ||
                ev.title.contains(q, ignoreCase = true) ||
                ev.note.contains(q, ignoreCase = true) ||
                ev.date.toString().contains(q) ||
                ev.type.displayName.contains(q))
        }.sortedBy { it.date }
    }
    val matchedAlarms = remember(q, filter, alarms.toList()) {
        if ((filter != "all" && filter != "alarm") || idle) emptyList()
        else alarms.filter {
            q.isBlank() || it.label.contains(q, ignoreCase = true) || it.date.toString().contains(q) ||
                it.triggerTime.toString().contains(q)
        }.sortedWith(compareBy({ it.date }, { it.triggerTime }))
    }

    fun highlight(text: String): AnnotatedString = buildAnnotatedString {
        if (q.isBlank()) {
            append(text)
        } else {
            var i = 0
            while (i < text.length) {
                val hit = text.indexOf(q, i, ignoreCase = true)
                if (hit < 0) {
                    append(text.substring(i))
                    break
                }
                append(text.substring(i, hit))
                withStyle(SpanStyle(background = theme.accent.copy(alpha = 0.35f), fontWeight = FontWeight.Black)) {
                    append(text.substring(hit, hit + q.length))
                }
                i = hit + q.length
            }
        }
    }

    MotionAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.bgCard,
        title = { Text(tr("搜尋排班日程與鬧鐘"), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("輸入班別名稱、日期 (如 11-18)、備註關鍵字..."), fontSize = 12.sp) },
                    singleLine = true,
                    trailingIcon = if (query.isNotEmpty()) {
                        { Icon(Tabler.X, contentDescription = tr("清除"), tint = theme.textMuted, modifier = Modifier.clip(CircleShape).clickable { query = "" }.padding(8.dp).size(16.dp)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filters) { (id, label) ->
                        val active = filter == id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) theme.accent else theme.bgElevated)
                                .clickable { filter = id }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            IconText(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else theme.textMain)
                        }
                    }
                }
                Text(
                    if (idle) tr("輸入關鍵字或點選上方分類開始搜尋") else tr("找到 {0} 筆結果", matchedEvents.size + matchedAlarms.size),
                    fontSize = 11.sp, color = theme.textMuted
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(matchedEvents) { ev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.bgElevated)
                                .clickable {
                                    onJumpToDate(ev.date)
                                    onDismiss()
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(ev.type.accentDot))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(highlight("${ev.date} · ${ev.title}"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.textMain)
                                if (ev.note.isNotBlank()) {
                                    Text(highlight(ev.note), fontSize = 11.sp, color = theme.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (onEditDate != null) {
                                Text(
                                    tr("編輯"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.accent,
                                    modifier = Modifier.clickable { onEditDate(ev.date); onDismiss() }.padding(6.dp)
                                )
                            }
                            IconText(tr("跳轉 →"), fontSize = 11.sp, color = theme.textMain)
                        }
                    }
                    items(matchedAlarms) { al ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.bgElevated)
                                .clickable {
                                    onJumpToDate(al.date)
                                    onDismiss()
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Tabler.Alarm, contentDescription = null, tint = if (al.enabled) theme.accentText else theme.textMuted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                highlight("${al.date} ${al.triggerTime.toString().take(5)} · ${al.label}"),
                                fontSize = 11.sp, color = if (al.enabled) theme.textMain else theme.textMuted,
                                modifier = Modifier.weight(1f)
                            )
                            Text(if (al.enabled) tr("開啟") else tr("關閉"), fontSize = 10.sp, color = theme.textMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("關閉"), color = theme.accentText) }
        }
    )
}

// ============================================================================
// 5. 自訂班別模板新增／編輯對話框
// ============================================================================
@Composable
fun TemplateEditorDialog(
    initialTemplate: ShiftTemplate?,
    theme: ThemePalette,
    onSave: (ShiftTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTemplate?.title ?: "") }
    var selectedType by remember { mutableStateOf(initialTemplate?.type ?: ShiftType.CUSTOM) }
    var startHour by remember { mutableStateOf((initialTemplate?.startTime?.hour ?: 9).toString().padStart(2, '0')) }
    var startMin by remember { mutableStateOf((initialTemplate?.startTime?.minute ?: 0).toString().padStart(2, '0')) }
    var endHour by remember { mutableStateOf((initialTemplate?.endTime?.hour ?: 18).toString().padStart(2, '0')) }
    var endMin by remember { mutableStateOf((initialTemplate?.endTime?.minute ?: 0).toString().padStart(2, '0')) }
    var wakeOffset by remember { mutableStateOf((initialTemplate?.wakeOffsetMinutes ?: 90L).toString()) }
    var leaveOffset by remember { mutableStateOf((initialTemplate?.leaveOffsetMinutes ?: 40L).toString()) }

    MotionAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.bgCard,
        title = {
            Text(
                text = if (initialTemplate == null) tr("新增自訂班別模板") else tr("編輯「{0}」", tr(initialTemplate.title)),
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = theme.textMain
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tr("班別名稱（如：豪宅夜班、機動班）"), fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShiftType.entries.forEach { t ->
                        val active = selectedType == t
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) t.badgeBg else theme.bgElevated)
                                .border(1.dp, if (active) t.badgeText else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { selectedType = t }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(tr(t.displayName), fontSize = 11.sp, color = t.badgeText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (selectedType != ShiftType.OFF) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = startHour, onValueChange = { if (it.length <= 2) startHour = it }, label = { Text(tr("起時"), fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = startMin, onValueChange = { if (it.length <= 2) startMin = it }, label = { Text(tr("起分"), fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                        Text("~", color = theme.textMuted)
                        OutlinedTextField(value = endHour, onValueChange = { if (it.length <= 2) endHour = it }, label = { Text(tr("迄時"), fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = endMin, onValueChange = { if (it.length <= 2) endMin = it }, label = { Text(tr("迄分"), fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = wakeOffset, onValueChange = { wakeOffset = it }, label = { Text(tr("第一段起床(班前分)"), fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = leaveOffset, onValueChange = { leaveOffset = it }, label = { Text(tr("第二段出門(班前分)"), fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val sTime = LocalTime.of(
                            (startHour.toIntOrNull() ?: 9).coerceIn(0, 23),
                            (startMin.toIntOrNull() ?: 0).coerceIn(0, 59)
                        )
                        val eTime = LocalTime.of(
                            (endHour.toIntOrNull() ?: 18).coerceIn(0, 23),
                            (endMin.toIntOrNull() ?: 0).coerceIn(0, 59)
                        )
                        onSave(
                            ShiftTemplate(
                                id = initialTemplate?.id ?: UUID.randomUUID().toString(),
                                title = title.trim(),
                                type = selectedType,
                                startTime = sTime,
                                endTime = eTime,
                                wakeOffsetMinutes = wakeOffset.toLongOrNull() ?: 90L,
                                leaveOffsetMinutes = leaveOffset.toLongOrNull() ?: 40L,
                                isOff = selectedType == ShiftType.OFF
                            )
                        )
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = theme.accent)
            ) {
                Text(tr("儲存模板"), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消"), color = theme.textMuted) }
        }
    )
}

// ============================================================================
// 6. 自訂獨立日期鬧鐘新增對話框
// ============================================================================