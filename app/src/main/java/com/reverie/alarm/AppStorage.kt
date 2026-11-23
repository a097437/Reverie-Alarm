package com.reverie.alarm

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore by preferencesDataStore(name = "reverie_alarm_prefs")

data class SavedShiftTemplate(
    val id: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val colorHex: Long,
    val isOvernight: Boolean
)

data class SavedShiftRecord(
    val dateStr: String,
    val templateId: String,
    val title: String,
    val startTime: String,
    val endTime: String,
    val colorHex: Long,
    val isOvernight: Boolean,
    val note: String = ""
)

data class SavedAlarmItem(
    val id: Int,
    val label: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val ringtoneUri: String? = null,
    val ringtoneName: String = "系統預設鈴聲",
    val maxSnoozeCount: Int = 2,
    val snoozeMinutes: Int = 5,
    val linkedShiftId: String? = null
)

// 讀寫流程在 AppPersistence.kt（舊格式的 shift_records／alarm_items 也在那裡處理）