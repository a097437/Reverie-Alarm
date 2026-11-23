package com.reverie.alarm

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ============================================================================
// 動畫系統：所有彈窗、抽屜、選單、膠囊、提示氣泡共用
//   - 設定頁「動態效果」可關閉動畫或調整快慢（Motion.enabled / Motion.speedFactor）
//   - MotionHost：父層把 visible 設成 false 時，先播完退場動畫才真正移除，
//     所以按鈕、返回鍵、點背景等所有關閉方式都會有退場動畫；動畫中再打開會直接接回進場
// ============================================================================

object Motion {
    var enabled by mutableStateOf(true)
    /** 0.6 = 快、1.0 = 標準、1.5 = 慢 */
    var speedFactor by mutableFloatStateOf(1f)

    /** 實際快慢由 AppMotionDurationScale 統一套用（含抽屜、換頁等既有動畫），這裡只回傳基準毫秒 */
    fun ms(base: Int): Int = base

    /** 進場：快出慢停；退場：慢起快收 */
    val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)   // M3 emphasized decelerate
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)    // M3 emphasized accelerate

    fun speedFactorOf(id: String): Float = when (id) {
        "fast" -> 0.6f
        "slow" -> 1.5f
        else -> 1f
    }
}

/**
 * 整個 App 的動畫時間倍率：關閉動畫 = 0（瞬間完成），否則依「輕快／標準／舒緩」。
 * 取代系統的 animator_duration_scale，所以手機把動畫關掉時 App 內動畫仍照設定播放。
 */
object AppMotionDurationScale : androidx.compose.ui.MotionDurationScale {
    override val scaleFactor: Float
        get() = if (Motion.enabled) Motion.speedFactor else 0f
}

/** 目前所在彈窗的顯示進度來源（MotionHost 提供） */
val LocalOverlayTransition = compositionLocalOf<Transition<Boolean>?> { null }

@Composable
fun MotionHost(visible: Boolean, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    // 開關的那一幀通常很重（建立彈窗視窗、整頁重新組合，約 100ms），
    // 先等兩幀讓畫面準備好再開始動畫，否則前半段動畫會在卡頓中被跳過、看起來像瞬間出現
    LaunchedEffect(visible) {
        if (state.targetState == visible) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        state.targetState = visible
    }
    if (visible || state.currentState || state.targetState) {
        val transition = updateTransition(state, label = "overlay")
        CompositionLocalProvider(LocalOverlayTransition provides transition) { content() }
    }
}

/** 0 = 完全隱藏、1 = 完全顯示（不在 MotionHost 裡時固定為 1） */
@Composable
fun overlayProgress(enterMs: Int = 320, exitMs: Int = 240): State<Float> {
    val t = LocalOverlayTransition.current ?: return remember { mutableFloatStateOf(1f) }
    val v = t.animateFloat(
        transitionSpec = {
            if (targetState) tween(Motion.ms(enterMs), easing = Motion.EnterEasing)
            else tween(Motion.ms(exitMs), easing = Motion.ExitEasing)
        },
        label = "overlayProgress"
    ) { if (it) 1f else 0f }
    return v
}

/** 是否正在退場（退場中不再接受點擊，避免重複觸發） */
@Composable
fun overlayClosing(): Boolean = LocalOverlayTransition.current?.targetState == false

/**
 * 全螢幕、無系統動畫的對話框視窗。
 * 對話框是獨立視窗，不會繼承 App 的字級設定，所以這裡再套一次（不跟隨手機系統字級）。
 */
@Composable
private fun AppDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val base = LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDensity provides androidx.compose.ui.unit.Density(base.density, UiScale.fontScale)
        ) { content() }
    }
}

/** 關掉 Dialog 視窗本身的系統遮罩與視窗動畫，改由我們自己畫，才能同步淡出 */
@Composable
private fun PlainDialogWindow() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setDimAmount(0f)
            w.setWindowAnimations(0)
        }
    }
}

private val noRipple = MutableInteractionSource()

/**
 * 底部抽屜：由下往上滑入；關閉時向下滑出＋背景遮罩同步淡出。
 * 拖曳上方把手往下拉超過 120dp 也會關閉。
 */
