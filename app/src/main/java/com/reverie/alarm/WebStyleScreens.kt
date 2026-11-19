package com.reverie.alarm

import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID

// ============================================================================
// 與網頁版外觀一致的畫面：頂部列、底部分頁列、兩段鬧鐘、班別模板、共享帳號
// 版面、文字與按鈕位置都對照 reverie_alarm.html 的實際畫面
// ============================================================================

val ROSE = Color(0xFFF43F5E)
val AMBER = Color(0xFFF59E0B)
val EMERALD = Color(0xFF10B981)

/** 各班別的圖示（對應網頁版 Tabler icon：sun / sunset / moon / leaf / pin） */
fun ShiftType.icon(): ImageVector = when (this) {
    ShiftType.EARLY -> Tabler.Sun
    ShiftType.EVENING -> Tabler.Sunset2
    ShiftType.NIGHT -> Tabler.Moon
    ShiftType.OFF -> Tabler.Leaf
    ShiftType.CUSTOM -> Tabler.Pin
}

// ---------------------------------------------------------------------------
// 共用小元件
// ---------------------------------------------------------------------------

/** 網頁版 theme-card / theme-elevated 圓角框 */
@Composable
fun WebCard(
    theme: ThemePalette,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    radius: Dp = 24.dp,
    border: Color = theme.borderColor,
    bg: Color = if (elevated) theme.bgElevated else theme.bgCard,
    padding: Dp = 14.dp,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(radius),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

/** 小圓角標籤（第一段・強效起床、跨隔日、今天…） */
@Composable
fun WebPill(text: String, color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 10.sp, filled: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.darkenForWhiteText() else color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, color = if (filled) Color.White else color.readable(), maxLines = 1, softWrap = false)
    }
}

/** 網頁版按鈕：accent 實心或描邊 */
@Composable
fun WebButton(
    text: String,
    theme: ThemePalette,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    color: Color = theme.accent,
    icon: ImageVector? = null,
    fontSize: TextUnit = 12.sp,
    radius: Dp = 14.dp,
    vertical: Dp = 9.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(if (filled) color.darkenForWhiteText().copy(alpha = if (enabled) 1f else 0.4f) else theme.bgCard)
            .border(1.dp, if (filled) Color.Transparent else theme.borderColor, RoundedCornerShape(radius))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconText(
            text, icon = icon, fontSize = fontSize, fontWeight = FontWeight.ExtraBold,
            color = if (filled) Color.White else theme.textMain, maxLines = 1
        )
    }
}

/** 網頁版的小開關（w-11 h-6） */
@Composable
fun WebToggle(checked: Boolean, theme: ThemePalette, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(50))
            .background(if (checked) theme.accent else theme.textMuted)
            .clickable { onToggle() }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
    }
}

/** 下拉選單樣式的方框（點了跳出時間選擇或選單） */
@Composable
fun WebSelectBox(text: String, theme: ThemePalette, modifier: Modifier = Modifier, fontSize: TextUnit = 13.sp, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(theme.bgCard)
            .border(1.dp, theme.borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = fontSize, fontWeight = FontWeight.Bold, color = theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Tabler.ChevronDown, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(18.dp))
    }
}

/** 小數字輸入框（起床提前 90 分） */
@Composable
fun WebNumberField(value: Int, color: Color, theme: ThemePalette, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(3)
            text.toIntOrNull()?.let(onChange)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(color),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(theme.bgCard)
            .border(1.dp, theme.borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp)
    )
}

/** 單行文字輸入框 */
@Composable
fun WebTextField(value: String, placeholder: String, theme: ThemePalette, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(theme.bgCard)
            .border(1.dp, theme.borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = theme.textMain, fontSize = 13.sp),
            cursorBrush = SolidColor(theme.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatHm(t: LocalTime) = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"

// ---------------------------------------------------------------------------
// 頂部列：☰  ‹ 2026年 11月 [今] ›          [⚡]  [⋮]
// ---------------------------------------------------------------------------
@Composable
fun WebTopBar(
    yearMonth: YearMonth,
    selectedDate: LocalDate,
    showBatchButton: Boolean,
    batchMode: Boolean,
    safeguardAlert: Boolean,
    theme: ThemePalette,
    showMoreMenu: Boolean,
    onToggleMoreMenu: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onToggleBatch: () -> Unit,
    onSearch: () -> Unit,
    onAddShift: () -> Unit,
    onAddAlarm: () -> Unit,
    onOpenWidgets: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().background(theme.bgBase)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.bgElevated)
                        .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp))
                        .clickable { onOpenDrawer() },
                    contentAlignment = Alignment.Center
                ) { Icon(Tabler.Menu2, contentDescription = tr("開啟左側收納選單"), tint = theme.textMain, modifier = Modifier.size(22.dp)) }
                if (safeguardAlert) {
                    Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(AMBER))
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Tabler.ChevronLeft, contentDescription = tr("上一期"), tint = theme.textMain.copy(alpha = 0.75f),
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).clickable { onPrev() }.padding(4.dp)
            )
            Row(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onToday() }.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("{0}年 {1}月", yearMonth.year, yearMonth.monthValue), fontSize = 18.sp, fontWeight = FontWeight.Black, color = theme.textMain, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(theme.accent.copy(alpha = 0.18f))
                        .border(1.dp, theme.accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) { Text(tr("今"), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = theme.accentText) }
            }
            Icon(
                Tabler.ChevronRight, contentDescription = tr("下一期"), tint = theme.textMain.copy(alpha = 0.75f),
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).clickable { onNext() }.padding(4.dp)
            )
            Spacer(Modifier.weight(1f))
            if (showBatchButton) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (batchMode) AMBER.copy(alpha = 0.25f) else theme.bgElevated)
                        .border(1.dp, if (batchMode) AMBER else theme.borderColor, RoundedCornerShape(16.dp))
                        .clickable { onToggleBatch() },
                    contentAlignment = Alignment.Center
                ) { Icon(Tabler.Bolt, contentDescription = tr("批次一鍵排班"), tint = AMBER.readable(), modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(8.dp))
            }
            Box {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.accent)
                        .clickable { onToggleMoreMenu(true) },
                    contentAlignment = Alignment.Center
                ) { Icon(Tabler.DotsVertical, contentDescription = tr("開啟更多操作選單"), tint = Color.White, modifier = Modifier.size(22.dp)) }
                MotionDropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { onToggleMoreMenu(false) },
                    palette = theme
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("快速建立與檢索"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = theme.textMuted, modifier = Modifier.weight(1f))
                        Text(
                            "%02d-%02d".format(selectedDate.monthValue, selectedDate.dayOfMonth),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = theme.accent
                        )
                    }
                    MenuEntry(Tabler.Search, Color(0xFF38BDF8), tr("搜尋日程與鬧鐘"), tr("關鍵字篩選班別、備註與提醒"), theme) { onToggleMoreMenu(false); onSearch() }
                    HorizontalDivider(color = theme.borderColor, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    MenuEntry(Tabler.Plus, theme.accent, tr("新增排班／日程"), tr("套用班別模板、備註與附件"), theme, solid = true) { onToggleMoreMenu(false); onAddShift() }
                    MenuEntry(Tabler.Alarm, theme.accent, tr("新增日期鬧鐘"), tr("指定日曆日期精準強效提醒"), theme) { onToggleMoreMenu(false); onAddAlarm() }
                    HorizontalDivider(color = theme.borderColor, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    MenuEntry(Tabler.DeviceMobile, Color(0xFF10B981), tr("桌面小工具"), tr("班表和下一個鬧鐘放到手機桌面"), theme) { onToggleMoreMenu(false); onOpenWidgets() }
                }
            }
        }
        HorizontalDivider(color = theme.borderColor.copy(alpha = 0.7f))
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, color: Color, title: String, sub: String, theme: ThemePalette, solid: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (solid) color else color.copy(alpha = 0.15f))
                    .border(1.dp, if (solid) Color.Transparent else color.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = if (solid) Color.White else color, modifier = Modifier.size(21.dp)) }
        },
        text = {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                Text(sub, fontSize = 12.sp, color = theme.textMuted, maxLines = 1)
            }
        }
    )
}

