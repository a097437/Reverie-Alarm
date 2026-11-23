package com.reverie.alarm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ============================================================================
// 色環調色盤（設定頁「自訂強調色」「自訂日曆底色」）
//   - 圓形色環：繞一圈選色相，越往外越鮮豔；下方滑桿調亮度
//   - 「同色系柔和建議」：依目前色相產生幾個莫蘭迪感的低彩度色，一點就套用
//   - 即時預覽：強調色看按鈕與文字的樣子，日曆底色看格子的樣子（文字自動換深淺）
//   - 也可以直接輸入色碼
// ============================================================================

/** 色相環一圈的顏色（從 3 點鐘方向順時針：紅 → 黃 → 綠 → 青 → 藍 → 洋紅 → 紅） */
private val HUE_RING = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) }

fun colorToHsv(c: Color): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), out)
    return out
}

fun colorToHex(c: Color): String = String.format("#%06X", c.toArgb() and 0xFFFFFF)

/** 日曆格子底色上的文字顏色（和 buildThemePalette 一樣的判斷） */
fun cellTextOn(bg: Color): Color = if (colorLuminance(bg) > 0.58f) Color(0xFF0F172A) else Color(0xFFF8FAFC)

/** 依色相產生柔和建議色：強調色是中低彩度的莫蘭迪色；日曆底色是 3 個深色＋3 個淺色 */
private fun softVariants(hue: Float, forCell: Boolean): List<Color> =
    if (forCell) listOf(
        0.30f to 0.13f, 0.22f to 0.18f, 0.14f to 0.24f,
        0.10f to 0.92f, 0.06f to 0.96f, 0.03f to 0.99f
    ).map { (s, v) -> Color.hsv(hue, s, v) }
    else listOf(
        0.22f to 0.78f, 0.30f to 0.70f, 0.38f to 0.64f,
        0.42f to 0.56f, 0.34f to 0.48f, 0.26f to 0.40f
    ).map { (s, v) -> Color.hsv(hue, s, v) }

@Composable
fun ColorWheelDialog(
    title: String,
    initialHex: String,
    forCalendarCell: Boolean,
    palette: ThemePalette,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val init = remember { colorToHsv(parseHexColorOrNull(initialHex) ?: Color(0xFF5B7C99)) }
    var hue by remember { mutableFloatStateOf(init[0]) }
    var sat by remember { mutableFloatStateOf(init[1]) }
    var value by remember { mutableFloatStateOf(init[2]) }
    var hexText by remember { mutableStateOf(colorToHex(Color.hsv(init[0], init[1], init[2]))) }
    val color = Color.hsv(hue, sat, value)

    fun pick(h: Float, s: Float, v: Float) {
        hue = h; sat = s; value = v
        hexText = colorToHex(Color.hsv(h, s, v))
    }

    MotionAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.bgCard,
        title = { Text(title, fontWeight = FontWeight.ExtraBold, color = palette.textMain) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HueSatWheel(hue, sat, value, Modifier.size(220.dp)) { h, s -> pick(h, s, value) }
                ValueSlider(hue, sat, value, palette) { v -> pick(hue, sat, v) }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("同色系柔和建議"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        softVariants(hue, forCalendarCell).forEach { c ->
                            val on = colorToHex(c) == colorToHex(color)
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(if (on) 3.dp else 1.dp, if (on) palette.accentText else palette.borderColor, CircleShape)
                                    .clickable { colorToHsv(c).let { pick(it[0], it[1], it[2]) } }
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ColorPreview(color, forCalendarCell, palette, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { raw ->
                            val t = raw.trim().take(7).let { if (it.startsWith("#")) it else "#$it" }.uppercase()
                            hexText = t
                            parseHexColorOrNull(t)?.takeIf { t.length == 7 }?.let { c ->
                                colorToHsv(c).let { hue = it[0]; sat = it[1]; value = it[2] }
                            }
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = palette.textMain),
                        label = { Text(tr("色碼"), fontSize = 11.sp) },
                        modifier = Modifier.width(128.dp)
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = palette.textMain) } },
        confirmButton = {
            TextButton(onClick = { onConfirm(colorToHex(color)) }) {
                Text(tr("套用"), color = palette.accentText, fontWeight = FontWeight.ExtraBold)
            }
        }
    )
}

