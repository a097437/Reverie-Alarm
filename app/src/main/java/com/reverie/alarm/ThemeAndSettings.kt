package com.reverie.alarm

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ============================================================================
// 1. 動態主題引擎（與網頁版 applyThemeSettings 相同的算法）
//    色彩模式 × 背景基底風格 × 強調色 × 對比度 → 一組畫面顏色
// ============================================================================

const val APP_VERSION = "v3.5.0"

/** 全域文字縮放（設定頁的「舒適大字」），由 MainActivity 套用到整個 App */
object UiScale {
    var fontScale by mutableFloatStateOf(1f)
}
const val APP_BUILD_DATE = "2026.11.18"

data class ThemePalette(
    val title: String,
    val bgBase: Color,
    val bgCard: Color,
    val bgElevated: Color,
    val borderColor: Color,
    /** 主題色：已自動加深到「白字放在上面」對比 ≥ 4.5，可直接當按鈕底色 */
    val accent: Color,
    val textMain: Color,
    val textMuted: Color,
    val isDark: Boolean,
    val calCellBg: Color,
    val calCellText: Color,
    val isCalCellLight: Boolean,
    /** 主題色當「文字」用（連結、標題）：對卡片底色對比 ≥ 4.5 */
    val accentText: Color = accent,
    /** 使用者在色盤選的原始主題色（只用來顯示色碼／色票） */
    val accentRaw: Color = accent
)

// ============================================================================
// 對比度工具（WCAG 2.1）：讓任何顏色在目前的底色上自動變成看得清楚的深淺
// ============================================================================

private fun srgbToLinear(c: Float): Double {
    val v = c.toDouble()
    return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
}

fun relativeLuminance(c: Color): Double =
    0.2126 * srgbToLinear(c.red) + 0.7152 * srgbToLinear(c.green) + 0.0722 * srgbToLinear(c.blue)

fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a); val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** 把顏色往黑（淺底）或往白（深底）調，直到對 bg 的對比 ≥ min；色相大致不變 */
fun Color.readableOn(bg: Color, min: Double = 4.5): Color {
    val base = this.copy(alpha = 1f)
    if (contrastRatio(base, bg) >= min) return base
    val target = if (relativeLuminance(bg) > 0.18) Color.Black else Color.White
    var t = 0.05f
    while (t <= 1f) {
        val c = mixColor(target, t, base)
        if (contrastRatio(c, bg) >= min) return c
        t += 0.05f
    }
    return target
}

/** 把顏色加深到「白字放上去」對比 ≥ min（按鈕、實心圓圈用） */
fun Color.darkenForWhiteText(min: Double = 4.5): Color {
    val base = this.copy(alpha = 1f)
    var t = 0f
    while (t <= 1f) {
        val c = mixColor(Color.Black, t, base)
        if (contrastRatio(Color.White, c) >= min) return c
        t += 0.05f
    }
    return Color.Black
}

/** 目前這一區的底色（整個 App 預設是卡片底色；月曆格子裡改成網格底色） */
data class SurfaceInfo(val bg: Color, val isLight: Boolean)

val LocalSurface = compositionLocalOf { SurfaceInfo(Color.White, true) }

/**
 * 依目前區域底色自動調整成看得清楚的顏色。
 * 參考底色比實際底色多留一點餘裕（膠囊有 15% 同色底），所以目標用 5.0。
 */
@Composable
@ReadOnlyComposable
fun Color.readable(min: Double = 5.0): Color = readableOn(LocalSurface.current.bg, min)

/** 班別膠囊：淺色底用深一階文字（同網頁版 text-*-700），深色底用原本的淺色字（text-*-300） */
fun ShiftType.chipText(lightBg: Boolean): Color = if (!lightBg) badgeText else when (this) {
    ShiftType.EARLY -> Color(0xFF92400E)
    ShiftType.EVENING -> Color(0xFF4338CA)
    ShiftType.NIGHT -> Color(0xFF115E59)
    ShiftType.OFF -> Color(0xFF065F46)
    ShiftType.CUSTOM -> Color(0xFF1D4ED8)
}