// ---------------------------------------------------------------------------
// 底部分頁列（選中的分頁有圓角底色與下方指示條）
// ---------------------------------------------------------------------------
@Composable
fun WebBottomBar(selectedTab: Int, theme: ThemePalette, position: () -> Float = { selectedTab.toFloat() }, onSelect: (Int) -> Unit) {
    val items = listOf(
        Tabler.CalendarMonth to tr("排班日曆"),
        Tabler.Alarm to tr("兩段鬧鐘"),
        Tabler.LayoutGrid to tr("班別模板"),
        Tabler.TemperatureSun to tr("天氣"),
        Tabler.Sparkles to tr("AI快速排班")
    )
    Column(Modifier.fillMaxWidth().background(theme.bgCard)) {
        HorizontalDivider(color = theme.borderColor.copy(alpha = 0.7f))
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            val itemW = maxWidth / items.size
            // 滑動指示膠囊：位置 = 目前分頁 + 拖曳進度，手指拖到一半就停在兩個分頁中間
            Box(
                Modifier
                    .width(itemW)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = position() * itemW.toPx() }
                    .padding(horizontal = 3.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(theme.accent.copy(alpha = 0.16f))
                    .border(1.dp, theme.accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp)
                        .width(20.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(theme.accent)
                )
            }
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { index, (icon, label) ->
                    // 越靠近指示膠囊越亮
                    val near = (1f - kotlin.math.abs(position() - index)).coerceIn(0f, 1f)
                    val tint = androidx.compose.ui.graphics.lerp(theme.textMain.copy(alpha = 0.6f), theme.accentText, near)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onSelect(index) }
                            .padding(bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(label, fontSize = 10.sp, fontWeight = if (near > 0.5f) FontWeight.ExtraBold else FontWeight.SemiBold, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分頁二：兩段式日期鬧鐘清單（網頁版 renderAlarmsView / renderAlarmItemCard）
// ---------------------------------------------------------------------------
@Composable
fun WebAlarmsScreen(
    alarms: List<ShiftAlarm>,
    events: List<ShiftEvent>,
    ringtones: List<RingtoneEntry>,
    defaultWakeUri: String?,
    defaultLeaveUri: String?,
    theme: ThemePalette,
    onAddAlarm: () -> Unit,
    onToggle: (ShiftAlarm) -> Unit,
    onDelete: (ShiftAlarm) -> Unit,
    onBatchDelete: (Set<String>) -> Unit,
    onClearDate: (LocalDate) -> Unit,
    onTestRing: (ShiftAlarm) -> Unit,
    onSetAlarmRingtone: (ShiftAlarm, RingtoneEntry) -> Unit,
    onImportRingtone: (Uri, String) -> RingtoneEntry,
    onDeleteRingtone: (RingtoneEntry) -> Unit,
    onSetDefaultRingtone: (AlarmStage, RingtoneEntry) -> Unit
) {
    val context = LocalContext.current
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var importTarget by remember { mutableStateOf<ShiftAlarm?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { RingtonePreviewer.stop() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
            }.getOrNull() ?: tr("匯入音檔")
            val entry = onImportRingtone(uri, name)
            importTarget?.let { onSetAlarmRingtone(it, entry) }
        }
        importTarget = null
    }

    fun ringtoneFor(al: ShiftAlarm): RingtoneEntry? =
        ringtones.firstOrNull { it.uriString == al.ringtoneUri && it.name == al.ringtoneName }
            ?: ringtones.firstOrNull { it.uriString == al.ringtoneUri }
            ?: ringtones.firstOrNull()

    fun togglePreview(rt: RingtoneEntry) {
        if (previewId == rt.id) {
            RingtonePreviewer.stop()
            previewId = null
            return
        }
        if (RingtonePreviewer.play(context, rt.uriString)) {
            previewId = rt.id
        } else {
            previewId = null
            AppToast.show(tr("無法播放這個音檔（檔案可能已被移除）"))
        }
    }

    val sorted = alarms.sortedWith(compareBy({ it.date }, { it.triggerTime }))
    val groups = sorted.groupBy { it.date }
    val today = LocalDate.now()
    val upcomingGroups = groups.filterKeys { it >= today }.toList()
    val pastGroups = groups.filterKeys { it < today }.toList().reversed()
    var showPast by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<LocalDate?>(null) }
    var lastClear by remember { mutableStateOf<LocalDate?>(null) }
    confirmClear?.let { lastClear = it }
    val weekNames = listOf(tr("週一"), tr("週二"), tr("週三"), tr("週四"), tr("週五"), tr("週六"), tr("週日"))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 標題列
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("兩段式日期鬧鐘清單"), fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain, modifier = Modifier.weight(1f))
                    if (sorted.isNotEmpty()) {
                        WebButton(if (selectMode) tr("完成") else tr("多選刪除"), theme, filled = false, radius = 16.dp, vertical = 10.dp) {
                            selectMode = !selectMode
                            selected.clear()
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (!selectMode) {
                        WebButton(tr("+ 新增鬧鐘"), theme, radius = 16.dp, vertical = 10.dp, onClick = onAddAlarm)
                    }
                }
                Text(tr("僅在指定「年/月/日」觸發，避免無效重複響鈴"), fontSize = 11.sp, color = theme.textMuted, modifier = Modifier.padding(top = 0.dp))
                if (selectMode && sorted.isNotEmpty()) {
                    WebCard(theme, elevated = true, radius = 16.dp, padding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val all = selected.size == sorted.size
                            WebButton(if (all) tr("取消全選") else tr("全選 ({0})", sorted.size), theme, filled = false, fontSize = 11.sp, vertical = 7.dp) {
                                selected.clear()
                                if (!all) selected.addAll(sorted.map { it.id })
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                buildAnnotatedString {
                                    append(tr("已選 "))
                                    withStyle(SpanStyle(color = theme.accentText, fontWeight = FontWeight.Black)) { append("${selected.size}") }
                                    append(tr(" 個"))
                                },
                                fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.weight(1f)
                            )
                            WebButton(
                                tr("刪除所選") + if (selected.isNotEmpty()) " (${selected.size})" else "",
                                theme, color = ROSE.readable(), icon = Tabler.Trash, fontSize = 11.sp, vertical = 7.dp,
                                enabled = selected.isNotEmpty()
                            ) {
                                onBatchDelete(selected.toSet())
                                selected.clear()
                                selectMode = false
                            }
                        }
                    }
                }
            }
        }

        if (sorted.isEmpty()) {
            item {
                WebCard(theme, padding = 32.dp) {
                    Text(tr("目前尚未設定任何日期鬧鐘"), fontSize = 13.sp, color = theme.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        listOf(upcomingGroups, if (showPast) pastGroups else emptyList()).forEachIndexed { section, sectionGroups ->
          if (section == 1 && pastGroups.isNotEmpty()) {
            item(key = "past-toggle") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showPast = !showPast }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Text(
                        tr("已過去的鬧鐘（{0} 天）", pastGroups.size),
                        fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = theme.textMuted, modifier = Modifier.weight(1f)
                    )
                    IconText(if (showPast) tr("收起 ▲") else tr("展開 ▼"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = theme.accentText)
                }
            }
          }
          sectionGroups.forEach { (date, items) ->
            item(key = "group-$date") {
              Box(Modifier.alpha(if (date < today) 0.6f else 1f)) {
                val dayShift = events.firstOrNull { it.date == date }
                val isToday = date == today
                val allGroupSelected = items.all { selected.contains(it.id) }
                WebCard(theme, radius = 24.dp, padding = 16.dp) {
                    Column {
                        // 日期標題：日期・星期・今天/明天，右邊是數量與清空
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("{0}月{1}日", date.monthValue, date.dayOfMonth), fontSize = 16.sp, fontWeight = FontWeight.Black, color = theme.textMain, softWrap = false)
                            Spacer(Modifier.width(6.dp))
                            Text(weekNames[date.dayOfWeek.value - 1], fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.textMuted, softWrap = false)
                            if (isToday) { Spacer(Modifier.width(8.dp)); WebPill(tr("今天"), EMERALD) }
                            if (date == today.plusDays(1)) { Spacer(Modifier.width(8.dp)); WebPill(tr("明天"), AMBER) }
                            if (dayShift?.type == ShiftType.OFF) { Spacer(Modifier.width(8.dp)); WebPill(tr("休假日"), EMERALD) }
                            Spacer(Modifier.weight(1f))
                            if (selectMode) {
                                Text(
                                    if (allGroupSelected) tr("取消整天") else tr("選取整天"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                        if (allGroupSelected) selected.removeAll(items.map { it.id }.toSet())
                                        else items.forEach { if (!selected.contains(it.id)) selected.add(it.id) }
                                    }.padding(6.dp)
                                )
                            } else {
                                Text(tr("{0} 個", items.size), fontSize = 12.sp, color = theme.textMuted, softWrap = false)
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Tabler.Trash, contentDescription = tr("清空這天的鬧鐘"), tint = theme.textMuted,
                                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable { confirmClear = date }.padding(7.dp)
                                )
                            }
                        }
                        // 依班別分組：每一班的「起床 → 出門」用一條時間軸串起來
                        alarmBlocks(date, items, events).forEachIndexed { bi, block ->
                            if (bi > 0) HorizontalDivider(color = theme.borderColor.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 12.dp))
                            else Spacer(Modifier.height(12.dp))
                            if (block.title != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                    Text(tr(block.title), fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.accentText, softWrap = false)
                                    if (block.note != null) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(block.note, fontSize = 12.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            block.alarms.forEachIndexed { ai, al ->
                                AlarmItemRow(
                                    al = al, theme = theme, selectMode = selectMode, isSelected = selected.contains(al.id),
                                    ringtones = ringtones, currentRingtone = ringtoneFor(al),
                                    showLine = ai < block.alarms.lastIndex,
                                    onSelect = { if (selected.contains(al.id)) selected.remove(al.id) else selected.add(al.id) },
                                    onDelete = { onDelete(al) },
                                    onTestRing = { onTestRing(al) },
                                    onToggle = { onToggle(al) },
                                    onPickRingtone = { onSetAlarmRingtone(al, it) },
                                    onImport = {
                                        importTarget = al
                                        picker.launch(arrayOf("audio/*"))
                                    }
                                )
                            }
                        }
                    }
                }
              }
            }
          }
        }

        // 鈴聲庫（網頁版放在同一分頁）
        item {
            WebCard(theme, radius = 24.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 收合的標題列：平常只佔一行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showLibrary = !showLibrary }
                    ) {
                        Icon(Tabler.Music, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr("鈴聲庫"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                            Text(tr("{0} 首・預設鈴聲與手機音檔匯入", ringtones.size), fontSize = 11.sp, color = theme.textMuted)
                        }
                        Icon(
                            if (showLibrary) Tabler.ChevronUp else Tabler.ChevronDown, contentDescription = if (showLibrary) tr("收起") else tr("展開"),
                            tint = theme.textMuted, modifier = Modifier.size(18.dp)
                        )
                    }
                  if (showLibrary) {
                    WebButton(tr("匯入手機音訊（MP3 / M4A / WAV）"), theme, filled = false, icon = Tabler.FolderOpen, fontSize = 12.sp, vertical = 9.dp, modifier = Modifier.fillMaxWidth()) {
                        importTarget = null
                        picker.launch(arrayOf("audio/*"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AlarmStage.WAKE to defaultWakeUri, AlarmStage.LEAVE to defaultLeaveUri).forEach { (stage, uri) ->
                            Column(Modifier.weight(1f)) {
                                Text(if (stage == AlarmStage.WAKE) tr("預設「第一段起床」鈴聲") else tr("預設「第二段出門」鈴聲"), fontSize = 10.sp, color = theme.textMuted)
                                Spacer(Modifier.height(4.dp))
                                RingtoneDropdown(
                                    ringtones = ringtones,
                                    current = ringtones.firstOrNull { it.uriString == uri } ?: ringtones.firstOrNull(),
                                    theme = theme,
                                    onPick = { onSetDefaultRingtone(stage, it) }
                                )
                            }
                        }
                    }
                    ringtones.forEach { rt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(theme.bgElevated)
                                .border(1.dp, theme.borderColor, RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WebPill(if (rt.isBuiltIn) tr("內建") else tr("本機音檔"), if (rt.isBuiltIn) theme.accent else EMERALD, fontSize = 9.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(tr(rt.name), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            val playing = previewId == rt.id
                            WebButton(
                                if (playing) tr("停止") else tr("試聽"), theme, filled = playing, color = ROSE.readable(),
                                icon = if (playing) Tabler.PlayerStop else Tabler.PlayerPlay, fontSize = 11.sp, vertical = 5.dp, radius = 10.dp
                            ) { togglePreview(rt) }
                            if (!rt.isBuiltIn) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Tabler.Trash, contentDescription = tr("移除匯入音檔"), tint = ROSE.readable(),
                                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable {
                                        if (playing) togglePreview(rt)
                                        onDeleteRingtone(rt)
                                    }.padding(5.dp)
                                )
                            }
                        }
                    }
                  }
                }
            }
        }
    }

    // 清空這天：先確認，避免誤觸小圖示
    MotionHost(confirmClear != null) {
        val d = lastClear
        val count = alarms.count { it.date == d }
        MotionAlertDialog(
            onDismissRequest = { confirmClear = null },
            containerColor = theme.bgCard,
            icon = { Icon(Tabler.Trash, contentDescription = null, tint = ROSE.readable()) },
            title = {
                Text(
                    if (d != null) tr("清空 {0}月{1}日 的鬧鐘？", d.monthValue, d.dayOfMonth) else tr("清空鬧鐘？"),
                    fontWeight = FontWeight.Black, color = theme.textMain
                )
            },
            text = { Text(tr("這天的 {0} 個鬧鐘會全部刪除，無法復原。", count), fontSize = 13.sp, color = theme.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = null
                    if (d != null) onClearDate(d)
                }) { Text(tr("全部清空"), color = ROSE.readable(), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text(tr("取消"), color = theme.textMuted) } }
        )
    }
}

