package com.reverie.alarm

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// 碼錶
//   - 用「開始的時間點」計算經過多久（不是一直累加），切分頁、關掉 App 再打開都會繼續算
//   - 狀態存在手機裡；重開機後如果還在跑，也會依時間點接著算
//   - 計次：每一圈的時間＋累計時間，最快一圈綠色、最慢一圈紅色；新的一圈會自動捲到最上面
//   - 跑的時候通知列有即時時間與「計次」「暫停」（ToolNotifications）
// ============================================================================
object StopwatchState {
    private const val PREFS = "reverie_stopwatch"
    var running by mutableStateOf(false)
        private set
    /** 暫停前累計的毫秒數 */
    var accumulatedMs by mutableLongStateOf(0L)
        private set
    /** 這一段開始的時間點（System.currentTimeMillis） */
    private var startedAt = 0L
    /** 每次按「計次」時的累計時間 */
    val laps = mutableStateListOf<Long>()
    private var loaded = false

    fun elapsed(now: Long = System.currentTimeMillis()): Long =
        accumulatedMs + if (running) (now - startedAt).coerceAtLeast(0L) else 0L

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        running = sp.getBoolean("running", false)
        accumulatedMs = sp.getLong("accumulated", 0L)
        startedAt = sp.getLong("started_at", 0L)
        laps.clear()
        sp.getString("laps", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.let { laps.addAll(it) }
    }

    private fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("running", running).putLong("accumulated", accumulatedMs).putLong("started_at", startedAt)
            .putString("laps", laps.joinToString(","))
            .apply()
        ToolNotifications.updateStopwatch(context)
    }

    fun start(context: Context) {
        if (running) return
        startedAt = System.currentTimeMillis()
        running = true
        save(context)
    }

    fun pause(context: Context) {
        if (!running) return
        accumulatedMs = elapsed()
        running = false
        save(context)
    }

    fun lap(context: Context) {
        if (!running) return
        laps.add(elapsed())
        save(context)
    }

    fun reset(context: Context) {
        running = false
        accumulatedMs = 0L
        startedAt = 0L
        laps.clear()
        save(context)
    }
}

/** 12:34.56；超過一小時 1:02:03.45 */
fun formatStopwatch(ms: Long): String {
    val cs = (ms / 10) % 100
    val s = (ms / 1000) % 60
    val m = (ms / 60_000) % 60
    val h = ms / 3_600_000
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%02d:%02d.%02d".format(m, s, cs)
}

