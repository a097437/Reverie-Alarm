package com.reverie.alarm

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// ============================================================================
// 與網頁版功能對齊的元件：
//   1. 日期／時間選擇器
//   2. 圖片縮圖與可左右滑動的全螢幕燈箱
//   3. 日期編輯面板（班別名稱、色系、時間、同步重設鬧鐘、當日鬧鐘微調）
//   4. 新增日期鬧鐘面板（提醒類型、貪睡間隔、鈴聲、倒數預覽）
// ============================================================================

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SHIFT_TYPE_OPTIONS get() = listOf(
    ShiftType.EARLY to tr("☀️ 早班"),
    ShiftType.EVENING to tr("🌆 晚班"),
    ShiftType.NIGHT to tr("🌙 夜班"),
    ShiftType.OFF to tr("🌿 休假"),
    ShiftType.CUSTOM to tr("📌 日程")
)

fun showDatePicker(context: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, y, mZeroBased, d -> onPick(LocalDate.of(y, mZeroBased + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth
    ).show()
}

/** 網頁版 formatCountdownDiff：距離響鈴還有多久 */
fun formatCountdown(from: LocalDateTime, to: LocalDateTime): String {
    val mins = Duration.between(from, to).toMinutes()
    if (mins < 0) return tr("⚠️ 此時間已經過去，不會響鈴")
    val d = mins / 1440
    val h = (mins % 1440) / 60
    val m = mins % 60
    val parts = buildList {
        if (d > 0) add(tr("{0} 天", d))
        if (h > 0) add(tr("{0} 小時", h))
        add(tr("{0} 分鐘", m))
    }
    return tr("⏳ 將於 {0} 後響鈴", parts.joinToString(" "))
}

// ---------------------------------------------------------------------------
// 圖片縮圖（在背景縮小解碼，避免大圖吃光記憶體）
// ---------------------------------------------------------------------------
private suspend fun loadBitmap(context: Context, uriStr: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        // 只讀使用者自己挑的外部圖片
        if (!UriSafety.isExternalContent(context, uriStr)) return@runCatching null
        val uri = Uri.parse(uriStr)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        // 避免超大圖片（解壓縮炸彈）把記憶體吃光
        if (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > 40_000_000L) return@runCatching null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()
}

@Composable
fun UriImage(uriStr: String, maxSide: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uriStr, maxSide) { value = loadBitmap(context, uriStr, maxSide) }
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Tabler.Photo, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        }
    }
}

/** 全螢幕圖片燈箱：左右滑動切換多張圖（網頁版 navigateLightbox） */
@Composable
fun ImageLightbox(images: List<ShiftAttachment>, startIndex: Int, theme: ThemePalette, onDismiss: () -> Unit) {
    if (images.isEmpty()) return
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
    MotionFullscreen(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${pager.currentPage + 1} / ${images.size}　${images[pager.currentPage].name}",
                    color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Tabler.X, contentDescription = tr("關閉"), tint = Color.White,
                    modifier = Modifier.size(28.dp).clickable { onDismiss() }
                )
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                UriImage(images[page].uriString, maxSide = 2048, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Button(
                onClick = {
                    val uriStr = images[pager.currentPage].uriString
                    // 只把「使用者自己挑的外部檔案」交給別的 App；本 App 自己的檔案或其他網址一律不開
                    if (!UriSafety.isExternalContent(context, uriStr)) {
                        AppToast.show(tr("無法開啟這個檔案"))
                    } else runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(uriStr), "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = theme.accent),
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("以系統相簿開啟原圖"), fontSize = 12.sp) }
        }
    }
}

// ---------------------------------------------------------------------------
// 小元件
// ---------------------------------------------------------------------------
@Composable
private fun TimeField(label: String, value: LocalTime, theme: ThemePalette, modifier: Modifier = Modifier, onPick: (LocalTime) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    MotionHost(picking) {
        TimeWheelDialog(
            title = tr("修改") + label.substringBefore("（"), context = null, initial = value, theme = theme,
            onDismiss = { picking = false },
            onConfirm = { picking = false; onPick(it) }
        )
    }
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = theme.textMuted)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.bgElevated)
                .border(1.dp, theme.borderColor, RoundedCornerShape(12.dp))
                .clickable { picking = true }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text(value.format(HM), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = theme.textMain) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, theme: ThemePalette, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) ->
            val active = id == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) theme.accent else theme.bgElevated)
                    .border(1.dp, if (active) theme.accent else theme.borderColor, RoundedCornerShape(10.dp))
                    .clickable { onSelect(id) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                IconText(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else theme.textMain)
            }
        }
    }
}