/** 色相（角度）× 飽和度（半徑）的圓形色環 */
@Composable
private fun HueSatWheel(hue: Float, sat: Float, value: Float, modifier: Modifier, onChange: (Float, Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    fun handle(p: Offset, w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f
        val dx = p.x - cx
        val dy = p.y - cy
        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        latest(deg, (hypot(dx, dy) / r).coerceIn(0f, 1f))
    }
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { handle(it, size.width, size.height) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { handle(it, size.width, size.height) }) { change, _ ->
                    change.consume()
                    handle(change.position, size.width, size.height)
                }
            }
    ) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(Brush.sweepGradient(HUE_RING, center = c), radius = r, center = c)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center = c, radius = r), radius = r, center = c)
        // 色環一直保持明亮，才看得清楚色相；亮度只反映在選取點、滑桿與預覽上
        drawCircle(Color.Black.copy(alpha = 0.18f), radius = r, center = c, style = Stroke(1.dp.toPx()))
        // 選取點
        val rad = Math.toRadians(hue.toDouble())
        val pos = Offset(c.x + (cos(rad) * sat * r).toFloat(), c.y + (sin(rad) * sat * r).toFloat())
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 15.dp.toPx(), center = pos)
        drawCircle(Color.White, radius = 13.dp.toPx(), center = pos)
        drawCircle(Color.hsv(hue, sat, value), radius = 10.dp.toPx(), center = pos)
    }
}

/** 亮度滑桿：從黑到目前色相＋飽和度的最亮色 */
@Composable
private fun ValueSlider(hue: Float, sat: Float, value: Float, palette: ThemePalette, onChange: (Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    fun handle(x: Float, w: Int, thumb: Float) {
        latest(((x - thumb) / (w - thumb * 2)).coerceIn(0.04f, 1f))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("亮度"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
            Text("${(value * 100).toInt()}%", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = palette.textMuted)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(Unit) { detectTapGestures { handle(it.x, size.width, 15.dp.toPx()) } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { handle(it.x, size.width, 15.dp.toPx()) }) { change, _ ->
                        change.consume()
                        handle(change.position.x, size.width, 15.dp.toPx())
                    }
                }
        ) {
            val thumb = 15.dp.toPx()
            val trackH = 14.dp.toPx()
            val top = (size.height - trackH) / 2f
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hue, sat, 1f)), startX = thumb, endX = size.width - thumb),
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, trackH),
                cornerRadius = CornerRadius(trackH / 2f)
            )
            val x = thumb + value * (size.width - thumb * 2)
            val cy = size.height / 2f
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = 14.dp.toPx(), center = Offset(x, cy))
            drawCircle(Color.White, radius = 12.dp.toPx(), center = Offset(x, cy))
            drawCircle(Color.hsv(hue, sat, value), radius = 9.dp.toPx(), center = Offset(x, cy))
        }
    }
}

/** 套用後的樣子：強調色看按鈕與文字；日曆底色看格子 */
@Composable
private fun ColorPreview(color: Color, forCell: Boolean, palette: ThemePalette, modifier: Modifier) {
    if (forCell) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("23" to tr("早班"), "24" to tr("休假")).forEach { (day, label) ->
                Column(
                    Modifier
                        .size(width = 48.dp, height = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(color)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(12.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(day, fontSize = 17.sp, fontWeight = FontWeight.Black, color = cellTextOn(color))
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = cellTextOn(color).copy(alpha = 0.8f))
                }
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.darkenForWhiteText())
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(tr("按鈕預覽"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }
            Text(tr("文字預覽 Aa"), fontSize = 13.sp, fontWeight = FontWeight.Black, color = color.readableOn(palette.bgCard, 4.5))
        }
    }
}

/** 設定頁色盤裡的「色環」按鈕：一圈彩虹 */
@Composable
fun ColorWheelButton(size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Canvas(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        val r = this.size.minDimension / 2f
        drawCircle(Brush.sweepGradient(HUE_RING, center = center), radius = r)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center = center, radius = r * 0.9f), radius = r)
        drawCircle(Color.White, radius = r * 0.36f)
        // 中間的「＋」
        val l = r * 0.2f
        val sw = 2.dp.toPx()
        drawLine(Color(0xFF334155), Offset(center.x - l, center.y), Offset(center.x + l, center.y), strokeWidth = sw)
        drawLine(Color(0xFF334155), Offset(center.x, center.y - l), Offset(center.x, center.y + l), strokeWidth = sw)
    }
}