@Composable
private fun RingtoneDropdown(ringtones: List<RingtoneEntry>, current: RingtoneEntry?, theme: ThemePalette, modifier: Modifier = Modifier, onPick: (RingtoneEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        WebSelectBox(tr(current?.name ?: BuiltInRingtones.WAKE_STRONG.name), theme, Modifier.fillMaxWidth(), fontSize = 12.sp) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = theme.bgCard) {
            ringtones.forEach { rt ->
                DropdownMenuItem(
                    text = { Text(tr(rt.name), fontSize = 13.sp, color = theme.textMain) },
                    trailingIcon = if (rt.id == current?.id) {
                        { Icon(Tabler.Check, contentDescription = null, tint = theme.accentText) }
                    } else null,
                    onClick = { open = false; onPick(rt) }
                )
            }
        }
    }
}

/** 同一班的鬧鐘（起床＋出門）；title 是班別名稱，note 是「明天 00:00 上班」 */
private data class AlarmBlock(val title: String?, val note: String?, val alarms: List<ShiftAlarm>)

private fun alarmShiftName(al: ShiftAlarm): String? =
    if (al.stage != AlarmStage.CUSTOM && al.label.contains("・")) al.label.substringBefore("・").trim().ifEmpty { null } else null

private fun alarmBlocks(date: LocalDate, items: List<ShiftAlarm>, events: List<ShiftEvent>): List<AlarmBlock> {
    val runs = mutableListOf<MutableList<ShiftAlarm>>()
    items.forEach { al ->
        val last = runs.lastOrNull()
        if (last != null && alarmShiftName(last.last()) == alarmShiftName(al) && alarmShiftName(al) != null) last.add(al) else runs.add(mutableListOf(al))
    }
    return runs.map { run ->
        val name = alarmShiftName(run.first())
        val after = java.time.LocalDateTime.of(date, run.last().triggerTime)
        val ev = if (name == null) null else events
            .filter { it.title == name && it.type != ShiftType.OFF && (it.date == date || it.date == date.plusDays(1)) }
            .sortedBy { java.time.LocalDateTime.of(it.date, it.startTime) }
            .firstOrNull { !java.time.LocalDateTime.of(it.date, it.startTime).isBefore(after) }
        val note = ev?.let { (if (it.date == date) "" else tr("明天 ")) + tr("{0} 上班", formatHm(it.startTime)) }
        AlarmBlock(name, note, run)
    }
}

