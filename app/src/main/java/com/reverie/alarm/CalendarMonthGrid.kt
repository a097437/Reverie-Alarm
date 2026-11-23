package com.reverie.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// ============================================================================
// 月曆格子（月視圖）
//
// 效能重點（120Hz 每一幀只有 8.3ms）：
//   - 每一格的資料用查表取得（日期 → 班別、有鬧鐘的日期…），不在每一格掃整份清單
//   - DayCell 是獨立、可以被跳過的元件：點一個日期時，只有新舊兩格重組，不是整個月三頁一起重組
//   - java.time 類別在 compose-stability.conf 標成穩定，LocalDate／YearMonth 參數用 equals 比較
// ============================================================================

/** 日期 → 那天的排班（同一天有多筆時取第一筆，和原本 firstOrNull 一樣） */
fun firstEventByDate(events: List<ShiftEvent>): Map<LocalDate, ShiftEvent> {
    val map = HashMap<LocalDate, ShiftEvent>(events.size * 2)
    for (e in events) map.putIfAbsent(e.date, e)
    return map
}

/** 有開啟鬧鐘的日期 */
fun activeAlarmDatesOf(alarms: List<ShiftAlarm>): Set<LocalDate> {
    val set = HashSet<LocalDate>()
    for (a in alarms) if (a.enabled) set.add(a.date)
    return set
}

