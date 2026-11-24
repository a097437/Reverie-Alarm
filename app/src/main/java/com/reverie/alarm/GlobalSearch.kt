package com.reverie.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ============================================================================
// 全域搜尋：排班、日程、備註與鬧鐘
//
// 搜尋邏輯是純函式（runGlobalSearch），在背景執行緒跑、輸入有去抖動，
// 資料多也不會卡住打字。空白分隔的多個關鍵字要「全部符合」才算命中；
// 每個關鍵字可以是文字、日期（11-18、11/18、1118、11月18日、2026/11/18、11月、明天）
// 或時間（06:30、0630）。
// ============================================================================

/** 日期條件：沒寫的欄位不限制（例如「11月」只看月份、「18日」只看日） */
internal data class DateQuery(val year: Int? = null, val month: Int? = null, val day: Int? = null) {
    fun matches(d: LocalDate): Boolean =
        (year == null || d.year == year) && (month == null || d.monthValue == month) && (day == null || d.dayOfMonth == day)
}

private val RE_YMD = Regex("""^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})[日號号]?$""")
private val RE_MD = Regex("""^(\d{1,2})[-/.月](\d{1,2})[日號号]?$""")
private val RE_YM = Regex("""^(\d{4})[-/.年](\d{1,2})月?$""")
private val RE_M = Regex("""^(\d{1,2})月$""")
private val RE_D = Regex("""^(\d{1,2})[日號号]$""")
private val RE_YMD8 = Regex("""^(\d{4})(\d{2})(\d{2})$""")
private val RE_MD4 = Regex("""^(\d{2})(\d{2})$""")
private val RE_HM = Regex("""^(\d{1,2})[:：](\d{2})$""")
private val RE_Y = Regex("""^((?:19|20|21)\d{2})年?$""")

/** 全形數字與符號轉半形，讓「１１／１８」也能搜 */
private fun normalizeTerm(s: String): String = buildString(s.length) {
    for (c in s) append(
        when (c) {
            in '０'..'９' -> '0' + (c - '０')
            '／' -> '/'
            '－', '—', '–' -> '-'
            '．' -> '.'
            else -> c
        }
    )
}

internal fun parseDateQuery(term: String, today: LocalDate): DateQuery? {
    val t = normalizeTerm(term).lowercase()
    fun exact(d: LocalDate) = DateQuery(d.year, d.monthValue, d.dayOfMonth)
    when (t) {
        "今天", "今日", "today" -> return exact(today)
        "明天", "明日", "tomorrow" -> return exact(today.plusDays(1))
        "後天" -> return exact(today.plusDays(2))
        "昨天", "昨日", "yesterday" -> return exact(today.minusDays(1))
    }
    fun valid(y: Int?, m: Int?, d: Int?): DateQuery? {
        if (y != null && y !in 1900..2200) return null
        if (m != null && m !in 1..12) return null
        if (d != null && d !in 1..31) return null
        if (m != null && d != null && d > Month.of(m).maxLength()) return null
        return DateQuery(y, m, d)
    }
    fun MatchResult.n(i: Int) = groupValues[i].toInt()
    RE_YMD.matchEntire(t)?.let { return valid(it.n(1), it.n(2), it.n(3)) }
    RE_YMD8.matchEntire(t)?.let { return valid(it.n(1), it.n(2), it.n(3)) }
    RE_MD.matchEntire(t)?.let { return valid(null, it.n(1), it.n(2)) }
    RE_MD4.matchEntire(t)?.let { return valid(null, it.n(1), it.n(2)) }
    RE_YM.matchEntire(t)?.let { return valid(it.n(1), it.n(2), null) }
    RE_M.matchEntire(t)?.let { return valid(null, it.n(1), null) }
    RE_D.matchEntire(t)?.let { return valid(null, null, it.n(1)) }
    // 「2026」「2026年」：整年
    RE_Y.matchEntire(t)?.let { return valid(it.n(1), null, null) }
    return null
}

internal fun parseTimeQuery(term: String): LocalTime? {
    val t = normalizeTerm(term)
    val (h, m) = RE_HM.matchEntire(t)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        ?: RE_MD4.matchEntire(t)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        ?: return null
    return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
}

