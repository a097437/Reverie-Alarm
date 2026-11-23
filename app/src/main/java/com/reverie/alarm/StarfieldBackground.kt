package com.reverie.alarm

import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ============================================================================
// 背景星星：在 App 背景隨機灑上小星星，畫面比較不單調
//   - 位置用「打散的格子」：每格隨機一點，分布均勻又不規則，不會擠成一團
//   - 大多是細小的星點，少數稍大的會有光暈，再少數是四角的閃光星
//   - 每顆星有自己的閃爍速度與節奏；關掉「介面過渡動畫」時星星不閃
//   - 深色主題用暖白／冷白／帶一點主題色的星光；淺色主題用淡淡的主題色，才看得到又不刺眼
//   - 省電、不拖慢滑動（120Hz 每一幀只有 8.3ms）：
//       * 一般星星分成幾組，每組畫在自己的圖層裡、只畫一次；閃爍時只改圖層的透明度，不用重錄繪圖指令
//       * 只有少數四角閃光星每次重畫（要跟著縮放），漸層與形狀都事先建好，不會每幀產生新物件
//       * 最後一次碰觸螢幕約 8 秒後就停住；停住時畫面完全不更新，系統可以降頻省電、手機不會一直發熱
//       * 省電模式不閃；App 在背景時自動暫停
// ============================================================================

private class Star(
    val x: Float,          // 0..1（佔畫面寬度的比例）
    val y: Float,          // 0..1
    val radiusDp: Float,
    val alpha: Float,
    val speed: Float,      // 閃爍速度（弧度／秒）
    val phase: Float,
    val kind: Int,         // 0 星點、1 有光暈、2 四角閃光星
    val tint: Int          // 0 白、1 暖、2 冷、3 主題色
)

/** 星星數量：依密度，以一般手機畫面（約 400×860dp）為準 */
fun starCountOf(density: String): Int = when (density) {
    "sparse" -> 42
    "dense" -> 150
    else -> 85
}

private fun makeStars(count: Int, seed: Int): List<Star> {
    val rnd = Random(seed)
    // 打散的格子：欄列數接近畫面比例（直式約 1:2.15）
    val cols = ceil(sqrt(count / 2.15)).toInt().coerceAtLeast(1)
    val rows = ceil(count.toFloat() / cols).toInt()
    val cells = (0 until cols * rows).shuffled(rnd).take(count)
    return cells.map { cell ->
        val cx = cell % cols
        val cy = cell / cols
        val roll = rnd.nextFloat()
        val kind = when {
            roll < 0.06f -> 2
            roll < 0.22f -> 1
            else -> 0
        }
        Star(
            x = (cx + rnd.nextFloat()) / cols,
            y = (cy + rnd.nextFloat()) / rows,
            radiusDp = when (kind) {
                2 -> 3.6f + rnd.nextFloat() * 2.4f
                1 -> 1.3f + rnd.nextFloat() * 0.8f
                else -> 0.55f + rnd.nextFloat() * 0.75f
            },
            alpha = when (kind) {
                2 -> 0.75f + rnd.nextFloat() * 0.25f
                1 -> 0.6f + rnd.nextFloat() * 0.3f
                else -> 0.35f + rnd.nextFloat() * 0.45f
            },
            speed = 0.6f + rnd.nextFloat() * 1.6f,
            phase = rnd.nextFloat() * (2f * PI.toFloat()),
            kind = kind,
            tint = rnd.nextInt(4)
        )
    }
}

/** 四角閃光星（✦）的形狀：中心亮、四個尖角 */
private fun sparklePath(center: Offset, r: Float): Path {
    val inner = r * 0.22f
    return Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + inner, center.y - inner)
        lineTo(center.x + r, center.y)
        lineTo(center.x + inner, center.y + inner)
        lineTo(center.x, center.y + r)
        lineTo(center.x - inner, center.y + inner)
        lineTo(center.x - r, center.y)
        lineTo(center.x - inner, center.y - inner)
        close()
    }
}

/** 一般星星分成幾組閃爍：每組一個圖層，閃爍時只改圖層透明度 */
private const val TWINKLE_GROUPS = 5
private val GROUP_SPEED = floatArrayOf(0.7f, 1.1f, 1.5f, 0.9f, 1.9f) // 弧度／秒
private val GROUP_PHASE = floatArrayOf(0f, 1.3f, 2.6f, 3.9f, 5.2f)
/** 最後一次碰觸螢幕之後，星星再閃多久就停住 */
private const val TWINKLE_AFTER_TOUCH_MS = 8_000L
/** 閃爍的更新間隔（約每秒 17 次；一次閃爍要好幾秒，這樣就夠順） */
private const val TWINKLE_TICK_MS = 50L
/** 關掉閃爍時固定的亮度 */
private const val STILL_TWINKLE = 0.75f

private fun twinkleOf(t: Float, speed: Float, phase: Float): Float = 0.5f + 0.5f * sin(t * speed + phase)

/** 最近一次碰觸螢幕的時間：MainActivity 在手指按下／放開、回到前景時更新 */
object UserActivity {
    private val last = MutableStateFlow(SystemClock.uptimeMillis())
    val lastTouch: StateFlow<Long> get() = last
    fun touched() {
        last.value = SystemClock.uptimeMillis()
    }
}

