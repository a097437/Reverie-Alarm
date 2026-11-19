package com.reverie.alarm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// ============================================================================
// Tabler Icons（https://tabler.io/icons ，MIT License，© Paweł Kuna）
// 由 tools/icons/gen_tabler_icons.py 從官方 outline SVG（v3.31.0）產生，請勿手動修改路徑。
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
    val Alarm: ImageVector by lazy { icon("alarm", "M12 13m-7 0a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M12 10l0 3l2 0", "M7 4l-2.75 2", "M17 4l2.75 2") }
    val AlertTriangle: ImageVector by lazy { icon("alert-triangle", "M12 9v4", "M10.363 3.591l-8.106 13.534a1.914 1.914 0 0 0 1.636 2.871h16.214a1.914 1.914 0 0 0 1.636 -2.87l-8.106 -13.536a1.914 1.914 0 0 0 -3.274 0z", "M12 16h.01") }
    val ArrowRight: ImageVector by lazy { icon("arrow-right", "M5 12l14 0", "M13 18l6 -6", "M13 6l6 6") }
    val Bell: ImageVector by lazy { icon("bell", "M10 5a2 2 0 1 1 4 0a7 7 0 0 1 4 6v3a4 4 0 0 0 2 3h-16a4 4 0 0 0 2 -3v-3a7 7 0 0 1 4 -6", "M9 17v1a3 3 0 0 0 6 0v-1") }
    val BellRinging: ImageVector by lazy { icon("bell-ringing", "M10 5a2 2 0 0 1 4 0a7 7 0 0 1 4 6v3a4 4 0 0 0 2 3h-16a4 4 0 0 0 2 -3v-3a7 7 0 0 1 4 -6", "M9 17v1a3 3 0 0 0 6 0v-1", "M21 6.727a11.05 11.05 0 0 0 -2.794 -3.727", "M3 6.727a11.05 11.05 0 0 1 2.792 -3.727") }
    val Bolt: ImageVector by lazy { icon("bolt", "M13 3l0 7l6 0l-8 11l0 -7l-6 0l8 -11") }
    val Bulb: ImageVector by lazy { icon("bulb", "M3 12h1m8 -9v1m8 8h1m-15.4 -6.4l.7 .7m12.1 -.7l-.7 .7", "M9 16a5 5 0 1 1 6 0a3.5 3.5 0 0 0 -1 3a2 2 0 0 1 -4 0a3.5 3.5 0 0 0 -1 -3", "M9.7 17l4.6 0") }
    val Calendar: ImageVector by lazy { icon("calendar", "M4 7a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12z", "M16 3v4", "M8 3v4", "M4 11h16", "M11 15h1", "M12 15v3") }
    val CalendarMonth: ImageVector by lazy { icon("calendar-month", "M4 7a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12z", "M16 3v4", "M8 3v4", "M4 11h16", "M8 14v4", "M12 14v4", "M16 14v4") }
    val CalendarWeek: ImageVector by lazy { icon("calendar-week", "M4 7a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12z", "M16 3v4", "M8 3v4", "M4 11h16", "M7 14h.013", "M10.01 14h.005", "M13.01 14h.005", "M16.015 14h.005", "M13.015 17h.005", "M7.01 17h.005", "M10.01 17h.005") }
    val ChartBar: ImageVector by lazy { icon("chart-bar", "M3 13a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v6a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M15 9a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M9 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v14a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M4 20h14") }
    val Check: ImageVector by lazy { icon("check", "M5 12l5 5l10 -10") }
    val ChevronDown: ImageVector by lazy { icon("chevron-down", "M6 9l6 6l6 -6") }
    val ChevronLeft: ImageVector by lazy { icon("chevron-left", "M15 6l-6 6l6 6") }
    val ChevronRight: ImageVector by lazy { icon("chevron-right", "M9 6l6 6l-6 6") }
    val ChevronUp: ImageVector by lazy { icon("chevron-up", "M6 15l6 -6l6 6") }
    val CircleCheck: ImageVector by lazy { icon("circle-check", "M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M9 12l2 2l4 -4") }
    val CircleX: ImageVector by lazy { icon("circle-x", "M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M10 10l4 4m0 -4l-4 4") }
    val Clock: ImageVector by lazy { icon("clock", "M3 12a9 9 0 1 0 18 0a9 9 0 0 0 -18 0", "M12 7v5l3 3") }
    val Cloud: ImageVector by lazy { icon("cloud", "M6.657 18c-2.572 0 -4.657 -2.007 -4.657 -4.483c0 -2.475 2.085 -4.482 4.657 -4.482c.393 -1.762 1.794 -3.2 3.675 -3.773c1.88 -.572 3.956 -.193 5.444 1c1.488 1.19 2.162 3.007 1.77 4.769h.99c1.913 0 3.464 1.56 3.464 3.486c0 1.927 -1.551 3.487 -3.465 3.487h-11.878") }
    val Copy: ImageVector by lazy { icon("copy", "M7 7m0 2.667a2.667 2.667 0 0 1 2.667 -2.667h8.666a2.667 2.667 0 0 1 2.667 2.667v8.666a2.667 2.667 0 0 1 -2.667 2.667h-8.666a2.667 2.667 0 0 1 -2.667 -2.667z", "M4.012 16.737a2.005 2.005 0 0 1 -1.012 -1.737v-10c0 -1.1 .9 -2 2 -2h10c.75 0 1.158 .385 1.5 1") }
    val Database: ImageVector by lazy { icon("database", "M12 6m-8 0a8 3 0 1 0 16 0a8 3 0 1 0 -16 0", "M4 6v6a8 3 0 0 0 16 0v-6", "M4 12v6a8 3 0 0 0 16 0v-6") }
    val DeviceMobile: ImageVector by lazy { icon("device-mobile", "M6 5a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-14z", "M11 4h2", "M12 17v.01") }
    val DoorExit: ImageVector by lazy { icon("door-exit", "M13 12v.01", "M3 21h18", "M5 21v-16a2 2 0 0 1 2 -2h7.5m2.5 10.5v7.5", "M14 7h7m-3 -3l3 3l-3 3") }
    val DotsVertical: ImageVector by lazy { icon("dots-vertical", "M12 12m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M12 19m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M12 5m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0") }
    val Download: ImageVector by lazy { icon("download", "M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2", "M7 11l5 5l5 -5", "M12 4l0 12") }
    val Eraser: ImageVector by lazy { icon("eraser", "M19 20h-10.5l-4.21 -4.3a1 1 0 0 1 0 -1.41l10 -10a1 1 0 0 1 1.41 0l5 5a1 1 0 0 1 0 1.41l-9.2 9.3", "M18 13.3l-6.3 -6.3") }
    val Eye: ImageVector by lazy { icon("eye", "M10 12a2 2 0 1 0 4 0a2 2 0 0 0 -4 0", "M21 12c-2.4 4 -5.4 6 -9 6c-3.6 0 -6.6 -2 -9 -6c2.4 -4 5.4 -6 9 -6c3.6 0 6.6 2 9 6") }
    val FileText: ImageVector by lazy { icon("file-text", "M14 3v4a1 1 0 0 0 1 1h4", "M17 21h-10a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2z", "M9 9l1 0", "M9 13l6 0", "M9 17l6 0") }
    val Flag: ImageVector by lazy { icon("flag", "M5 5a5 5 0 0 1 7 0a5 5 0 0 0 7 0v9a5 5 0 0 1 -7 0a5 5 0 0 0 -7 0v-9z", "M5 21v-7") }
    val FolderOpen: ImageVector by lazy { icon("folder-open", "M5 19l2.757 -7.351a1 1 0 0 1 .936 -.649h12.307a1 1 0 0 1 .986 1.164l-.996 5.211a2 2 0 0 1 -1.964 1.625h-14.026a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2h4l3 3h7a2 2 0 0 1 2 2v2") }
    val Heart: ImageVector by lazy { icon("heart", "M19.5 12.572l-7.5 7.428l-7.5 -7.428a5 5 0 1 1 7.5 -6.566a5 5 0 1 1 7.5 6.572") }
    val HeartHandshake: ImageVector by lazy { icon("heart-handshake", "M19.5 12.572l-7.5 7.428l-7.5 -7.428a5 5 0 1 1 7.5 -6.566a5 5 0 1 1 7.5 6.572", "M12 6l-3.293 3.293a1 1 0 0 0 0 1.414l.543 .543c.69 .69 1.81 .69 2.5 0l1 -1a3.182 3.182 0 0 1 4.5 0l2.25 2.25", "M12.5 15.5l2 2", "M15 13l2 2") }
    val Hearts: ImageVector by lazy { icon("hearts", "M14.017 18l-2.017 2l-7.5 -7.428a5 5 0 1 1 7.5 -6.566a5 5 0 0 1 8.153 5.784", "M15.99 20l4.197 -4.223a2.81 2.81 0 0 0 0 -3.948a2.747 2.747 0 0 0 -3.91 -.007l-.28 .282l-.279 -.283a2.747 2.747 0 0 0 -3.91 -.007a2.81 2.81 0 0 0 -.007 3.948l4.182 4.238z") }
    val Hourglass: ImageVector by lazy { icon("hourglass", "M6.5 7h11", "M6.5 17h11", "M6 20v-2a6 6 0 1 1 12 0v2a1 1 0 0 1 -1 1h-10a1 1 0 0 1 -1 -1z", "M6 4v2a6 6 0 1 0 12 0v-2a1 1 0 0 0 -1 -1h-10a1 1 0 0 0 -1 1z") }
    val InfoCircle: ImageVector by lazy { icon("info-circle", "M3 12a9 9 0 1 0 18 0a9 9 0 0 0 -18 0", "M12 9h.01", "M11 12h1v4h1") }
    val LayoutGrid: ImageVector by lazy { icon("layout-grid", "M4 4m0 1a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M14 4m0 1a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M4 14m0 1a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z", "M14 14m0 1a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1z") }
    val Leaf: ImageVector by lazy { icon("leaf", "M5 21c.5 -4.5 2.5 -8 7 -10", "M9 18c6.218 0 10.5 -3.288 11 -12v-2h-4.014c-9 0 -11.986 4 -12 9c0 1 0 3 2 5h3z") }
    val Link: ImageVector by lazy { icon("link", "M9 15l6 -6", "M11 6l.463 -.536a5 5 0 0 1 7.071 7.072l-.534 .464", "M13 18l-.397 .534a5.068 5.068 0 0 1 -7.127 0a4.972 4.972 0 0 1 0 -7.071l.524 -.463") }
    val Lock: ImageVector by lazy { icon("lock", "M5 13a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6z", "M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0", "M8 11v-4a4 4 0 1 1 8 0v4") }
    val Menu2: ImageVector by lazy { icon("menu-2", "M4 6l16 0", "M4 12l16 0", "M4 18l16 0") }
    val Moon: ImageVector by lazy { icon("moon", "M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454z") }
    val Music: ImageVector by lazy { icon("music", "M3 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0", "M13 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0", "M9 17v-13h10v13", "M9 8h10") }
    val Notes: ImageVector by lazy { icon("notes", "M5 3m0 2a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2z", "M9 7l6 0", "M9 11l6 0", "M9 15l4 0") }
    val Palette: ImageVector by lazy { icon("palette", "M12 21a9 9 0 0 1 0 -18c4.97 0 9 3.582 9 8c0 1.06 -.474 2.078 -1.318 2.828c-.844 .75 -1.989 1.172 -3.182 1.172h-2.5a2 2 0 0 0 -1 3.75a1.3 1.3 0 0 1 -1 2.25", "M8.5 10.5m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M12.5 7.5m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M16.5 10.5m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0") }
    val Paperclip: ImageVector by lazy { icon("paperclip", "M15 7l-6.5 6.5a1.5 1.5 0 0 0 3 3l6.5 -6.5a3 3 0 0 0 -6 -6l-6.5 6.5a4.5 4.5 0 0 0 9 9l6.5 -6.5") }
    val Pencil: ImageVector by lazy { icon("pencil", "M4 20h4l10.5 -10.5a2.828 2.828 0 1 0 -4 -4l-10.5 10.5v4", "M13.5 6.5l4 4") }
    val Photo: ImageVector by lazy { icon("photo", "M15 8h.01", "M3 6a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12z", "M3 16l5 -5c.928 -.893 2.072 -.893 3 0l5 5", "M14 14l1 -1c.928 -.893 2.072 -.893 3 0l3 3") }
    val Pin: ImageVector by lazy { icon("pin", "M15 4.5l-4 4l-4 1.5l-1.5 1.5l7 7l1.5 -1.5l1.5 -4l4 -4", "M9 15l-4.5 4.5", "M14.5 4l5.5 5.5") }
    val PlayerPlay: ImageVector by lazy { icon("player-play", "M7 4v16l13 -8z") }
    val PlayerStop: ImageVector by lazy { icon("player-stop", "M5 5m0 2a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2z") }
    val Plus: ImageVector by lazy { icon("plus", "M12 5l0 14", "M5 12l14 0") }
    val Refresh: ImageVector by lazy { icon("refresh", "M20 11a8.1 8.1 0 0 0 -15.5 -2m-.5 -4v4h4", "M4 13a8.1 8.1 0 0 0 15.5 2m.5 4v-4h-4") }
    val Search: ImageVector by lazy { icon("search", "M10 10m-7 0a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M21 21l-6 -6") }
    val Settings: ImageVector by lazy { icon("settings", "M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 2.296 .07 2.572 -1.065z", "M9 12a3 3 0 1 0 6 0a3 3 0 0 0 -6 0") }
    val ShieldCheck: ImageVector by lazy { icon("shield-check", "M11.46 20.846a12 12 0 0 1 -7.96 -14.846a12 12 0 0 0 8.5 -3a12 12 0 0 0 8.5 3a12 12 0 0 1 -.09 7.06", "M15 19l2 2l4 -4") }
    val Sparkles: ImageVector by lazy { icon("sparkles", "M16 18a2 2 0 0 1 2 2a2 2 0 0 1 2 -2a2 2 0 0 1 -2 -2a2 2 0 0 1 -2 2zm0 -12a2 2 0 0 1 2 2a2 2 0 0 1 2 -2a2 2 0 0 1 -2 -2a2 2 0 0 1 -2 2zm-7 12a6 6 0 0 1 6 -6a6 6 0 0 1 -6 -6a6 6 0 0 1 -6 6a6 6 0 0 1 6 6z") }
    val Sun: ImageVector by lazy { icon("sun", "M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M3 12h1m8 -9v1m8 8h1m-9 8v1m-6.4 -15.4l.7 .7m12.1 -.7l-.7 .7m0 11.4l.7 .7m-12.1 -.7l-.7 .7") }
    val Sunset2: ImageVector by lazy { icon("sunset-2", "M3 13h1", "M20 13h1", "M5.6 6.6l.7 .7", "M18.4 6.6l-.7 .7", "M8 13a4 4 0 1 1 8 0", "M3 17h18", "M7 20h5", "M16 20h1", "M12 5v-1") }
    val Trash: ImageVector by lazy { icon("trash", "M4 7l16 0", "M10 11l0 6", "M14 11l0 6", "M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12", "M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3") }
    val Upload: ImageVector by lazy { icon("upload", "M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2", "M7 9l5 -5l5 5", "M12 4l0 12") }
    val Users: ImageVector by lazy { icon("users", "M9 7m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M3 21v-2a4 4 0 0 1 4 -4h4a4 4 0 0 1 4 4v2", "M16 3.13a4 4 0 0 1 0 7.75", "M21 21v-2a4 4 0 0 0 -3 -3.85") }
    val Volume: ImageVector by lazy { icon("volume", "M15 8a5 5 0 0 1 0 8", "M17.7 5a9 9 0 0 1 0 14", "M6 15h-2a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1h2l3.5 -4.5a.8 .8 0 0 1 1.5 .5v14a.8 .8 0 0 1 -1.5 .5l-3.5 -4.5") }
    val X: ImageVector by lazy { icon("x", "M18 6l-12 12", "M6 6l12 12") }
    val Zzz: ImageVector by lazy { icon("zzz", "M4 12h6l-6 8h6", "M14 4h6l-6 8h6") }
    val ArrowLeft: ImageVector by lazy { icon("arrow-left", "M5 12l14 0", "M5 12l6 6", "M5 12l6 -6") }
    val CloudRain: ImageVector by lazy { icon("cloud-rain", "M7 18a4.6 4.4 0 0 1 0 -9a5 4.5 0 0 1 11 2h1a3.5 3.5 0 0 1 0 7", "M11 13v2m0 3v2m4 -5v2m0 3v2") }
    val CloudStorm: ImageVector by lazy { icon("cloud-storm", "M7 18a4.6 4.4 0 0 1 0 -9a5 4.5 0 0 1 11 2h1a3.5 3.5 0 0 1 0 7h-1", "M13 14l-2 4l3 0l-2 4") }
    val CloudFog: ImageVector by lazy { icon("cloud-fog", "M7 16a4.6 4.4 0 0 1 0 -9a5 4.5 0 0 1 11 2h1a3.5 3.5 0 0 1 0 7h-12", "M5 20l14 0") }
    val CloudSnow: ImageVector by lazy { icon("cloud-snow", "M7 18a4.6 4.4 0 0 1 0 -9a5 4.5 0 0 1 11 2h1a3.5 3.5 0 0 1 0 7", "M11 15v.01m0 3v.01m0 3v.01m4 -4v.01m0 3v.01") }
    val Umbrella: ImageVector by lazy { icon("umbrella", "M4 12a8 8 0 0 1 16 0z", "M12 12v6a2 2 0 0 0 4 0") }
    val Temperature: ImageVector by lazy { icon("temperature", "M10 13.5a4 4 0 1 0 4 0v-8.5a2 2 0 0 0 -4 0v8.5", "M10 9l4 0") }
    val TemperatureSun: ImageVector by lazy { icon("temperature-sun", "M4 13.5a4 4 0 1 0 4 0v-8.5a2 2 0 1 0 -4 0v8.5", "M4 9h4", "M13 16a4 4 0 1 0 0 -8a4.07 4.07 0 0 0 -1 .124", "M13 3v1", "M21 12h1", "M13 20v1", "M19.4 5.6l-.7 .7", "M18.7 17.7l.7 .7") }
    val Wind: ImageVector by lazy { icon("wind", "M5 8h8.5a2.5 2.5 0 1 0 -2.34 -3.24", "M3 12h15.5a2.5 2.5 0 1 1 -2.34 3.24", "M4 16h5.5a2.5 2.5 0 1 1 -2.34 3.24") }
    val Droplet: ImageVector by lazy { icon("droplet", "M7.502 19.423c2.602 2.105 6.395 2.105 8.996 0c2.602 -2.105 3.262 -5.708 1.566 -8.546l-4.89 -7.26c-.42 -.625 -1.287 -.803 -1.936 -.397a1.376 1.376 0 0 0 -.41 .397l-4.893 7.26c-1.695 2.838 -1.035 6.441 1.567 8.546z") }
    val MapPin: ImageVector by lazy { icon("map-pin", "M9 11a3 3 0 1 0 6 0a3 3 0 0 0 -6 0", "M17.657 16.657l-4.243 4.243a2 2 0 0 1 -2.827 0l-4.244 -4.243a8 8 0 1 1 11.314 0z") }
    val Sunrise: ImageVector by lazy { icon("sunrise", "M3 17h1m16 0h1m-15.4 -6.4l.7 .7m12.1 -.7l-.7 .7m-9.7 5.7a4 4 0 0 1 8 0", "M3 21l18 0", "M12 9v-6l3 3m-6 0l3 -3") }
    val SunHigh: ImageVector by lazy { icon("sun-high", "M14.828 14.828a4 4 0 1 0 -5.656 -5.656a4 4 0 0 0 5.656 5.656z", "M6.343 17.657l-1.414 1.414", "M6.343 6.343l-1.414 -1.414", "M17.657 6.343l1.414 -1.414", "M17.657 17.657l1.414 1.414", "M4 12h-2", "M12 4v-2", "M20 12h2", "M12 20v2") }
    val Minus: ImageVector by lazy { icon("minus", "M5 12l14 0") }
    val Microphone: ImageVector by lazy { icon("microphone", "M9 2m0 3a3 3 0 0 1 3 -3h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h0a3 3 0 0 1 -3 -3z", "M5 10a7 7 0 0 0 14 0", "M8 21l8 0", "M12 17l0 4") }
    val Wand: ImageVector by lazy { icon("wand", "M6 21l15 -15l-3 -3l-15 15l3 3", "M15 6l3 3", "M9 3a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2", "M19 13a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2") }
    val Camera: ImageVector by lazy { icon("camera", "M5 7h1a2 2 0 0 0 2 -2a1 1 0 0 1 1 -1h6a1 1 0 0 1 1 1a2 2 0 0 0 2 2h1a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2", "M9 13a3 3 0 1 0 6 0a3 3 0 0 0 -6 0") }
    val Send: ImageVector by lazy { icon("send", "M10 14l11 -11", "M21 3l-6.5 18a.55 .55 0 0 1 -1 0l-3.5 -7l-7 -3.5a.55 .55 0 0 1 0 -1l18 -6.5") }
    val Key: ImageVector by lazy { icon("key", "M16.555 3.843l3.602 3.602a2.877 2.877 0 0 1 0 4.069l-2.643 2.643a2.877 2.877 0 0 1 -4.069 0l-.301 -.301l-6.558 6.558a2 2 0 0 1 -1.239 .578l-.175 .008h-1.172a1 1 0 0 1 -.993 -.883l-.007 -.117v-1.172a2 2 0 0 1 .467 -1.284l.119 -.13l.414 -.414h2v-2h2v-2l2.144 -2.144l-.301 -.301a2.877 2.877 0 0 1 0 -4.069l2.643 -2.643a2.877 2.877 0 0 1 4.069 0z", "M15 9h.01") }
}
