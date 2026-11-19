# 下載 Tabler Icons（MIT）outline SVG，轉成 Compose ImageVector 原始碼
# 用法：python tools/icons/gen_tabler_icons.py（要新增圖示就把名稱加進 NAMES）
import os, re, sys, urllib.request, xml.etree.ElementTree as ET

VER = '3.31.0'
HERE = os.path.dirname(os.path.abspath(__file__))
DL = os.path.join(HERE, '.cache')
OUT = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'java', 'com', 'reverie', 'alarm', 'TablerIcons.kt')

NAMES = """alarm alert-triangle arrow-right bell bell-ringing bolt bulb calendar calendar-month calendar-week
chart-bar check chevron-down chevron-left chevron-right chevron-up circle-check circle-x clock cloud copy
database device-mobile door-exit dots-vertical download eraser eye file-text flag folder-open heart
heart-handshake hearts hourglass info-circle layout-grid leaf link lock menu-2 moon music notes palette
paperclip pencil photo pin player-play player-stop plus refresh search settings shield-check sparkles sun
sunset-2 trash upload users volume x zzz
arrow-left cloud-rain cloud-storm cloud-fog cloud-snow umbrella temperature temperature-sun wind droplet map-pin sunrise sun-high
minus microphone wand camera send key""".split()

os.makedirs(DL, exist_ok=True)
NS = '{http://www.w3.org/2000/svg}'


def camel(n):
    return ''.join(p[:1].upper() + p[1:] for p in n.split('-'))


out = []
for n in NAMES:
    f = os.path.join(DL, n + '.svg')
    if not os.path.exists(f):
        url = f'https://unpkg.com/@tabler/icons@{VER}/icons/outline/{n}.svg'
        with urllib.request.urlopen(url, timeout=30) as r:
            data = r.read()
        open(f, 'wb').write(data)
    root = ET.parse(f).getroot()
    paths = []
    for el in root.iter():
        tag = el.tag.replace(NS, '')
        if tag == 'svg':
            continue
        if tag != 'path':
            sys.exit(f'{n}: unsupported element {tag}')
        if el.get('stroke') == 'none':
            continue  # Tabler 的 24x24 透明底框
        d = el.get('d')
        if not re.fullmatch(r'[MmLlHhVvCcSsQqTtAaZz0-9.,\-\s]+', d):
            sys.exit(f'{n}: unexpected path data')
        filled = el.get('fill') == 'currentColor'
        paths.append((d, filled))
    args = ', '.join(('F(' if fl else '') + '"' + d + '"' + (')' if fl else '') for d, fl in paths)
    out.append(f'    val {camel(n)}: ImageVector by lazy {{ icon("{n}", {args}) }}')

kt = '''package com.reverie.alarm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// ============================================================================
// Tabler Icons（https://tabler.io/icons ，MIT License，© Paweł Kuna）
// 由 tools/icons/gen_tabler_icons.py 從官方 outline SVG（v''' + VER + '''）產生，請勿手動修改路徑。
// 直接內嵌向量路徑：不需要網路或字型檔，離線也能顯示。
// 統一 24×24、線寬 2、圓角端點；Icon() 的 tint 會整個換色，深淺色模式都跟著文字顏色走。
// ============================================================================

/** 實心路徑（少數圖示的小圓點） */
private class F(val d: String)

private const val STROKE = 2f

private fun icon(name: String, vararg parts: Any): ImageVector {
    val b = ImageVector.Builder(
        name = "tabler-$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    )
    for (p in parts) {
        if (p is F) {
            b.addPath(addPathNodes(p.d), fill = SolidColor(Color.Black))
        } else {
            b.addPath(
                addPathNodes(p as String),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }
    return b.build()
}

object Tabler {
''' + '\n'.join(out) + '\n}\n'
open(OUT, 'w', encoding='utf-8').write(kt)
print(len(out), 'icons ->', OUT)