@Composable
fun StarfieldBackground(
    amount: String,
    seed: Int,
    accent: Color,
    isDark: Boolean,
    twinkle: Boolean,
    modifier: Modifier = Modifier
) {
    val stars = remember(amount, seed) { makeStars(starCountOf(amount), 20261124 + seed) }
    // 一般星星（星點、有光暈的）依序分到各組；四角閃光星另外畫，因為它們閃的時候也會縮放
    val groups = remember(stars) {
        List(TWINKLE_GROUPS) { g -> stars.filterIndexed { i, s -> s.kind != 2 && i % TWINKLE_GROUPS == g } }
    }
    val sparkles = remember(stars) { stars.filter { it.kind == 2 } }

    // 累積的閃爍時間（秒）：只在閃的時候往前走，停住後再開始不會突然跳一下
    var t by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    LaunchedEffect(twinkle) {
        if (!twinkle) return@LaunchedEffect
        val power = context.getSystemService(PowerManager::class.java)
        while (true) {
            val seen = UserActivity.lastTouch.value
            if (SystemClock.uptimeMillis() < seen + TWINKLE_AFTER_TOUCH_MS && power?.isPowerSaveMode != true) {
                var last = withFrameMillis { it }
                while (SystemClock.uptimeMillis() < UserActivity.lastTouch.value + TWINKLE_AFTER_TOUCH_MS) {
                    delay(TWINKLE_TICK_MS)
                    // App 不在畫面上時 withFrameMillis 會自動暫停
                    withFrameMillis { now ->
                        t += (now - last).coerceIn(0L, 200L) / 1000f
                        last = now
                    }
                }
            } else {
                // 閒置（或省電模式）：停住、完全不畫，等下一次碰觸
                UserActivity.lastTouch.first { it > seen }
            }
        }
    }

    // 星光顏色
    val palette = if (isDark) listOf(
        Color(0xFFFFFFFF),
        Color(0xFFFFF1D6),
        Color(0xFFDCE7FF),
        mixColor(accent, 0.45f, Color.White)
    ) else {
        val deep = accent.darkenForWhiteText(3.0)
        listOf(deep, mixColor(deep, 0.7f, Color(0xFFB8860B)), mixColor(deep, 0.7f, Color(0xFF475569)), deep)
    }
    val strength = if (isDark) 1f else 0.42f

    Box(modifier) {
        // ---- 一般星星：每組畫一次，閃爍只改這一層的透明度（圖層屬性，不重畫內容） ----
        for (g in 0 until TWINKLE_GROUPS) {
            val list = groups[g]
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val tw = if (twinkle) twinkleOf(t, GROUP_SPEED[g], GROUP_PHASE[g]) else STILL_TWINKLE
                        alpha = 0.35f + 0.65f * tw
                        // 把透明度直接乘到每個繪圖指令上，不另外開離屏緩衝（星星幾乎不重疊）
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        // 位置與光暈漸層只在畫面大小（或顏色設定）改變時算一次
                        val centers = list.map { Offset(it.x * w, it.y * h) }
                        val glows = list.mapIndexed { i, s ->
                            if (s.kind != 1) null else {
                                val a = (s.alpha * strength).coerceIn(0f, 1f)
                                val c = palette[s.tint]
                                Brush.radialGradient(
                                    listOf(c.copy(alpha = a * 0.5f), c.copy(alpha = 0f)),
                                    center = centers[i], radius = s.radiusDp * density * 3.2f
                                )
                            }
                        }
                        onDrawBehind {
                            for (i in list.indices) {
                                val s = list[i]
                                val a = (s.alpha * strength).coerceIn(0f, 1f)
                                val r = s.radiusDp * density
                                glows[i]?.let { drawCircle(it, radius = r * 3.2f, center = centers[i]) }
                                drawCircle(palette[s.tint].copy(alpha = a), radius = r, center = centers[i])
                            }
                        }
                    }
            )
        }
        // ---- 四角閃光星：數量少，每次重畫（跟著縮放）；漸層與形狀事先建好 ----
        if (sparkles.isNotEmpty()) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val centers = sparkles.map { Offset(it.x * w, it.y * h) }
                        val radii = sparkles.map { it.radiusDp * density }
                        val paths = sparkles.indices.map { sparklePath(centers[it], radii[it]) }
                        val glows = sparkles.indices.map { i ->
                            val c = palette[sparkles[i].tint]
                            Brush.radialGradient(
                                listOf(c.copy(alpha = 0.45f), c.copy(alpha = 0f)),
                                center = centers[i], radius = radii[i] * 2.4f
                            )
                        }
                        onDrawBehind {
                            for (i in sparkles.indices) {
                                val s = sparkles[i]
                                val tw = if (twinkle) twinkleOf(t, s.speed, s.phase) else STILL_TWINKLE
                                val a = (s.alpha * (0.35f + 0.65f * tw) * strength).coerceIn(0f, 1f)
                                val f = 0.85f + 0.3f * tw
                                val center = centers[i]
                                scale(f, f, pivot = center) {
                                    drawCircle(glows[i], radius = radii[i] * 2.4f, center = center, alpha = a)
                                    drawPath(paths[i], palette[s.tint], alpha = a)
                                    drawCircle(Color.White, radius = radii[i] * 0.18f, center = center, alpha = if (isDark) a else a * 0.6f)
                                }
                            }
                        }
                    }
            )
        }
    }
}