@Composable
private fun AlarmItemRow(
    al: ShiftAlarm,
    theme: ThemePalette,
    selectMode: Boolean,
    isSelected: Boolean,
    ringtones: List<RingtoneEntry>,
    currentRingtone: RingtoneEntry?,
    showLine: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTestRing: () -> Unit,
    onToggle: () -> Unit,
    onPickRingtone: (RingtoneEntry) -> Unit,
    onImport: () -> Unit
) {
    val (stageWord, stageColor) = when (al.stage) {
        AlarmStage.WAKE -> tr("起床") to ROSE
        AlarmStage.LEAVE -> tr("出門") to AMBER
        AlarmStage.CUSTOM -> tr("鬧鐘") to theme.accent
    }
    val dot = stageColor.readable(3.0)
    val dim = if (al.enabled) 1f else 0.45f
    val note = when (al.stage) {
        AlarmStage.LEAVE -> if (al.label.contains("禁止貪睡")) tr("禁止貪睡") else null
        AlarmStage.CUSTOM -> al.label.takeIf { it.isNotBlank() }?.let { trLabel(it) }
        AlarmStage.WAKE -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) theme.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = selectMode) { onSelect() }
    ) {
        // 時間軸：圓點＋接到下一個鬧鐘的線
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (selectMode) 9.dp else 13.dp))
            if (selectMode) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) theme.accent else Color.Transparent)
                        .border(2.dp, if (isSelected) Color.Transparent else theme.textMuted, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) { if (isSelected) Icon(Tabler.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) }
            } else {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot.copy(alpha = dim)))
            }
            if (showLine) {
                Box(Modifier.padding(top = 5.dp, bottom = 1.dp).width(2.dp).weight(1f).clip(RoundedCornerShape(1.dp)).background(theme.borderColor))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (showLine) 14.dp else 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatHm(al.triggerTime), fontSize = 26.sp, fontWeight = FontWeight.Black, color = theme.textMain.copy(alpha = dim), softWrap = false, lineHeight = 30.sp)
                Spacer(Modifier.width(8.dp))
                Text(stageWord, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = dot.copy(alpha = dim), softWrap = false)
                if (note != null) {
                    Text(" · $note", fontSize = 12.sp, color = theme.textMuted.copy(alpha = dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            RingtoneLine(ringtones, currentRingtone, theme, enabled = !selectMode, onPick = onPickRingtone, onImport = onImport)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            if (selectMode) {
                Icon(
                    Tabler.Trash, contentDescription = tr("刪除這個鬧鐘"), tint = ROSE.readable(),
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onDelete() }.padding(8.dp)
                )
            } else {
                Icon(
                    Tabler.Volume, contentDescription = tr("試響"), tint = theme.textMuted,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onTestRing() }.padding(8.dp)
                )
                Spacer(Modifier.width(4.dp))
                WebToggle(al.enabled, theme, onToggle)
            }
        }
    }
}