@Composable
private fun SubHeader(title: String, theme: ThemePalette, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconText(title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = theme.textMain, modifier = Modifier.weight(1f))
        if (action != null) {
            IconText(
                action, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.accentText,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction() }.padding(6.dp)
            )
        }
    }
}

/** 圖片縮圖網格 + 附件清單（日期編輯與新增排班共用） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaSection(
    attachments: MutableList<ShiftAttachment>,
    theme: ThemePalette,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onOpenImage: (List<ShiftAttachment>, Int) -> Unit
) {
    val images = attachments.filter { it.isImage }
    val files = attachments.filter { !it.isImage }
    SubHeader(tr("🖼️ 圖片紀錄（{0}）", images.size), theme, tr("+ 上傳圖片"), onPickImage)
    if (images.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            images.forEachIndexed { i, img ->
                Box(Modifier.size(72.dp)) {
                    UriImage(
                        img.uriString, maxSide = 256,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).clickable { onOpenImage(images, i) }
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable { attachments.remove(img) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Tabler.X, contentDescription = tr("移除圖片"), tint = Color.White, modifier = Modifier.size(12.dp)) }
                }
            }
        }
    }
    SubHeader(tr("📎 相關附件（{0}）", files.size), theme, tr("+ 附加檔案"), onPickFile)
    files.forEach { att ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(theme.bgElevated)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ext = att.name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
            Text(ext, fontSize = 9.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.width(40.dp))
            Text(att.name, fontSize = 11.sp, color = theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(
                Tabler.X, contentDescription = tr("移除附件"), tint = Color(0xFFF43F5E),
                modifier = Modifier.size(16.dp).clickable { attachments.remove(att) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 日期編輯面板（網頁版 renderDateEditModalContent）
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DateEditSheet(
    date: LocalDate,
    event: ShiftEvent?,
    dayAlarms: List<ShiftAlarm>,
    templates: List<ShiftTemplate>,
    theme: ThemePalette,
    onApplyTemplate: (ShiftTemplate, String, List<ShiftAttachment>) -> Unit,
    onSave: (title: String, type: ShiftType, start: LocalTime, end: LocalTime, note: String, attachments: List<ShiftAttachment>, syncAlarms: Boolean) -> Unit,
    onClearDay: () -> Unit,
    onAlarmTimeChange: (ShiftAlarm, LocalTime) -> Unit,
    onAlarmToggle: (ShiftAlarm) -> Unit,
    onAlarmDelete: (ShiftAlarm) -> Unit,
    onAlarmTestRing: (ShiftAlarm) -> Unit,
    onAddAlarm: () -> Unit,
    onPickImage: ((ShiftAttachment) -> Unit) -> Unit,
    onPickFile: ((ShiftAttachment) -> Unit) -> Unit,
    onOpenImage: (List<ShiftAttachment>, Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 套用模板後 event.id 會換新，表單跟著重新帶入
    val formKey = event?.id ?: "none-$date"
    var title by remember(formKey) { mutableStateOf(event?.title.orEmpty()) }
    var type by remember(formKey) { mutableStateOf(event?.type ?: ShiftType.EARLY) }
    var start by remember(formKey) {
        mutableStateOf(if (event != null && event.type != ShiftType.OFF) event.startTime else LocalTime.of(8, 0))
    }
    var end by remember(formKey) {
        mutableStateOf(if (event != null && event.type != ShiftType.OFF) event.endTime else LocalTime.of(17, 0))
    }
    var syncAlarms by remember(formKey) { mutableStateOf(event != null && event.type != ShiftType.OFF) }
    var note by remember(formKey) { mutableStateOf(event?.note.orEmpty()) }
    val attachments = remember(formKey) { mutableStateListOf<ShiftAttachment>().apply { addAll(event?.attachments.orEmpty()) } }
    MotionBottomSheet(palette = theme, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(tr("排班與兩段式鬧鐘設置"), fontSize = 11.sp, color = theme.accentText, fontWeight = FontWeight.Bold)
            Text(date.toString(), fontSize = 20.sp, fontWeight = FontWeight.Black)

            Text(tr("一鍵切換班別模板（保留備註與附件）"), fontSize = 11.sp, color = theme.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.forEach { tpl ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(tpl.type.chipBg())
                            .clickable { onApplyTemplate(tpl, note, attachments.toList()) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(tr(tpl.title), color = tpl.type.chipText(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
                }
            }

            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text(tr("班別／日程名稱"), fontSize = 12.sp) },
                placeholder = { Text(tr("例如：早班、特休"), fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Text(tr("班別色系"), fontSize = 11.sp, color = theme.textMuted)
            ChipRow(SHIFT_TYPE_OPTIONS.map { it.first.name to it.second }, type.name, theme) { type = ShiftType.valueOf(it) }

            if (type != ShiftType.OFF) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeField(tr("上班／開始時間"), start, theme, Modifier.weight(1f)) { start = it }
                    TimeField(if (!end.isAfter(start)) tr("下班／結束時間（隔日）") else tr("下班／結束時間"), end, theme, Modifier.weight(1f)) { end = it }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(theme.bgElevated)
                    .clickable { syncAlarms = !syncAlarms }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("儲存時同步重設「起床 ＋ 出門」兩段式鬧鐘"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Checkbox(checked = syncAlarms, onCheckedChange = { syncAlarms = it }, colors = CheckboxDefaults.colors(checkedColor = theme.accent))
            }

            // 當日鬧鐘微調
            Surface(shape = RoundedCornerShape(14.dp), color = theme.bgElevated, border = BorderStroke(1.dp, theme.borderColor)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubHeader(tr("⏰ 當日鬧鐘微調（{0}）", dayAlarms.size), theme, tr("+ 加設鬧鐘"), onAddAlarm)
                    if (dayAlarms.isEmpty()) {
                        Text(tr("此日無設定鬧鐘"), fontSize = 11.sp, color = theme.textMuted)
                    }
                    var pickingAlarm by remember { mutableStateOf<ShiftAlarm?>(null) }
                    var lastPicking by remember { mutableStateOf<ShiftAlarm?>(null) }
                    pickingAlarm?.let { lastPicking = it }
                    MotionHost(pickingAlarm != null) {
                        lastPicking?.let { target ->
                            androidx.compose.runtime.key(target.id) {
                                TimeWheelDialog(
                                    title = tr("修改鬧鐘時間"), context = target.label.substringBefore("（"), initial = target.triggerTime, theme = theme,
                                    onDismiss = { pickingAlarm = null },
                                    onConfirm = { pickingAlarm = null; onAlarmTimeChange(target, it) }
                                )
                            }
                        }
                    }
                    dayAlarms.forEach { al ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(trLabel(al.label), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        al.triggerTime.format(HM), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, theme.borderColor, RoundedCornerShape(8.dp))
                                            .clickable { pickingAlarm = al }
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                    Text(
                                        tr("試響"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMain,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(theme.bgCard)
                                            .clickable { onAlarmTestRing(al) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Switch(
                                checked = al.enabled, onCheckedChange = { onAlarmToggle(al) },
                                colors = SwitchDefaults.colors(checkedTrackColor = theme.accent)
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Tabler.X, contentDescription = tr("刪除鬧鐘"), tint = Color(0xFFF43F5E),
                                modifier = Modifier.size(20.dp).clickable { onAlarmDelete(al) }
                            )
                        }
                    }
                }
            }

            // 多行私人備註
            IconText(tr("📝 多行私人備註（僅本人可見）"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(tr("【交接事項】"), tr("【攜帶物品】"), tr("【調班紀錄】")).forEach { tag ->
                    IconText(
                        "+ ${tag.trim('【', '】')}", fontSize = 11.sp, color = theme.accent, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.bgElevated)
                            .border(1.dp, theme.borderColor, RoundedCornerShape(8.dp))
                            .clickable { note = if (note.isBlank()) "$tag " else "$note\n$tag " }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                placeholder = { Text(tr("輸入交接事項、備忘或調班紀錄..."), fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(12.dp)
            )

            MediaSection(
                attachments = attachments, theme = theme,
                onPickImage = { onPickImage { attachments.add(it) } },
                onPickFile = { onPickFile { attachments.add(it) } },
                onOpenImage = onOpenImage
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClearDay, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Text(tr("清除當日"), color = Color(0xFFF43F5E), fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val finalTitle = title.trim().ifBlank { SHIFT_TYPE_OPTIONS.first { it.first == type }.second.drop(3) }
                        val (s, e) = if (type == ShiftType.OFF) LocalTime.of(0, 0) to LocalTime.of(23, 59) else start to end
                        onSave(finalTitle, type, s, e, note, attachments.toList(), syncAlarms)
                    },
                    modifier = Modifier.weight(1.5f),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accent),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tr("儲存變更"), fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 新增日期鬧鐘（網頁版 form-add-alarm）
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAlarmSheet(
    defaultDate: LocalDate,
    ringtones: List<RingtoneEntry>,
    defaultWakeUri: String?,
    defaultLeaveUri: String?,
    theme: ThemePalette,
    onAdd: (date: LocalDate, time: LocalTime, label: String, stage: AlarmStage, snoozeMinutes: Int, ringtone: RingtoneEntry?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(defaultDate) }
    var time by remember { mutableStateOf(LocalTime.now().plusMinutes(5).withSecond(0).withNano(0)) }
    var label by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(AlarmStage.CUSTOM) }
    var snooze by remember { mutableIntStateOf(5) }
    var ringtoneId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = LocalDateTime.now() } }

    // 沒手動選鈴聲時，跟著提醒類型帶入預設鈴聲
    val stageDefaultUri = when (stage) {
        AlarmStage.LEAVE -> defaultLeaveUri
        else -> defaultWakeUri
    }
    val effectiveRingtone = ringtones.firstOrNull { it.id == ringtoneId }
        ?: ringtones.firstOrNull { it.uriString == stageDefaultUri }
        ?: ringtones.firstOrNull()

    MotionBottomSheet(palette = theme, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconText(tr("⏰ 新增日期鬧鐘"), fontSize = 18.sp, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("指定日曆日期"), fontSize = 11.sp, color = theme.textMuted)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.bgElevated)
                            .border(1.dp, theme.borderColor, RoundedCornerShape(12.dp))
                            .clickable { showDatePicker(context, date) { date = it } }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(date.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
                TimeField(tr("響鈴時間"), time, theme, Modifier.weight(1f)) { time = it }
            }
            IconText(
                formatCountdown(now.withSecond(0).withNano(0), LocalDateTime.of(date, time)),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.accent
            )
            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text(tr("鬧鐘標籤"), fontSize = 12.sp) },
                placeholder = { Text(tr("例如：早班強效起床、出門倒數提醒"), fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Text(tr("提醒類型"), fontSize = 11.sp, color = theme.textMuted)
            ChipRow(
                listOf(AlarmStage.WAKE.name to tr("🔔 第一段：強效起床"), AlarmStage.LEAVE.name to tr("🚪 第二段：出門倒數"), AlarmStage.CUSTOM.name to tr("⏰ 獨立日期鬧鐘")),
                stage.name, theme
            ) { stage = AlarmStage.valueOf(it) }
            Text(tr("貪睡間隔"), fontSize = 11.sp, color = theme.textMuted)
            ChipRow(listOf("5" to tr("5 分鐘（強力模式）"), "10" to tr("10 分鐘"), "0" to tr("關閉貪睡（一次起）")), snooze.toString(), theme) { snooze = it.toInt() }
            Text(tr("鬧鐘鈴聲（可在「兩段鬧鐘」分頁匯入手機音檔）"), fontSize = 11.sp, color = theme.textMuted)
            ChipRow(ringtones.map { it.id to it.name }, effectiveRingtone?.id.orEmpty(), theme) { ringtoneId = it }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Text(tr("取消"), color = theme.textMuted)
                }
                Button(
                    onClick = {
                        val finalLabel = label.trim().ifBlank {
                            when (stage) {
                                AlarmStage.WAKE -> tr("強效起床鬧鐘")
                                AlarmStage.LEAVE -> tr("出門倒數提醒")
                                AlarmStage.CUSTOM -> tr("自訂日期鬧鐘")
                            }
                        }
                        onAdd(date, time, finalLabel, stage, snooze, effectiveRingtone)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.5f),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.accent),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tr("建立日期鬧鐘"), fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}
