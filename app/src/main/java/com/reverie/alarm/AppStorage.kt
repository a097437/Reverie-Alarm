package com.reverie.alarm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

class AppRepository(private val context: Context) {
    private val gson = Gson()
    private val keyRecords = stringPreferencesKey("shift_records")
    private val keyAlarms = stringPreferencesKey("alarm_items")

    val shiftRecordsFlow: Flow<List<SavedShiftRecord>> = context.dataStore.data.map { prefs ->
        val json = prefs[keyRecords] ?: return@map emptyList()
        gson.fromJson(json, Array<SavedShiftRecord>::class.java).toList()
    }

    suspend fun saveShiftRecords(records: List<SavedShiftRecord>) {
        context.dataStore.edit { prefs ->
            prefs[keyRecords] = gson.toJson(records)
        }
    }

    val alarmsFlow: Flow<List<SavedAlarmItem>> = context.dataStore.data.map { prefs ->
        val json = prefs[keyAlarms] ?: return@map getDefaultAlarms()
        gson.fromJson(json, Array<SavedAlarmItem>::class.java).toList()
    }

    suspend fun saveAlarms(alarms: List<SavedAlarmItem>) {
        context.dataStore.edit { prefs ->
            prefs[keyAlarms] = gson.toJson(alarms)
        }
    }

    private fun getDefaultAlarms(): List<SavedAlarmItem> = listOf(
        SavedAlarmItem(101, "夜班出門準備 (第一段)", 21, 30, true),
        SavedAlarmItem(102, "夜班最後確認 (防賴床)", 22, 0, true, maxSnoozeCount = 1)
    )
}