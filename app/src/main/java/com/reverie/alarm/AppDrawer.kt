package com.reverie.alarm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth

// ============================================================================
// 左側滑出式收納側欄（對應網頁版 renderNavDrawerContent）
// ============================================================================

private data class DrawerViewItem(val mode: CalendarViewMode, val label: String, val sub: String, val icon: ImageVector)

/** 圓角小方框裡的圖示（網頁版各列左側的 icon 底框） */
@Composable
private fun IconTile(icon: ImageVector, tint: Color, bg: Color, size: Int = 36, iconSize: Int = 18, solid: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (solid) bg else bg.copy(alpha = 0.15f))
            .border(1.dp, if (solid) Color.Transparent else bg.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize.dp)) }
}

@Composable
fun ReverieDrawerContent(
    currentYearMonth: YearMonth,
    events: List<ShiftEvent>,
    viewMode: CalendarViewMode,
    isCalendarTab: Boolean,
    accountEmail: String?,
    accentHex: String,
    conflictCount: Int,
    autoCheckUpdate: Boolean,
    palette: ThemePalette,
    subscriptions: List<PartnerSubscription>,
    viewingCode: String?,
    holidayCompare: Boolean,
    onSelectSource: (String?) -> Unit,
    onToggleCompare: () -> Unit,
    onSelectViewMode: (CalendarViewMode) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccount: () -> Unit,
    onRunSafeguardCheck: () -> Unit,
    onImportHolidays: () -> Unit,
    onOpenAlarmCheck: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val monthEvents = events.filter { YearMonth.from(it.date) == currentYearMonth }
    val early = monthEvents.count { it.type == ShiftType.EARLY }
    val evening = monthEvents.count { it.type == ShiftType.EVENING }
    val night = monthEvents.count { it.type == ShiftType.NIGHT }
    val offDays = monthEvents.count { it.type == ShiftType.OFF }
    val totalHours = monthEvents.filter { it.type != ShiftType.OFF }.sumOf { ev ->
        val s = ev.startTime.toSecondOfDay() / 60
        val e = ev.endTime.toSecondOfDay() / 60
        val diff = if (e <= s) (24 * 60 - s + e) else (e - s)
        diff / 60.0
    }
    val hoursText = if (totalHours % 1.0 == 0.0) totalHours.toInt().toString() else String.format("%.1f", totalHours)
    val accentName = MORANDI_SWATCHES.firstOrNull { it.hex.equals(accentHex.ifBlank { "#5B7C99" }, ignoreCase = true) }?.name
        ?: accentHex.uppercase()

    val viewItems = listOf(
        DrawerViewItem(CalendarViewMode.YEAR, "年視圖", "全年 12 月總覽", Icons.Outlined.DateRange),
        DrawerViewItem(CalendarViewMode.MONTH, "月視圖", "大格子月曆網格", Icons.Outlined.CalendarMonth),
        DrawerViewItem(CalendarViewMode.WEEK, "週視圖", "7 日詳情展開", Icons.Outlined.CalendarViewWeek),
        DrawerViewItem(CalendarViewMode.DAY, "日視圖", "24H 跨夜時間軸", Icons.Outlined.Schedule)
    )

    ModalDrawerSheet(
        drawerContainerColor = palette.bgBase,
        drawerContentColor = palette.textMain,
        drawerShape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp),
        modifier = Modifier.width(320.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ---- 標頭：RA + 名稱 + 版本 + 關閉 ----
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(palette.accent),
                        contentAlignment = Alignment.Center
                    ) { Text("RA", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reverie Alarm", fontSize = 17.sp, fontWeight = FontWeight.Black, color = palette.textMain, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                APP_VERSION, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                color = palette.textMuted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.bgElevated)
                                    .border(1.dp, palette.borderColor, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text("輪班通勤與強效鬧鐘", fontSize = 11.sp, color = palette.textMuted)
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.bgElevated)
                            .border(1.dp, palette.borderColor, RoundedCornerShape(14.dp))
                            .clickable { onCloseDrawer() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.Close, contentDescription = "關閉側邊選單", tint = palette.textMain, modifier = Modifier.size(18.dp)) }
                }

                // ---- 搜尋入口 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.bgCard)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(20.dp))
                        .clickable {
                            onCloseDrawer()
                            onOpenSearch()
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(Icons.Outlined.Search, Color(0xFF38BDF8), Color(0xFF38BDF8), size = 34, iconSize = 18)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "搜尋班別、日期、備註或鬧鐘...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.textMain.copy(alpha = 0.85f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text("搜尋", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = palette.accentText)
                }

                // ---- 帳號狀態與日曆來源切換卡 ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.bgCard)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(22.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.bgElevated)
                            .border(1.dp, palette.borderColor, RoundedCornerShape(16.dp))
                            .clickable {
                                onCloseDrawer()
                                onOpenAccount()
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (accountEmail != null) palette.accent else palette.textMuted.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (accountEmail != null) (CloudSync.user?.name?.takeIf { it.isNotBlank() } ?: accountEmail).take(1).uppercase() else "訪", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (accountEmail != null) (CloudSync.user?.name?.takeIf { it.isNotBlank() } ?: "Reverie 使用者") else "訪客單機模式",
                                fontSize = 14.sp, fontWeight = FontWeight.Black, color = palette.textMain
                            )
                            Text(
                                accountEmail ?: "點擊一鍵登入 Google 雲端同步",
                                fontSize = 11.sp, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(20.dp))
                    }

                    Text("檢視日曆來源", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMuted)
                    var sourceOpen by remember { mutableStateOf(false) }
                    val viewingSub = subscriptions.firstOrNull { it.code == viewingCode }
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.bgElevated)
                                .border(1.dp, palette.borderColor, RoundedCornerShape(14.dp))
                                .clickable { sourceOpen = true }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (viewingSub == null) "📅 我的日曆（完整權限）" else "👀 ${viewingSub.name}",
                                fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMain,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = sourceOpen, onDismissRequest = { sourceOpen = false }, containerColor = palette.bgCard) {
                            DropdownMenuItem(
                                text = { Text("📅 我的日曆（完整權限）", fontSize = 13.sp, color = palette.textMain) },
                                trailingIcon = if (viewingCode == null) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, tint = palette.accentText) }
                                } else null,
                                onClick = { sourceOpen = false; onSelectSource(null) }
                            )
                            subscriptions.forEach { sub ->
                                DropdownMenuItem(
                                    text = { Text("👀 ${sub.name}", fontSize = 13.sp, color = palette.textMain) },
                                    trailingIcon = if (viewingCode == sub.code) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, tint = palette.accentText) }
                                    } else null,
                                    onClick = { sourceOpen = false; onSelectSource(sub.code) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (holidayCompare) EMERALD.copy(alpha = 0.15f) else palette.bgElevated)
                            .border(1.dp, if (holidayCompare) EMERALD.copy(alpha = 0.5f) else palette.borderColor, RoundedCornerShape(14.dp))
                            .clickable { onToggleCompare() }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.VolunteerActivism, contentDescription = null, tint = if (holidayCompare) EMERALD.readable() else palette.textMain, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("雙方共同休假日對照", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMain, modifier = Modifier.weight(1f))
                        Text(
                            if (holidayCompare) "已開啟" else "關閉", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                            color = if (holidayCompare) EMERALD.readable() else palette.textMuted
                        )
                    }
                }

                // ---- 日曆檢視維度 2×2 ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("日曆檢視維度", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMuted)
                        Text("點選立即切換", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.accentText)
                    }
                    viewItems.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            rowItems.forEach { item ->
                                val active = isCalendarTab && viewMode == item.mode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(palette.bgCard)
                                        .border(if (active) 2.dp else 1.dp, if (active) palette.accent else palette.borderColor, RoundedCornerShape(20.dp))
                                        .clickable {
                                            onSelectViewMode(item.mode)
                                            onCloseDrawer()
                                        }
                                        .padding(12.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        IconTile(
                                            item.icon, if (active) Color.White else palette.textMain,
                                            if (active) palette.accent else palette.textMuted, size = 30, iconSize = 16, solid = active
                                        )
                                        Text(item.label, fontSize = 15.sp, fontWeight = FontWeight.Black, color = if (active) palette.accentText else palette.textMain)
                                        Text(item.sub, fontSize = 11.sp, color = palette.textMuted, maxLines = 1)
                                    }
                                    if (active) {
                                        Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(palette.accent))
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 本月工時統計 ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.bgCard)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(22.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BarChart, contentDescription = null, tint = palette.accentText, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${currentYearMonth.monthValue}月排班工時統計", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = palette.accentText, modifier = Modifier.weight(1f))
                        Text("預估 $hoursText hr", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = palette.textMain.copy(alpha = 0.85f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatCell("早班", early, Color(0xFFF59E0B), palette, Modifier.weight(1f))
                        StatCell("晚班", evening, Color(0xFF818CF8), palette, Modifier.weight(1f))
                        StatCell("夜班", night, Color(0xFF2DD4BF), palette, Modifier.weight(1f))
                        StatCell("休假", offDays, Color(0xFF10B981), palette, Modifier.weight(1f))
                    }
                }

                // ---- 智慧防護與快速工具 ----
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("智慧防護與快速工具", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMuted)
                    val hasConflict = conflictCount > 0
                    ToolRow(
                        icon = Icons.Outlined.WarningAmber,
                        iconColor = (if (hasConflict) AMBER else EMERALD).readable(),
                        title = "休假日鬧鐘防呆檢測",
                        sub = "檢查明日休假與上班鬧鐘衝突",
                        badge = if (hasConflict) "$conflictCount 筆衝突" else "保護中",
                        badgeColor = (if (hasConflict) AMBER else EMERALD).readable(),
                        palette = palette
                    ) {
                        onCloseDrawer()
                        onRunSafeguardCheck()
                    }
                    ToolRow(
                        icon = Icons.Outlined.CalendarMonth,
                        iconColor = palette.textMuted,
                        title = "一鍵匯入國定假日",
                        sub = "匯入年度假日並啟用防誤響",
                        badge = "匯入",
                        badgeColor = palette.accent,
                        palette = palette,
                        arrowBadge = true
                    ) {
                        onImportHolidays()
                        onCloseDrawer()
                    }
                    ToolRow(
                        icon = Icons.Outlined.Shield,
                        iconColor = palette.accent,
                        title = "鬧鐘權限檢查與測試",
                        sub = "確認鎖屏、省電模式下也會準時響",
                        badge = "檢查",
                        badgeColor = palette.accent,
                        palette = palette,
                        arrowBadge = true
                    ) {
                        onCloseDrawer()
                        onOpenAlarmCheck()
                    }
                }
            }

            // ---- 底部固定：設定 / 關於 ----
            HorizontalDivider(color = palette.borderColor)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.bgCard)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(22.dp))
                        .clickable {
                            onCloseDrawer()
                            onOpenSettings()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(Icons.Outlined.Settings, Color.White, palette.accent, size = 44, iconSize = 22, solid = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("系統與質感主題設定", fontSize = 15.sp, fontWeight = FontWeight.Black, color = palette.textMain)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(palette.accent))
                            Spacer(Modifier.width(6.dp))
                            Text("$accentName · 備份與規則", fontSize = 12.sp, color = palette.textMuted, maxLines = 1)
                        }
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(20.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.bgCard)
                        .border(1.dp, palette.borderColor, RoundedCornerShape(22.dp))
                        .clickable {
                            onCloseDrawer()
                            onOpenAbout()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).border(1.dp, palette.borderColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.Info, contentDescription = null, tint = palette.textMain, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("關於應用", fontSize = 15.sp, fontWeight = FontWeight.Black, color = palette.textMain)
                            Spacer(Modifier.width(6.dp))
                            Text(APP_VERSION, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = palette.textMuted)
                        }
                        Text(if (autoCheckUpdate) "自動檢查更新已開啟" else "手動檢查更新模式", fontSize = 12.sp, color = palette.textMuted)
                    }
                    Text(
                        "最新版本", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMain,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.bgElevated)
                            .border(1.dp, palette.borderColor, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, count: Int, color: Color, palette: ThemePalette, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.bgElevated)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$count", fontSize = 17.sp, fontWeight = FontWeight.Black, color = color)
        Text(label, fontSize = 11.sp, color = palette.textMain.copy(alpha = 0.8f))
    }
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    sub: String,
    badge: String,
    badgeColor: Color,
    palette: ThemePalette,
    arrowBadge: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.bgCard)
            .border(1.dp, palette.borderColor, RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, iconColor, iconColor, size = 38, iconSize = 18)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Black, color = palette.textMain, maxLines = 1)
            Text(sub, fontSize = 11.sp, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        if (arrowBadge) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(badge, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = badgeColor)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = badgeColor, modifier = Modifier.size(15.dp))
            }
        } else {
            Text(
                badge, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = badgeColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(badgeColor.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
