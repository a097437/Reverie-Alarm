package com.reverie.alarm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

// ============================================================================
// 分頁五：AI 快速排班（聊天＋拍班表照片＋語音）
//   - 登入 Google 帳號：文字與照片交給 Gemini（Firebase AI Logic），可以一直對話修正
//   - 沒登入：文字用手機上的快速排班解析（不上傳），照片功能提示登入
// ============================================================================

data class AiMessage(
    val fromUser: Boolean,
    val text: String,
    val image: ImageBitmap? = null,
    val isError: Boolean = false,
    /** Gemini 產生的回覆才有：可以回報不當內容（Google Play 的 AI 內容政策要求） */
    val report: AiReportDraft? = null
)

/** 聊天內容放在分頁外面，切換分頁回來還在 */
class AiChatState {
    val messages = mutableStateListOf<AiMessage>()
    val turns = mutableListOf<AiTurn>()
    var plan by mutableStateOf<Map<LocalDate, String>>(emptyMap())
    var busy by mutableStateOf(false)
    fun reset() { messages.clear(); turns.clear(); plan = emptyMap(); busy = false }
}

/** 範例句（中文原文）。登入時交給 AI，英文模式顯示英文；沒登入時手機上的解析只懂中文，所以顯示中文 */
private val AI_EXAMPLES = listOf("21 到 25 號早班，26 號休假", "下週一三五晚班", "這個月週六日休假，其他早班")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiScheduleScreen(
    state: AiChatState,
    templates: List<ShiftTemplate>,
    events: List<ShiftEvent>,
    baseMonth: YearMonth,
    theme: ThemePalette,
    onApply: (Map<LocalDate, ShiftTemplate>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = CloudSync.user
    val signedIn = user != null
    var input by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<AiReportDraft?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // ---- 班別對應 ----
    val offTpl = templates.firstOrNull { it.isOff }
    fun matchTemplate(shift: String): ShiftTemplate? =
        templates.firstOrNull { it.title == shift }
            ?: templates.firstOrNull { shift.contains(it.title) || it.title.contains(shift) }
            ?: if (shift.contains("休") || shift.equals("off", true)) offTpl else null
    val resolved = state.plan.mapNotNull { (d, s) -> matchTemplate(s)?.let { d to it } }
    val changes = resolved.count { (d, tpl) -> events.firstOrNull { it.date == d }?.title != tpl.title }

    // ---- 照片、語音 ----
    val camFile = remember { File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "roster.jpg") } }
    val camUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", camFile) }
    fun takeUri(uri: android.net.Uri) {
        scope.launch { runCatching { photo = AiSchedule.loadPhoto(context, uri) }.onFailure { AppToast.show(it.message ?: tr("讀不到這張照片")) } }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) takeUri(camUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) takeUri(uri) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (said.isNotEmpty()) input = if (input.isBlank()) said else input.trimEnd() + "，" + said
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, tr("說出要排的班，例如：21 到 25 號早班"))
        try { speech.launch(intent) } catch (_: ActivityNotFoundException) { AppToast.show(tr("這支手機沒有語音輸入，請改用打字")) }
    }
    fun signIn() {
        val act = context as? Activity ?: return
        if (signingIn) return
        signingIn = true
        scope.launch {
            CloudSync.signIn(act)?.let { AppToast.show(it) }
            signingIn = false
        }
    }

    // ---- 送出 ----
    fun localParse(text: String, note: String? = null) {
        val p = QuickScheduleParser.parse(text, templates.map { it.title }, offTpl?.title, LocalDate.now(), baseMonth)
        state.plan = state.plan + p.days
        val msg = buildString {
            if (note != null) append(note).append("\n")
            if (p.days.isNotEmpty()) append(tr("好的，整理出 {0} 天，確認下面的預覽沒問題就按「套用」。", p.days.size))
            else append(tr("我看不太懂這句，可以換個說法，例如「21 到 25 號早班」。"))
            p.problems.forEach { append("\n・").append(it) }
        }
        state.messages += AiMessage(false, msg)
    }

    fun send() {
        val text = input.trim().take(AiGuard.MAX_INPUT_CHARS)
        val img = photo
        if ((text.isEmpty() && img == null) || state.busy) return
        if (img != null && !signedIn) { AppToast.show(tr("登入 Google 帳號後就能讓 AI 讀班表照片")); return }
        // 每天次數、送出間隔：超過時文字改用手機上的解析，照片請使用者明天再試
        if (signedIn) AiGuard.check(context, img != null)?.let { why ->
            if (img != null) { AppToast.show(why); return }
            input = ""
            state.messages += AiMessage(true, text)
            localParse(text, note = tr("⚠️ {0}。先改用手機上的快速排班幫你解析：", why))
            return
        }
        input = ""; photo = null
        state.messages += AiMessage(true, text, img?.asImageBitmap())
        if (!signedIn) { localParse(text); return }

        state.turns += AiTurn(true, text.ifEmpty { tr("這是我的班表，請幫我整理我的班。") }, img)
        state.busy = true
        AiGuard.record(context, img != null)
        scope.launch {
            try {
                val system = AiSchedule.systemPrompt(templates, LocalDate.now(), baseMonth, user?.name, AiPrefs.rosterName(context))
                val r = AiSchedule.send(context, system, state.turns.toList())
                state.turns += AiTurn(false, r.raw) // 留著完整的 JSON，下一輪修正時 Gemini 才知道目前的清單
                state.plan = r.days
                if (r.person.isNotBlank()) AiPrefs.setRosterName(context, r.person)
                val unmatched = r.days.filter { (_, s) -> matchTemplate(s) == null }
                val extra = if (unmatched.isEmpty()) "" else
                    tr("\n・對不上班別、不會套用：") + unmatched.entries.joinToString("、") { (d, s) -> "${d.monthValue}/${d.dayOfMonth}「$s」" }
                state.messages += AiMessage(false, r.reply + extra, report = AiReportDraft(prompt = text, reply = r.reply))
            } catch (e: AiException) {
                AiGuard.refund(context, img != null) // 沒拿到回覆不算次數
                state.turns.removeLastOrNull()
                if (img == null && text.isNotEmpty()) localParse(text, note = tr("⚠️ {0}。先改用手機上的快速排班幫你解析：", e.message))
                else {
                    state.messages += AiMessage(false, e.message ?: tr("AI 暫時無法使用"), isError = true)
                    photo = img; input = text // 放回去，方便重送
                }
            } finally {
                state.busy = false
            }
        }
    }

    // 有新訊息就捲到最下面
    LaunchedEffect(state.messages.size, state.busy, state.plan.size) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // 鍵盤打開時把輸入框推上來（扣掉底部分頁列的高度）
    val density = LocalDensity.current
    val imeExtra = with(density) {
        (WindowInsets.ime.getBottom(this) - WindowInsets.navigationBars.getBottom(this) - 73.dp.roundToPx()).coerceAtLeast(0).toDp()
    }

    Column(Modifier.fillMaxSize().padding(bottom = imeExtra)) {
        // 標題
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp)) {
            Icon(Tabler.Sparkles, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("AI快速排班"), fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                Text(
                    if (signedIn) tr("用聊的、用說的，或拍一張班表照片") else tr("打字或用說的，一次排好很多天"),
                    fontSize = 11.sp, color = theme.textMuted
                )
            }
            if (state.messages.isNotEmpty()) {
                Icon(
                    Tabler.Refresh, contentDescription = tr("重新開始"), tint = theme.textMuted,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = !state.busy) { state.reset() }.padding(8.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!signedIn) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.accent.copy(alpha = 0.10f))
                            .border(1.dp, theme.accent.copy(alpha = 0.3f), RoundedCornerShape(18.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(tr("登入 Google 帳號，解鎖 AI"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                        Text(tr("拍紙本或公司貼的班表，AI 幫你讀出整個月；也能用聊天的方式修改。不用設定任何金鑰。"), fontSize = 12.sp, color = theme.textMuted, lineHeight = 17.sp)
                        WebButton(if (signingIn) tr("登入中…") else tr("用 Google 帳號登入"), theme, Modifier.fillMaxWidth(), fontSize = 13.sp, vertical = 10.dp, enabled = !signingIn) { signIn() }
                        Text(tr("沒登入也能用：文字會用手機上的快速排班解析，不會上傳。"), fontSize = 11.sp, color = theme.textMuted)
                    }
                }
            }
            if (state.messages.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiBubble(AiMessage(false, if (signedIn) tr("嗨 {0}！拍一張班表照片給我，或直接告訴我要怎麼排班。", user?.name.orEmpty()) else tr("嗨！告訴我要怎麼排班，例如「21 到 25 號早班」。")), theme)
                        Text(tr("試試這樣說"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (signedIn) SuggestionChip(tr("📷 拍一張班表照片"), theme) { runCatching { takePhoto.launch(camUri) } }
                            AI_EXAMPLES.forEach { k -> val ex = if (signedIn) tr(k) else k; SuggestionChip(ex, theme) { input = ex } }
                        }
                    }
                }
            }
            items(state.messages.size) { i -> AiBubble(state.messages[i], theme) { reporting = it } }
            if (state.busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 36.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.accent)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("AI 正在看…"), fontSize = 12.sp, color = theme.textMuted)
                    }
                }
            }
            if (resolved.isNotEmpty() && !state.busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlanPreview(resolved, events, theme, changes)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (changes > 0) theme.accent.darkenForWhiteText() else theme.bgElevated)
                                .clickable(enabled = changes > 0) {
                                    onApply(resolved.toMap())
                                    state.messages += AiMessage(false, tr("已套用 {0} 天，兩段式鬧鐘也排好了 ✅", changes))
                                    state.plan = emptyMap(); state.turns.clear()
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (changes > 0) tr("套用 {0} 天（同時排好兩段式鬧鐘）", changes) else tr("沒有需要變動的日子"),
                                fontSize = 14.sp, fontWeight = FontWeight.Black, color = if (changes > 0) Color.White else theme.textMuted
                            )
                        }
                    }
                }
            }
        }

        // ---- 附加的照片 ----
        photo?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                Image(
                    p.asImageBitmap(), contentDescription = tr("要送出的班表照片"), contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, theme.borderColor, RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("班表照片，可以加一句說明再送出"), fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.weight(1f))
                Icon(Tabler.X, contentDescription = tr("移除照片"), tint = theme.textMuted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { photo = null }.padding(7.dp))
            }
        }

        // ---- 輸入列 ----
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)) {
            Box {
                Icon(
                    Tabler.Camera, contentDescription = tr("附加班表照片"), tint = if (signedIn) theme.accentText else theme.textMuted,
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(theme.bgElevated).clickable {
                        if (signedIn) attachMenu = true else AppToast.show(tr("登入 Google 帳號後就能讓 AI 讀班表照片"))
                    }.padding(12.dp)
                )
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }, containerColor = theme.bgCard) {
                    DropdownMenuItem(
                        text = { Text(tr("拍照"), color = theme.textMain) },
                        leadingIcon = { Icon(Tabler.Camera, contentDescription = null, tint = theme.accentText) },
                        onClick = { attachMenu = false; runCatching { takePhoto.launch(camUri) }.onFailure { AppToast.show(tr("打不開相機")) } }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("從相簿選"), color = theme.textMain) },
                        leadingIcon = { Icon(Tabler.Photo, contentDescription = null, tint = theme.accentText) },
                        onClick = { attachMenu = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(theme.bgElevated)
                    .border(1.dp, theme.borderColor, RoundedCornerShape(23.dp)).padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (input.isEmpty()) Text(if (signedIn) tr("跟 AI 說要怎麼排班…") else tr("例如：21 到 25 號早班"), fontSize = 14.sp, color = theme.textMuted)
                BasicTextField(
                    value = input, onValueChange = { input = it.take(AiGuard.MAX_INPUT_CHARS) }, maxLines = 5,
                    textStyle = TextStyle(color = theme.textMain, fontSize = 14.sp, lineHeight = 20.sp), cursorBrush = SolidColor(theme.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(8.dp))
            val canSend = (input.isNotBlank() || photo != null) && !state.busy
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(theme.accent.darkenForWhiteText().copy(alpha = if (state.busy) 0.4f else 1f))
                    .clickable(enabled = !state.busy) { if (canSend) send() else listen() },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (canSend) Tabler.Send else Tabler.Microphone, contentDescription = if (canSend) tr("送出") else tr("用說的"), tint = Color.White, modifier = Modifier.size(21.dp))
            }
        }
        Text(
            when {
                !signedIn -> tr("目前在手機上解析，不會上傳")
                input.length > AiGuard.MAX_INPUT_CHARS - 50 -> tr("{0}/{1} 字", input.length, AiGuard.MAX_INPUT_CHARS)
                else -> tr("AI 模式：送出的文字與照片會交給 Google Gemini 處理 · 今天還能用 {0} 次", AiGuard.remaining(context))
            },
            fontSize = 10.sp, color = theme.textMuted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp)
        )
    }

    // ---- 回報 AI 回覆 ----
    var lastReport by remember { mutableStateOf<AiReportDraft?>(null) }
    reporting?.let { lastReport = it }
    MotionHost(reporting != null) {
        val draft = lastReport
        var sending by remember { mutableStateOf(false) }
        MotionAlertDialog(
            onDismissRequest = { if (!sending) reporting = null },
            containerColor = theme.bgCard,
            title = { Text(tr("回報這則 AI 回覆？"), fontWeight = FontWeight.Black, color = theme.textMain) },
            text = {
                Text(
                    tr("覺得內容不當、冒犯或有問題時可以回報。會把這則 AI 回覆和你當時送出的文字（不含照片）交給開發者檢查。"),
                    fontSize = 13.sp, color = theme.textMuted, lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(enabled = !sending, onClick = {
                    if (draft == null) return@TextButton
                    sending = true
                    scope.launch {
                        val err = AiReports.send(draft)
                        sending = false
                        reporting = null
                        AppToast.show(err ?: tr("✅ 已回報，謝謝你"))
                    }
                }) { Text(if (sending) tr("回報中…") else tr("回報"), color = ROSE.readable(), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !sending, onClick = { reporting = null }) { Text(tr("取消"), color = theme.textMuted) } }
        )
    }
}

@Composable
private fun SuggestionChip(text: String, theme: ThemePalette, onClick: () -> Unit) {
    IconText(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = theme.textMain,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, theme.borderColor, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun AiBubble(m: AiMessage, theme: ThemePalette, onReport: (AiReportDraft) -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!m.fromUser) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(theme.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Tabler.Sparkles, contentDescription = "AI", tint = theme.accentText, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.image?.let {
                Image(it, contentDescription = tr("班表照片"), contentScale = ContentScale.Crop, modifier = Modifier.size(150.dp).clip(RoundedCornerShape(16.dp)))
            }
            if (m.text.isNotEmpty()) {
                Text(
                    m.text, fontSize = 14.sp, lineHeight = 20.sp,
                    color = when { m.fromUser -> Color.White; m.isError -> ROSE.readable(); else -> theme.textMain },
                    modifier = Modifier
                        .widthIn(max = 290.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(when { m.fromUser -> theme.accent.darkenForWhiteText(); m.isError -> ROSE.copy(alpha = 0.12f); else -> theme.bgElevated })
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                )
            }
            m.report?.let { r ->
                IconText(
                    tr("⚑ 回報"), fontSize = 11.sp, color = theme.textMuted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onReport(r) }.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** 排班預覽：每一天原本 → 新的班別；不變的變淡、過去的標「已過」 */
@Composable
fun PlanPreview(resolved: List<Pair<LocalDate, ShiftTemplate>>, events: List<ShiftEvent>, theme: ThemePalette, changes: Int) {
    val today = LocalDate.now()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("預覽"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
            Spacer(Modifier.width(8.dp))
            Text(tr("共 {0} 天・會變動 {1} 天", resolved.size, changes), fontSize = 12.sp, color = theme.textMuted)
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.bgElevated)) {
            resolved.forEachIndexed { i, (d, tpl) ->
                if (i > 0) HorizontalDivider(color = theme.borderColor.copy(alpha = 0.5f))
                val old = events.firstOrNull { it.date == d }
                val same = old?.title == tpl.title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().alpha(if (same) 0.5f else 1f).padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text("${d.monthValue}/${d.dayOfMonth}", fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain, modifier = Modifier.width(48.dp))
                    Text(I18n.weekday(d.dayOfWeek), fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.width(34.dp))
                    if (d.isBefore(today)) Text(tr("已過"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.textMuted, modifier = Modifier.padding(end = 6.dp))
                    Spacer(Modifier.weight(1f))
                    if (old != null && !same) {
                        Text(tr(old.title), fontSize = 12.sp, color = theme.textMuted, textDecoration = TextDecoration.LineThrough)
                        Icon(Tabler.ArrowRight, contentDescription = tr("改成"), tint = theme.textMuted, modifier = Modifier.padding(horizontal = 6.dp).size(13.dp))
                    }
                    if (same) Text(tr("不變  "), fontSize = 11.sp, color = theme.textMuted)
                    ShiftPill(tpl)
                }
            }
        }
    }
}

@Composable
private fun ShiftPill(tpl: ShiftTemplate) {
    val type = if (tpl.isOff) ShiftType.OFF else tpl.type
    Text(
        tr(tpl.title), fontSize = 12.sp, fontWeight = FontWeight.Black, color = type.chipText(),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(type.chipBg())
            .border(1.dp, type.chipBorder(), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}
