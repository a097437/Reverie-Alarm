package com.reverie.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

// ============================================================================
// 分頁四：天氣（結合班表的通勤天氣）
// ============================================================================

private val SKY = Color(0xFF38BDF8)
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val WEEK get() = java.time.DayOfWeek.entries.map { I18n.weekday(it) }

/** 一個通勤時間點：出門（或上班）、下班 */
private data class CommutePoint(val label: String, val at: LocalDateTime, val isLeave: Boolean)

/** 通勤天氣要顯示的兩個時間點；班別膠囊顯示 [event] */
private data class Commute(val event: ShiftEvent, val points: List<CommutePoint>)

private data class ShiftSpan(val event: ShiftEvent, val leave: CommutePoint, val end: CommutePoint)

private fun shiftSpans(events: List<ShiftEvent>, alarms: List<ShiftAlarm>, from: LocalDate): List<ShiftSpan> =
    (-1L..7L).mapNotNull { off ->
        val d = from.plusDays(off)
        val ev = events.firstOrNull { it.date == d && it.type != ShiftType.OFF } ?: return@mapNotNull null
        val start = LocalDateTime.of(d, ev.startTime)
        val end = LocalDateTime.of(if (!ev.endTime.isAfter(ev.startTime)) d.plusDays(1) else d, ev.endTime)
        val leaveAlarm = alarms.firstOrNull { it.date == d && it.stage == AlarmStage.LEAVE && it.enabled }
        val leave = if (leaveAlarm != null) CommutePoint(tr("出門"), LocalDateTime.of(d, leaveAlarm.triggerTime), true)
        else CommutePoint(tr("上班"), start, true)
        ShiftSpan(ev, leave, CommutePoint(tr("下班"), end, false))
    }

/**
 * 還沒出門 → 這一班的出門＋下班；
 * 已經在上班 → 這一班的下班＋下一班的出門。
 */
private fun findCommute(events: List<ShiftEvent>, alarms: List<ShiftAlarm>, now: LocalDateTime): Commute? {
    val spans = shiftSpans(events, alarms, now.toLocalDate())
    val current = spans.firstOrNull { it.end.at.isAfter(now) } ?: return null
    if (current.leave.at.isAfter(now)) return Commute(current.event, listOf(current.leave, current.end))
    val next = spans.firstOrNull { it.leave.at.isAfter(current.end.at) }
    return Commute(current.event, listOfNotNull(current.end, next?.leave))
}

/** 「出門 18:50」；不是今天的加上日期 */
private fun pointTitle(p: CommutePoint): String {
    val d = p.at.toLocalDate()
    val day = when (d) {
        LocalDate.now() -> ""
        LocalDate.now().plusDays(1) -> tr("明天 ")
        else -> "${d.monthValue}/${d.dayOfMonth} "
    }
    return "$day${p.label} ${p.at.format(HM)}"
}