/** 鈴聲：一行小字，點一下換鈴聲或從手機匯入 */
@Composable
private fun RingtoneLine(
    ringtones: List<RingtoneEntry>, current: RingtoneEntry?, theme: ThemePalette, enabled: Boolean,
    onPick: (RingtoneEntry) -> Unit, onImport: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { open = true }.padding(vertical = 4.dp)
        ) {
            Icon(Tabler.Music, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                tr(current?.name ?: BuiltInRingtones.WAKE_STRONG.name), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = theme.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(2.dp))
            Icon(Tabler.ChevronDown, contentDescription = tr("更換鈴聲"), tint = theme.textMuted, modifier = Modifier.size(13.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = theme.bgCard) {
            ringtones.forEach { rt ->
                DropdownMenuItem(
                    text = { Text(tr(rt.name), fontSize = 13.sp, color = theme.textMain) },
                    trailingIcon = if (rt.id == current?.id) {
                        { Icon(Tabler.Check, contentDescription = null, tint = theme.accentText) }
                    } else null,
                    onClick = { open = false; onPick(rt) }
                )
            }
            HorizontalDivider(color = theme.borderColor)
            DropdownMenuItem(
                text = { Text(tr("從手機匯入音檔…"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.accentText) },
                leadingIcon = { Icon(Tabler.FolderOpen, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(18.dp)) },
                onClick = { open = false; onImport() }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 分頁三：班別模板（網頁版 renderTemplatesView：直接在卡片上修改時段與提前分鐘）
// ---------------------------------------------------------------------------
private val DEFAULT_TEMPLATE_IDS = setOf("tpl-early", "tpl-evening", "tpl-night", "tpl-off")

@Composable
fun WebTemplatesScreen(
    templates: List<ShiftTemplate>,
    defaultWakeOffset: Int,
    defaultLeaveOffset: Int,
    theme: ThemePalette,
    onImportHolidays: () -> Unit,
    onUpdateTemplate: (ShiftTemplate) -> Unit,
    onDeleteTemplate: (ShiftTemplate) -> Unit,
    onAddTemplate: (ShiftTemplate) -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ShiftTemplate?>(null) }
    var lastDelete by remember { mutableStateOf<ShiftTemplate?>(null) }
    confirmDelete?.let { lastDelete = it }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 標題列
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("班別模板"), fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain, modifier = Modifier.weight(1f))
                    if (!adding) WebButton(tr("+ 新增班別"), theme, radius = 16.dp, vertical = 10.dp) { adding = true }
                }
                Text(tr("點時間或分鐘數就能修改，會立即儲存；之後排的班會照新設定響鈴"), fontSize = 11.sp, color = theme.textMuted, lineHeight = 16.sp)
            }
        }
        if (adding) {
            item(key = "add-form") {
                NewTemplateCard(theme, defaultWakeOffset, defaultLeaveOffset, onCancel = { adding = false }) {
                    onAddTemplate(it)
                    adding = false
                }
            }
        }
        items(templates, key = { it.id }) { tpl ->
            TemplateCard(tpl, theme, isDefault = tpl.id in DEFAULT_TEMPLATE_IDS, onUpdate = onUpdateTemplate, onDelete = { confirmDelete = tpl })
        }
        // 國定假日：一年用一次，放最下面、一行就好
        item {
            WebCard(theme, radius = 20.dp, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(EMERALD.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Tabler.CalendarMonth, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(19.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("國定假日"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                        Text(tr("假日還有上班鬧鐘時會提醒你"), fontSize = 11.sp, color = theme.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    WebButton(tr("匯入"), theme, filled = false, color = EMERALD.readable(), icon = Tabler.Download, fontSize = 12.sp, vertical = 8.dp, onClick = onImportHolidays)
                }
            }
        }
    }

    MotionHost(confirmDelete != null) {
        val t = lastDelete
        MotionAlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = theme.bgCard,
            icon = { Icon(Tabler.Trash, contentDescription = null, tint = ROSE.readable()) },
            title = { Text(tr("刪除「{0}」模板？", tr(t?.title ?: "")), fontWeight = FontWeight.Black, color = theme.textMain) },
            text = { Text(tr("已經排好的班和鬧鐘不會受影響，只是之後不能再選這個班別。"), fontSize = 13.sp, color = theme.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (t != null) onDeleteTemplate(t)
                }) { Text(tr("刪除"), color = ROSE.readable(), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("取消"), color = theme.textMuted) } }
        )
    }
}

/** 上班多久：「9 小時」「8 小時 30 分」 */
private fun shiftLength(start: LocalTime, end: LocalTime): String {
    var mins = java.time.Duration.between(start, end).toMinutes()
    if (mins <= 0) mins += 24 * 60
    val h = mins / 60
    val m = mins % 60
    return if (m == 0L) tr("{0} 小時", h) else tr("{0} 小時 {1} 分", h, m)
}

@Composable
private fun ShiftChip(type: ShiftType, title: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(type.chipBg())
            .border(1.dp, type.chipBorder(), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(type.icon(), contentDescription = null, tint = type.chipText(), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(tr(title), fontSize = 13.sp, fontWeight = FontWeight.Black, color = type.chipText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 可點的時間：小標＋大字時間 */
@Composable
private fun TimeTile(
    label: String, time: LocalTime, theme: ThemePalette, tag: String? = null, shiftName: String? = null,
    modifier: Modifier = Modifier, onPick: (LocalTime) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    MotionHost(picking) {
        TimeWheelDialog(
            title = tr("修改{0}時間", label), context = shiftName?.takeIf { it.isNotBlank() }, initial = time, theme = theme,
            onDismiss = { picking = false },
            onConfirm = { picking = false; onPick(it) }
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(theme.bgElevated)
            .clickable { picking = true }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
            if (tag != null) {
                Spacer(Modifier.width(4.dp))
                Text(tag, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA78BFA).readable())
            }
        }
        Text(formatHm(time), fontSize = 24.sp, fontWeight = FontWeight.Black, color = theme.textMain, lineHeight = 28.sp)
    }
}

/** 提前幾分鐘：−／＋ 每次 5 分，中間的數字也可以直接輸入 */
@Composable
private fun OffsetStepper(isWake: Boolean, value: Int, theme: ThemePalette, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val c = (if (isWake) ROSE else AMBER).readable()
    val max = if (isWake) 360 else 240
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(theme.bgElevated).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isWake) Tabler.BellRinging else Tabler.DoorExit, contentDescription = null, tint = c, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (isWake) tr("提前起床（分）") else tr("提前出門（分）"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted, softWrap = false)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(Tabler.Minus, tr("少 5 分"), theme) { onChange((value - 5).coerceAtLeast(0)) }
            WebNumberField(value, c, theme, Modifier.weight(1f).padding(horizontal = 4.dp)) { onChange(it.coerceIn(0, max)) }
            StepButton(Tabler.Plus, tr("多 5 分"), theme) { onChange((value + 5).coerceAtMost(max)) }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, theme: ThemePalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).border(1.dp, theme.borderColor, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = desc, tint = theme.textMain, modifier = Modifier.size(14.dp)) }
}

