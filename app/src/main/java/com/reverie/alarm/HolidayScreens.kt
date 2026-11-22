package com.reverie.alarm

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ============================================================================
// 國定假日：休假日鬧鐘防呆視窗、國定假日設定面板（判斷規則在 HolidayRules.kt）
// ============================================================================

/** 按過「全部保留」的那組問題：記在手機裡，重開 App 也不會再問同一件事 */
object GuardPrefs {
    private const val PREFS = "reverie_guard"
    private const val KEY_DISMISSED = "dismissed"
    private const val KEY_NO_ALARM = "no_alarm_dismissed"

    fun dismissed(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DISMISSED, "") ?: ""

    fun setDismissed(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DISMISSED, key).apply()
    }

    /** 「明天有班卻沒鬧鐘」按過「知道了」的那一次 */
    fun noAlarmDismissed(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NO_ALARM, "") ?: ""

    fun setNoAlarmDismissed(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NO_ALARM, key).apply()
    }
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 國定假日目前的設定，一行說完（側邊欄、班別模板頁用） */
fun holidaySummary(policy: HolidayPolicy): String {
    if (!policy.enabled) return tr("未開啟・點一下設定")
    val y = TaiwanHolidays.years
    return tr("已標示 {0}–{1}・{2}", y.first, y.last, if (policy.mode == HolidayMode.OFFICE) tr("見紅就休") else tr("照班表上班"))
}

/** 「10/9（五）」／「Oct 9 (Fri)」 */
fun holidayDateLabel(d: LocalDate): String =
    if (I18n.isEn) "${I18n.monthDay(d)} (${I18n.weekday(d.dayOfWeek)})"
    else "${d.monthValue}/${d.dayOfMonth}（${I18n.weekdayNarrow(d.dayOfWeek)}）"

/** 鬧鐘什麼時候響：同一天只寫時間，前一晚（大夜班）特別標出來 */
private fun alarmWhen(a: ShiftAlarm, day: LocalDate): String = when (a.date) {
    day -> a.triggerTime.format(HM)
    day.minusDays(1) -> tr("前一晚 {0}", a.triggerTime.format(HM))
    else -> "${a.date.monthValue}/${a.date.dayOfMonth} ${a.triggerTime.format(HM)}"
}

private fun reasonOf(item: GuardItem, report: GuardReport): String = when (item.kind) {
    GuardKind.SHIFT_NOW_OFF -> {
        val ev = item.event
        if (ev == null || ev.date == report.date) tr("明天你排的是休假，這是原本上班的鬧鐘")
        else tr("{0} 已改成休假，這是那天上班的鬧鐘", holidayDateLabel(ev.date))
    }
    GuardKind.SHIFT_REMOVED -> tr("這個鬧鐘原本的班已經刪除")
    GuardKind.OWN_ALARM_DAY_OFF -> tr("那天放假；這是你自己設的鬧鐘，需要就保留")
    GuardKind.HOLIDAY_SHIFT -> tr("沒勾就照常上班，鬧鐘照響")
}

/** 那天狀態的小標籤文字與顏色 */
private fun dayStatusChip(s: DayStatus, muted: Color): Pair<String, Color> = when (s.kind) {
    DayKind.SHIFT -> "${tr(s.event!!.title)} ${s.event.startTime.format(HM)}" to s.event.type.accentDot
    DayKind.PLAN -> tr(s.event!!.title) to ShiftType.CUSTOM.accentDot
    DayKind.OFF -> tr("休假") to EMERALD
    DayKind.HOLIDAY_OFF -> tr("放假") to EMERALD
    DayKind.NONE -> tr("未排班") to muted
}