@Composable
fun MonthGrid(
    ym: YearMonth,
    weekStartsOnMonday: Boolean,
    today: LocalDate,
    selectedDate: LocalDate,
    quickCapsuleDate: LocalDate?,
    eventByDate: Map<LocalDate, ShiftEvent>,
    activeAlarmDates: Set<LocalDate>,
    batchSelected: Set<LocalDate>,
    partnerOff: Set<LocalDate>,
    holidayPolicy: HolidayPolicy,
    weather: WeatherData?,
    fontScale: Float,
    theme: ThemePalette,
    effectiveAccent: Color,
    effectiveCardBg: Color,
    onDateClick: (LocalDate) -> Unit,
    onOpenEditSheet: () -> Unit
) {
    val firstDayOfMonth = ym.atDay(1)
    val leadingEmpty = if (weekStartsOnMonday) firstDayOfMonth.dayOfWeek.value - 1 else firstDayOfMonth.dayOfWeek.value % 7
    val daysInMonth = ym.lengthOfMonth()
    val rows = (leadingEmpty + daysInMonth + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(CAL_ROW_GAP.dp), modifier = Modifier.fillMaxWidth()) {
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val index = r * 7 + c
                    val dayNum = index - leadingEmpty + 1
                    val inMonth = dayNum in 1..daysInMonth
                    // 上／下個月的日子也畫出來（淡化、不能點），月曆看起來比較完整
                    val cellDate = firstDayOfMonth.plusDays((index - leadingEmpty).toLong())
                    val ev = eventByDate[cellDate]
                    // 我這天放假（我排的休假，或見紅休的國定假日）＋對方也休
                    val isMutualOff = inMonth && cellDate in partnerOff &&
                        DayResolver.resolveDay(cellDate, ev, holidayPolicy).isOff
                    // 左上角：未來幾天的天氣小圖示
                    val wxCode = if (inMonth && !isMutualOff && !cellDate.isBefore(today)) weather?.day(cellDate)?.code else null
                    DayCell(
                        date = cellDate,
                        inMonth = inMonth,
                        isFirstRow = r == 0,
                        event = ev,
                        // 國定假日：紅字＋假日名稱（有排班時名稱讓給班別，以排班為準）
                        holiday = if (inMonth) holidayPolicy.holidayOn(cellDate) else null,
                        isToday = inMonth && cellDate == today,
                        isSelected = inMonth && cellDate == selectedDate,
                        isBatchSel = inMonth && cellDate in batchSelected,
                        hasActiveAlarm = inMonth && cellDate in activeAlarmDates,
                        isMutualOff = isMutualOff,
                        weatherCode = wxCode,
                        showCapsule = inMonth && quickCapsuleDate == cellDate,
                        fontScale = fontScale,
                        theme = theme,
                        effectiveAccent = effectiveAccent,
                        effectiveCardBg = effectiveCardBg,
                        onDateClick = onDateClick,
                        onOpenEditSheet = onOpenEditSheet,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isFirstRow: Boolean,
    event: ShiftEvent?,
    holiday: Holiday?,
    isToday: Boolean,
    isSelected: Boolean,
    isBatchSel: Boolean,
    hasActiveAlarm: Boolean,
    isMutualOff: Boolean,
    weatherCode: Int?,
    showCapsule: Boolean,
    fontScale: Float,
    theme: ThemePalette,
    effectiveAccent: Color,
    effectiveCardBg: Color,
    onDateClick: (LocalDate) -> Unit,
    onOpenEditSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasNote = event != null && (event.note.isNotBlank() || event.attachments.isNotEmpty())
    val gridBg = effectiveCardBg
    val light = theme.isCalCellLight

    // 底塊：選取 = 強調色實心；有排班 = 班別色淡淡的底；沒排班 = 透明
    val tileBg = when {
        isSelected -> effectiveAccent.darkenForWhiteText()
        event != null -> mixColor(event.type.accentDot, if (light) 0.16f else 0.22f, gridBg)
        else -> Color.Transparent
    }
    val surfaceBg = if (tileBg == Color.Transparent) gridBg else tileBg
    val dow = date.dayOfWeek
    val numberColor = when {
        isSelected -> Color.White
        isToday -> effectiveAccent.readableOn(surfaceBg, 4.5)
        holiday != null || dow == DayOfWeek.SUNDAY -> ROSE.readableOn(surfaceBg, 4.5)
        dow == DayOfWeek.SATURDAY -> Color(0xFF3B82F6).readableOn(surfaceBg, 4.5)
        else -> theme.calCellText.readableOn(surfaceBg, 4.5)
    }
    val ring = when {
        isBatchSel -> Color(0xFFF59E0B)
        isMutualOff && !isSelected -> EMERALD.readableOn(gridBg, 3.0)
        isToday && !isSelected -> effectiveAccent.readableOn(gridBg, 3.0)
        else -> Color.Transparent
    }
    val bounce by rememberBounceScale(
        key = event?.let { "${it.id}|${it.title}|${it.type}" },
        resetKey = date
    )
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .height(CAL_CELL_HEIGHT.dp)
            .graphicsLayer {
                alpha = if (inMonth) 1f else 0.32f
                if (inMonth) { scaleX = bounce; scaleY = bounce }
            }
            .then(
                if (isSelected) Modifier.shadow(6.dp, shape, ambientColor = effectiveAccent, spotColor = effectiveAccent)
                else Modifier
            )
            .clip(shape)
            .background(tileBg)
            .border(if (ring != Color.Transparent) 1.5.dp else 0.dp, ring, shape)
            .then(if (inMonth) Modifier.clickable { onDateClick(date) } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        // 選取格子時浮出「編輯」膠囊（第一排改放到格子下方）。
        // 從沒出現過的格子不放 MotionHost，每頁省下幾十個動畫狀態與協程
        if (inMonth) {
            val everShown = remember { BooleanArray(1) }
            if (showCapsule) everShown[0] = true
            if (everShown[0]) {
                MotionHost(showCapsule) {
                    EditCapsulePopup(isFirstRow = isFirstRow, theme = theme, onEdit = onOpenEditSheet)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                color = numberColor,
                fontSize = (19 * fontScale).sp,
                fontWeight = if (isSelected || isToday) FontWeight.Black else FontWeight.Bold,
                lineHeight = (21 * fontScale).sp
            )
            // 數字下方：班別名稱；沒排班的國定假日顯示假日名稱（紅字）；都沒有就留白，讓數字保持同一高度
            Text(
                text = event?.title?.let { shortShiftName(it) } ?: holiday?.let { tr(it.short) } ?: " ",
                color = when {
                    event == null && holiday == null -> Color.Transparent
                    isSelected -> Color.White.copy(alpha = 0.92f)
                    event == null -> ROSE.readableOn(surfaceBg, 4.5)
                    else -> event.type.chipText(light).readableOn(surfaceBg, 4.5)
                },
                fontSize = (11 * fontScale).sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = (13 * fontScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 3.dp)
            )
        }
        // 右上角：當天有開啟的鬧鐘
        if (hasActiveAlarm) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White else ROSE.darkenForWhiteText()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Tabler.Bell, contentDescription = tr("有鬧鐘"),
                    tint = if (isSelected) effectiveAccent.darkenForWhiteText() else Color.White,
                    modifier = Modifier.size(9.dp)
                )
            }
        }
        // 左上角：未來幾天的天氣小圖示
        if (weatherCode != null) {
            Icon(
                wxIcon(weatherCode), contentDescription = wxText(weatherCode),
                tint = if (isSelected) Color.White.copy(alpha = 0.9f) else Color(0xFF38BDF8).readableOn(surfaceBg, 3.0),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp).size(11.dp)
            )
        }
        // 左上角：雙方共同休假
        if (isMutualOff) {
            Icon(
                Tabler.Hearts, contentDescription = tr("共同休假"),
                tint = if (isSelected) Color.White else EMERALD.readableOn(surfaceBg, 3.0),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp).size(11.dp)
            )
        }
        // 底部小圓點：有私人備註或附件
        if (hasNote) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White else Color(0xFF7DD3FC).readableOn(surfaceBg, 3.0))
            )
        }
    }
}