/** 「06:30 起床 → 07:20 出門 → 08:00 上班」 */
@Composable
private fun AlarmPreviewLine(start: LocalTime, wake: Long, leave: Long, theme: ThemePalette) {
    val wakeC = ROSE.readable(3.0)
    val leaveC = AMBER.readable(3.0)
    Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Tabler.Alarm, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = wakeC, fontWeight = FontWeight.Black)) { append(formatHm(start.minusMinutes(wake))) }
                append(tr(" 起床  →  "))
                withStyle(SpanStyle(color = leaveC, fontWeight = FontWeight.Black)) { append(formatHm(start.minusMinutes(leave))) }
                append(tr(" 出門  →  "))
                withStyle(SpanStyle(color = theme.textMain, fontWeight = FontWeight.Black)) { append(formatHm(start)) }
                append(tr(" 上班"))
            },
            fontSize = 12.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    // 夜班這種凌晨上班的班：起床、出門會落在前一天
    val startMin = start.hour * 60 + start.minute
    if (wake > startMin || leave > startMin) {
        Text(
            if (leave > startMin) tr("起床、出門都在上班前一天") else tr("起床在上班前一天"),
            fontSize = 11.sp, color = theme.textMuted, modifier = Modifier.padding(start = 18.dp, top = 2.dp)
        )
    }
    }
}