// ---------------------------------------------------------------------------
// 休假日鬧鐘防呆視窗：逐項勾選、寫清楚原因；什麼都不勾＝全部保留
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HolidayGuardDialog(
    report: GuardReport,
    palette: ThemePalette,
    onKeepAll: () -> Unit,
    onApply: (List<GuardItem>) -> Unit,
    onEditDay: (LocalDate) -> Unit
) {
    val checks = remember(report.items.map { it.key }) {
        mutableStateMapOf<String, Boolean>().apply { report.items.forEach { put(it.key, it.defaultChecked) } }
    }
    val selected = report.items.filter { checks[it.key] == true }
    val needsDecision = report.items.isNotEmpty()
    val hasHolidayShift = report.items.any { it.kind == GuardKind.HOLIDAY_SHIFT }
    val warn = AMBER.readableOn(palette.bgCard, 3.0)
    MotionAlertDialog(
        onDismissRequest = onKeepAll,
        containerColor = palette.bgCard,
        icon = {
            if (needsDecision || report.shiftWithoutAlarm != null) {
                Icon(Tabler.AlertTriangle, contentDescription = null, tint = warn, modifier = Modifier.size(30.dp))
            } else {
                Icon(Tabler.ShieldCheck, contentDescription = null, tint = EMERALD.readableOn(palette.bgCard, 3.0), modifier = Modifier.size(30.dp))
            }
        },
        title = {
            Text(
                when {
                    report.items.any { it.defaultChecked } -> tr("明天有鬧鐘要確認")
                    hasHolidayShift -> tr("明天是國定假日")
                    needsDecision -> tr("明天放假，提醒你一下")
                    report.shiftWithoutAlarm != null -> tr("明天有班，但沒有鬧鐘")
                    else -> tr("明天的鬧鐘沒問題")
                },
                fontWeight = FontWeight.ExtraBold, color = palette.textMain
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 明天是什麼日子
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("明天") + " " + holidayDateLabel(report.date), fontSize = 13.sp, fontWeight = FontWeight.Black, color = palette.textMain)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        report.day.holiday?.let { WebPill(tr(it.name), ROSE, fontSize = 11.sp) }
                        val (txt, color) = dayStatusChip(report.day, palette.textMuted)
                        WebPill(txt, color, fontSize = 11.sp)
                    }
                }

                report.items.forEach { item ->
                    GuardItemRow(item, report, checks[item.key] == true, palette) { checks[item.key] = it }
                }

                if (report.ringing.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(EMERALD.copy(alpha = 0.08f))
                            .border(1.dp, EMERALD.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        IconText(
                            when {
                                hasHolidayShift -> tr("✅ 沒改的話，這些鬧鐘會照常響")
                                report.day.isHolidayWork -> tr("✅ {0}照常上班，這些鬧鐘會照響", tr(report.day.holiday!!.name))
                                else -> tr("✅ 這些鬧鐘會照常響")
                            },
                            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = EMERALD.readableOn(palette.bgCard, 4.5)
                        )
                        report.ringing.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(alarmWhen(a, report.date), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = palette.textMain)
                                Spacer(Modifier.width(8.dp))
                                Text(trLabel(a.label), fontSize = 12.sp, color = palette.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                report.shiftWithoutAlarm?.let { ev ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AMBER.copy(alpha = 0.10f))
                            .border(1.dp, AMBER.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconText(
                            tr("⚠️ 明天{0} {1} 上班，但沒有開著的鬧鐘", tr(ev.title), ev.startTime.format(HM)),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = warn, modifier = Modifier.weight(1f)
                        )
                        Text(
                            tr("去設定"), fontSize = 12.sp, fontWeight = FontWeight.Black, color = palette.accentText,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onEditDay(ev.date) }.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }

                if (!needsDecision && report.ringing.isEmpty() && report.shiftWithoutAlarm == null) {
                    Text(tr("明天沒有會響的鬧鐘，也沒有要處理的衝突。"), fontSize = 12.sp, color = palette.textMuted)
                }
            }
        },
        dismissButton = if (needsDecision) {
            { TextButton(onClick = onKeepAll) { Text(tr("全部保留"), color = palette.textMain, fontWeight = FontWeight.Bold) } }
        } else null,
        confirmButton = {
            if (needsDecision) {
                val onlyAlarms = selected.none { it.kind == GuardKind.HOLIDAY_SHIFT }
                TextButton(enabled = selected.isNotEmpty(), onClick = { onApply(selected) }) {
                    Text(
                        when {
                            selected.isEmpty() -> tr("沒有勾選")
                            onlyAlarms -> tr("暫停勾選的 {0} 個", selected.size)
                            else -> tr("套用勾選的 {0} 項", selected.size)
                        },
                        color = if (selected.isNotEmpty()) warn else palette.textMuted,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            } else {
                TextButton(onClick = onKeepAll) { Text(tr("知道了"), color = palette.accentText, fontWeight = FontWeight.ExtraBold) }
            }
        }
    )
}

@Composable
private fun GuardItemRow(item: GuardItem, report: GuardReport, checked: Boolean, palette: ThemePalette, onCheck: (Boolean) -> Unit) {
    val warn = AMBER.readableOn(palette.bgElevated, 3.0)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.bgElevated)
            .border(1.dp, if (checked) AMBER.copy(alpha = 0.6f) else palette.borderColor, RoundedCornerShape(14.dp))
            .clickable { onCheck(!checked) }
            .padding(end = 12.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck, colors = CheckboxDefaults.colors(checkedColor = AMBER.darkenForWhiteText()))
        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (item.kind == GuardKind.HOLIDAY_SHIFT) {
                val ev = item.event
                Text(
                    tr("這天放假：把「{0}」改成休假", tr(ev?.title ?: "")),
                    fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMain
                )
                Text(
                    if (item.alarms.isEmpty()) tr("沒勾就照常上班")
                    else tr("鬧鐘 {0} 會一起關掉；沒勾就照常上班、鬧鐘照響", item.alarms.joinToString("、") { alarmWhen(it, report.date) }),
                    fontSize = 11.sp, color = palette.textMuted, lineHeight = 15.sp
                )
            } else {
                val a = item.alarms.first()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(alarmWhen(a, report.date), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, color = warn)
                    Spacer(Modifier.width(8.dp))
                    Text(trLabel(a.label), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = palette.textMain, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(reasonOf(item, report), fontSize = 11.sp, color = palette.textMuted, lineHeight = 15.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 國定假日設定面板：一個開關＋兩種上班方式，輪班族和一般上班族共用
// ---------------------------------------------------------------------------

@Composable
fun HolidaySettingsSheet(
    prefs: AppPreferences,
    events: List<ShiftEvent>,
    today: LocalDate,
    palette: ThemePalette,
    onUpdate: (AppPreferences) -> Unit,
    onConvertToOff: (List<ShiftEvent>) -> Unit,
    onDismiss: () -> Unit
) {
    val policy = prefs.holidayPolicy()
    val years = TaiwanHolidays.years
    MotionBottomSheet(palette = palette, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(ROSE.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Tabler.Flag, contentDescription = null, tint = ROSE.readable(), modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("國定假日"), fontSize = 20.sp, fontWeight = FontWeight.Black, color = palette.textMain)
                    Text(tr("內建人事行政總處 {0}–{1} 年行事曆", years.first, years.last), fontSize = 11.sp, color = palette.textMuted)
                }
            }

            ToggleRow(
                tr("在日曆標示國定假日"),
                tr("紅字加假日名稱；關掉後，國定假日完全不影響班表與鬧鐘"),
                prefs.holidayLabels, palette
            ) { onUpdate(prefs.copy(holidayLabels = it)) }

            if (today.year > years.last) {
                IconText(
                    tr("⚠️ 內建的假日資料只到 {0} 年，請更新 App 取得新年度的假日", years.last),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AMBER.readable()
                )
            }

            if (prefs.holidayLabels) {
                Text(tr("國定假日那天，你通常："), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
                ModeCard(
                    selected = policy.mode == HolidayMode.ROSTER, icon = Tabler.Clock,
                    title = tr("照班表上班"), tag = tr("輪班・排班制"),
                    desc = tr("保全、醫護、服務業等。假日只標紅字，班表和鬧鐘完全照你排的。"),
                    palette = palette
                ) { onUpdate(prefs.copy(holidayMode = HolidayMode.ROSTER.key)) }
                ModeCard(
                    selected = policy.mode == HolidayMode.OFFICE, icon = Tabler.Sun,
                    title = tr("見紅就休"), tag = tr("一般上班族"),
                    desc = tr("沒排班的國定假日當放假；假日排了上班，前一天會先問你要不要放。"),
                    palette = palette
                ) { onUpdate(prefs.copy(holidayMode = HolidayMode.OFFICE.key)) }

                if (policy.mode == HolidayMode.OFFICE) {
                    SectionCard(tr("見紅就休的細節"), palette) {
                        ToggleRow(
                            tr("政府補假日也放假"),
                            tr("例如國慶日遇到週六、週五補假。民間公司的補假日依勞資協商，不一定同一天"),
                            prefs.holidayIncludeMakeUp, palette
                        ) { onUpdate(prefs.copy(holidayIncludeMakeUp = it)) }
                        ToggleRow(
                            tr("批次／AI 排班時略過國定假日"),
                            tr("一次排很多天的上班時，放假的國定假日自動空下來，需要時可以一鍵加回"),
                            prefs.holidaySkipInBatch, palette
                        ) { onUpdate(prefs.copy(holidaySkipInBatch = it)) }
                    }
                    HolidayShiftsCard(DayResolver.upcomingHolidayShifts(today, events, policy), palette, onConvertToOff)
                }

                UpcomingHolidays(today, events, policy, palette)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(EMERALD.copy(alpha = 0.08f))
                    .border(1.dp, EMERALD.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                IconText(
                    tr("🛡️ 已排好的班永遠優先：國定假日不會自己改動或關閉任何鬧鐘。"),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EMERALD.readableOn(palette.bgCard, 4.5)
                )
            }
            Text(
                tr("勞基法第 39 條：國定假日出勤，工資加倍發給；補假日以公司規定為準。"),
                fontSize = 10.sp, color = palette.textMuted, lineHeight = 14.sp
            )
        }
        // 面板是獨立視窗，「復原」提示要在這裡再放一份才看得到
        TopToastHost(palette)
    }
}

@Composable
private fun ModeCard(selected: Boolean, icon: ImageVector, title: String, tag: String, desc: String, palette: ThemePalette, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.12f) else palette.bgElevated)
            .border(if (selected) 2.dp else 1.dp, if (selected) palette.accent else palette.borderColor, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(palette.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = palette.accentText, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Black, color = palette.textMain)
                Spacer(Modifier.width(6.dp))
                WebPill(tag, palette.accent)
            }
            Text(desc, fontSize = 12.sp, color = palette.textMuted, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) palette.accent else palette.borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(palette.accent)) }
    }
}