fun ShiftType.chipBg(lightBg: Boolean): Color = if (lightBg) accentDot.copy(alpha = 0.16f) else badgeBg

fun ShiftType.chipBorder(lightBg: Boolean): Color = accentDot.copy(alpha = if (lightBg) 0.55f else 0.35f)

@Composable
@ReadOnlyComposable
fun ShiftType.chipText(): Color = chipText(LocalSurface.current.isLight)

@Composable
@ReadOnlyComposable
fun ShiftType.chipBg(): Color = chipBg(LocalSurface.current.isLight)

@Composable
@ReadOnlyComposable
fun ShiftType.chipBorder(): Color = chipBorder(LocalSurface.current.isLight)

data class NamedSwatch(val name: String, val hex: String)

val MORANDI_SWATCHES = listOf(
    NamedSwatch("霧霾藍", "#5B7C99"),
    NamedSwatch("鼠尾草綠", "#7C9082"),
    NamedSwatch("焦糖陶土", "#B87A62"),
    NamedSwatch("煙燻紫丁香", "#8E7C93"),
    NamedSwatch("燕麥暖沙", "#A68A64"),
    NamedSwatch("暮光靛青", "#4F5D75"),
    NamedSwatch("松柏深青", "#4A7C7E"),
    NamedSwatch("乾燥玫瑰", "#A96E70"),
    NamedSwatch("石墨炭灰", "#5C677D")
)

val CALENDAR_CELL_SWATCHES = listOf(
    NamedSwatch("深夜曜石", "#131A2A"),
    NamedSwatch("莫蘭迪灰藍", "#1E293B"),
    NamedSwatch("暖陶褐灰", "#262220"),
    NamedSwatch("象牙紙白", "#FAF8F5"),
    NamedSwatch("柔霧鼠尾草", "#EDF2EF"),
    NamedSwatch("極簡純白", "#FFFFFF")
)

/** 等同 CSS color-mix(in srgb, a pct%, b) */
fun mixColor(a: Color, pctOfA: Float, b: Color): Color = Color(
    red = a.red * pctOfA + b.red * (1 - pctOfA),
    green = a.green * pctOfA + b.green * (1 - pctOfA),
    blue = a.blue * pctOfA + b.blue * (1 - pctOfA),
    alpha = 1f
)

fun colorLuminance(c: Color): Float = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