@Composable
fun MotionBottomSheet(
    palette: ThemePalette,
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.94f,
    fixedHeightFraction: Float? = null,
    content: @Composable () -> Unit
) {
    val progress by overlayProgress()
    val closing = overlayClosing()
    val dismiss by rememberUpdatedState(onDismiss)
    AppDialog(onDismissRequest = { if (!closing) dismiss() }) {
        PlainDialogWindow()
        var dragPx by remember { mutableFloatStateOf(0f) }
        val snapBack = remember { Animatable(0f) }
        val density = LocalDensity.current
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f * progress))
                .clickable(interactionSource = noRipple, indication = null) { if (!closing) dismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            val sheetMax = maxHeight * maxHeightFraction
            val heightPx = with(density) { sheetMax.toPx() }
            Surface(
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = palette.bgCard,
                contentColor = palette.textMain,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (fixedHeightFraction != null) it.heightIn(min = maxHeight * fixedHeightFraction, max = maxHeight * fixedHeightFraction) else it.heightIn(max = sheetMax) }
                    .graphicsLayer {
                        translationY = (1f - progress) * heightPx + dragPx + snapBack.value
                    }
                    .clickable(interactionSource = noRipple, indication = null) { /* 吃掉點擊 */ }
            ) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    // 把手：可往下拖曳關閉
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { d -> dragPx = (dragPx + d).coerceAtLeast(0f) },
                                onDragStopped = {
                                    val threshold = with(density) { 120.dp.toPx() }
                                    if (dragPx > threshold) {
                                        dismiss()
                                    } else {
                                        snapBack.snapTo(dragPx)
                                        dragPx = 0f
                                        snapBack.animateTo(0f, tween(Motion.ms(180), easing = Motion.EnterEasing))
                                    }
                                }
                            )
                            .padding(top = 10.dp, bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(palette.textMuted.copy(alpha = 0.4f)))
                    }
                    content()
                }
            }
        }
    }
}

/** 置中彈窗（取代 AlertDialog）：微幅上浮淡入；關閉時向下滑出淡出＋遮罩同步淡出 */
@Composable
fun MotionAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color? = null
) {
    val progress by overlayProgress()
    val closing = overlayClosing()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val bg = containerColor ?: androidx.compose.material3.MaterialTheme.colorScheme.surface
    AppDialog(onDismissRequest = { if (!closing) dismiss() }) {
        PlainDialogWindow()
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f * progress))
                .clickable(interactionSource = noRipple, indication = null) { if (!closing) dismiss() }
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = bg,
                shadowElevation = 6.dp,
                modifier = modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = progress
                        translationY = (1f - progress) * with(density) { 72.dp.toPx() }
                        val s = 0.88f + 0.12f * progress
                        scaleX = s; scaleY = s
                    }
                    .clickable(interactionSource = noRipple, indication = null) { }
            ) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 14.dp)) {
                    if (icon != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { icon() }
                        Spacer(Modifier.size(12.dp))
                    }
                    if (title != null) {
                        androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.MaterialTheme.typography.titleLarge) { title() }
                        Spacer(Modifier.size(14.dp))
                    }
                    if (text != null) {
                        Box(Modifier.weight(1f, fill = false)) {
                            androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.MaterialTheme.typography.bodyMedium) { text() }
                        }
                        Spacer(Modifier.size(14.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/** 全螢幕彈窗（圖片燈箱）：淡入＋微幅上浮；關閉時下滑淡出 */
@Composable
fun MotionFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val progress by overlayProgress()
    val closing = overlayClosing()
    val dismiss by rememberUpdatedState(onDismiss)
    AppDialog(onDismissRequest = { if (!closing) dismiss() }) {
        PlainDialogWindow()
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = progress
                    translationY = (1f - progress) * with(density) { 96.dp.toPx() }
                }
        ) { content() }
    }
}

// ---------------------------------------------------------------------------
// ⋮ 下拉選單：從右上角微幅放大並向下浮出淡入；收合時反向縮小淡出
// ---------------------------------------------------------------------------
private class MenuPositionProvider(private val gapPx: Int, private val edgePx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(edgePx, (windowSize.width - popupContentSize.width - edgePx).coerceAtLeast(edgePx))
        return IntOffset(x, anchorBounds.bottom + gapPx)
    }
}