/** 見紅休：已經排在放假國定假日的上班，可以勾選後一次改成休假 */
@Composable
private fun HolidayShiftsCard(clash: List<Pair<ShiftEvent, Holiday>>, palette: ThemePalette, onConvertToOff: (List<ShiftEvent>) -> Unit) {
    if (clash.isEmpty()) {
        IconText(tr("✅ 接下來放假的國定假日都沒有排上班"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMuted)
        return
    }
    val picks = remember(clash.map { it.first.id }) {
        mutableStateMapOf<String, Boolean>().apply { clash.forEach { put(it.first.id, true) } }
    }
    val chosen = clash.map { it.first }.filter { picks[it.id] == true }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AMBER.copy(alpha = 0.10f))
            .border(1.dp, AMBER.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconText(
            tr("⚠️ 已排好的班裡，有 {0} 天是放假的國定假日", clash.size),
            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = AMBER.readable()
        )
        Text(tr("勾選要放假的日子；要上班的取消勾選就好"), fontSize = 11.sp, color = palette.textMuted)
        clash.forEach { (ev, h) ->
            val on = picks[ev.id] == true
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picks[ev.id] = !on },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = on, onCheckedChange = { picks[ev.id] = it }, colors = CheckboxDefaults.colors(checkedColor = AMBER.darkenForWhiteText()))
                Text(holidayDateLabel(ev.date), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMain, modifier = Modifier.width(84.dp))
                Text(tr(h.name), fontSize = 12.sp, color = ROSE.readable(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(tr(ev.title), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = ev.type.chipText(), modifier = Modifier.padding(end = 6.dp))
            }
        }
        Spacer(Modifier.size(4.dp))
        WebButton(
            if (chosen.isEmpty()) tr("沒有勾選") else tr("勾選的 {0} 天改成休假", chosen.size),
            palette, Modifier.fillMaxWidth(), color = AMBER, enabled = chosen.isNotEmpty(), fontSize = 13.sp, vertical = 11.dp
        ) { onConvertToOff(chosen) }
        Text(tr("那幾天的上班鬧鐘會一起關掉，改完可以按「復原」"), fontSize = 10.sp, color = palette.textMuted)
    }
}

/** 接下來的國定假日：每一天你排了什麼 */
@Composable
private fun UpcomingHolidays(today: LocalDate, events: List<ShiftEvent>, policy: HolidayPolicy, palette: ThemePalette) {
    val next = TaiwanHolidays.between(today, today.plusDays(180)).take(6)
    if (next.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.bgElevated)
            .border(1.dp, palette.borderColor, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(tr("接下來的國定假日"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = palette.accentText)
        next.forEach { h ->
            val s = DayResolver.resolve(h.date, events, policy)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(holidayDateLabel(h.date), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textMain, modifier = Modifier.width(84.dp))
                Text(tr(h.name), fontSize = 12.sp, color = ROSE.readable(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val (txt, color) = dayStatusChip(s, palette.textMuted)
                WebPill(txt, color, fontSize = 10.sp)
            }
        }
    }
}