private class SearchTerm(val text: String, val date: DateQuery?, val time: LocalTime?)

/** 一筆搜尋結果（排班或鬧鐘其一） */
internal class SearchHit(
    val key: String,
    val date: LocalDate,
    val time: LocalTime,
    val event: ShiftEvent?,
    val alarm: ShiftAlarm?,
    /** 是不是靠日期條件命中的（日期要反白） */
    val dateHit: Boolean
)

internal class SearchOutcome(
    val upcoming: List<SearchHit>,
    val past: List<SearchHit>,
    /** 每個分類的命中數（不受目前選的分類影響），沒有關鍵字時是空的 */
    val counts: Map<String, Int>,
    /** 要在結果裡反白的文字關鍵字 */
    val highlightTerms: List<String>,
    /** 沒輸入也沒選分類：只列出接下來的幾筆 */
    val idle: Boolean
) {
    val total: Int get() = upcoming.size + past.size
}

private const val FILTER_ALL = "all"
private const val FILTER_ALARM = "alarm"
private const val IDLE_PREVIEW = 8
private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun LocalTime.hm(): String = format(HM)

internal fun runGlobalSearch(
    query: String,
    filter: String,
    events: List<ShiftEvent>,
    alarms: List<ShiftAlarm>,
    today: LocalDate
): SearchOutcome {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { raw ->
        val date = parseDateQuery(raw, today)
        // 「2026」當年份，不當成 20:26
        val time = if (date != null && date.month == null && date.day == null) null else parseTimeQuery(raw)
        SearchTerm(raw, date, time)
    }
    val upcomingOrder = compareBy<SearchHit>({ it.date }, { it.time })
    val pastOrder = compareByDescending<SearchHit> { it.date }.thenByDescending { it.time }

    if (terms.isEmpty() && filter == FILTER_ALL) {
        val upcoming = ArrayList<SearchHit>()
        events.forEach { ev -> if (!ev.date.isBefore(today)) upcoming += SearchHit("e" + ev.id, ev.date, ev.startTime, ev, null, false) }
        alarms.forEach { al -> if (al.enabled && !al.date.isBefore(today)) upcoming += SearchHit("a" + al.id, al.date, al.triggerTime, null, al, false) }
        upcoming.sortWith(upcomingOrder)
        return SearchOutcome(upcoming.take(IDLE_PREVIEW), emptyList(), emptyMap(), emptyList(), idle = true)
    }

    val counts = HashMap<String, Int>()
    val upcoming = ArrayList<SearchHit>()
    val past = ArrayList<SearchHit>()
    fun bump(id: String) {
        counts[id] = (counts[id] ?: 0) + 1
        counts[FILTER_ALL] = (counts[FILTER_ALL] ?: 0) + 1
    }
    fun add(hit: SearchHit) {
        if (hit.date.isBefore(today)) past += hit else upcoming += hit
    }

    // 各關鍵字都要命中（文字、日期、時間其中一種即可）；回傳 null 代表不符合，否則回傳「是否靠日期命中」
    fun matchAll(date: LocalDate, time: LocalTime, texts: List<String>): Boolean? {
        var dateHit = false
        for (t in terms) {
            val byText = texts.any { it.contains(t.text, ignoreCase = true) }
            val byDate = t.date?.matches(date) == true
            val byTime = t.time == time
            if (!byText && !byDate && !byTime) return null
            if (byDate) dateHit = true
        }
        return dateHit
    }

    for (ev in events) {
        val texts = listOf(
            ev.title, tr(ev.title), ev.note,
            ev.type.displayName, tr(ev.type.displayName), ev.startTime.hm()
        )
        val dateHit = matchAll(ev.date, ev.startTime, texts) ?: continue
        bump(ev.type.name)
        if (filter == FILTER_ALL || filter == ev.type.name) add(SearchHit("e" + ev.id, ev.date, ev.startTime, ev, null, dateHit))
    }
    for (al in alarms) {
        val texts = listOf(al.label, tr(al.label), tr(al.stage.label), al.triggerTime.hm())
        val dateHit = matchAll(al.date, al.triggerTime, texts) ?: continue
        bump(FILTER_ALARM)
        if (filter == FILTER_ALL || filter == FILTER_ALARM) add(SearchHit("a" + al.id, al.date, al.triggerTime, null, al, dateHit))
    }
    upcoming.sortWith(upcomingOrder)
    past.sortWith(pastOrder)
    // 日期／時間關鍵字不反白文字（例如「11/18」不會去標備註裡的「11」）
    val highlight = terms.filter { it.date == null && it.time == null }.map { it.text }
    return SearchOutcome(upcoming, past, counts, highlight, idle = false)
}