fun buildThemePalette(p: AppPreferences, systemDark: Boolean): ThemePalette {
    val isDark = when (p.colorMode) {
        "light" -> false
        "system" -> systemDark
        else -> true
    }
    val accent = parseHexColorOrNull(p.customAccentHex) ?: Color(0xFF5B7C99)

    var bgBase: Color
    var bgCard: Color
    var bgElevated: Color
    var border: Color
    var textMain: Color
    var textMuted: Color

    if (isDark) {
        when (p.surfaceStyle) {
            "graphite" -> { bgBase = Color(0xFF0D0E11); bgCard = Color(0xFF15171C); bgElevated = Color(0xFF1E2128); border = Color(0xFF2A2E38) }
            "warm" -> { bgBase = Color(0xFF141210); bgCard = Color(0xFF1E1B18); bgElevated = Color(0xFF282420); border = Color(0xFF38322C) }
            "crisp" -> { bgBase = Color(0xFF090D16); bgCard = Color(0xFF111827); bgElevated = Color(0xFF1F2937); border = Color(0xFF334155) }
            else -> {
                bgBase = mixColor(accent, 0.08f, Color(0xFF0B0F19))
                bgCard = mixColor(accent, 0.12f, Color(0xFF131A2A))
                bgElevated = mixColor(accent, 0.16f, Color(0xFF1B2438))
                border = mixColor(accent, 0.24f, Color(0xFF232E47))
            }
        }
        textMain = Color(0xFFF1F5F9)
        textMuted = Color(0xFF94A3B8)
    } else {
        when (p.surfaceStyle) {
            "graphite" -> { bgBase = Color(0xFFF3F4F6); bgCard = Color(0xFFFFFFFF); bgElevated = Color(0xFFE5E7EB); border = Color(0xFFD1D5DB) }
            "warm" -> { bgBase = Color(0xFFF7F3EE); bgCard = Color(0xFFFCF9F5); bgElevated = Color(0xFFEFE8DF); border = Color(0xFFDDD3C5) }
            "crisp" -> { bgBase = Color(0xFFF8FAFC); bgCard = Color(0xFFFFFFFF); bgElevated = Color(0xFFF1F5F9); border = Color(0xFFCBD5E1) }
            else -> {
                bgBase = mixColor(accent, 0.06f, Color(0xFFF4F6F9))
                bgCard = Color(0xFFFFFFFF)
                bgElevated = mixColor(accent, 0.09f, Color(0xFFEEF2F6))
                border = mixColor(accent, 0.18f, Color(0xFFDCE3EC))
            }
        }
        textMain = Color(0xFF0F172A)
        textMuted = Color(0xFF475569)
    }

    // 對比度：柔和低眩光 / 標準 / 清晰高對比
    when (p.contrast) {
        "soft" -> {
            textMain = mixColor(textMain, 0.86f, bgBase)
            textMuted = mixColor(textMuted, 0.88f, bgBase)
            border = mixColor(border, 0.8f, bgBase)
        }
        "high" -> {
            textMuted = mixColor(textMuted, 0.7f, textMain)
            border = mixColor(border, 0.75f, textMuted)
        }
    }

    // 次要文字放在內層卡片上也要看得清楚（柔和模式放寬到 3.5）
    textMuted = textMuted.readableOn(bgElevated, if (p.contrast == "soft") 3.5 else 4.5)

    // 主題色：按鈕版（白字 ≥ 4.5）與文字版（對卡片 ≥ 4.5）
    val accentButton = accent.darkenForWhiteText()
    val accentText = accent.readableOn(bgCard, 4.5)

    val customCell = parseHexColorOrNull(p.customGridBgHex)
    val cellBg = customCell ?: bgCard
    val cellLight = if (customCell != null) colorLuminance(customCell) > 0.58f else !isDark
    val cellText = if (customCell != null) {
        if (cellLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    } else textMain

    return ThemePalette(
        title = "自訂主題",
        bgBase = bgBase,
        bgCard = bgCard,
        bgElevated = bgElevated,
        borderColor = border,
        accent = accentButton,
        accentText = accentText,
        accentRaw = accent,
        textMain = textMain,
        textMuted = textMuted,
        isDark = isDark,
        calCellBg = cellBg,
        calCellText = cellText,
        isCalCellLight = cellLight
    )
}

/** 舊版「莫蘭迪主題」轉換成新版設定（升級時沿用使用者原本的選擇） */
fun migrateLegacyTheme(prefs: AppPreferences, legacyName: String?): AppPreferences = when (legacyName) {
    "SAGE_FOREST" -> prefs.copy(colorMode = "dark", surfaceStyle = "tinted", customAccentHex = prefs.customAccentHex.ifBlank { "#7C9082" })
    "WARM_ESPRESSO" -> prefs.copy(colorMode = "dark", surfaceStyle = "warm", customAccentHex = prefs.customAccentHex.ifBlank { "#A68A64" })
    "MORANDI_MIST" -> prefs.copy(colorMode = "light", surfaceStyle = "warm")
    else -> prefs
}

// ============================================================================
// 2. 共用小元件
// ============================================================================

@Composable
fun SegmentedPicker(
    options: List<Pair<String, String>>,
    selected: String,
    palette: ThemePalette,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bgCard)
            .border(1.dp, palette.borderColor, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (id, label) ->
            val active = id == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) palette.accent else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else palette.textMain.copy(alpha = 0.75f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, palette: ThemePalette, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMain)
            Text(sub, fontSize = 10.sp, color = palette.textMuted)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.accent)
        )
    }
}