/** 數字等寬：跑的時候寬度不變，不會左右晃，也不會讓外框跟著重新排版 */
private val DIGITS = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun StopwatchScreen(theme: ThemePalette) {
    val context = LocalContext.current
    StopwatchState.load(context)
    // 跑的時候每一幀都更新（數字才會順順地跳）。
    // 這個畫面本身不讀它：只交給大時間和「目前這一圈」兩個小元件讀，所以每幀只有它們重組，
    // 按鈕、整個計次列表只在按下按鈕時才重組
    // 「工具」分頁不在畫面上時停止每幀更新（時間是用開始的時間點算的，回來時一樣準）
    val clock = rememberFrameClock(StopwatchState.running && LocalTabVisible.current)
    val laps = StopwatchState.laps.toList()
    val started = StopwatchState.running || StopwatchState.accumulatedMs > 0
    // 每一圈的時間（第 1 圈在最前面）
    val splits = laps.mapIndexed { i, total -> total - (if (i == 0) 0L else laps[i - 1]) }
    val fastest = if (splits.size >= 2) splits.withIndex().minBy { it.value }.index else -1
    val slowest = if (splits.size >= 2) splits.withIndex().maxBy { it.value }.index else -1
    val digits = DIGITS
    val listState = rememberLazyListState()
    // 新增一圈時捲回最上面，看得到最新的一圈
    LaunchedEffect(laps.size) { if (laps.isNotEmpty()) listState.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        // ---- 大時間：分秒大字、百分秒小一點（數字等寬，跑的時候不會左右晃） ----
        Box(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 28.dp), contentAlignment = Alignment.Center) {
            StopwatchBigTime(clock, theme)
        }

        // ---- 按鈕：左邊 計次／重設，右邊 開始／暫停／繼續 ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val running = StopwatchState.running
            val leftEnabled = running || started
            ToolRoundButton(
                label = if (running || !started) tr("計次") else tr("重設"), enabled = leftEnabled,
                bg = theme.bgElevated, fg = theme.textMain, border = theme.borderColor
            ) {
                if (StopwatchState.running) StopwatchState.lap(context) else StopwatchState.reset(context)
            }
            ToolRoundButton(
                label = when { running -> tr("暫停"); started -> tr("繼續"); else -> tr("開始") },
                enabled = true,
                bg = if (running) ROSE.copy(alpha = 0.18f) else EMERALD.copy(alpha = 0.18f),
                fg = if (running) ROSE.readable() else EMERALD.readable(),
                border = if (running) ROSE.copy(alpha = 0.5f) else EMERALD.copy(alpha = 0.5f)
            ) {
                if (StopwatchState.running) StopwatchState.pause(context) else StopwatchState.start(context)
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = theme.borderColor)

        // ---- 計次列表（最新的在最上面；跑的時候最上面是目前這一圈） ----
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (started && (laps.isNotEmpty() || StopwatchState.running)) {
                item(key = "current") {
                    CurrentLapRow(clock, laps.size + 1, laps.lastOrNull() ?: 0L, theme, Modifier.animateItem())
                }
            }
            items(splits.indices.reversed().toList(), key = { "lap-$it" }) { i ->
                val color = when (i) {
                    fastest -> EMERALD.readable()
                    slowest -> ROSE.readable()
                    else -> theme.textMain
                }
                LapRow(tr("第 {0} 圈", i + 1), formatStopwatch(splits[i]), formatStopwatch(laps[i]), color, theme, digits, Modifier.animateItem())
            }
            if (!started) {
                item(key = "hint") {
                    Text(
                        tr("按「開始」計時，跑的時候按「計次」記下每一圈"), fontSize = 13.sp, color = theme.textMuted,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp).animateItem(), textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** 大時間：每一幀讀時間、只有這個 Text 重組 */
@Composable
private fun StopwatchBigTime(clock: LongState, theme: ThemePalette) {
    val elapsed = StopwatchState.elapsed(clock.longValue)
    val full = formatStopwatch(elapsed)
    val big = if (elapsed >= 3_600_000) 56.sp else 72.sp
    Text(
        buildAnnotatedString {
            append(full.substringBeforeLast('.'))
            withStyle(SpanStyle(fontSize = big * 0.6f, color = theme.textMuted)) { append("." + full.substringAfterLast('.')) }
        },
        style = DIGITS, fontSize = big, fontWeight = FontWeight.Light, color = theme.textMain
    )
}

/** 目前這一圈（跑的時候列表最上面那一列）：每一幀讀時間、只有這一列重組 */
@Composable
private fun CurrentLapRow(clock: LongState, lapNumber: Int, lastLapTotal: Long, theme: ThemePalette, modifier: Modifier) {
    val elapsed = StopwatchState.elapsed(clock.longValue)
    LapRow(tr("第 {0} 圈", lapNumber), formatStopwatch(elapsed - lastLapTotal), formatStopwatch(elapsed), theme.textMain, theme, DIGITS, modifier)
}

@Composable
private fun LapRow(name: String, split: String, total: String, color: Color, theme: ThemePalette, digits: TextStyle, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 15.sp, color = color, modifier = Modifier.width(96.dp))
            Text(split, style = digits, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
            Text(total, style = digits, fontSize = 13.sp, color = theme.textMuted)
        }
        HorizontalDivider(color = theme.borderColor.copy(alpha = 0.5f))
    }
}