@Composable
fun WeatherScreen(theme: ThemePalette, events: List<ShiftEvent>, alarms: List<ShiftAlarm>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        WeatherRepo.load(context)
        WeatherRepo.refresh(context)
    }

    fun useCurrentLocation() {
        scope.launch {
            locating = true
            val loc = currentLocation(context)
            locating = false
            if (loc == null) {
                AppToast.show(tr("⚠️ 抓不到目前位置，請確認已開啟定位，或改選縣市"))
                return@launch
            }
            val geo = WeatherRepo.reverseGeocode(context, loc.latitude, loc.longitude)
            val county = geo.county ?: nearestCounty(loc.latitude, loc.longitude).county
            WeatherRepo.setPlace(context, WeatherPlace("目前位置", loc.latitude, loc.longitude, county, isCurrent = true), geo.district)
            showPicker = false
            WeatherRepo.refresh(context, force = true)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useCurrentLocation() else AppToast.show(tr("沒有定位權限，請改選縣市"))
    }

    fun requestCurrentLocation() {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ok) useCurrentLocation() else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val place = WeatherRepo.place
    val data = WeatherRepo.data?.takeIf { it.place == place }
    val now = LocalDateTime.now()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- 地區與更新 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(50))
                        .background(theme.bgCard)
                        .border(1.dp, theme.borderColor, RoundedCornerShape(50))
                        .clickable { showPicker = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Icon(Tabler.MapPin, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            place == null -> tr("選擇地區")
                            place.isCurrent -> tr("目前位置・") + (WeatherRepo.district ?: place.county)
                            else -> place.name
                        },
                        fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Tabler.ChevronDown, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(16.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                    if (data != null) {
                        Text(
                            tr("更新於 ") + Instant.ofEpochMilli(data.fetchedAt).atZone(ZoneId.systemDefault()).toLocalTime().format(HM),
                            fontSize = 11.sp, color = theme.textMuted, maxLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (place != null) RefreshButton(theme, WeatherRepo.loading) { scope.launch { WeatherRepo.refresh(context, force = true) } }
                }
            }
        }

        if (place == null) {
            item { ChoosePlaceCard(theme, locating, onCurrent = { requestCurrentLocation() }, onPick = { showPicker = true }) }
            return@LazyColumn
        }

        WeatherRepo.error?.let { msg ->
            item {
                IconText(
                    "⚠️ " + msg + if (data != null) tr("（顯示上次的資料）") else "",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AMBER.readable(),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AMBER.copy(alpha = 0.12f)).padding(12.dp)
                )
            }
        }

        if (data == null) {
            item {
                WebCard(theme, radius = 28.dp, padding = 28.dp) {
                    Text(
                        if (WeatherRepo.loading) tr("正在取得天氣資料…") else tr("還沒有天氣資料，按右上角重新整理"),
                        fontSize = 13.sp, color = theme.textMuted, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            return@LazyColumn
        }

        // ---- 特報 ----
        if (data.alerts.isNotEmpty()) {
            items(data.alerts) { a -> AlertCard(theme, a, data.place.county) }
        }

        // ---- 目前天氣 ----
        item { CurrentCard(theme, data, WeatherRepo.district) }

        // ---- 這一班的通勤天氣 ----
        item { CommuteCard(theme, data, findCommute(events, alarms, now)) }

        // ---- 逐時預報 ----
        item { HourlyCard(theme, data, now) }

        // ---- 7 天預報＋班表 ----
        item { DailyCard(theme, data, events) }

        // ---- 細節 ----
        item { DetailsGrid(theme, data) }

        item {
            Text(
                tr("天氣資料：Open-Meteo（CC BY 4.0）・特報：國家災害防救科技中心 NCDR 災害示警公開資料。僅傳送地區經緯度，不會上傳個人資料。"),
                fontSize = 10.sp, color = theme.textMuted, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    MotionHost(showPicker) {
        MotionBottomSheet(palette = theme, onDismiss = { showPicker = false }, maxHeightFraction = 0.8f) {
            PlacePicker(theme, place, locating, onCurrent = { requestCurrentLocation() }) { p ->
                WeatherRepo.setPlace(context, p)
                showPicker = false
                scope.launch { WeatherRepo.refresh(context, force = true) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 元件
// ---------------------------------------------------------------------------

@Composable
private fun RefreshButton(theme: ThemePalette, loading: Boolean, onClick: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(theme.bgCard)
            .border(1.dp, theme.borderColor, CircleShape)
            .clickable(enabled = !loading) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Tabler.Refresh, contentDescription = tr("重新整理天氣"), tint = theme.textMain,
            modifier = Modifier.size(18.dp).rotate(if (loading) angle else 0f)
        )
    }
}

@Composable
private fun ChoosePlaceCard(theme: ThemePalette, locating: Boolean, onCurrent: () -> Unit, onPick: () -> Unit) {
    WebCard(theme, radius = 28.dp, padding = 22.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(SKY.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) { Icon(Tabler.TemperatureSun, contentDescription = null, tint = SKY.readable(3.0), modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(12.dp))
            Text(tr("先選擇你的地區"), fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain)
            Spacer(Modifier.height(4.dp))
            Text(tr("天氣會結合你的班表，顯示出門與下班時段的天氣和帶傘提醒。"), fontSize = 12.sp, color = theme.textMuted, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                WebButton(if (locating) tr("定位中…") else tr("使用目前位置"), theme, icon = Tabler.MapPin, modifier = Modifier.weight(1f), enabled = !locating, onClick = onCurrent)
                WebButton(tr("選擇縣市"), theme, filled = false, modifier = Modifier.weight(1f), onClick = onPick)
            }
        }
    }
}

@Composable
private fun PlacePicker(theme: ThemePalette, current: WeatherPlace?, locating: Boolean, onCurrent: () -> Unit, onPick: (WeatherPlace) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
        Text(tr("選擇天氣地區"), fontSize = 16.sp, fontWeight = FontWeight.Black, color = theme.textMain)
        Spacer(Modifier.height(4.dp))
        Text(tr("用縣市政府所在地的天氣；特報也會依這個縣市篩選。"), fontSize = 11.sp, color = theme.textMuted)
        Spacer(Modifier.height(12.dp))
        WebButton(
            if (locating) tr("定位中…") else tr("使用目前位置（需要定位權限）"), theme, icon = Tabler.MapPin,
            modifier = Modifier.fillMaxWidth(), enabled = !locating, onClick = onCurrent
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TW_PLACES.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        val active = current != null && !current.isCurrent && current.name == p.name
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) theme.accent.darkenForWhiteText() else theme.bgElevated)
                                .border(1.dp, if (active) Color.Transparent else theme.borderColor, RoundedCornerShape(12.dp))
                                .clickable { onPick(p) }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(tr(p.name), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else theme.textMain)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val ALERT_ORANGE = Color(0xFFFF8000)
private val ALERT_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
private val ZH_NUM = listOf("一", "二", "三", "四", "五")

/** 特報卡：和氣象署特報網頁相同的完整內容（發布時間、概述、特報區域、注意事項、網址） */
@Composable
private fun AlertCard(theme: ThemePalette, a: WxAlert, homeCounty: String?) {
    val tint = alertTint(a.color)
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val home = homeCounty?.replace('台', '臺')
    // 預設收合（只看標題、發布時間和概述開頭），點一下展開全文
    var open by remember(a.title, a.sent) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(tint.copy(alpha = 0.13f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .clickable { open = !open }
            .animateContentSize(tween(320, easing = Motion.EnterEasing))
            .padding(14.dp)
    ) {
        // 標題＋所在縣市的燈號
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Tabler.AlertTriangle, contentDescription = null, tint = tint.readable(3.0), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (I18n.isEn) trAlert(a.name) else a.name,
                fontSize = 15.sp, fontWeight = FontWeight.Black, color = tint.readable(), modifier = Modifier.weight(1f)
            )
            if (a.color.isNotBlank()) {
                Text(
                    tr("{0} {1}", tr(home ?: ""), tr("${a.color}燈號")),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (a.color == "黃色") Color(0xFFFFE14D) else tint)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        a.sent?.let { Text(tr("發布時間：{0}", it.format(ALERT_TIME)), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SKY.readable()) }
        a.expires?.let { Text(tr("有效至：{0}", it.format(ALERT_TIME)), fontSize = 12.sp, color = theme.textMuted) }

        if (!open) {
            Spacer(Modifier.height(6.dp))
            Text(a.summary, fontSize = 13.sp, color = theme.textMain, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AlertToggle(theme, open = false)
            return@Column
        }

        // 內容分段，依序編號（大雨特報沒有注意事項，就只有兩段）
        var n = 0
        @Composable
        fun Section(title: String, body: @Composable () -> Unit) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (I18n.isEn) tr(title) else "${ZH_NUM.getOrElse(n) { "${n + 1}" }}、$title",
                fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.textMain
            )
            n++
            Spacer(Modifier.height(2.dp))
            body()
        }
        Section("概述") { Text(a.summary, fontSize = 13.sp, color = theme.textMain, lineHeight = 20.sp) }
        if (a.areas.isNotEmpty()) Section("特報區域") {
            a.areas.forEach { ar ->
                Text("【${tr(ar.level)}】", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.textMain, lineHeight = 20.sp)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        ar.counties.forEachIndexed { i, c ->
                            if (i > 0) append(if (I18n.isEn) ", " else "、")
                            if (c == home) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Black, color = tint.readable()))
                                append(tr(c)); pop()
                            } else append(tr(c))
                        }
                        if (!I18n.isEn) append("。")
                    },
                    fontSize = 13.sp, color = theme.textMain, lineHeight = 20.sp
                )
            }
        }
        if (a.instructions.isNotEmpty()) Section("注意（警戒）事項") {
            a.instructions.forEach { Text(it, fontSize = 13.sp, color = theme.textMain, lineHeight = 20.sp) }
        }

        // 網址：點一下用瀏覽器開
        val links = listOfNotNull(
            a.web.takeIf { it.isNotBlank() }?.let { tr("氣象署特報網頁") to it },
            a.cap.takeIf { it.isNotBlank() }?.let { tr("原始資料（NCDR）") to it }
        )
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = tint.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))
            links.forEach { (label, url) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { runCatching { uri.openUri(url) } }
                        .padding(vertical = 4.dp)
                ) {
                    Text(label, fontSize = 11.sp, color = theme.textMuted)
                    Text(
                        url, fontSize = 12.sp, color = SKY.readable(), lineHeight = 17.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                }
            }
        }
        Text(
            if (I18n.isEn) "Source: ${a.source}" else tr("資料來源：{0}", a.source),
            fontSize = 10.sp, color = theme.textMuted, modifier = Modifier.padding(top = 4.dp)
        )
        AlertToggle(theme, open = true)
    }
}