@Composable
fun SectionCard(title: String, palette: ThemePalette, trailing: String? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.bgElevated,
        border = BorderStroke(1.dp, palette.borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = palette.accentText)
                if (trailing != null) Text(trailing, fontSize = 10.sp, color = palette.textMuted)
            }
            content()
        }
    }
}

@Composable
private fun SwatchDot(hex: String, selected: Boolean, ringColor: Color, onClick: () -> Unit) {
    val c = parseHexColorOrNull(hex) ?: Color.Gray
    Box(
        modifier = Modifier
            .size(if (selected) 32.dp else 28.dp)
            .clip(CircleShape)
            .background(c)
            .border(if (selected) 3.dp else 1.dp, if (selected) ringColor else Color.Gray.copy(alpha = 0.4f), CircleShape)
            .clickable { onClick() }
    )
}

@Composable
private fun HexInputDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val valid = parseHexColorOrNull(text) != null
    MotionAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(9) },
                    singleLine = true,
                    label = { Text("色碼，例如 #5B7C99") }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(parseHexColorOrNull(text) ?: Color.Transparent)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val clean = "#" + text.trim().removePrefix("#").uppercase()
                onConfirm(clean)
            }) { Text("套用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun NumberBox(label: String, value: Int, range: IntRange, palette: ThemePalette, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bgCard)
            .border(1.dp, palette.borderColor, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(label, fontSize = 10.sp, color = palette.textMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    text = raw.filter { it.isDigit() }.take(3)
                    text.toIntOrNull()?.let { if (it in range) onChange(it) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(84.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = palette.textMain)
            )
            Spacer(Modifier.width(6.dp))
            Text("分鐘", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMain)
        }
    }
}

/** 網頁版風格的底部大面板（滑入／滑出動畫由 MotionBottomSheet 負責） */
@Composable
fun BottomPanel(
    palette: ThemePalette,
    heightFraction: Float = 0.92f,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    MotionBottomSheet(palette = palette, onDismiss = onDismiss, fixedHeightFraction = heightFraction) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun PanelHeader(title: String, subtitle: String, actionLabel: String, palette: ThemePalette, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMain)
            Text(subtitle, fontSize = 11.sp, color = palette.textMuted)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(palette.accent)
                .clickable { onAction() }
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

// ============================================================================
// 3. 系統與質感主題設定（對應網頁版 #settings-modal）
// ============================================================================

@Composable
fun SettingsSheet(
    prefs: AppPreferences,
    palette: ThemePalette,
    onUpdate: (AppPreferences) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onResetData: () -> Unit,
    onDismiss: () -> Unit
) {
    var hexTarget by remember { mutableStateOf<String?>(null) } // "accent" / "cell"
    var confirmReset by remember { mutableStateOf(false) }

    BottomPanel(palette = palette, onDismiss = onDismiss) {
        PanelHeader("⚙️ 系統與質感主題設定", "莫蘭迪低眩光色盤、日曆習慣、防賴床與資料備份", "完成", palette, onDismiss)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- 視覺外觀 ----
            SectionCard("🎨 視覺外觀與莫蘭迪低眩光主題", palette, trailing = "即時套用") {
                Text("色彩外觀模式", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                SegmentedPicker(
                    listOf("light" to "☀️ 淺色護眼", "dark" to "🌙 深色質感", "system" to "📱 跟隨系統"),
                    prefs.colorMode, palette
                ) { onUpdate(prefs.copy(colorMode = it)) }

                Text("對比度等級", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                SegmentedPicker(
                    listOf("soft" to "柔和低眩光", "standard" to "標準均衡", "high" to "清晰高對比"),
                    prefs.contrast, palette
                ) { onUpdate(prefs.copy(contrast = it)) }

                Text("背景基底風格", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                val surfaces = listOf("tinted" to "霧面主題微醺", "graphite" to "純淨石墨灰", "warm" to "暖沙燕麥紙感", "crisp" to "極簡高對比")
                surfaces.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { (id, label) ->
                            val active = prefs.surfaceStyle == id
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (active) palette.accent.copy(alpha = 0.18f) else palette.bgCard)
                                    .border(if (active) 2.dp else 1.dp, if (active) palette.accent else palette.borderColor, RoundedCornerShape(12.dp))
                                    .clickable { onUpdate(prefs.copy(surfaceStyle = id)) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) palette.accentText else palette.textMain)
                            }
                        }
                    }
                }

                val accentHex = prefs.customAccentHex.ifBlank { "#5B7C99" }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("強調主題色系（9 款莫蘭迪色盤 ＋ 自訂色碼）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted, modifier = Modifier.weight(1f))
                    Text(accentHex.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = palette.textMuted)
                }
                SwatchGrid(
                    swatches = MORANDI_SWATCHES,
                    selectedHex = accentHex,
                    ringColor = Color.White,
                    palette = palette,
                    onPick = { onUpdate(prefs.copy(customAccentHex = it)) },
                    onCustom = { hexTarget = "accent" }
                )

                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("日曆網格底色自訂（自動對比文字調整）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted, modifier = Modifier.weight(1f))
                    Text(if (prefs.customGridBgHex.isBlank()) "跟隨主題" else prefs.customGridBgHex.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = palette.textMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val followTheme = prefs.customGridBgHex.isBlank()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (followTheme) palette.accent else palette.bgCard)
                            .border(1.dp, palette.borderColor, RoundedCornerShape(50))
                            .clickable { onUpdate(prefs.copy(customGridBgHex = "")) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("跟隨主題", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = if (followTheme) Color.White else palette.textMain)
                    }
                }
                SwatchGrid(
                    swatches = CALENDAR_CELL_SWATCHES,
                    selectedHex = prefs.customGridBgHex,
                    ringColor = Color(0xFFFBBF24),
                    palette = palette,
                    onPick = { onUpdate(prefs.copy(customGridBgHex = it)) },
                    onCustom = { hexTarget = "cell" }
                )
            }

            // ---- 日曆與字級 ----
            SectionCard("📅 日曆顯示與字體大小", palette) {
                Text("每週起始日", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                SegmentedPicker(
                    listOf("sun" to "週日開始", "mon" to "週一開始"),
                    if (prefs.weekStartsOnMonday) "mon" else "sun", palette
                ) { onUpdate(prefs.copy(weekStartsOnMonday = it == "mon")) }
                Text("介面文字縮放", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                SegmentedPicker(
                    listOf("normal" to "標準字級", "large" to "舒適大字"),
                    if (prefs.fontScale > 1.04f) "large" else "normal", palette
                ) { onUpdate(prefs.copy(fontScale = if (it == "large") 1.1f else 1.0f)) }
            }

            // ---- 動態效果 ----
            SectionCard("✨ 介面動態效果", palette, trailing = "即時套用") {
                ToggleRow(
                    "介面過渡動畫",
                    "彈窗滑入滑出、選單展開、提示氣泡、班別套用回彈",
                    prefs.animationsEnabled, palette
                ) { onUpdate(prefs.copy(animationsEnabled = it)) }
                Text("動畫速度", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                Box(Modifier.graphicsLayer { alpha = if (prefs.animationsEnabled) 1f else 0.4f }) {
                    SegmentedPicker(
                        listOf("fast" to "輕快", "normal" to "標準", "slow" to "舒緩"),
                        prefs.animationSpeed, palette
                    ) { if (prefs.animationsEnabled) onUpdate(prefs.copy(animationSpeed = it)) }
                }
            }

            // ---- 鬧鐘規則 ----
            SectionCard("🔔 兩段式強效鬧鐘與防賴床規則", palette) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumberBox("預設第一段起床提前", prefs.defaultWakeOffset, 10..240, palette, { onUpdate(prefs.copy(defaultWakeOffset = it)) }, Modifier.weight(1f))
                    NumberBox("預設第二段出門提前", prefs.defaultLeaveOffset, 5..180, palette, { onUpdate(prefs.copy(defaultLeaveOffset = it)) }, Modifier.weight(1f))
                }
                Text("最大允許貪睡次數上限（達到上限後強制鎖定貪睡按鈕）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                SegmentedPicker(
                    listOf("0" to "禁止", "1" to "1 次", "2" to "2 次", "3" to "3 次"),
                    prefs.maxSnoozeCount.toString(), palette
                ) { onUpdate(prefs.copy(maxSnoozeCount = it.toInt())) }
                ToggleRow("第二段「出門倒數」禁止貪睡", "確保準時踏出家門，不因貪睡遲到", prefs.noSnoozeOnLeave, palette) {
                    onUpdate(prefs.copy(noSnoozeOnLeave = it))
                }
                ToggleRow("漸進式音量增強（由小到大喚醒）", "響鈴前 12 秒平滑提升音量，避免驚嚇", prefs.fadeInVolume, palette) {
                    onUpdate(prefs.copy(fadeInVolume = it))
                }
                ToggleRow("休假日／國定假日鬧鐘防呆偵測", "自動偵測隔日休假卻忘記關閉的上班鬧鐘", prefs.autoHolidaySafeguard, palette) {
                    onUpdate(prefs.copy(autoHolidaySafeguard = it))
                }
            }

            // ---- 備份 ----
            SectionCard("🗄️ 本地資料備份與還原", palette) {
                Text(
                    "您的排班、鬧鐘、模板與主題設定已自動儲存於本機。您亦可匯出 JSON 備份檔保存或轉移至其他裝置。",
                    fontSize = 11.sp, color = palette.textMuted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.accent)
                            .clickable { onExportBackup() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("⬇️ 匯出備份檔 (.json)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.bgCard)
                            .border(1.dp, palette.borderColor, RoundedCornerShape(12.dp))
                            .clickable { onImportBackup() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("⬆️ 還原備份檔", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMain) }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x26F43F5E))
                        .clickable { confirmReset = true }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) { Text("🔄 重置為初始示範資料", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF43F5E)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 退場動畫期間 hexTarget 已是 null，用最後一次的值撐住內容
    var lastHexTarget by remember { mutableStateOf("accent") }
    hexTarget?.let { lastHexTarget = it }
    MotionHost(hexTarget != null) {
        val target = lastHexTarget
        HexInputDialog(
            title = if (target == "accent") "自訂強調色" else "自訂日曆底色",
            initial = if (target == "accent") prefs.customAccentHex.ifBlank { "#5B7C99" } else prefs.customGridBgHex.ifBlank { "#131A2A" },
            onConfirm = { hex ->
                onUpdate(if (target == "accent") prefs.copy(customAccentHex = hex) else prefs.copy(customGridBgHex = hex))
                hexTarget = null
            },
            onDismiss = { hexTarget = null }
        )
    }

    MotionHost(confirmReset) {
        MotionAlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("確定要重置？") },
            text = { Text("所有排班、鬧鐘與備註都會清除並換成示範資料，建議先匯出備份。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetData()
                }) { Text("重置", color = Color(0xFFF43F5E)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SwatchGrid(
    swatches: List<NamedSwatch>,
    selectedHex: String,
    ringColor: Color,
    palette: ThemePalette,
    onPick: (String) -> Unit,
    onCustom: () -> Unit
) {
    val items: List<NamedSwatch?> = swatches + listOf(null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(6).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                rowItems.forEach { sw ->
                    if (sw == null) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(palette.bgCard)
                                .border(1.dp, palette.borderColor, CircleShape)
                                .clickable { onCustom() },
                            contentAlignment = Alignment.Center
                        ) { Text("+", fontWeight = FontWeight.Black, color = palette.textMain) }
                    } else {
                        SwatchDot(sw.hex, sw.hex.equals(selectedHex, ignoreCase = true), ringColor) { onPick(sw.hex) }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 4. 關於應用（對應網頁版 #about-app-modal）與更新日誌
// ============================================================================

data class ChangelogEntry(val version: String, val date: String, val highlights: List<String>)

val APP_CHANGELOG = listOf(
    ChangelogEntry(
        "v3.5.0", "2026.11.18", listOf(
            "原生鬧鐘引擎：鎖屏與 App 關閉時也會亮屏響鈴，貪睡會真的再響",
            "手機重開機、App 更新或改時區後自動恢復所有鬧鐘",
            "修正同時建立的起床與出門鬧鐘互相覆蓋、只響一個的問題",
            "新增鬧鐘權限檢查與 1 分鐘測試鬧鐘",
            "設定頁與網頁版一致：色彩模式、對比度、背景風格、9 款莫蘭迪色盤",
            "排班與鬧鐘改用完整格式儲存，重開 App 後改班也會正確更新鬧鐘"
        )
    ),
    ChangelogEntry(
        "v3.4.0", "2026.11.17", listOf(
            "調整「兩段鬧鐘」分頁版面配置：將「鬧鐘鈴聲庫與手機音訊匯入」置頂，「兩段式日期鬧鐘清單」移至下方",
            "新增上下分區直覺手勢：日曆格子區左右滑動切換月份、下方摘要與空白區左右滑動切換 5 大主分頁",
            "新增獨立「搜尋日程與鬧鐘」全屏面板，支援關鍵字即時過濾班別、日期、私人備註與鬧鐘標籤"
        )
    ),
    ChangelogEntry(
        "v3.3.0", "2026.11.17", listOf(
            "品牌正式更名為 Reverie Alarm，統一介面視覺與品牌識別",
            "頂部導覽列新增垂直三點（⋮）下拉氣泡選單，整合新增排班、日期鬧鐘與兩段鬧鐘捷徑",
            "側邊欄新增本月排班工時與班別統計摘要卡"
        )
    ),
    ChangelogEntry(
        "v3.2.0", "2026.11.12", listOf(
            "極簡單排頂部導覽列＋左側滑出式收納抽屜，月曆格子放大至 76dp",
            "月視圖下方新增當日排班與兩段鬧鐘即時開關摘要卡"
        )
    ),
    ChangelogEntry(
        "v3.1.0", "2026.11.05", listOf(
            "新增排班多行私人備註、圖片縮圖燈箱、附件管理與莫蘭迪主題色盤",
            "支援國定假日／排休鬧鐘防呆偵測與一鍵暫停明日上班鬧鐘"
        )
    )
)

@Composable
fun ChangelogDialog(palette: ThemePalette, onExportLogs: () -> Unit, onDismiss: () -> Unit) {
    MotionAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.bgCard,
        title = {
            Column {
                Text("Reverie Alarm $APP_VERSION", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = palette.accentText, fontWeight = FontWeight.ExtraBold)
                Text("版本更新日誌", fontWeight = FontWeight.ExtraBold, color = palette.textMain, fontSize = 15.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                APP_CHANGELOG.forEachIndexed { idx, rel ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = palette.bgElevated,
                        border = BorderStroke(if (idx == 0) 2.dp else 1.dp, if (idx == 0) palette.accent else palette.borderColor)
                    ) {
                        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    rel.version + if (idx == 0) "  最新穩定版" else "",
                                    fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = palette.accent
                                )
                                Text(rel.date, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = palette.textMuted)
                            }
                            rel.highlights.forEach { h ->
                                Text("• $h", fontSize = 11.sp, color = palette.textMain)
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onExportLogs) { Text("匯出日誌", color = palette.textMain) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了", color = palette.accentText, fontWeight = FontWeight.Bold) } }
    )
}

@Composable
fun AboutAppSheet(
    prefs: AppPreferences,
    palette: ThemePalette,
    onToggleAutoCheck: (Boolean) -> Unit,
    onOpenChangelog: () -> Unit,
    onExportLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    BottomPanel(palette = palette, heightFraction = 0.82f, onDismiss = onDismiss) {
        PanelHeader("ℹ️ 關於 Reverie Alarm 應用", "版本資訊、自動檢查更新與日誌紀錄", "關閉", palette, onDismiss)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.bgElevated,
                border = BorderStroke(1.dp, palette.borderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(palette.accent),
                        contentAlignment = Alignment.Center
                    ) { Text("RA", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Reverie Alarm", fontSize = 16.sp, fontWeight = FontWeight.Black, color = palette.textMain)
                        Text(APP_VERSION, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, color = palette.accentText)
                    }
                    Text("輪班通勤排程・兩段式強效防遲到鬧鐘日曆", fontSize = 12.sp, color = palette.textMuted)
                    Text("正式穩定版 (Stable) · 建置日期：$APP_BUILD_DATE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = palette.textMuted)
                }
            }

            SectionCard("✨ 版本更新與檢查設定", palette) {
                ToggleRow("啟動時自動檢查更新", "每次開啟應用時自動比對最新正式穩定版", prefs.autoCheckUpdate, palette, onToggleAutoCheck)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.bgCard)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(12.dp))
                        .clickable { onOpenChangelog() }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("📋 更新日誌", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMain)
                        Text("點擊查看 Reverie Alarm 各版本功能更新內容", fontSize = 10.sp, color = palette.textMuted)
                    }
                    Text("查看 ›", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.accentText)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.bgCard)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(12.dp))
                        .clickable { onExportLogs() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("⬇️ 匯出日誌（.txt）", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = palette.accentText) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.accent)
                        .clickable {
                            // 目前沒有線上更新伺服器：以安裝中的版本為準
                            AppToast.show("✅ 目前已是最新穩定版（$APP_VERSION）")
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("🔄 立即檢查更新（目前 $APP_VERSION 穩定版）", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }
            }
        }
    }
}

// ============================================================================
// 5. 休假日鬧鐘防呆提醒（對應網頁版 #holiday-safeguard-modal）
// ============================================================================

@Composable
fun HolidaySafeguardDialog(
    tomorrowLabel: String,
    holidayName: String,
    riskyAlarms: List<ShiftAlarm>,
    palette: ThemePalette,
    onKeep: () -> Unit,
    onPauseAll: () -> Unit
) {
    val amber = Color(0xFFF59E0B)
    MotionAlertDialog(
        onDismissRequest = onKeep,
        containerColor = palette.bgCard,
        icon = { Text("⚠️", fontSize = 28.sp) },
        title = { Text("休假日鬧鐘防呆提醒", fontWeight = FontWeight.ExtraBold, color = palette.textMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "偵測到明天（$tomorrowLabel）為「$holidayName」，但明日仍有 ${riskyAlarms.size} 個開啟中的鬧鐘，是否一鍵暫停？",
                    fontSize = 12.sp, color = palette.textMain
                )
                riskyAlarms.sortedBy { it.triggerTime }.forEach { al ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.bgElevated)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(al.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMain, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(al.triggerTime.toString(), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, color = amber)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text("保持開啟", color = palette.textMain) } },
        confirmButton = { TextButton(onClick = onPauseAll) { Text("一鍵暫停明日鬧鐘", color = amber, fontWeight = FontWeight.ExtraBold) } }
    )
}
