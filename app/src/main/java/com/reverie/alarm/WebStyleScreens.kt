package com.reverie.alarm

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
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
    ShiftType.EARLY -> Icons.Outlined.WbSunny
    ShiftType.EVENING -> Icons.Outlined.WbTwilight
    ShiftType.NIGHT -> Icons.Outlined.DarkMode
    ShiftType.OFF -> Icons.Outlined.Eco
    ShiftType.CUSTOM -> Icons.Outlined.PushPin
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
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (filled) Color.White else theme.textMain, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, color = if (filled) Color.White else theme.textMain, maxLines = 1)
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
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(18.dp))
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

private fun formatHm(t: LocalTime) = "${t.hour}:${t.minute.toString().padStart(2, '0')}"

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
    onAddAlarm: () -> Unit
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
                ) { Icon(Icons.Outlined.Menu, contentDescription = "開啟左側收納選單", tint = theme.textMain, modifier = Modifier.size(22.dp)) }
                if (safeguardAlert) {
                    Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(AMBER))
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.ChevronLeft, contentDescription = "上一期", tint = theme.textMain.copy(alpha = 0.75f),
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).clickable { onPrev() }.padding(4.dp)
            )
            Row(
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onToday() }.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${yearMonth.year}年 ${yearMonth.monthValue}月", fontSize = 18.sp, fontWeight = FontWeight.Black, color = theme.textMain, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(theme.accent.copy(alpha = 0.18f))
                        .border(1.dp, theme.accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) { Text("今", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = theme.accentText) }
            }
            Icon(
                Icons.Outlined.ChevronRight, contentDescription = "下一期", tint = theme.textMain.copy(alpha = 0.75f),
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
                ) { Icon(Icons.Filled.Bolt, contentDescription = "批次一鍵排班", tint = AMBER.readable(), modifier = Modifier.size(22.dp)) }
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
                ) { Icon(Icons.Filled.MoreVert, contentDescription = "開啟更多操作選單", tint = Color.White, modifier = Modifier.size(22.dp)) }
                MotionDropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { onToggleMoreMenu(false) },
                    palette = theme
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("快速建立與檢索", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = theme.textMuted, modifier = Modifier.weight(1f))
                        Text(
                            "%02d-%02d".format(selectedDate.monthValue, selectedDate.dayOfMonth),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = theme.accent
                        )
                    }
                    MenuEntry(Icons.Outlined.Search, Color(0xFF38BDF8), "搜尋日程與鬧鐘", "關鍵字篩選班別、備註與提醒", theme) { onToggleMoreMenu(false); onSearch() }
                    HorizontalDivider(color = theme.borderColor, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    MenuEntry(Icons.Outlined.Add, theme.accent, "新增排班／日程", "套用班別模板、備註與附件", theme, solid = true) { onToggleMoreMenu(false); onAddShift() }
                    MenuEntry(Icons.Outlined.Alarm, theme.accent, "新增日期鬧鐘", "指定日曆日期精準強效提醒", theme) { onToggleMoreMenu(false); onAddAlarm() }
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
        Icons.Outlined.CalendarMonth to "排班日曆",
        Icons.Outlined.Alarm to "兩段鬧鐘",
        Icons.Outlined.GridView to "班別模板",
        Icons.Outlined.Group to "共享帳號",
        Icons.Outlined.PhoneAndroid to "桌面工具"
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
            }.getOrNull() ?: "匯入音檔"
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
            AppToast.show("無法播放這個音檔（檔案可能已被移除）")
        }
    }

    val sorted = alarms.sortedWith(compareBy({ it.date }, { it.triggerTime }))
    val groups = sorted.groupBy { it.date }
    val today = LocalDate.now()
    val weekNames = listOf("週一", "週二", "週三", "週四", "週五", "週六", "週日")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 標題列
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("兩段式日期鬧鐘清單", fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                        Text("僅在指定「年/月/日」觸發，避免無效重複響鈴", fontSize = 11.sp, color = theme.textMuted)
                    }
                    if (sorted.isNotEmpty()) {
                        WebButton(if (selectMode) "完成" else "多選刪除", theme, filled = false, radius = 16.dp, vertical = 10.dp) {
                            selectMode = !selectMode
                            selected.clear()
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (!selectMode) {
                        WebButton("+ 新增鬧鐘", theme, radius = 16.dp, vertical = 10.dp, onClick = onAddAlarm)
                    }
                }
                if (selectMode && sorted.isNotEmpty()) {
                    WebCard(theme, elevated = true, radius = 16.dp, padding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val all = selected.size == sorted.size
                            WebButton(if (all) "取消全選" else "全選 (${sorted.size})", theme, filled = false, fontSize = 11.sp, vertical = 7.dp) {
                                selected.clear()
                                if (!all) selected.addAll(sorted.map { it.id })
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                buildAnnotatedString {
                                    append("已選 ")
                                    withStyle(SpanStyle(color = theme.accentText, fontWeight = FontWeight.Black)) { append("${selected.size}") }
                                    append(" 個")
                                },
                                fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.weight(1f)
                            )
                            WebButton(
                                "刪除所選" + if (selected.isNotEmpty()) " (${selected.size})" else "",
                                theme, color = ROSE.readable(), icon = Icons.Outlined.Delete, fontSize = 11.sp, vertical = 7.dp,
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
                    Text("目前尚未設定任何日期鬧鐘", fontSize = 13.sp, color = theme.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        groups.forEach { (date, items) ->
            item(key = "group-$date") {
                val dayShift = events.firstOrNull { it.date == date }
                val isToday = date == today
                val allGroupSelected = items.all { selected.contains(it.id) }
                WebCard(theme, radius = 28.dp, padding = 12.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 日期標題
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isToday) theme.accent else theme.bgBase)
                                    .border(1.dp, if (isToday) Color.Transparent else theme.borderColor, RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(date.toString(), fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = if (isToday) Color.White else theme.textMain)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(weekNames[date.dayOfWeek.value - 1], fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = theme.textMain)
                            if (isToday) { Spacer(Modifier.width(6.dp)); WebPill("今天", EMERALD) }
                            if (date == today.plusDays(1)) { Spacer(Modifier.width(6.dp)); WebPill("明天", AMBER) }
                            if (dayShift?.type == ShiftType.OFF) { Spacer(Modifier.width(6.dp)); WebPill("休假日", EMERALD) }
                            if (dayShift != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "· ${dayShift.title}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.textMain.copy(alpha = 0.85f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (selectMode) {
                                Text(
                                    if (allGroupSelected) "取消整天" else "選取整天", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                        if (allGroupSelected) selected.removeAll(items.map { it.id }.toSet())
                                        else items.forEach { if (!selected.contains(it.id)) selected.add(it.id) }
                                    }.padding(6.dp)
                                )
                            } else {
                                Text("${items.size} 個", fontSize = 12.sp, color = theme.textMuted, softWrap = false)
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClearDate(date) }.padding(4.dp)
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = ROSE.readable(), modifier = Modifier.size(15.dp))
                                    Text("清空此日", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = ROSE.readable(), softWrap = false)
                                }
                            }
                        }
                        HorizontalDivider(color = theme.borderColor)
                        items.forEach { al ->
                            AlarmItemCard(
                                al = al, theme = theme, selectMode = selectMode, isSelected = selected.contains(al.id),
                                ringtones = ringtones, currentRingtone = ringtoneFor(al),
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

        // 鈴聲庫（網頁版放在同一分頁）
        item {
            WebCard(theme, radius = 24.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("鬧鐘鈴聲庫與手機音訊匯入", fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                            }
                            Text("支援從手機檔案總管匯入 MP3 / M4A / WAV 作為鬧鐘鈴聲", fontSize = 10.sp, color = theme.textMuted)
                        }
                        WebButton("匯入手機音訊", theme, icon = Icons.Outlined.FolderOpen, fontSize = 11.sp, vertical = 7.dp) {
                            importTarget = null
                            picker.launch(arrayOf("audio/*"))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AlarmStage.WAKE to defaultWakeUri, AlarmStage.LEAVE to defaultLeaveUri).forEach { (stage, uri) ->
                            Column(Modifier.weight(1f)) {
                                Text(if (stage == AlarmStage.WAKE) "預設「第一段起床」鈴聲" else "預設「第二段出門」鈴聲", fontSize = 10.sp, color = theme.textMuted)
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
                            WebPill(if (rt.isBuiltIn) "內建" else "本機音檔", if (rt.isBuiltIn) theme.accent else EMERALD, fontSize = 9.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(rt.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            val playing = previewId == rt.id
                            WebButton(
                                if (playing) "停止" else "試聽", theme, filled = playing, color = ROSE.readable(),
                                icon = if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, fontSize = 11.sp, vertical = 5.dp, radius = 10.dp
                            ) { togglePreview(rt) }
                            if (!rt.isBuiltIn) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Outlined.Delete, contentDescription = "移除匯入音檔", tint = ROSE.readable(),
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

@Composable
private fun RingtoneDropdown(ringtones: List<RingtoneEntry>, current: RingtoneEntry?, theme: ThemePalette, modifier: Modifier = Modifier, onPick: (RingtoneEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        WebSelectBox(current?.name ?: BuiltInRingtones.WAKE_STRONG.name, theme, Modifier.fillMaxWidth(), fontSize = 12.sp) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = theme.bgCard) {
            ringtones.forEach { rt ->
                DropdownMenuItem(
                    text = { Text(rt.name, fontSize = 13.sp, color = theme.textMain) },
                    trailingIcon = if (rt.id == current?.id) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = theme.accentText) }
                    } else null,
                    onClick = { open = false; onPick(rt) }
                )
            }
        }
    }
}

@Composable
private fun AlarmItemCard(
    al: ShiftAlarm,
    theme: ThemePalette,
    selectMode: Boolean,
    isSelected: Boolean,
    ringtones: List<RingtoneEntry>,
    currentRingtone: RingtoneEntry?,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTestRing: () -> Unit,
    onToggle: () -> Unit,
    onPickRingtone: (RingtoneEntry) -> Unit,
    onImport: () -> Unit
) {
    val (stageText, stageColor) = when (al.stage) {
        AlarmStage.WAKE -> "第一段・強效起床" to ROSE
        AlarmStage.LEAVE -> "第二段・出門倒數" to AMBER
        AlarmStage.CUSTOM -> "獨立日期鬧鐘" to theme.accent
    }
    val alpha = if (al.enabled) 1f else 0.65f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (isSelected) theme.accent.copy(alpha = 0.15f) else theme.bgElevated)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) theme.accent else theme.borderColor, RoundedCornerShape(22.dp))
            .clickable(enabled = selectMode) { onSelect() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectMode) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isSelected) theme.accent else Color.Transparent)
                        .border(2.dp, if (isSelected) Color.Transparent else theme.textMuted, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center
                ) { if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        al.triggerTime.toString().take(5), fontSize = 23.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                        color = theme.textMain.copy(alpha = alpha), softWrap = false
                    )
                    Spacer(Modifier.width(6.dp))
                    WebPill(stageText, stageColor, fontSize = 9.sp)
                }
                Text(al.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.textMain.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selectMode) {
                WebButton("刪除", theme, color = ROSE.readable(), icon = Icons.Outlined.Delete, fontSize = 11.sp, vertical = 6.dp, radius = 12.dp, onClick = onDelete)
            } else {
                WebButton("試響", theme, filled = false, icon = Icons.AutoMirrored.Outlined.VolumeUp, fontSize = 11.sp, vertical = 6.dp, radius = 14.dp, onClick = onTestRing)
                Spacer(Modifier.width(8.dp))
                WebToggle(al.enabled, theme, onToggle)
            }
        }
        HorizontalDivider(color = theme.borderColor.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(14.dp))
            Text(" 鈴聲：", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.accentText)
            RingtoneDropdown(ringtones, currentRingtone, theme, Modifier.weight(1f), onPickRingtone)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.accent.copy(alpha = 0.15f))
                    .border(1.dp, theme.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .clickable { onImport() }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) { Text("+ 匯入", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.accentText) }
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
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            WebCard(theme, radius = 20.dp, bg = theme.accent.copy(alpha = 0.10f), border = theme.accent.copy(alpha = 0.35f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("班別模板自由時段設定", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = theme.accentText)
                    }
                    Text(
                        buildAnnotatedString {
                            append("您可以直接調整各班別的")
                            withStyle(SpanStyle(fontWeight = FontWeight.Black, color = theme.textMain)) { append("上班／下班時段") }
                            append("以及")
                            withStyle(SpanStyle(fontWeight = FontWeight.Black, color = theme.textMain)) { append("班前起床／出門提醒分鐘數") }
                            append("。修改後會即時儲存，未來套用該班別時將自動依新時段排程。")
                        },
                        fontSize = 12.sp, color = theme.textMain.copy(alpha = 0.85f), lineHeight = 18.sp
                    )
                }
            }
        }
        item {
            WebCard(theme, radius = 20.dp, bg = EMERALD.copy(alpha = 0.10f), border = EMERALD.copy(alpha = 0.35f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("國定假日行事曆連動", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = EMERALD.readable())
                        }
                        Text("匯入年度國定假日，自動觸發「休假日鬧鐘防呆」", fontSize = 11.sp, color = theme.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    WebButton("一鍵匯入假日", theme, color = Color(0xFF059669), fontSize = 13.sp, vertical = 10.dp, onClick = onImportHolidays)
                }
            }
        }
        items(templates, key = { it.id }) { tpl ->
            TemplateCard(tpl, theme, isDefault = tpl.id in DEFAULT_TEMPLATE_IDS, onUpdate = onUpdateTemplate, onDelete = { onDeleteTemplate(tpl) })
        }
        item {
            var name by remember { mutableStateOf("") }
            var type by remember { mutableStateOf(ShiftType.EARLY) }
            var start by remember { mutableStateOf(LocalTime.of(12, 0)) }
            var end by remember { mutableStateOf(LocalTime.of(21, 0)) }
            var wake by remember { mutableStateOf(defaultWakeOffset) }
            var leave by remember { mutableStateOf(defaultLeaveOffset) }
            var typeOpen by remember { mutableStateOf(false) }
            val typeNames = mapOf(ShiftType.EARLY to "琥珀金 (早班系)", ShiftType.EVENING to "靛紫色 (晚班系)", ShiftType.NIGHT to "青綠色 (夜班系)")
            WebCard(theme, radius = 24.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("+ 新增自訂輪班模板", fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WebTextField(name, "模板名稱 (如: 中班)", theme, Modifier.weight(1f)) { name = it }
                        Box(Modifier.weight(1f)) {
                            WebSelectBox(typeNames.getValue(type), theme, Modifier.fillMaxWidth()) { typeOpen = true }
                            DropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }, containerColor = theme.bgCard) {
                                typeNames.forEach { (t, label) ->
                                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp, color = theme.textMain) }, onClick = { type = t; typeOpen = false })
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("上班時間", fontSize = 11.sp, color = theme.textMuted)
                            Spacer(Modifier.height(4.dp))
                            WebSelectBox(formatHm(start), theme, Modifier.fillMaxWidth()) { showTimePicker(context, start) { start = it } }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("下班時間", fontSize = 11.sp, color = theme.textMuted)
                            Spacer(Modifier.height(4.dp))
                            WebSelectBox(formatHm(end), theme, Modifier.fillMaxWidth()) { showTimePicker(context, end) { end = it } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("起床提前：", fontSize = 12.sp, color = theme.textMuted)
                        WebNumberField(wake, theme.textMain, theme, Modifier.width(64.dp)) { wake = it }
                        Text(" 分", fontSize = 12.sp, color = theme.textMuted)
                        Spacer(Modifier.weight(1f))
                        Text("出門提前：", fontSize = 12.sp, color = theme.textMuted)
                        WebNumberField(leave, theme.textMain, theme, Modifier.width(64.dp)) { leave = it }
                        Text(" 分", fontSize = 12.sp, color = theme.textMuted)
                    }
                    WebButton("儲存自訂模板", theme, Modifier.fillMaxWidth(), vertical = 12.dp) {
                        if (name.isBlank()) {
                            AppToast.show("請輸入模板名稱")
                            return@WebButton
                        }
                        onAddTemplate(
                            ShiftTemplate(
                                id = "tpl-custom-" + UUID.randomUUID().toString().take(8),
                                title = name.trim(), type = type, startTime = start, endTime = end,
                                wakeOffsetMinutes = wake.toLong(), leaveOffsetMinutes = leave.toLong()
                            )
                        )
                        name = ""
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(tpl: ShiftTemplate, theme: ThemePalette, isDefault: Boolean, onUpdate: (ShiftTemplate) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val isCrossDay = !tpl.isOff && !tpl.endTime.isAfter(tpl.startTime)
    WebCard(theme, radius = 24.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(tpl.type.chipBg())
                        .border(1.dp, tpl.type.chipBorder(), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(tpl.type.icon(), contentDescription = null, tint = tpl.type.chipText(), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tpl.title, fontSize = 13.sp, fontWeight = FontWeight.Black, color = tpl.type.chipText())
                }
                if (isCrossDay) { Spacer(Modifier.width(8.dp)); WebPill("跨隔日", Color(0xFF14B8A6)) }
                Spacer(Modifier.weight(1f))
                if (isDefault) {
                    Text("預設模板", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
                } else {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(ROSE.copy(alpha = 0.12f)).clickable { onDelete() }.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = ROSE.readable(), modifier = Modifier.size(14.dp))
                        Text("刪除", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ROSE.readable())
                    }
                }
            }
            if (tpl.isOff) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(EMERALD.copy(alpha = 0.10f))
                        .border(1.dp, EMERALD.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("全天休假・自動啟動「休假日鬧鐘防呆」偵測", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EMERALD.readable())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(true, false).forEach { isStart ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(theme.bgElevated)
                                .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                buildAnnotatedString {
                                    append(if (isStart) "上班時間" else "下班時間")
                                    if (!isStart && isCrossDay) withStyle(SpanStyle(color = Color(0xFFA78BFA).readable())) { append(" (隔日)") }
                                },
                                fontSize = 11.sp, color = theme.textMuted
                            )
                            Spacer(Modifier.height(6.dp))
                            val t = if (isStart) tpl.startTime else tpl.endTime
                            WebSelectBox(formatHm(t), theme, Modifier.fillMaxWidth()) {
                                showTimePicker(context, t) { picked ->
                                    onUpdate(if (isStart) tpl.copy(startTime = picked) else tpl.copy(endTime = picked))
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(true, false).forEach { isWake ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(theme.bgElevated)
                                .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val c = (if (isWake) ROSE else AMBER).readable()
                            Icon(
                                if (isWake) Icons.Outlined.NotificationsActive else Icons.AutoMirrored.Outlined.ExitToApp,
                                contentDescription = null, tint = c, modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(if (isWake) "起床提前" else "出門提前", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = theme.textMain, softWrap = false)
                            Spacer(Modifier.weight(1f))
                            WebNumberField(
                                (if (isWake) tpl.wakeOffsetMinutes else tpl.leaveOffsetMinutes).toInt(), c, theme, Modifier.width(46.dp)
                            ) { v ->
                                val clamped = v.coerceIn(0, if (isWake) 360 else 240).toLong()
                                onUpdate(if (isWake) tpl.copy(wakeOffsetMinutes = clamped) else tpl.copy(leaveOffsetMinutes = clamped))
                            }
                            Text(" 分", fontSize = 11.sp, color = theme.textMuted)
                        }
                    }
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
            val err = runCatching { block() }.getOrElse { it.message ?: "發生錯誤" }
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
                        Icon(Icons.Outlined.Cloud, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("帳號與雲端資料隔離", fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (signedIn) EMERALD.copy(alpha = 0.15f) else theme.bgElevated).padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (signedIn) "雲端同步中" else "單機免登入模式", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (signedIn) EMERALD.readable() else theme.textMain.copy(alpha = 0.85f))
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
                            WebButton("登出", theme, filled = false, fontSize = 11.sp, vertical = 6.dp, enabled = !working) {
                                act {
                                    CloudSync.signOut(context)
                                    AppToast.show("已登出 Google 帳號，目前為單機模式（本機資料都還在）")
                                    null
                                }
                            }
                        }
                        Text(
                            "☁️ 公開班表會自動同步到雲端。私人備註、圖片、附件與鬧鐘只存在這支手機，不會上傳。",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EMERALD.readable(), lineHeight = 18.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(EMERALD.copy(alpha = 0.10f)).padding(10.dp)
                        )
                    } else {
                        Text(
                            "目前以單機模式運行，所有排班與鬧鐘資料都儲存在本機。登入 Google 帳號後會產生專屬邀請碼，可以把「公開班表」分享給家人與伴侶，也能訂閱對方的班表。",
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
                                    if (act == null) AppToast.show("無法開啟登入畫面") else act {
                                        CloudSync.signIn(act) ?: run {
                                            AppToast.show("☁️ 已登入，公開班表開始同步")
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
                                if (working || CloudSync.busy) "登入中…" else "使用 Google 帳戶登入並同步",
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
                        Icon(Icons.Outlined.Link, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("我的公開班表共享（唯讀）", fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
                        if (signedIn) {
                            WebToggle(publicOn, theme) {
                                val on = !publicOn
                                act {
                                    CloudSync.setPublicLink(on) ?: run {
                                        AppToast.show(if (on) "🔗 已開啟共享，邀請碼重新生效" else "🔒 已關閉共享，邀請碼立即失效")
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
                            Text("6 位數共享邀請碼", fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    !signedIn -> "登入後產生"
                                    inviteCode == null -> "產生中…"
                                    !publicOn -> "已停用"
                                    else -> inviteCode
                                },
                                fontSize = if (signedIn && publicOn && inviteCode != null) 22.sp else 13.sp,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = theme.accentText,
                                letterSpacing = if (signedIn && publicOn) 3.sp else 0.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val canShare = signedIn && publicOn && inviteCode != null
                            WebButton("分享邀請碼", theme, Modifier.weight(1f), enabled = canShare, vertical = 11.dp) {
                                val text = "我的 Reverie Alarm 班表邀請碼：$inviteCode\n在 App「共享帳號」分頁輸入這組代碼，就能看到我的班表。"
                                runCatching {
                                    context.startActivity(android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                                        }, "分享邀請碼"
                                    ))
                                }.onFailure {
                                    clipboard.setText(AnnotatedString(text))
                                    AppToast.show("📋 已複製邀請碼")
                                }
                            }
                            WebButton("預覽對方看到的", theme, Modifier.weight(1f), filled = false, vertical = 11.dp) { showPreview = true }
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
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(16.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("資料隔離：") }
                                append("雲端只存「日期、班別名稱與上下班時間」。私人備註、排班圖片、附件與鬧鐘不會上傳，任何人都看不到。關閉共享後，對方立刻無法讀取。")
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
                        Icon(Icons.Outlined.Group, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("已訂閱的親友／伴侶班表", fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
                        val on = partnerSync.holidayCompare
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) EMERALD.copy(alpha = 0.2f) else theme.bgElevated)
                                .border(1.dp, if (on) EMERALD else theme.borderColor, RoundedCornerShape(12.dp))
                                .clickable {
                                    onUpdate(partnerSync.copy(holidayCompare = !on))
                                    AppToast.show(if (!on) "💚 已開啟共同休假對照，綠色標記為兩人同休日" else "已關閉共同休假對照")
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(if (on) "✓ 共同休假對照中" else "共同休假對照", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = if (on) EMERALD.readable() else theme.textMain)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WebTextField(codeInput, if (signedIn) "輸入對方 6 位數邀請碼" else "登入後才能訂閱", theme, Modifier.weight(1f)) { v -> codeInput = v.filter { it.isDigit() }.take(6) }
                        Spacer(Modifier.width(10.dp))
                        WebButton(if (working) "處理中…" else "訂閱班表", theme, vertical = 12.dp, enabled = signedIn && !working) {
                            val code = codeInput
                            act {
                                CloudSync.subscribe(code) ?: run {
                                    codeInput = ""
                                    AppToast.show("✅ 已訂閱邀請碼 #$code 的唯讀班表")
                                    null
                                }
                            }
                        }
                    }
                    if (subs.isEmpty()) {
                        Text(
                            if (signedIn) "還沒有訂閱任何班表。請對方在他的 App 按「分享邀請碼」，再把代碼輸入上方。" else "登入 Google 帳號後，就能訂閱家人或伴侶的班表。",
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
                                        sub.unavailable -> "對方已關閉共享"
                                        sub.shifts == null -> "邀請碼：${sub.code} · 同步中…"
                                        else -> "邀請碼：${sub.code} · ${sub.shifts.size} 天班表"
                                    },
                                    fontSize = 12.sp, color = if (sub.unavailable) ROSE.readable() else theme.textMuted
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            val isViewing = viewingCode == sub.code
                            WebButton(
                                if (isViewing) "檢視中" else "查看日曆 →", theme, filled = isViewing,
                                fontSize = 12.sp, vertical = 9.dp, radius = 16.dp, enabled = !sub.unavailable
                            ) { onOpenPartnerCalendar(sub) }
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.Delete, contentDescription = "取消訂閱", tint = theme.textMuted,
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
            title = "${user?.name ?: "我"}的公開班表",
            rows = events.sortedBy { it.date }.map { PartnerShift(it.date.toString(), it.title, it.type.name, it.startTime.toString(), it.endTime.toString()) },
            theme = theme,
            footer = "對方只看得到這些：不包含任何鬧鐘、私人備註、圖片或附件。",
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
            title = { Text("取消訂閱？", fontWeight = FontWeight.Black, color = theme.textMain) },
            text = { Text("不再顯示「${sub?.name}」的班表。之後可以再用邀請碼重新訂閱。", fontSize = 13.sp, color = theme.textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsub = null
                    if (sub != null) scope.launch { CloudSync.unsubscribe(sub.uid) }
                }) { Text("取消訂閱", color = ROSE.readable(), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmUnsub = null }) { Text("保留", color = theme.textMuted) } }
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
                Text("免下載 App・即時唯讀網頁", fontSize = 11.sp, color = theme.textMuted)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rows.isEmpty()) item { Text("目前沒有公開的排班", fontSize = 12.sp, color = theme.textMuted) }
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
                            Text(r.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = type.chipText(), modifier = Modifier.weight(1f))
                            Text(if (type == ShiftType.OFF) "全天休假" else "${r.start.take(5)} - ${r.end.take(5)}", fontSize = 11.sp, color = theme.textMain)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(footer, fontSize = 11.sp, color = EMERALD.readable())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉", color = theme.accentText) } }
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
            Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("編輯", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

