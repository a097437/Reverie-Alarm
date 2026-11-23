package com.reverie.alarm

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// 文字前後的小圖示：統一用 Tabler 向量圖示，取代 Emoji
//
// 文字開頭寫「⏰ 」「✅ 」這類標記、結尾寫「 →」「 ▼」時，畫面上會換成對應的
// Tabler 圖示（跟著文字顏色、與中文垂直置中）。提示氣泡也用同一張對照表。
// ============================================================================

/** 開頭標記 → Tabler 圖示 */
private val LEAD_ICONS: List<Pair<String, () -> ImageVector>> = listOf(
    "🇹🇼" to { Tabler.Flag },
    "⏰" to { Tabler.Alarm },
    "✅" to { Tabler.CircleCheck },
    "❌" to { Tabler.CircleX },
    "🛡️" to { Tabler.ShieldCheck },
    "👀" to { Tabler.Eye },
    "☀️" to { Tabler.Sun },
    "🔄" to { Tabler.Refresh },
    "🔔" to { Tabler.BellRinging },
    "📅" to { Tabler.Calendar },
    "🌙" to { Tabler.Moon },
    "📌" to { Tabler.Pin },
    "📋" to { Tabler.Copy },
    "⚠️" to { Tabler.AlertTriangle },
    "🚪" to { Tabler.DoorExit },
    "🌆" to { Tabler.Sunset2 },
    "🌿" to { Tabler.Leaf },
    "📄" to { Tabler.FileText },
    "💚" to { Tabler.Heart },
    "🧹" to { Tabler.Eraser },
    "🎵" to { Tabler.Music },
    "⚡" to { Tabler.Bolt },
    "✨" to { Tabler.Sparkles },
    "⬇️" to { Tabler.Download },
    "⬆️" to { Tabler.Upload },
    "🖼️" to { Tabler.Photo },
    "🔊" to { Tabler.Volume },
    "🗑️" to { Tabler.Trash },
    "💞" to { Tabler.Hearts },
    "📲" to { Tabler.DeviceMobile },
    "📱" to { Tabler.DeviceMobile },
    "⚙️" to { Tabler.Settings },
    "🎨" to { Tabler.Palette },
    "🗄️" to { Tabler.Database },
    "⏳" to { Tabler.Hourglass },
    "📎" to { Tabler.Paperclip },
    "📝" to { Tabler.Notes },
    "☁️" to { Tabler.Cloud },
    "🔗" to { Tabler.Link },
    "🔒" to { Tabler.Lock },
    "😴" to { Tabler.Zzz },
    "ℹ️" to { Tabler.InfoCircle },
    "🔑" to { Tabler.Key },
    "📷" to { Tabler.Camera },
    "🎉" to { Tabler.Confetti },
    "⚑" to { Tabler.Flag },
    "✓" to { Tabler.Check },
    "+" to { Tabler.Plus }
)

/** 結尾標記 → Tabler 圖示 */
private val TRAIL_ICONS: List<Pair<String, () -> ImageVector>> = listOf(
    "→" to { Tabler.ArrowRight },
    "▼" to { Tabler.ChevronDown },
    "▲" to { Tabler.ChevronUp }
)

/** 拆成「開頭圖示、文字、結尾圖示」 */
data class IconLabel(val lead: ImageVector?, val text: String, val trail: ImageVector?, val leadMark: String?)

fun parseIconLabel(raw: String): IconLabel {
    var s = raw
    var lead: ImageVector? = null
    var mark: String? = null
    for ((m, ic) in LEAD_ICONS) {
        // 「+」只在後面有空白時才算圖示（避免「+886」之類）
        val prefix = if (m == "+") "+ " else m
        if (s.startsWith(prefix) || (m != "+" && s.startsWith(m.removeSuffix("️")))) {
            val cut = if (s.startsWith(prefix)) prefix.length else m.removeSuffix("️").length
            s = s.substring(cut).trimStart('️', ' ')
            lead = ic()
            mark = m
            break
        }
    }
    var trail: ImageVector? = null
    for ((m, ic) in TRAIL_ICONS) {
        if (s.endsWith(" $m")) {
            s = s.removeSuffix(" $m").trimEnd()
            trail = ic()
            break
        }
    }
    return IconLabel(lead, s, trail, mark)
}

/** 去掉開頭／結尾標記後的純文字（給沒辦法放圖示的地方用，例如系統通知） */
fun plainLabel(raw: String): String = parseIconLabel(raw).text

/** 提示氣泡等用的語意色：成功綠、失敗紅、警告琥珀，其餘跟文字同色 */
fun leadMarkTint(mark: String?, fallback: Color): Color = when (mark) {
    "✅", "✓", "💚" -> EMERALD
    "❌" -> ROSE
    "⚠️" -> AMBER
    else -> fallback
}

/**
 * 圖示＋文字：圖示大小約為字級的 1.15 倍，與中文垂直置中，
 * 文字太長時只縮文字、不把圖示擠掉。
 */
@Composable
fun IconText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight? = null,
    color: Color = LocalContentColor.current,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    alignTop: Boolean = false
) {
    val parsed = parseIconLabel(text)
    val lead = icon ?: parsed.lead
    val iconSize = (fontSize.value * 1.15f * UiScale.fontScale).coerceAtLeast(11f).dp
    val gap = (fontSize.value * 0.36f).coerceIn(3f, 7f).dp
    Row(
        modifier = modifier,
        verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically
    ) {
        if (lead != null) {
            Icon(
                lead, contentDescription = null, tint = iconTint ?: color,
                modifier = Modifier
                    .then(if (alignTop) Modifier.padding(top = ((fontSize.value * 0.18f)).dp) else Modifier)
                    .size(iconSize)
            )
            Spacer(Modifier.width(gap))
        }
        Text(
            parsed.text,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = fontSize, fontWeight = fontWeight, color = color,
            maxLines = maxLines, softWrap = softWrap, overflow = overflow,
            textAlign = textAlign, lineHeight = lineHeight
        )
        if (parsed.trail != null) {
            Spacer(Modifier.width(gap - 1.dp))
            Icon(parsed.trail, contentDescription = null, tint = iconTint ?: color, modifier = Modifier.size(iconSize * 0.95f))
        }
    }
}