@Composable
private fun TemplateCard(tpl: ShiftTemplate, theme: ThemePalette, isDefault: Boolean, onUpdate: (ShiftTemplate) -> Unit, onDelete: () -> Unit) {
    val isCrossDay = !tpl.isOff && !tpl.endTime.isAfter(tpl.startTime)
    WebCard(theme, radius = 24.dp, padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShiftChip(tpl.type, tpl.title)
                Spacer(Modifier.width(8.dp))
                if (!tpl.isOff) {
                    Text(shiftLength(tpl.startTime, tpl.endTime), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.textMuted, softWrap = false)
                }
                Spacer(Modifier.weight(1f))
                if (isDefault) {
                    Text(tr("預設"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
                } else {
                    Icon(
                        Tabler.Trash, contentDescription = tr("刪除模板"), tint = theme.textMuted,
                        modifier = Modifier.size(32.dp).clip(CircleShape).clickable { onDelete() }.padding(7.dp)
                    )
                }
            }
            if (tpl.isOff) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Tabler.ShieldCheck, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("整天休假；忘了關的上班鬧鐘會自動提醒你"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = theme.textMuted)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeTile(tr("上班"), tpl.startTime, theme, shiftName = tpl.title, modifier = Modifier.weight(1f)) { onUpdate(tpl.copy(startTime = it)) }
                    Icon(Tabler.ArrowRight, contentDescription = null, tint = theme.textMuted, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                    TimeTile(tr("下班"), tpl.endTime, theme, tag = if (isCrossDay) tr("隔日") else null, shiftName = tpl.title, modifier = Modifier.weight(1f)) { onUpdate(tpl.copy(endTime = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OffsetStepper(true, tpl.wakeOffsetMinutes.toInt(), theme, Modifier.weight(1f)) { onUpdate(tpl.copy(wakeOffsetMinutes = it.toLong())) }
                    OffsetStepper(false, tpl.leaveOffsetMinutes.toInt(), theme, Modifier.weight(1f)) { onUpdate(tpl.copy(leaveOffsetMinutes = it.toLong())) }
                }
                AlarmPreviewLine(tpl.startTime, tpl.wakeOffsetMinutes, tpl.leaveOffsetMinutes, theme)
            }
        }
    }
}

/** 新增自訂班別：按「+ 新增班別」才出現 */
@Composable
private fun NewTemplateCard(theme: ThemePalette, defaultWake: Int, defaultLeave: Int, onCancel: () -> Unit, onSave: (ShiftTemplate) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ShiftType.EARLY) }
    var start by remember { mutableStateOf(LocalTime.of(12, 0)) }
    var end by remember { mutableStateOf(LocalTime.of(21, 0)) }
    var wake by remember { mutableStateOf(defaultWake) }
    var leave by remember { mutableStateOf(defaultLeave) }
    val colors = listOf(ShiftType.EARLY to tr("早班色"), ShiftType.EVENING to tr("晚班色"), ShiftType.NIGHT to tr("夜班色"))
    WebCard(theme, radius = 24.dp, padding = 16.dp, border = theme.accent.copy(alpha = 0.5f)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("新增自訂班別"), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain)
            WebTextField(name, tr("班別名稱（例如：中班）"), theme, Modifier.fillMaxWidth()) { name = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEach { (t, label) ->
                    val active = t == type
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) t.chipBg() else theme.bgElevated)
                            .border(if (active) 1.5.dp else 1.dp, if (active) t.accentDot else theme.borderColor, RoundedCornerShape(12.dp))
                            .clickable { type = t }
                            .padding(vertical = 9.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(t.icon(), contentDescription = null, tint = if (active) t.chipText() else theme.textMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) t.chipText() else theme.textMuted, softWrap = false)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeTile(tr("上班"), start, theme, shiftName = name, modifier = Modifier.weight(1f)) { start = it }
                Icon(Tabler.ArrowRight, contentDescription = null, tint = theme.textMuted, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                TimeTile(tr("下班"), end, theme, tag = if (!end.isAfter(start)) tr("隔日") else null, shiftName = name, modifier = Modifier.weight(1f)) { end = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OffsetStepper(true, wake, theme, Modifier.weight(1f)) { wake = it }
                OffsetStepper(false, leave, theme, Modifier.weight(1f)) { leave = it }
            }
            AlarmPreviewLine(start, wake.toLong(), leave.toLong(), theme)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WebButton(tr("取消"), theme, Modifier.weight(1f), filled = false, vertical = 12.dp, onClick = onCancel)
                WebButton(tr("儲存"), theme, Modifier.weight(1f), vertical = 12.dp) {
                    if (name.isBlank()) {
                        AppToast.show(tr("請輸入班別名稱"))
                        return@WebButton
                    }
                    onSave(
                        ShiftTemplate(
                            id = "tpl-custom-" + UUID.randomUUID().toString().take(8),
                            title = name.trim(), type = type, startTime = start, endTime = end,
                            wakeOffsetMinutes = wake.toLong(), leaveOffsetMinutes = leave.toLong()
                        )
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分頁四：共享帳號（網頁版 renderSharingAndAccountView；目前沒有真的伺服器，資料為示範）
// ---------------------------------------------------------------------------
@Composable
fun WebSharingScreen(
    events: List<ShiftEvent>,
    alarmsCount: Int,
    partnerSync: PartnerSyncState,
    theme: ThemePalette,
    onUpdate: (PartnerSyncState) -> Unit,
    onOpenPartnerCalendar: (PartnerSubscription) -> Unit,
    viewingCode: String? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val user = CloudSync.user
    val profile = CloudSync.profile
    val subs = CloudSync.subscriptions.toList()
    val signedIn = user != null
    val inviteCode = profile?.inviteCode
    val publicOn = profile?.publicLinkEnabled == true
    var codeInput by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var confirmUnsub by remember { mutableStateOf<PartnerSubscription?>(null) }

    fun act(block: suspend () -> String?) {
        if (working) return
        working = true
        scope.launch {
            val err = runCatching { block() }.getOrElse { it.message ?: tr("發生錯誤") }
            working = false
            if (err != null) AppToast.show(err)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------------- 帳號 ----------------
        item {
            WebCard(theme, radius = 26.dp, padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Tabler.Cloud, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("帳號與雲端資料隔離"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (signedIn) EMERALD.copy(alpha = 0.15f) else theme.bgElevated).padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (signedIn) tr("雲端同步中") else tr("單機免登入模式"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (signedIn) EMERALD.readable() else theme.textMain.copy(alpha = 0.85f))
                        }
                    }
                    if (user != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(theme.accent), contentAlignment = Alignment.Center) {
                                Text(user.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(user.name, fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(user.email, fontSize = 11.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            WebButton(tr("登出"), theme, filled = false, fontSize = 11.sp, vertical = 6.dp, enabled = !working) {
                                act {
                                    CloudSync.signOut(context)
                                    AppToast.show(tr("已登出 Google 帳號，目前為單機模式（本機資料都還在）"))
                                    null
                                }
                            }
                        }
                    } else {
                        Text(
                            tr("目前以單機模式運行，所有排班與鬧鐘資料都儲存在本機。登入 Google 帳號後會產生專屬邀請碼，可以把「公開班表」分享給家人與伴侶，也能訂閱對方的班表。"),
                            fontSize = 13.sp, color = theme.textMain.copy(alpha = 0.85f), lineHeight = 20.sp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(16.dp))
                                .clickable(enabled = !working && !CloudSync.busy) {
                                    val act = context as? android.app.Activity
                                    if (act == null) AppToast.show(tr("無法開啟登入畫面")) else act {
                                        CloudSync.signIn(act) ?: run {
                                            AppToast.show(tr("☁️ 已登入，公開班表開始同步"))
                                            null
                                        }
                                    }
                                }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("G", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFF4285F4))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (working || CloudSync.busy) tr("登入中…") else tr("使用 Google 帳戶登入並同步"),
                                fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1E293B)
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 我的公開班表共享 ----------------
        item {
            WebCard(theme, radius = 26.dp, padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Tabler.Link, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("我的公開班表共享（唯讀）"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
                        if (signedIn) {
                            WebToggle(publicOn, theme) {
                                val on = !publicOn
                                act {
                                    CloudSync.setPublicLink(on) ?: run {
                                        AppToast.show(if (on) tr("🔗 已開啟共享，邀請碼重新生效") else tr("🔒 已關閉共享，邀請碼立即失效"))
                                        null
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(theme.bgElevated)
                            .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("6 位數共享邀請碼"), fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    !signedIn -> tr("登入後產生")
                                    inviteCode == null -> tr("產生中…")
                                    !publicOn -> tr("已停用")
                                    else -> inviteCode
                                },
                                fontSize = if (signedIn && publicOn && inviteCode != null) 22.sp else 13.sp,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = theme.accentText,
                                letterSpacing = if (signedIn && publicOn) 3.sp else 0.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val canShare = signedIn && publicOn && inviteCode != null
                            WebButton(tr("分享邀請碼"), theme, Modifier.weight(1f), enabled = canShare, vertical = 11.dp) {
                                val text = tr("我的 Reverie Alarm 班表邀請碼：{0}\n在 App「共享帳號」分頁輸入這組代碼，就能看到我的班表。", inviteCode)
                                runCatching {
                                    context.startActivity(android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                                        }, tr("分享邀請碼")
                                    ))
                                }.onFailure {
                                    clipboard.setText(AnnotatedString(text))
                                    AppToast.show(tr("📋 已複製邀請碼"))
                                }
                            }
                            WebButton(tr("預覽對方看到的"), theme, Modifier.weight(1f), filled = false, vertical = 11.dp) { showPreview = true }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(EMERALD.copy(alpha = 0.10f))
                            .border(1.dp, EMERALD.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        Icon(Tabler.Lock, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(16.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append(tr("資料隔離：")) }
                                append(tr("雲端只存「日期、班別名稱與上下班時間」。私人備註、排班圖片、附件與鬧鐘不會上傳，任何人都看不到。關閉共享後，對方立刻無法讀取。"))
                            },
                            fontSize = 12.sp, color = Color(0xFF6EE7B7).readable(6.0), lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // ---------------- 我訂閱的班表 ----------------
        item {
            WebCard(theme, radius = 26.dp, padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Tabler.Users, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("已訂閱的親友／伴侶班表"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
                        val on = partnerSync.holidayCompare
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) EMERALD.copy(alpha = 0.2f) else theme.bgElevated)
                                .border(1.dp, if (on) EMERALD else theme.borderColor, RoundedCornerShape(12.dp))
                                .clickable {
                                    onUpdate(partnerSync.copy(holidayCompare = !on))
                                    AppToast.show(if (!on) tr("💚 已開啟共同休假對照，綠色標記為兩人同休日") else tr("已關閉共同休假對照"))
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            IconText(if (on) tr("✓ 共同休假對照中") else tr("共同休假對照"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = if (on) EMERALD.readable() else theme.textMain)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WebTextField(codeInput, if (signedIn) tr("輸入對方 6 位數邀請碼") else tr("登入後才能訂閱"), theme, Modifier.weight(1f)) { v -> codeInput = v.filter { it.isDigit() }.take(6) }
                        Spacer(Modifier.width(10.dp))
                        WebButton(if (working) tr("處理中…") else tr("訂閱班表"), theme, vertical = 12.dp, enabled = signedIn && !working) {
                            val code = codeInput
                            act {
                                CloudSync.subscribe(code) ?: run {
                                    codeInput = ""
                                    AppToast.show(tr("✅ 已訂閱邀請碼 #{0} 的唯讀班表", code))
                                    null
                                }
                            }
                        }
                    }
                    if (subs.isEmpty()) {
                        Text(
                            if (signedIn) tr("還沒有訂閱任何班表。請對方在他的 App 按「分享邀請碼」，再把代碼輸入上方。") else tr("登入 Google 帳號後，就能訂閱家人或伴侶的班表。"),
                            fontSize = 12.sp, color = theme.textMuted, lineHeight = 18.sp
                        )
                    }
                    subs.forEach { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(theme.bgElevated)
                                .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(sub.name, fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        sub.unavailable -> tr("對方已關閉共享")
                                        sub.shifts == null -> tr("邀請碼：{0} · 同步中…", sub.code)
                                        else -> tr("邀請碼：{0} · {1} 天班表", sub.code, sub.shifts.size)
                                    },
                                    fontSize = 12.sp, color = if (sub.unavailable) ROSE.readable() else theme.textMuted
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            val isViewing = viewingCode == sub.code
                            WebButton(
                                if (isViewing) tr("檢視中") else tr("查看日曆 →"), theme, filled = isViewing,
                                fontSize = 12.sp, vertical = 9.dp, radius = 16.dp, enabled = !sub.unavailable
                            ) { onOpenPartnerCalendar(sub) }
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Tabler.Trash, contentDescription = tr("取消訂閱"), tint = theme.textMuted,
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable { confirmUnsub = sub }.padding(7.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    MotionHost(showPreview) {
        PublicSchedulePreview(
            title = tr("{0}的公開班表", user?.name ?: "我"),
            rows = events.sortedBy { it.date }.map { PartnerShift(it.date.toString(), it.title, it.type.name, it.startTime.toString(), it.endTime.toString()) },
            theme = theme,
            footer = tr("對方只看得到這些：不包含任何鬧鐘、私人備註、圖片或附件。"),
            onDismiss = { showPreview = false }
        )
    }

    var lastUnsub by remember { mutableStateOf<PartnerSubscription?>(null) }
    confirmUnsub?.let { lastUnsub = it }
    MotionHost(confirmUnsub != null) {
        val sub = lastUnsub
        MotionAlertDialog(
            onDismissRequest = { confirmUnsub = null },
            containerColor = theme.bgCard,
            title = { Text(tr("取消訂閱？"), fontWeight = FontWeight.Black, color = theme.textMain) },
            text = { Text(tr("不再顯示「{0}」的班表。之後可以再用邀請碼重新訂閱。", sub?.name), fontSize = 13.sp, color = theme.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsub = null
                    if (sub != null) scope.launch { CloudSync.unsubscribe(sub.uid) }
                }) { Text(tr("取消訂閱"), color = ROSE.readable(), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmUnsub = null }) { Text(tr("保留"), color = theme.textMuted) } }
        )
    }
}

/** 唯讀班表清單（家人網頁視角 / 訂閱對象的班表） */
@Composable
fun PublicSchedulePreview(title: String, rows: List<PartnerShift>, theme: ThemePalette, footer: String, onDismiss: () -> Unit) {
    MotionAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.bgCard,
        title = {
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                Text(tr("免下載 App・即時唯讀網頁"), fontSize = 11.sp, color = theme.textMuted)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rows.isEmpty()) item { Text(tr("目前沒有公開的排班"), fontSize = 12.sp, color = theme.textMuted) }
                    items(rows) { r ->
                        val type = runCatching { ShiftType.valueOf(r.type) }.getOrDefault(ShiftType.CUSTOM)
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bgElevated).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(r.date, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = theme.textMuted)
                            Spacer(Modifier.width(8.dp))
                            Icon(type.icon(), contentDescription = null, tint = type.chipText(), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr(r.title), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = type.chipText(), modifier = Modifier.weight(1f))
                            Text(if (type == ShiftType.OFF) tr("全天休假") else "${r.start.take(5)} - ${r.end.take(5)}", fontSize = 11.sp, color = theme.textMain)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Tabler.ShieldCheck, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(footer, fontSize = 11.sp, color = EMERALD.readable())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("關閉"), color = theme.accentText) } }
    )
}

// ---------------------------------------------------------------------------
// 選取日曆格子時浮出的「編輯」膠囊
//   - 預設在格子正上方置中，間距 8dp，不遮住日期與班別
//   - 第一排（上方是星期列）或上方空間不足時，改到格子正下方
//   - 左右永遠留 8dp，不會超出螢幕
// ---------------------------------------------------------------------------
private class CapsulePositionProvider(
    private val preferBelow: Boolean,
    private val gapPx: Int,
    private val edgePx: Int,
    private val topLimitPx: Int
) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val centerX = anchorBounds.left + anchorBounds.width / 2 - w / 2
        val x = centerX.coerceIn(edgePx, (windowSize.width - w - edgePx).coerceAtLeast(edgePx))
        val aboveY = anchorBounds.top - gapPx - h
        val belowY = anchorBounds.bottom + gapPx
        val fitsAbove = aboveY >= topLimitPx
        val fitsBelow = belowY + h <= windowSize.height - edgePx
        val y = when {
            preferBelow && fitsBelow -> belowY
            fitsAbove -> aboveY
            fitsBelow -> belowY
            else -> aboveY.coerceAtLeast(edgePx)
        }
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

@Composable
fun EditCapsulePopup(isFirstRow: Boolean, theme: ThemePalette, onEdit: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val provider = remember(isFirstRow, density) {
        with(density) {
            CapsulePositionProvider(
                preferBelow = isFirstRow,
                gapPx = 8.dp.roundToPx(),
                edgePx = 8.dp.roundToPx(),
                // 上方至少要留過狀態列與頂部導覽列（約 72dp），否則改放下方
                topLimitPx = 72.dp.roundToPx()
            )
        }
    }
    // 進場：由下往上浮出放大；退場：向下微縮並淡出（MotionHost 控制）
    val appear by overlayProgress(enterMs = 260, exitMs = 200)
    val closing = overlayClosing()
    androidx.compose.ui.window.Popup(
        popupPositionProvider = provider,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false)
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp) // 給陰影留空間
                .graphicsLayer {
                    alpha = appear
                    val sc = 0.6f + 0.4f * appear
                    scaleX = sc; scaleY = sc
                    translationY = (1f - appear) * 18.dp.toPx()
                }
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(50),
                    ambientColor = Color.Black.copy(alpha = if (theme.isDark) 0.6f else 0.25f),
                    spotColor = Color.Black.copy(alpha = if (theme.isDark) 0.7f else 0.35f)
                )
                .clip(RoundedCornerShape(50))
                .background(theme.accent)
                .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(50))
                .clickable(enabled = !closing) { onEdit() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Tabler.Pencil, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("編輯"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}


/** 從側邊欄打開的全螢幕子頁面：頂部返回鍵＋標題，內容可捲動 */
@Composable
fun SubPageScaffold(title: String, theme: ThemePalette, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.bgBase)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(theme.bgCard)
                    .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Tabler.ArrowLeft, contentDescription = tr("返回"), tint = theme.textMain, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Black, color = theme.textMain)
        }
        HorizontalDivider(color = theme.borderColor)
        Box(Modifier.weight(1f)) { content() }
    }
    // 子頁面是獨立視窗，提示氣泡要在這裡再放一份才看得到
    TopToastHost(theme)
}
