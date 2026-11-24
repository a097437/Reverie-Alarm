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
    holidayPolicy: HolidayPolicy,
    theme: ThemePalette,
    onSelectDateAndSwitchMonth: (LocalDate) -> Unit,
    weekStartsOnMonday: Boolean = false,
    onDateChange: (LocalDate) -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onEditDate: (LocalDate) -> Unit = {}
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    when (viewMode) {
        // 週視圖、日視圖：Google 日曆風格的時間軸（見 CalendarTimeViews.kt）
        CalendarViewMode.WEEK, CalendarViewMode.DAY -> TimeGridCalendar(
            weekMode = viewMode == CalendarViewMode.WEEK,
            selectedDate = selectedDate,
            weekStartsOnMonday = weekStartsOnMonday,
            events = events,
            alarms = alarms,
            holidayPolicy = holidayPolicy,
            theme = theme,
            onDateChange = onDateChange,
            onOpenDay = onOpenDay,
            onEditDate = onEditDate
        )

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