private fun alertTint(color: String) = when (color) {
    "紅色" -> ROSE
    "橙色" -> ALERT_ORANGE
    else -> AMBER
}

/**
 * 排班日曆上方的特報提示：收合時一行（縣市＋特報名稱），點一下展開每則的燈號、發布時間和概述；
 * 「看完整內容」到天氣頁（有特報區域、注意事項和網址）
 */
@Composable
fun WeatherAlertBanner(alerts: List<WxAlert>, county: String, fontScale: Float, onOpenWeather: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val rank = mapOf("紅色" to 3, "橙色" to 2, "黃色" to 1)
    val tint = alertTint(alerts.maxByOrNull { rank[it.color] ?: 0 }?.color.orEmpty())
    val fg = tint.readable()
    val sep = if (I18n.isEn) ", " else "、"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.2f))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable { open = !open }
            .animateContentSize(tween(320, easing = Motion.EnterEasing))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Tabler.AlertTriangle, contentDescription = null, tint = fg, modifier = Modifier.size((16 * fontScale).dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "${tr(county)}${if (I18n.isEn) ": " else "："}" + alerts.joinToString(sep) { if (I18n.isEn) trAlert(it.name) else it.name },
                fontSize = (12 * fontScale).sp, fontWeight = FontWeight.Bold, color = fg,
                maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (open) Tabler.ChevronUp else Tabler.ChevronDown, contentDescription = if (open) tr("收起") else tr("展開"),
                tint = fg, modifier = Modifier.size(16.dp)
            )
        }
        if (open) {
            alerts.forEach { a ->
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = tint.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (I18n.isEn) trAlert(a.name) else a.name,
                        fontSize = (13 * fontScale).sp, fontWeight = FontWeight.Black, color = alertTint(a.color).readable(),
                        modifier = Modifier.weight(1f)
                    )
                    if (a.color.isNotBlank()) {
                        Text(
                            tr("${a.color}燈號"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black.copy(alpha = 0.85f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (a.color == "黃色") Color(0xFFFFE14D) else alertTint(a.color))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                a.sent?.let { Text(tr("發布時間：{0}", it.format(ALERT_TIME)), fontSize = (11 * fontScale).sp, color = SKY.readable()) }
                Spacer(Modifier.height(2.dp))
                Text(a.summary, fontSize = (12 * fontScale).sp, color = fg, lineHeight = (18 * fontScale).sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenWeather() }
                    .padding(vertical = 4.dp)
            ) {
                Text(tr("看完整內容（區域、注意事項、網址）"), fontSize = (12 * fontScale).sp, fontWeight = FontWeight.Bold, color = fg)
                Icon(Tabler.ChevronRight, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 特報卡底部的「展開全文 ▼／收起 ▲」 */
@Composable
private fun AlertToggle(theme: ThemePalette, open: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        Text(if (open) tr("收起") else tr("展開全文"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (open) Tabler.ChevronUp else Tabler.ChevronDown, contentDescription = null,
            tint = theme.textMuted, modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun CurrentCard(theme: ThemePalette, data: WeatherData, district: String?) {
    val c = data.current
    val today = data.day(LocalDate.now())
    WebCard(theme, radius = 28.dp, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.temp.tempText(), fontSize = 56.sp, fontWeight = FontWeight.Black, color = theme.textMain, lineHeight = 60.sp)
                Text(wxText(c.code), fontSize = 16.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("體感 {0}", c.feels.tempText()) + (today?.let { tr("・最高 {0} 最低 {1}", it.tMax.tempText(), it.tMin.tempText()) } ?: ""),
                    fontSize = 12.sp, color = theme.textMuted
                )
            }
            // 右側：天氣圖示＋目前縣市與地區
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(SKY.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(wxIcon(c.code, c.isDay), contentDescription = wxText(c.code), tint = SKY.readable(3.0), modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Tabler.MapPin, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(tr(data.place.county), fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain, maxLines = 1)
                }
                Text(
                    district ?: if (data.place.isCurrent) tr("目前位置") else tr("縣市代表點"),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.textMuted, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CommuteCard(theme: ThemePalette, data: WeatherData, commute: Commute?) {
    WebCard(theme, radius = 24.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Tabler.DoorExit, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("通勤天氣"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText, modifier = Modifier.weight(1f))
                if (commute != null) {
                    val d = commute.event.date
                    val label = when (d) {
                        LocalDate.now() -> tr("今天")
                        LocalDate.now().plusDays(1) -> tr("明天")
                        else -> "${d.monthValue}/${d.dayOfMonth}"
                    }
                    WebPill("$label・${tr(commute.event.title)}", commute.event.type.accentDot, fontSize = 11.sp)
                }
            }
            if (commute == null) {
                Text(tr("今天和明天都沒有要上班的班，好好休息！"), fontSize = 13.sp, color = theme.textMuted)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                commute.points.forEach { p -> CommuteSlot(theme, pointTitle(p), data.hourAt(p.at), Modifier.weight(1f)) }
            }
            val hint = commute.points.firstNotNullOfOrNull { p -> umbrellaHint(data, p.at, hours = if (p.isLeave) 2 else 1) }
            IconText(
                if (hint != null) "☁️ $hint" else tr("✅ 通勤時段降雨機率低，不用帶傘"),
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (hint != null) SKY.readable() else EMERALD.readable(),
                icon = if (hint != null) Tabler.Umbrella else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (hint != null) SKY else EMERALD).copy(alpha = 0.10f))
                    .padding(10.dp)
            )
        }
    }
}

@Composable
private fun CommuteSlot(theme: ThemePalette, title: String, h: HourWx?, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(theme.bgElevated)
            .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = theme.textMuted, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        if (h == null) {
            Text(tr("超出預報範圍"), fontSize = 12.sp, color = theme.textMuted)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(wxIcon(h.code, h.isDay), contentDescription = null, tint = SKY.readable(3.0), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(h.temp.tempText(), fontSize = 24.sp, fontWeight = FontWeight.Black, color = theme.textMain)
        }
        Spacer(Modifier.height(2.dp))
        Text(tr("{0}・降雨 {1}%", wxText(h.code), h.pop), fontSize = 11.sp, color = theme.textMuted, maxLines = 1)
    }
}

@Composable
private fun HourlyCard(theme: ThemePalette, data: WeatherData, now: LocalDateTime) {
    val start = now.withMinute(0).withSecond(0).withNano(0)
    val list = data.hours.filter { !it.time.isBefore(start) }.take(24)
    WebCard(theme, radius = 24.dp, padding = 0.dp) {
        Column(Modifier.padding(vertical = 14.dp)) {
            SectionTitle(theme, Tabler.Clock, tr("24 小時預報"), Modifier.padding(horizontal = 14.dp))
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list) { h ->
                    val isNow = h.time == start
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isNow) SKY.copy(alpha = 0.14f) else theme.bgElevated)
                            .padding(vertical = 10.dp)
                    ) {
                        Text(if (isNow) tr("現在") else h.time.format(DateTimeFormatter.ofPattern(tr("HH時"))), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
                        Spacer(Modifier.height(6.dp))
                        Icon(wxIcon(h.code, h.isDay), contentDescription = null, tint = SKY.readable(3.0), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(h.temp.tempText(), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                        Text(
                            "${h.pop}%", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = if (h.pop >= 50) SKY.readable() else theme.textMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyCard(theme: ThemePalette, data: WeatherData, events: List<ShiftEvent>) {
    val days = data.days.filter { !it.date.isBefore(LocalDate.now()) }.take(7)
    val lo = days.minOfOrNull { it.tMin } ?: 0.0
    val hi = days.maxOfOrNull { it.tMax } ?: 1.0
    WebCard(theme, radius = 24.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionTitle(theme, Tabler.CalendarWeek, tr("7 天預報與班表"))
            Spacer(Modifier.height(8.dp))
            days.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = theme.borderColor.copy(alpha = 0.6f))
                val ev = events.firstOrNull { it.date == d.date }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 9.dp)) {
                    Column(Modifier.width(54.dp)) {
                        Text(if (i == 0) tr("今天") else WEEK[d.date.dayOfWeek.value - 1], fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                        Text("${d.date.monthValue}/${d.date.dayOfMonth}", fontSize = 10.sp, color = theme.textMuted)
                    }
                    Box(Modifier.width(48.dp)) {
                        if (ev != null) {
                            Text(
                                tr(ev.title), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                                color = ev.type.chipText(), overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ev.type.chipBg()).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Icon(wxIcon(d.code), contentDescription = wxText(d.code), tint = SKY.readable(3.0), modifier = Modifier.size(22.dp))
                    Text(
                        "${d.pop}%", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (d.pop >= 50) SKY.readable() else theme.textMuted,
                        modifier = Modifier.width(40.dp).padding(start = 6.dp)
                    )
                    Text(d.tMin.tempText(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.textMuted, modifier = Modifier.width(32.dp))
                    TempBar(theme, d.tMin, d.tMax, lo, hi, Modifier.weight(1f))
                    Text(d.tMax.tempText(), fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.textMain, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** 最低～最高溫的長條（同一週的溫度範圍內） */
@Composable
private fun TempBar(theme: ThemePalette, tMin: Double, tMax: Double, lo: Double, hi: Double, modifier: Modifier) {
    val span = (hi - lo).coerceAtLeast(1.0)
    val a = ((tMin - lo) / span).toFloat().coerceIn(0f, 1f)
    val b = ((tMax - lo) / span).toFloat().coerceIn(a, 1f)
    Box(modifier.height(6.dp).clip(RoundedCornerShape(50)).background(theme.bgElevated)) {
        Row(Modifier.fillMaxSize()) {
            if (a > 0f) Spacer(Modifier.weight(a))
            Box(
                Modifier.weight((b - a).coerceAtLeast(0.04f)).fillMaxSize().clip(RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(SKY, AMBER)))
            )
            if (b < 1f) Spacer(Modifier.weight(1f - b))
        }
    }
}

@Composable
private fun DetailsGrid(theme: ThemePalette, data: WeatherData) {
    val today = data.day(LocalDate.now())
    val c = data.current
    val uv = today?.uv ?: 0.0
    val uvLevel = when {
        uv < 3 -> tr("低")
        uv < 6 -> tr("中")
        uv < 8 -> tr("高")
        uv < 11 -> tr("過量")
        else -> tr("危險")
    }
    val cells = listOf(
        Triple(Tabler.Sunrise, tr("日出"), today?.sunrise?.format(HM) ?: "—"),
        Triple(Tabler.Sunset2, tr("日落"), today?.sunset?.format(HM) ?: "—"),
        Triple(Tabler.SunHigh, tr("紫外線"), "${"%.0f".format(uv)}・$uvLevel"),
        Triple(Tabler.Droplet, tr("濕度"), "${c.humidity}%"),
        Triple(Tabler.Wind, tr("風速"), "${"%.0f".format(c.wind)} km/h"),
        Triple(Tabler.Umbrella, tr("今日降雨"), "${today?.pop ?: 0}%")
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label, value) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(theme.bgCard)
                            .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain, fontFamily = FontFamily.Default, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(theme: ThemePalette, icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = theme.accentText, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.accentText)
    }
}

// ---------------------------------------------------------------------------
// 目前位置（只用大概位置，抓一次就好）
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        for (p in providers) {
            val loc = withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        lm.getCurrentLocation(p, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (loc != null) return loc
        }
    }
    return (providers + LocationManager.PASSIVE_PROVIDER).firstNotNullOfOrNull { p ->
        runCatching { lm.getLastKnownLocation(p) }.getOrNull()
    }
}
