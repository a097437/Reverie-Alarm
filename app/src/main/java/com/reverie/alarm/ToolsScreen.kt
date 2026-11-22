package com.reverie.alarm

import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// 底部「工具」分頁：天氣、碼錶、計時、桌面小工具，上方用膠囊切換
//   - 切換時：選取的底色膠囊滑過去，內容依方向從側邊滑進來（往右的分頁從右邊進、往左從左邊進）
//   - 動畫快慢跟著設定的「介面動態效果」（AppMotionDurationScale）
//   - 記住上次看的工具；從碼錶／計時的通知點進來會直接打開那一個
// ============================================================================
enum class ToolSection(val label: String) {
    WEATHER("天氣"),
    STOPWATCH("碼錶"),
    TIMER("計時"),
    WIDGETS("小工具")
}

/** 從通知點進來時要打開哪個工具（MainActivity 讀到後清掉）；也負責記住上次看的工具 */
object ToolNav {
    const val EXTRA = "open_tool"
    private const val PREFS = "reverie_tools"
    var pending by mutableStateOf<ToolSection?>(null)

    fun fromIntent(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA) ?: return
        runCatching { ToolSection.valueOf(name) }.getOrNull()?.let { pending = it }
    }

    fun last(context: Context): ToolSection =
        runCatching { ToolSection.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("section", null)!!) }
            .getOrDefault(ToolSection.WEATHER)

    fun save(context: Context, s: ToolSection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("section", s.name).apply()
    }
}

@Composable
fun ToolsScreen(
    section: ToolSection,
    theme: ThemePalette,
    onSectionChange: (ToolSection) -> Unit,
    weather: @Composable () -> Unit,
    stopwatch: @Composable () -> Unit,
    timer: @Composable () -> Unit,
    widgets: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ToolSwitcher(section, theme, onSectionChange)
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                // 依方向滑入：往右邊的工具 → 新內容從右邊進來、舊內容往左邊退
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(320, easing = Motion.EnterEasing)) { w -> dir * w / 4 } +
                    fadeIn(tween(240, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(tween(240, easing = Motion.ExitEasing)) { w -> -dir * w / 5 } +
                        fadeOut(tween(160)))
            },
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "toolSection"
        ) { s ->
            Box(Modifier.fillMaxSize()) {
                when (s) {
                    ToolSection.WEATHER -> weather()
                    ToolSection.STOPWATCH -> stopwatch()
                    ToolSection.TIMER -> timer()
                    ToolSection.WIDGETS -> widgets()
                }
            }
        }
    }
}

/** 上方切換列：選取的底色膠囊會跟著滑過去（有一點彈性），文字與圖示顏色漸變 */
@Composable
private fun ToolSwitcher(section: ToolSection, theme: ThemePalette, onSectionChange: (ToolSection) -> Unit) {
    val items = ToolSection.entries
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.bgElevated)
            .border(1.dp, theme.borderColor, RoundedCornerShape(16.dp))
            .padding(4.dp)
    ) {
        val segW = maxWidth / items.size
        val x by animateDpAsState(
            targetValue = segW * section.ordinal,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
            label = "toolPill"
        )
        Box(
            Modifier.offset(x = x).width(segW).height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.accent.darkenForWhiteText())
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEach { s ->
                val active = s == section
                val fg by animateColorAsState(if (active) Color.White else theme.textMuted, tween(220), label = "toolFg")
                val icon: ImageVector = when (s) {
                    ToolSection.WEATHER -> Tabler.TemperatureSun
                    ToolSection.STOPWATCH -> Tabler.Stopwatch
                    ToolSection.TIMER -> Tabler.Hourglass
                    ToolSection.WIDGETS -> Tabler.DeviceMobile
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .width(segW).height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { if (!active) onSectionChange(s) }
                ) {
                    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr(s.label), fontSize = 12.sp, fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold, color = fg, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 碼錶與計時共用的圓形按鈕：按下去會縮一點、放開彈回來，並有輕微震動回饋；
 * 顏色改變（開始 ↔ 暫停）時漸變。
 */
@Composable
fun ToolRoundButton(label: String, enabled: Boolean, bg: Color, fg: Color, border: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "btnScale")
    val bgC by animateColorAsState(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.3f), tween(220), label = "btnBg")
    val fgC by animateColorAsState(if (enabled) fg else fg.copy(alpha = 0.4f), tween(220), label = "btnFg")
    val bdC by animateColorAsState(if (enabled) border else border.copy(alpha = 0.3f), tween(220), label = "btnBd")
    val view = LocalView.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(88.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bgC)
            .border(1.5.dp, bdC, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = fgC)
    }
}