@Composable
fun MotionDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    palette: ThemePalette,
    width: Dp = 290.dp,
    content: @Composable () -> Unit
) {
    MotionHost(expanded) {
        val progress by overlayProgress(enterMs = 280, exitMs = 200)
        val closing = overlayClosing()
        val density = LocalDensity.current
        val provider = remember(density) { with(density) { MenuPositionProvider(6.dp.roundToPx(), 8.dp.roundToPx()) } }
        Popup(
            popupPositionProvider = provider,
            onDismissRequest = { if (!closing) onDismissRequest() },
            properties = PopupProperties(focusable = !closing)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.bgCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.borderColor),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .padding(4.dp)
                    .widthIn(min = width, max = width)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(1f, 0f) // 右上角
                        alpha = progress
                        val s = 0.7f + 0.3f * progress
                        scaleX = s; scaleY = s
                        translationY = (1f - progress) * with(density) { -24.dp.toPx() }
                    }
            ) {
                Column(Modifier.padding(vertical = 6.dp)) { content() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 班別色塊「微幅放大再回彈」：key 改變（套用新班別）時播一次
// ---------------------------------------------------------------------------
@Composable
fun rememberBounceScale(key: Any?, resetKey: Any?): State<Float> {
    val scale = remember(resetKey) { Animatable(1f) }
    // 上一次的 key：一般陣列不是 Compose 狀態，只拿來比較，不會觸發重組
    val last = remember(resetKey) { arrayOf(key) }
    // key 真的變了才啟動協程：月曆一頁幾十格，平常不必每一格都開一個
    if (key != last[0]) {
        LaunchedEffect(resetKey, key) {
            try {
                if (key != null && Motion.enabled) {
                    scale.snapTo(1f)
                    scale.animateTo(1f, keyframes {
                        durationMillis = Motion.ms(380).coerceAtLeast(1)
                        1.24f at (durationMillis * 0.38f).toInt() using Motion.EnterEasing
                        0.94f at (durationMillis * 0.7f).toInt()
                    })
                }
                last[0] = key
            } finally {
                // 動畫被中途打斷時回到原本大小，不會停在放大的狀態
                if (scale.value != 1f) withContext(NonCancellable) { scale.snapTo(1f) }
            }
        }
    }
    return scale.asState()
}

// ---------------------------------------------------------------------------
// 頂部提示氣泡：從上方輕微滑入、停留後滑出淡出（取代系統 Toast）
// ---------------------------------------------------------------------------
object AppToast {
    internal var message by mutableStateOf<String?>(null)
    internal var serial by mutableIntStateOf(0)
    /** 提示上的按鈕文字（例如「復原」）；沒有按鈕是 null */
    internal var actionLabel: String? = null
    private var action: (() -> Unit)? = null

    fun show(text: CharSequence) = show(text, null, null)

    /** 帶一個按鈕的提示（例如「復原」）：停留久一點；按下去只會執行一次 */
    fun show(text: CharSequence, actionLabel: String?, onAction: (() -> Unit)?) {
        message = text.toString()
        this.actionLabel = if (onAction != null) actionLabel else null
        action = onAction
        serial++
    }

    internal fun runAction() {
        val a = action ?: return
        action = null
        a()
    }
}

@Composable
fun TopToastHost(palette: ThemePalette) {
    val serial = AppToast.serial
    val msg = AppToast.message
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf("") }
    var shownAction by remember { mutableStateOf<String?>(null) }
    // 子頁面打開時才建立的提示氣泡，不要把打開前的舊訊息再顯示一次
    val startSerial = remember { serial }
    LaunchedEffect(serial) {
        if (serial == 0 || serial == startSerial || msg == null) return@LaunchedEffect
        shown = msg
        shownAction = AppToast.actionLabel
        visible = true
        delay(if (shownAction != null) 6000 else 2200) // 有「復原」按鈕時多留一點時間
        visible = false
    }
    MotionHost(visible) {
        val progress by overlayProgress(enterMs = 320, exitMs = 240)
        val density = LocalDensity.current
        Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                    .graphicsLayer {
                        alpha = progress
                        translationY = (1f - progress) * with(density) { -64.dp.toPx() }
                    }
                    .shadow(10.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.bgElevated)
                    .border(1.dp, palette.borderColor, RoundedCornerShape(18.dp))
                    .clickable(interactionSource = noRipple, indication = null) { visible = false }
                    .padding(horizontal = 16.dp, vertical = 11.dp)
            ) {
                val label = parseIconLabel(shown)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconText(
                        shown, color = palette.textMain, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        iconTint = leadMarkTint(label.leadMark, palette.accentText).let { c ->
                            if (label.leadMark == null) palette.textMain else c.readableOn(palette.bgElevated, 3.0)
                        },
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    shownAction?.let { act ->
                        Spacer(Modifier.width(12.dp))
                        Text(
                            act, color = palette.accentText, fontSize = 13.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    AppToast.runAction()
                                    visible = false
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