/** 把多個關鍵字在文字裡反白（不分大小寫） */
private fun highlightText(text: String, terms: List<String>, style: SpanStyle): AnnotatedString {
    if (terms.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            var best = -1
            var bestLen = 0
            for (t in terms) {
                val hit = text.indexOf(t, i, ignoreCase = true)
                if (hit >= 0 && (best < 0 || hit < best || (hit == best && t.length > bestLen))) {
                    best = hit; bestLen = t.length
                }
            }
            if (best < 0) {
                append(text.substring(i)); break
            }
            append(text.substring(i, best))
            withStyle(style) { append(text.substring(best, best + bestLen)) }
            i = best + bestLen
        }
    }
}

private fun relativeDayLabel(d: LocalDate, today: LocalDate): String {
    val n = ChronoUnit.DAYS.between(today, d)
    return when {
        n == 0L -> tr("今天")
        n == 1L -> tr("明天")
        n == -1L -> tr("昨天")
        n in 2L..99L -> tr("{0} 天後", n)
        n in -99L..-2L -> tr("{0} 天前", -n)
        else -> ""
    }
}

private class SearchFilter(val id: String, val label: String, val icon: ImageVector?, val tint: Color?)

private data class SearchInput(
    val query: String,
    val filter: String,
    val events: List<ShiftEvent>,
    val alarms: List<ShiftAlarm>
)

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
    var filter by remember { mutableStateOf(FILTER_ALL) }
    val today = remember { LocalDate.now() }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var searching by remember { mutableStateOf(false) }
    val latestEvents by rememberUpdatedState(events)
    val latestAlarms by rememberUpdatedState(alarms)

    // 輸入去抖動＋背景計算：打字時 180ms 內的新輸入會取消上一次搜尋；點分類則立即搜尋
    LaunchedEffect(Unit) {
        var lastQuery: String? = null
        snapshotFlow { SearchInput(query, filter, latestEvents.toList(), latestAlarms.toList()) }
            .collectLatest { input ->
                val typed = lastQuery != null && input.query != lastQuery
                lastQuery = input.query
                if (typed) {
                    searching = true
                    delay(180)
                }
                outcome = withContext(Dispatchers.Default) {
                    runGlobalSearch(input.query, input.filter, input.events, input.alarms, today)
                }
                searching = false
            }
    }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 等對話框視窗掛上去再要焦點，鍵盤才叫得出來
        delay(150)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    val filters = remember(theme) {
        listOf(SearchFilter(FILTER_ALL, tr("全部"), Tabler.LayoutGrid, null)) +
            listOf(
                ShiftType.EARLY to Tabler.Sun, ShiftType.EVENING to Tabler.Sunset2, ShiftType.NIGHT to Tabler.Moon,
                ShiftType.OFF to Tabler.Leaf, ShiftType.CUSTOM to Tabler.Pin
            ).map { (t, ic) -> SearchFilter(t.name, tr(t.displayName), ic, t.accentDot.readableOn(theme.bgElevated, 3.0)) } +
            SearchFilter(FILTER_ALARM, tr("鬧鐘"), Tabler.Alarm, theme.accentText)
    }
    val hitStyle = remember(theme) { SpanStyle(background = theme.accent.copy(alpha = 0.38f), fontWeight = FontWeight.Black) }

    MotionAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.bgCard,
        // 不放標題：搜尋框本身就說明了用途，鍵盤打開時把空間留給結果
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ---- 膠囊搜尋框 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(theme.bgElevated)
                        .border(if (focused) 1.5.dp else 1.dp, if (focused) theme.accent else theme.borderColor, CircleShape)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                            runCatching { focus.requestFocus() }; keyboard?.show()
                        }
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Tabler.Search, contentDescription = null, tint = if (focused) theme.accentText else theme.textMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(tr("搜尋排班、備註、鬧鐘或日期"), fontSize = 14.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it.take(60) },
                            singleLine = true,
                            textStyle = TextStyle(color = theme.textMain, fontSize = 14.sp),
                            cursorBrush = SolidColor(theme.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onFocusChanged { focused = it.isFocused }
                        )
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).clickable { query = ""; runCatching { focus.requestFocus() } },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Tabler.X, contentDescription = tr("清除"), tint = theme.textMuted, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Spacer(Modifier.width(10.dp))
                    }
                }

                // ---- 分類膠囊（可橫向捲動、文字不換行） ----
                val counts = outcome?.counts.orEmpty()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(filters, key = { it.id }) { f ->
                        SearchFilterChip(f, active = filter == f.id, count = counts[f.id], showCount = counts.isNotEmpty(), theme = theme) {
                            filter = f.id
                        }
                    }
                }

                // ---- 結果（筆數顯示在分類膠囊與分段標題上，鍵盤打開時把空間留給結果） ----
                val o = outcome
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 460.dp)) {
                    when {
                        o == null -> Unit
                        o.total == 0 -> SearchEmptyState(
                            idle = o.idle,
                            query = query.trim(),
                            filterActive = filter != FILTER_ALL,
                            theme = theme,
                            onShowAll = { filter = FILTER_ALL }
                        )
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            if (o.upcoming.isNotEmpty()) {
                                item(key = "h-up") {
                                    SearchSectionHeader(if (o.idle) tr("接下來") else tr("接下來 · {0}", o.upcoming.size), theme, searching)
                                }
                                items(o.upcoming, key = { it.key }) { hit ->
                                    SearchResultRow(hit, today, o.highlightTerms, hitStyle, theme, onEditDate?.let { edit -> { edit(hit.date); onDismiss() } }) {
                                        onJumpToDate(hit.date); onDismiss()
                                    }
                                }
                            }
                            if (o.past.isNotEmpty()) {
                                item(key = "h-past") { SearchSectionHeader(tr("過去 · {0}", o.past.size), theme, searching && o.upcoming.isEmpty()) }
                                items(o.past, key = { it.key }) { hit ->
                                    SearchResultRow(hit, today, o.highlightTerms, hitStyle, theme, onEditDate?.let { edit -> { edit(hit.date); onDismiss() } }) {
                                        onJumpToDate(hit.date); onDismiss()
                                    }
                                }
                            }
                            if (o.idle) {
                                item(key = "tip") {
                                    Text(
                                        tr("可以搜班別、備註、鬧鐘名稱，或日期：11/18、1118、11月18日、明天"),
                                        fontSize = 11.sp, color = theme.textMuted, lineHeight = 15.sp,
                                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("關閉"), color = theme.accentText, fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun SearchFilterChip(
    f: SearchFilter,
    active: Boolean,
    count: Int?,
    showCount: Boolean,
    theme: ThemePalette,
    onClick: () -> Unit
) {
    val content = if (active) Color.White else theme.textMain
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(if (active) theme.accent else theme.bgElevated)
            .border(1.dp, if (active) theme.accent else theme.borderColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (f.icon != null) {
            Icon(f.icon, contentDescription = null, tint = if (active) Color.White else (f.tint ?: theme.textMuted), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(f.label, fontSize = 12.sp, fontWeight = if (active) FontWeight.Black else FontWeight.Bold, color = content, maxLines = 1, softWrap = false)
        if (showCount) {
            Spacer(Modifier.width(5.dp))
            Text(
                (count ?: 0).toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                color = if (active) Color.White.copy(alpha = 0.85f) else theme.textMuted
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String, theme: ThemePalette, searching: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Black, color = theme.textMuted, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
        if (searching) Text(tr("搜尋中…"), fontSize = 10.sp, color = theme.textMuted)
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
    today: LocalDate,
    terms: List<String>,
    hitStyle: SpanStyle,
    theme: ThemePalette,
    onEdit: (() -> Unit)?,
    onClick: () -> Unit
) {
    val ev = hit.event
    val al = hit.alarm
    val isPast = hit.date.isBefore(today)
    val isToday = hit.date == today
    val muted = (al != null && !al.enabled) || isPast
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(theme.bgElevated)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 日期方塊：日期命中時用主題色框起來
        Column(
            modifier = Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (hit.dateHit) theme.accent.copy(alpha = 0.28f) else if (isToday) theme.accent.copy(alpha = 0.14f) else Color.Transparent)
                .then(if (hit.dateHit) Modifier.border(1.dp, theme.accent, RoundedCornerShape(10.dp)) else Modifier)
                .padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hit.date.year != today.year) {
                Text(hit.date.year.toString(), fontSize = 9.sp, color = theme.textMuted, maxLines = 1)
            }
            Text(
                "${hit.date.monthValue}/${hit.date.dayOfMonth}", fontSize = 14.sp, fontWeight = FontWeight.Black,
                color = if (isToday || hit.dateHit) theme.accentText else theme.textMain, maxLines = 1, softWrap = false
            )
            Text(I18n.weekday(hit.date.dayOfWeek), fontSize = 10.sp, color = theme.textMuted, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.width(3.dp).height(34.dp).clip(CircleShape)
                .background(ev?.type?.accentDot ?: theme.accent)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (ev != null) {
                Text(
                    highlightText(tr(ev.title), terms, hitStyle), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (muted) theme.textMuted else theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val time = if (ev.type == ShiftType.OFF) tr("全天") else "${ev.startTime.hm()}–${ev.endTime.hm()}"
                Text(
                    highlightText("${tr(ev.type.displayName)} · $time", terms, hitStyle), fontSize = 11.sp, color = theme.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (ev.note.isNotBlank()) {
                    Text(
                        highlightText(ev.note.replace('\n', ' '), terms, hitStyle), fontSize = 11.sp, color = theme.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (al != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Tabler.Alarm, contentDescription = null, tint = if (al.enabled) theme.accentText else theme.textMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        highlightText(al.triggerTime.hm(), terms, hitStyle), fontSize = 14.sp, fontWeight = FontWeight.Black,
                        color = if (muted) theme.textMuted else theme.textMain, maxLines = 1, softWrap = false
                    )
                }
                Text(
                    highlightText(plainLabel(tr(al.label)), terms, hitStyle), fontSize = 11.sp,
                    color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!al.enabled) Text(tr("已關閉"), fontSize = 10.sp, color = theme.textMuted)
            }
        }
        val rel = relativeDayLabel(hit.date, today)
        if (rel.isNotEmpty()) {
            Text(
                rel, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                color = if (isToday) theme.accentText else theme.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        if (ev != null && onEdit != null) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onEdit),
                contentAlignment = Alignment.Center
            ) {
                Icon(Tabler.Pencil, contentDescription = tr("編輯"), tint = theme.textMuted, modifier = Modifier.size(16.dp))
            }
        } else {
            Icon(Tabler.ChevronRight, contentDescription = null, tint = theme.textMuted, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
        }
    }
}

@Composable
private fun SearchEmptyState(
    idle: Boolean,
    query: String,
    filterActive: Boolean,
    theme: ThemePalette,
    onShowAll: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(theme.bgElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (idle) Tabler.Calendar else Tabler.Search, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(26.dp))
        }
        Text(
            when {
                idle -> tr("接下來沒有排班或鬧鐘")
                query.isNotEmpty() -> tr("找不到「{0}」", query)
                else -> tr("這個分類還沒有項目")
            },
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.textMain, textAlign = TextAlign.Center
        )
        Text(
            if (idle) tr("在日曆排好班之後，這裡會列出接下來的行程")
            else tr("換個關鍵字，或用別的日期寫法試試：11/18、1118、11月18日"),
            fontSize = 12.sp, color = theme.textMuted, textAlign = TextAlign.Center, lineHeight = 17.sp
        )
        if (filterActive && !idle) {
            TextButton(onClick = onShowAll) { Text(tr("改搜全部分類"), color = theme.accentText, fontWeight = FontWeight.Bold) }
        }
    }
}
