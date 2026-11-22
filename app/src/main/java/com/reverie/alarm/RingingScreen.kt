package com.reverie.alarm

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ============================================================================
// 全螢幕響鈴畫面（參考系統鬧鐘的鎖屏樣式）
//   - 上方：日期與大時鐘、鬧鐘名稱、距離上班還有多久、出門的帶傘提醒
//   - 中間：「5分鐘後提醒」貪睡按鈕（不能貪睡時顯示提醒文字）
//   - 任意處往上滑關閉：畫面跟著手指走，放開時超過 1/4 螢幕或甩得夠快就飛出去並關閉，否則彈回來
// 畫面本身不播聲音，聲音由 AlarmRingService 負責
// ============================================================================

private fun untilText(from: LocalDateTime, to: LocalDateTime): String {
    val mins = Duration.between(from, to).toMinutes()
    if (mins <= 0) return tr("已到達時間")
    val h = mins / 60
    val m = mins % 60
    return when {
        h > 0 && m > 0 -> tr("{0} 小時 {1} 分鐘", h, m)
        h > 0 -> tr("{0} 小時", h)
        else -> tr("{0} 分鐘", m)
    }
}

@Composable
fun RingingScreen(
    spec: AlarmSpec,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }

    val isLeave = spec.stage == "LEAVE"
    val subText = spec.shiftStartLocal?.let { startStr ->
        runCatching {
            val start = LocalDateTime.parse(startStr)
            tr("距離上班時間（{0}）還有 {1}", start.format(DateTimeFormatter.ofPattern("HH:mm")), untilText(now, start))
        }.getOrNull()
    }
    val canSnooze = AlarmRingService.canSnooze(spec)

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF8A4560), Color(0xFF5A3550), Color(0xFF231C30))))
    ) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val progress = (-offsetY.value / heightPx).coerceIn(0f, 1f)
        val dragState = rememberDraggableState { delta ->
            if (!done) {
                // 只能往上滑；往下拉有阻力、最多一點點
                val step = if (delta > 0 && offsetY.value >= 0f) delta * 0.25f else delta
                val next = (offsetY.value + step).coerceIn(-heightPx, heightPx * 0.08f)
                scope.launch { offsetY.snapTo(next) }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (!done) {
                            if (-offsetY.value > heightPx * 0.25f || velocity < -2500f) {
                                done = true
                                offsetY.animateTo(-heightPx, tween(260))
                                onDismiss()
                            } else {
                                offsetY.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 380f))
                            }
                        }
                    }
                )
                .graphicsLayer {
                    translationY = offsetY.value
                    alpha = 1f - progress * 0.9f
                }
                .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                tr("{0}月{1}日", now.monthValue, now.dayOfMonth) + " " + I18n.weekday(now.dayOfWeek),
                color = Color.White.copy(alpha = 0.75f), fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Color.White.copy(alpha = 0.88f), fontSize = 96.sp, fontWeight = FontWeight.Light, lineHeight = 104.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(trLabel(spec.label), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (subText != null) {
                Spacer(Modifier.height(4.dp))
                Text(subText, color = Color(0xFFFDE68A), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            if (isLeave) {
                val ctx = LocalContext.current
                val hint = remember {
                    WeatherRepo.load(ctx.applicationContext)
                    umbrellaHint(WeatherRepo.data, LocalDateTime.now())
                }
                if (hint != null) {
                    Spacer(Modifier.height(10.dp))
                    IconText(
                        hint, icon = Tabler.Umbrella, color = Color(0xFFBAE6FD), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (canSnooze) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                        .clickable(enabled = !done) { onSnooze() }
                        .padding(horizontal = 30.dp, vertical = 18.dp)
                ) {
                    Icon(Tabler.Alarm, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(tr("{0}分鐘後提醒", spec.snoozeMinutes), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text(tr("還能貪睡 {0} 次", spec.remainingSnooze), color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                    }
                }
            } else if (spec.stage != "TIMER") {
                Text(
                    tr("已達防賴床上限／出門倒數禁止貪睡，請立即解除！"),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x55E11D48)).padding(horizontal = 18.dp, vertical = 14.dp)
                )
            }

            Spacer(Modifier.weight(1.3f))

            // 往上滑的提示：箭頭上下跳動
            val bounce = rememberInfiniteTransition(label = "hint")
            val dy by bounce.animateFloat(
                initialValue = 0f, targetValue = -10f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "hintY"
            )
            Icon(
                Tabler.ChevronUp, contentDescription = null, tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(30.dp).graphicsLayer { translationY = dy * density }
            )
            Text(tr("往上滑關閉鬧鐘"), color = Color.White.copy(alpha = 0.88f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
        }
    }
}
