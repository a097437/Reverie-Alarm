package com.reverie.alarm

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ============================================================================
// 計時（倒數計時器）
//   - 用「結束的時間點」計算剩多久：切分頁、關掉 App 都會繼續倒數
//   - 時間到由系統精準鬧鐘叫醒 App 響鈴（和兩段式鬧鐘同一套響鈴畫面），鎖屏或 App 關掉也會響
//   - 暫停／取消時會一起取消系統排程；倒數中通知列有即時倒數與按鈕（ToolNotifications）
//   - 時間到之後按「完成」或「重新開始」會順便停掉還在響的鈴聲
// ============================================================================
enum class TimerStatus { IDLE, RUNNING, PAUSED }

object CountdownTimerState {
    private const val PREFS = "reverie_timer"
    const val ALARM_KEY = AlarmEngine.TIMER_PREFIX + "main"

    var status by mutableStateOf(TimerStatus.IDLE)
        private set
    /** 這次倒數的總長度（含按「+1 分鐘」加上去的） */
    var durationMs by mutableLongStateOf(5 * 60_000L)
        private set
    /** 使用者設定的長度：取消／完成後滾輪回到這個長度，「重新開始」也用它（不含 +1 分鐘，像系統時鐘） */
    var presetMs by mutableLongStateOf(5 * 60_000L)
        private set
    /** 執行中：結束的時間點（System.currentTimeMillis） */
    var endAt by mutableLongStateOf(0L)
        private set
    /** 暫停中：剩下的毫秒數 */
    private var pausedRemaining = 0L
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        status = runCatching { TimerStatus.valueOf(sp.getString("status", "IDLE")!!) }.getOrDefault(TimerStatus.IDLE)
        durationMs = sp.getLong("duration", 5 * 60_000L)
        presetMs = sp.getLong("preset", durationMs)
        endAt = sp.getLong("end_at", 0L)
        pausedRemaining = sp.getLong("paused_remaining", 0L)
    }

    private fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", status.name).putLong("duration", durationMs).putLong("preset", presetMs)
            .putLong("end_at", endAt).putLong("paused_remaining", pausedRemaining).apply()
        ToolNotifications.updateTimer(context)
    }

    fun remaining(now: Long = System.currentTimeMillis()): Long = when (status) {
        TimerStatus.RUNNING -> (endAt - now).coerceAtLeast(0L)
        TimerStatus.PAUSED -> pausedRemaining
        TimerStatus.IDLE -> durationMs
    }

    /** 執行中而且已經到時間了（響過鈴） */
    fun isFinished(now: Long = System.currentTimeMillis()) = status == TimerStatus.RUNNING && endAt <= now

    fun endTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(endAt), ZoneId.systemDefault())

    fun start(context: Context, duration: Long) {
        if (duration <= 0) return
        stopRingIfTimer(context)
        presetMs = duration
        durationMs = duration
        endAt = System.currentTimeMillis() + duration
        status = TimerStatus.RUNNING
        save(context)
        scheduleRing(context)
        AppLog.i("Timer", "開始計時 ${formatTimer(duration)}，結束於 ${endTime()}")
    }

    fun pause(context: Context) {
        if (status != TimerStatus.RUNNING || isFinished()) return
        pausedRemaining = remaining()
        status = TimerStatus.PAUSED
        AlarmEngine.cancel(context, ALARM_KEY)
        save(context)
    }

    fun resume(context: Context) {
        if (status != TimerStatus.PAUSED) return
        endAt = System.currentTimeMillis() + pausedRemaining
        status = TimerStatus.RUNNING
        save(context)
        scheduleRing(context)
    }

    /** 多加一分鐘（執行中或暫停中都可以） */
    fun addMinute(context: Context) {
        when (status) {
            TimerStatus.RUNNING -> if (!isFinished()) { endAt += 60_000L; durationMs += 60_000L; save(context); scheduleRing(context) }
            TimerStatus.PAUSED -> { pausedRemaining += 60_000L; durationMs += 60_000L; save(context) }
            TimerStatus.IDLE -> Unit
        }
    }

    /** 取消，或時間到之後按「完成」／關掉鈴聲：回到設定畫面（回到原本設定的長度），還在響就一起停掉 */
    fun reset(context: Context) {
        stopRingIfTimer(context)
        status = TimerStatus.IDLE
        durationMs = presetMs
        endAt = 0L
        pausedRemaining = 0L
        AlarmEngine.cancel(context, ALARM_KEY)
        save(context)
    }

    /** 計時器的鈴聲還在響 → 停掉（不會影響正在響的其他鬧鐘） */
    private fun stopRingIfTimer(context: Context) {
        if (AlarmRingService.ringingKey != ALARM_KEY) return
        runCatching {
            context.startService(
                Intent(context, AlarmRingService::class.java)
                    .setAction(AlarmRingService.ACTION_DISMISS_KEY)
                    .putExtra(AlarmEngine.EXTRA_KEY, ALARM_KEY)
            )
        }.onFailure { AppLog.w("Timer", "停止計時鈴聲失敗", it) }
    }

    private fun scheduleRing(context: Context) {
        val spec = AlarmSpec(
            key = ALARM_KEY,
            triggerLocal = endTime().toString(),
            label = tr("計時 {0} 結束", formatTimer(durationMs)),
            stage = "TIMER",
            stageLabel = tr("計時結束"),
            ringtoneUri = BuiltInRingtones.uri(context, BuiltInRingtones.MUSIC_BOX),
            snoozeMinutes = 0,
            maxSnooze = 0,
            fadeIn = false // 計時器時間到要馬上聽得到
        )
        if (!AlarmEngine.schedule(context, spec)) AppLog.w("Timer", "計時結束時間已過，沒有排程")
    }
}

