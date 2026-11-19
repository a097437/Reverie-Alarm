package com.reverie.alarm

import java.time.LocalDate
import java.time.LocalTime

// ============================================================================
// 資料儲存 v2：完整保存排班與鬧鐘（含 ID 與兩者的關聯）
// 舊版格式會在重開 App 後遺失「鬧鐘屬於哪個班」，導致改班時舊鬧鐘刪不掉
// ============================================================================

data class EventDto(
    val id: String = "",
    val date: String = "",
    val title: String = "",
    val type: String = "CUSTOM",
    val start: String = "08:00",
    val end: String = "17:00",
    val note: String = "",
    val attachments: List<ShiftAttachment>? = null
)

data class AlarmDto(
    val id: String = "",
    val requestCode: Int = 0,
    val eventId: String? = null,
    val date: String = "",
    val time: String = "",
    val stage: String = "CUSTOM",
    val label: String = "",
    val enabled: Boolean = true,
    val ringtoneUri: String? = null,
    val ringtoneName: String = "強效雙音階喚醒",
    val maxSnoozeCount: Int = 2,
    val snoozeMinutes: Int = 5
)

fun ShiftEvent.toDto() = EventDto(
    id = id,
    date = date.toString(),
    title = title,
    type = type.name,
    start = startTime.toString(),
    end = endTime.toString(),
    note = note,
    attachments = attachments
)

fun EventDto.toModel(): ShiftEvent? = runCatching {
    ShiftEvent(
        id = id.ifBlank { java.util.UUID.randomUUID().toString() },
        date = LocalDate.parse(date),
        title = title,
        type = runCatching { ShiftType.valueOf(type) }.getOrDefault(ShiftType.CUSTOM),
        startTime = LocalTime.parse(start),
        endTime = LocalTime.parse(end),
        note = note,
        attachments = attachments.orEmpty()
    )
}.getOrNull()

fun ShiftAlarm.toDto() = AlarmDto(
    id = id,
    requestCode = requestCode,
    eventId = eventId,
    date = date.toString(),
    time = triggerTime.toString(),
    stage = stage.name,
    label = label,
    enabled = enabled,
    ringtoneUri = ringtoneUri,
    ringtoneName = ringtoneName,
    maxSnoozeCount = maxSnoozeCount,
    snoozeMinutes = snoozeMinutes
)

fun AlarmDto.toModel(): ShiftAlarm? = runCatching {
    ShiftAlarm(
        id = id.ifBlank { java.util.UUID.randomUUID().toString() },
        requestCode = if (requestCode != 0) requestCode else kotlin.random.Random.nextInt(1, Int.MAX_VALUE),
        eventId = eventId,
        date = LocalDate.parse(date),
        triggerTime = LocalTime.parse(time),
        stage = runCatching { AlarmStage.valueOf(stage) }.getOrDefault(AlarmStage.CUSTOM),
        label = label,
        enabled = enabled,
        ringtoneUri = ringtoneUri,
        ringtoneName = ringtoneName,
        maxSnoozeCount = maxSnoozeCount,
        // 0 = 關閉貪睡（一次起），要保留下來
        snoozeMinutes = snoozeMinutes.coerceAtLeast(0)
    )
}.getOrNull()
