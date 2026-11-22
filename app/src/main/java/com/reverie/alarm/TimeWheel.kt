package com.reverie.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.abs

// ============================================================================
// 滾輪式時間選擇（上午/下午・時・分）：取代系統的時鐘盤 TimePickerDialog
// ============================================================================

private val WHEEL_ITEM = 52.dp
private const val WHEEL_VISIBLE = 5

/**
 * 一欄滾輪。loop = true 時可以無限往上下滾（時、分）；
 * 停下來會吸附到最近的一格，點上下的數字也會直接捲過去。
 */
@Composable
fun WheelColumn(
    count: Int,
    initial: Int,
    loop: Boolean,
    width: Dp,
    theme: ThemePalette,
    label: (Int) -> String,
    onSelected: (Int) -> Unit
) {
    val total = if (loop) count * 1000 else count
    val start = if (loop) count * 500 + initial else initial
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { WHEEL_ITEM.toPx() }
    val center by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0 }
    }
    LaunchedEffect(center) { onSelected(center % count) }

    LazyColumn(
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = WHEEL_ITEM * (WHEEL_VISIBLE / 2)),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(width).height(WHEEL_ITEM * WHEEL_VISIBLE)
    ) {
        items(total) { i ->
            // 依「離中間幾格」決定大小與透明度，捲動時連續變化
            val dist = abs(i - (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPx))
            val near = (1f - dist / 2.6f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ITEM)
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        scope.launch { state.animateScrollToItem(i) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(i % count),
                    fontSize = 34.sp,
                    fontWeight = if (i == center) FontWeight.Bold else FontWeight.Normal,
                    color = theme.textMain,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        val s = 0.72f + 0.28f * near
                        scaleX = s; scaleY = s
                        alpha = 0.18f + 0.82f * near * near
                    }
                )
            }
        }
    }
}

/**
 * 滾輪時間選擇彈窗。
 * @param title 例如「修改上班時間」
 * @param context 標題下的小字，例如「早班」；會接上選好的 24 小時制時間
 */
@Composable
fun TimeWheelDialog(
    title: String,
    context: String?,
    initial: LocalTime,
    theme: ThemePalette,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    var pm by remember { mutableIntStateOf(if (initial.hour >= 12) 1 else 0) }
    var hour12 by remember { mutableIntStateOf(initial.hour % 12) } // 0 代表 12 點
    var minute by remember { mutableIntStateOf(initial.minute) }
    val picked = LocalTime.of(hour12 + pm * 12, minute)

    MotionAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.bgCard,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(context, formatHm24(picked)).joinToString(" · "),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.textMuted
                )
                Spacer(Modifier.height(14.dp))
                // 欄位標題
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Spacer(Modifier.width(84.dp))
                    Text(tr("時"), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain, textAlign = TextAlign.Center, modifier = Modifier.width(88.dp))
                    Text(tr("分"), fontSize = 15.sp, fontWeight = FontWeight.Black, color = theme.textMain, textAlign = TextAlign.Center, modifier = Modifier.width(88.dp))
                }
                Box(contentAlignment = Alignment.Center) {
                    // 中間選取列的淡色底
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(WHEEL_ITEM)
                            .clip(RoundedCornerShape(14.dp))
                            .background(theme.textMain.copy(alpha = 0.06f))
                    )
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        AmPmColumn(pm, theme) { pm = it }
                        WheelColumn(12, hour12, loop = true, width = 88.dp, theme = theme, label = { if (it == 0) "12" else "%02d".format(it) }) { hour12 = it }
                        WheelColumn(60, minute, loop = true, width = 88.dp, theme = theme, label = { "%02d".format(it) }) { minute = it }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                PillButton(tr("取消"), theme.textMain.copy(alpha = 0.10f), theme.textMain, Modifier.weight(1f), onDismiss)
                PillButton(tr("完成"), theme.accent.darkenForWhiteText(), Color.White, Modifier.weight(1f)) { onConfirm(picked) }
            }
        }
    )
}

/** 上午／下午：兩格就好，點一下或上下滑都能切換 */
@Composable
private fun AmPmColumn(pm: Int, theme: ThemePalette, onChange: (Int) -> Unit) {
    var dragged by remember { mutableStateOf(0f) }
    // 讓選到的那一個落在中間那一列（第 3 列）
    val top by androidx.compose.animation.core.animateDpAsState(if (pm == 0) WHEEL_ITEM * 2 else WHEEL_ITEM, label = "ampm")
    Column(
        modifier = Modifier
            .width(84.dp)
            .height(WHEEL_ITEM * WHEEL_VISIBLE)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d ->
                    dragged += d
                    if (dragged < -40f && pm == 0) { onChange(1); dragged = 0f }
                    if (dragged > 40f && pm == 1) { onChange(0); dragged = 0f }
                },
                onDragStopped = { dragged = 0f }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(top))
        listOf(tr("上午"), tr("下午")).forEachIndexed { i, s ->
            val on = i == pm
            Box(
                modifier = Modifier
                    .height(WHEEL_ITEM)
                    .fillMaxWidth()
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onChange(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    s, fontSize = if (on) 26.sp else 21.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = theme.textMain.copy(alpha = if (on) 1f else 0.4f)
                )
            }
        }
    }
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Black, color = fg) }
}

private fun formatHm24(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