/** 04:59、1:02:03（無條件進位：剩 0.4 秒時還顯示 00:01） */
fun formatTimer(ms: Long): String {
    val totalSec = (ms + 999) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private val PRESETS = listOf(1, 3, 5, 10, 15, 30, 60) // 分鐘
private val HM = DateTimeFormatter.ofPattern("HH:mm")
/** 數字等寬：倒數時寬度不變、不會左右晃 */
private val TIMER_DIGITS = TextStyle(fontFeatureSettings = "tnum")

/** 「將在幾點結束」：自己每 10 秒更新，不必讓整個設定畫面（三個滾輪）跟著重組 */
@Composable
private fun TimerEndHint(total: Long, theme: ThemePalette) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000L); now = LocalDateTime.now() } }
    Text(
        if (total > 0) tr("將在 {0} 結束", now.plusSeconds(total / 1000).format(HM)) else tr("請先設定時間"),
        fontSize = 13.sp, color = theme.textMuted
    )
}

@Composable
fun TimerScreen(theme: ThemePalette) {
    val context = LocalContext.current
    CountdownTimerState.load(context)
    // 設定畫面 ↔ 倒數畫面：淡入淡出加一點縮放
    AnimatedContent(
        targetState = CountdownTimerState.status == TimerStatus.IDLE,
        transitionSpec = {
            (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320, easing = Motion.EnterEasing), initialScale = 0.94f)) togetherWith
                (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 1.04f))
        },
        label = "timerMode"
    ) { idle ->
        if (idle) TimerSetup(theme) else TimerRunning(theme)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerSetup(theme: ThemePalette) {
    val context = LocalContext.current
    val initial = CountdownTimerState.durationMs / 1000
    var h by remember { mutableIntStateOf((initial / 3600).toInt()) }
    var m by remember { mutableIntStateOf(((initial % 3600) / 60).toInt()) }
    var s by remember { mutableIntStateOf((initial % 60).toInt()) }
    var wheelKey by remember { mutableIntStateOf(0) } // 按常用時間時重建滾輪
    val total = (h * 3600L + m * 60L + s) * 1000L

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            listOf(tr("小時"), tr("分"), tr("秒")).forEachIndexed { i, label ->
                if (i > 0) Spacer(Modifier.width(16.dp))
                Text(label, fontSize = 12.sp, color = theme.textMuted, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
            }
        }
        key(wheelKey) {
            Box(contentAlignment = Alignment.Center) {
                // 中間選取列的底色
                Box(Modifier.width(300.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).background(theme.bgElevated))
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    WheelColumn(count = 24, initial = h, loop = true, width = 84.dp, theme = theme, label = { "%02d".format(it) }) { h = it }
                    Text(":", fontSize = 30.sp, color = theme.textMuted, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
                    WheelColumn(count = 60, initial = m, loop = true, width = 84.dp, theme = theme, label = { "%02d".format(it) }) { m = it }
                    Text(":", fontSize = 30.sp, color = theme.textMuted, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
                    WheelColumn(count = 60, initial = s, loop = true, width = 84.dp, theme = theme, label = { "%02d".format(it) }) { s = it }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PRESETS.forEach { min ->
                val active = total == min * 60_000L
                val bg by animateColorAsState(if (active) theme.accent.darkenForWhiteText() else theme.bgElevated, tween(200), label = "presetBg")
                val fg by animateColorAsState(if (active) Color.White else theme.textMain, tween(200), label = "presetFg")
                Text(
                    if (min >= 60) tr("{0} 小時", min / 60) else tr("{0} 分鐘", min),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(bg)
                        .border(1.dp, if (active) Color.Transparent else theme.borderColor, RoundedCornerShape(50))
                        .clickable { h = min / 60; m = min % 60; s = 0; wheelKey++ }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        TimerEndHint(total, theme)
        Spacer(Modifier.height(18.dp))
        ToolRoundButton(
            label = tr("開始"), enabled = total > 0,
            bg = EMERALD.copy(alpha = 0.18f), fg = EMERALD.readable(), border = EMERALD.copy(alpha = 0.5f)
        ) { CountdownTimerState.start(context, total) }
        Spacer(Modifier.height(12.dp))
        Text(tr("時間到會響鈴，App 關掉或鎖屏也會響"), fontSize = 12.sp, color = theme.textMuted)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TimerRunning(theme: ThemePalette) {
    val context = LocalContext.current
    val st = CountdownTimerState.status
    // 倒數中每一幀更新（進度環才會平順）；時間到之後只要每半秒更新「已超過」。
    // 「工具」分頁不在畫面上時停止更新（時間是用結束的時間點算的，回來時一樣準）
    val visible = LocalTabVisible.current
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st, CountdownTimerState.endAt, visible) {
        clock.longValue = System.currentTimeMillis()
        while (visible && CountdownTimerState.status == TimerStatus.RUNNING) {
            if (CountdownTimerState.isFinished()) { delay(500L); clock.longValue = System.currentTimeMillis() }
            else withFrameMillis { clock.longValue = System.currentTimeMillis() }
        }
    }
    // 數字一秒才變一次：用 derivedStateOf 換算成「秒」，這個畫面一秒最多重組一次；
    // 每一幀都要動的只有進度環，它在 Canvas 的繪製區塊裡自己讀時間
    val remainingSec by remember { derivedStateOf { (CountdownTimerState.remaining(clock.longValue) + 999) / 1000 } }
    val finished by remember { derivedStateOf { CountdownTimerState.isFinished(clock.longValue) } }
    val overSec by remember {
        derivedStateOf { ((clock.longValue - CountdownTimerState.endAt).coerceAtLeast(0L) + 999) / 1000 }
    }
    val digits = TIMER_DIGITS
    val total = CountdownTimerState.durationMs.coerceAtLeast(1L)
    // 最後 10 秒變琥珀色提醒，時間到變紅色
    val ringColor = animateColorAsState(
        when {
            finished -> ROSE
            st == TimerStatus.RUNNING && remainingSec <= 10L -> AMBER
            else -> theme.accent
        },
        tween(400), label = "timerRing"
    )
    val track = theme.borderColor

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                // 繪製階段才讀時間：每一幀只重畫這個圓環，不會重組整個畫面
                val now = clock.longValue
                val fraction = (CountdownTimerState.remaining(now).toFloat() / total).coerceIn(0f, 1f)
                val stroke = 12.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                // 剩下的部分：從 12 點方向開始，隨時間縮短
                val sweep = 360f * (if (CountdownTimerState.isFinished(now)) 1f else fraction)
                drawArc(ringColor.value, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (finished) tr("時間到") else formatTimer(remainingSec * 1000), style = digits,
                    fontSize = if (finished) 40.sp else if (remainingSec * 1000 >= 3_600_000) 46.sp else 58.sp,
                    fontWeight = if (finished) FontWeight.Black else FontWeight.Light,
                    color = if (finished) ROSE.readable() else theme.textMain
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        finished -> tr("已超過 {0}", formatTimer(overSec * 1000))
                        st == TimerStatus.PAUSED -> tr("已暫停")
                        else -> tr("{0} 結束", CountdownTimerState.endTime().format(HM))
                    },
                    style = digits, fontSize = 14.sp, color = theme.textMuted
                )
                Text(tr("共 {0}", formatTimer(total)), style = digits, fontSize = 12.sp, color = theme.textMuted.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolRoundButton(
                label = if (finished) tr("完成") else tr("取消"), enabled = true,
                bg = theme.bgElevated, fg = theme.textMain, border = theme.borderColor
            ) { CountdownTimerState.reset(context) }
            if (!finished) {
                Text(
                    tr("+1 分鐘"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.accentText,
                    modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, theme.borderColor, RoundedCornerShape(50))
                        .clickable { CountdownTimerState.addMinute(context) }.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            when {
                finished -> ToolRoundButton(
                    label = tr("重新開始"), enabled = true,
                    bg = EMERALD.copy(alpha = 0.18f), fg = EMERALD.readable(), border = EMERALD.copy(alpha = 0.5f)
                ) { CountdownTimerState.start(context, CountdownTimerState.presetMs) }
                st == TimerStatus.RUNNING -> ToolRoundButton(
                    label = tr("暫停"), enabled = true,
                    bg = AMBER.copy(alpha = 0.18f), fg = AMBER.readable(), border = AMBER.copy(alpha = 0.5f)
                ) { CountdownTimerState.pause(context) }
                else -> ToolRoundButton(
                    label = tr("繼續"), enabled = true,
                    bg = EMERALD.copy(alpha = 0.18f), fg = EMERALD.readable(), border = EMERALD.copy(alpha = 0.5f)
                ) { CountdownTimerState.resume(context) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
