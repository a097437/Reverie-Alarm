package com.reverie.alarm

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference

// ============================================================================
// 存檔與讀檔：都不在主執行緒做
//
//   persist()：畫面上每次改資料都呼叫（取代原本的 persistAllData 內容）
//     - 300ms 內的多次修改合併成一次
//     - JSON 在背景執行緒轉好；DataStore 的 edit { } 裡只放字串、全部只寫一次
//       （edit { } 會在「呼叫者」的執行緒執行：以前從畫面的協程呼叫，所有 JSON 都在主執行緒轉，
//        而且每次存檔分三次寫、每次都重寫整個檔案）
//     - 內容沒變就不寫檔；鬧鐘排程、小工具、雲端上傳也只在相關資料變了才做
//       （每次 App 啟動後的第一次存檔一律全做，和以前一樣會順便把排程對齊一次）
//     - 用自己的協程範圍，畫面關掉時寫到一半也不會被取消
//   flush()：App 進背景時呼叫，等待中的修改立刻寫入
//   load()：開 App 時在背景讀檔、解析，解析完才交給畫面
// ============================================================================

/** 要存的完整資料：畫面上的狀態拍一張快照（全是不可變的資料類別） */
class PersistSnapshot(
    val events: List<ShiftEvent>,
    val alarms: List<ShiftAlarm>,
    val templates: List<ShiftTemplate>,
    val themeName: String,
    val prefs: AppPreferences,
    val ringtones: List<RingtoneEntry>,
    val partner: PartnerSyncState
)

/** 讀到的資料是哪一種格式 */
enum class LoadSource {
    /** 目前的格式（排班與鬧鐘的關聯完整） */
    V2,
    /** 很舊的版本留下的格式，已轉成目前的資料 */
    LEGACY,
    /** 沒有任何排班資料（第一次安裝） */
    EMPTY
}

class LoadedAppData(
    val theme: MorandiTheme?,
    /** 已套用鈴聲遷移；沒有存檔時是預設值 */
    val prefs: AppPreferences,
    val partner: PartnerSyncState?,
    /** 內建鈴聲＋使用者自己匯入的音檔 */
    val ringtones: List<RingtoneEntry>,
    /** null＝沒有存過模板，沿用預設模板 */
    val templates: List<ShiftTemplate>?,
    val source: LoadSource,
    val events: List<ShiftEvent>,
    /** 已套用鈴聲遷移 */
    val alarms: List<ShiftAlarm>
)

object AppPersistence {
    private const val DEBOUNCE_MS = 300L

    private val KEY_TEMPLATES = stringPreferencesKey("saved_shift_templates")
    private val KEY_THEME = stringPreferencesKey("saved_morandi_theme")
    private val KEY_PREFS = stringPreferencesKey("saved_app_preferences")
    private val KEY_RINGTONES = stringPreferencesKey("saved_ringtone_library")
    private val KEY_ATTACHMENTS = stringPreferencesKey("saved_event_attachments")
    private val KEY_PARTNER = stringPreferencesKey("saved_partner_sync")
    private val KEY_WEEK_MONDAY = booleanPreferencesKey("saved_week_monday")
    private val KEY_FONT_SCALE = floatPreferencesKey("saved_font_scale")
    private val KEY_EVENTS_V2 = stringPreferencesKey("v2_events")
    private val KEY_ALARMS_V2 = stringPreferencesKey("v2_alarms")
    // 舊格式：v2 讀不到時的備援，和以前一樣每次一起更新
    private val KEY_RECORDS_V1 = stringPreferencesKey("shift_records")
    private val KEY_ALARMS_V1 = stringPreferencesKey("alarm_items")

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Pending(val context: Context, val snapshot: PersistSnapshot)
    private val pending = AtomicReference<Pending?>(null)
    private val writeLock = Mutex()
    /** 等待中的延遲寫入（只在主執行緒存取） */
    private var timer: Job? = null
    @Volatile private var sideEffectsDoneOnce = false
    /**
     * 畫面把存檔讀進來之前不接受存檔：
     * 否則剛開好的空畫面（例如旋轉螢幕重建時）可能被當成「資料」蓋掉真正的存檔
     */
    @Volatile private var ready = false

    // ------------------------------------------------------------------ 存檔

    /** 畫面上的資料改了（主執行緒呼叫） */
    fun persist(context: Context, snapshot: PersistSnapshot) {
        if (!ready) return
        pending.set(Pending(context.applicationContext, snapshot))
        timer?.cancel()
        timer = scope.launch {
            delay(DEBOUNCE_MS)
            // 開始寫之後就不能被取消（新的修改會等這次寫完再寫）
            withContext(NonCancellable) { writePending() }
        }
    }

    /** App 進背景：等待中的修改立刻寫入（主執行緒呼叫） */
    fun flush() {
        if (pending.get() == null) return
        timer?.cancel()
        timer = null
        scope.launch { withContext(NonCancellable) { writePending() } }
    }

    private suspend fun writePending() = writeLock.withLock {
        val p = pending.getAndSet(null) ?: return@withLock
        runCatching { write(p.context, p.snapshot) }.onFailure { AppLog.e("Store", "存檔失敗", it) }
    }

    private suspend fun write(context: Context, s: PersistSnapshot) {
        // 1. 在背景執行緒把 JSON 轉好
        // 注意：DataStore 自己定義了 Key.to()，這裡用 Pair(...) 才是一般的配對
        val strings: List<Pair<Preferences.Key<String>, String>> = listOf(
            Pair(KEY_EVENTS_V2, gson.toJson(s.events.map { it.toDto() })),
            Pair(KEY_ALARMS_V2, gson.toJson(s.alarms.map { it.toDto() })),
            Pair(KEY_PREFS, gson.toJson(s.prefs)),
            Pair(KEY_TEMPLATES, gson.toJson(s.templates.map { it.toSaved() })),
            Pair(KEY_RINGTONES, gson.toJson(s.ringtones)),
            Pair(KEY_ATTACHMENTS, gson.toJson(s.events.associate { it.date.toString() to it.attachments })),
            Pair(KEY_PARTNER, gson.toJson(s.partner)),
            Pair(KEY_THEME, s.themeName),
            Pair(KEY_RECORDS_V1, gson.toJson(s.events.map { it.toLegacyRecord() })),
            Pair(KEY_ALARMS_V1, gson.toJson(s.alarms.map { it.toLegacyItem() }))
        )

        // 2. 一次寫入：edit { } 裡只比對、放字串；全部和檔案裡一樣時 DataStore 不會寫檔
        val changed = HashSet<String>()
        context.dataStore.edit { p ->
            changed.clear()
            for ((key, value) in strings) {
                if (p[key] != value) { p[key] = value; changed += key.name }
            }
            if (p[KEY_WEEK_MONDAY] != s.prefs.weekStartsOnMonday) p[KEY_WEEK_MONDAY] = s.prefs.weekStartsOnMonday
            if (p[KEY_FONT_SCALE] != s.prefs.fontScale) p[KEY_FONT_SCALE] = s.prefs.fontScale
        }

        // 3. 跟著資料走的事：只在相關資料變了才做
        val first = !sideEffectsDoneOnce
        val eventsChanged = first || KEY_EVENTS_V2.name in changed
        val alarmsChanged = first || KEY_ALARMS_V2.name in changed
        val prefsChanged = first || KEY_PREFS.name in changed
        withContext(Dispatchers.IO) {
            // 鬧鐘規則（NativeAlarmScheduler.rules）由畫面在呼叫 persist 前就更新好了
            if (alarmsChanged || prefsChanged) NativeAlarmScheduler.syncAll(context, s.alarms)
            if (eventsChanged || alarmsChanged || prefsChanged) {
                ShiftWidgetProvider.saveSnapshot(
                    context, s.events, s.prefs.holidayPolicy(), s.prefs.customAccentHex, s.prefs.weekStartsOnMonday
                )
            }
            if (prefsChanged) EveningGuard.schedule(context, s.prefs) // 前一晚提醒（設定改了就重排）
        }
        // 已登入就把公開欄位（日期、班別、時間）同步到雲端（雲端模組的狀態在主執行緒）
        if (eventsChanged) withContext(Dispatchers.Main) { CloudSync.schedulePush(s.events) }
        sideEffectsDoneOnce = true
    }

    // ------------------------------------------------------------------ 讀檔

    /**
     * 開 App 時讀取全部資料（在背景執行緒呼叫）。
     * 會先把還沒寫進去的修改寫完再讀，例如旋轉螢幕前 300ms 內的修改。
     */
    suspend fun load(context: Context, today: LocalDate): LoadedAppData {
        ready = false
        writePending()
        val p = context.dataStore.data.first()

        val theme = p[KEY_THEME]?.let { name -> runCatching { MorandiTheme.valueOf(name) }.getOrNull() }
        val loadedPrefs = p[KEY_PREFS]?.let { pJson ->
            runCatching { gson.fromJson(pJson, AppPreferences::class.java) }.getOrNull()?.let {
                // 舊版沒有 colorMode：把原本選的莫蘭迪主題轉成新設定
                if (pJson.contains("\"colorMode\"")) it else migrateLegacyTheme(it, p[KEY_THEME])
            }
        }
        val partner = p[KEY_PARTNER]?.let { runCatching { gson.fromJson(it, PartnerSyncState::class.java) }.getOrNull() }
        val savedRingtones = p[KEY_RINGTONES]?.let {
            runCatching { gson.fromJson(it, Array<RingtoneEntry>::class.java).toList() }.getOrNull()
        }.orEmpty()
        val savedTemplates = p[KEY_TEMPLATES]?.let {
            runCatching { gson.fromJson(it, Array<SavedShiftTemplate>::class.java).toList() }.getOrNull()
        }.orEmpty()
        // 一筆都讀不出來時沿用預設模板（畫面上有地方直接取第一個模板）
        val templates = savedTemplates.mapNotNull { it.toModel() }.takeIf { it.isNotEmpty() }

        val v2Events = p[KEY_EVENTS_V2]?.let { raw ->
            runCatching { gson.fromJson(raw, Array<EventDto>::class.java).mapNotNull { it.toModel() } }.getOrNull()
        }
        val v2Alarms = p[KEY_ALARMS_V2]?.let { raw ->
            runCatching { gson.fromJson(raw, Array<AlarmDto>::class.java).mapNotNull { it.toModel() } }.getOrNull()
        }

        var source = LoadSource.EMPTY
        var events: List<ShiftEvent> = emptyList()
        var alarms: List<ShiftAlarm> = emptyList()
        if (v2Events != null && v2Alarms != null) {
            // v2 完整格式：保留排班與鬧鐘的關聯
            source = LoadSource.V2
            events = v2Events
            alarms = v2Alarms
        } else {
            // 很舊的版本：只有 v2 不存在時才解析舊格式
            val records = p[KEY_RECORDS_V1]?.let {
                runCatching { gson.fromJson(it, Array<SavedShiftRecord>::class.java).toList() }.getOrNull()
            }.orEmpty()
            if (records.isNotEmpty()) {
                source = LoadSource.LEGACY
                events = legacyEvents(records, parseAttachments(p[KEY_ATTACHMENTS]))
                alarms = legacyAlarms(context, p[KEY_ALARMS_V1], today)
            }
        }

        // 鈴聲換新：移除舊版借用手機系統音效的項目，舊設定自動改成新的內建合成鈴聲
        return LoadedAppData(
            theme = theme,
            prefs = BuiltInRingtones.migratePrefs(context, loadedPrefs ?: AppPreferences()),
            partner = partner,
            ringtones = BuiltInRingtones.mergeLibrary(context, savedRingtones),
            templates = templates,
            source = source,
            events = events,
            alarms = alarms.map { BuiltInRingtones.migrateAlarm(context, it) }
        )
    }

    /** 畫面已經把 load() 的結果套用好了，之後的修改才會存檔 */
    fun markLoaded() {
        ready = true
    }

    // ------------------------------------------------------------------ 格式轉換

    private fun ShiftTemplate.toSaved() = SavedShiftTemplate(
        id = id,
        name = "${type.name}|$title|$wakeOffsetMinutes|$leaveOffsetMinutes",
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        colorHex = 0L,
        isOvernight = isOff
    )

    private fun SavedShiftTemplate.toModel(): ShiftTemplate? {
        val parts = name.split("|", limit = 4)
        if (parts.size != 4) return null
        return runCatching {
            ShiftTemplate(
                id = id,
                title = parts[1],
                type = runCatching { ShiftType.valueOf(parts[0]) }.getOrDefault(ShiftType.CUSTOM),
                startTime = LocalTime.parse(startTime),
                endTime = LocalTime.parse(endTime),
                wakeOffsetMinutes = parts[2].toLongOrNull() ?: 90L,
                leaveOffsetMinutes = parts[3].toLongOrNull() ?: 40L,
                isOff = isOvernight
            )
        }.getOrNull()
    }

    private fun ShiftEvent.toLegacyRecord() = SavedShiftRecord(
        dateStr = date.toString(),
        templateId = type.name,
        title = title,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        colorHex = 0L,
        isOvernight = endTime.isBefore(startTime),
        note = note
    )

    private fun ShiftAlarm.toLegacyItem() = SavedAlarmItem(
        id = requestCode,
        label = "$date|${stage.name}|$label",
        hour = triggerTime.hour,
        minute = triggerTime.minute,
        enabled = enabled,
        ringtoneUri = ringtoneUri,
        ringtoneName = ringtoneName,
        maxSnoozeCount = if (stage == AlarmStage.LEAVE) 0 else maxSnoozeCount
    )

    private fun parseAttachments(json: String?): Map<String, List<ShiftAttachment>> = json?.let { attJson ->
        runCatching {
            val rawMap = gson.fromJson(attJson, Map::class.java) as? Map<*, *>
            rawMap?.mapNotNull { (k, v) ->
                val keyStr = k as? String ?: return@mapNotNull null
                keyStr to gson.fromJson(gson.toJson(v), Array<ShiftAttachment>::class.java).toList()
            }?.toMap()
        }.getOrNull()
    } ?: emptyMap()

    private fun legacyEvents(records: List<SavedShiftRecord>, attachments: Map<String, List<ShiftAttachment>>): List<ShiftEvent> =
        records.mapNotNull { rec ->
            runCatching {
                ShiftEvent(
                    date = LocalDate.parse(rec.dateStr),
                    title = rec.title,
                    type = runCatching { ShiftType.valueOf(rec.templateId) }.getOrDefault(ShiftType.CUSTOM),
                    startTime = LocalTime.parse(rec.startTime),
                    endTime = LocalTime.parse(rec.endTime),
                    note = rec.note,
                    attachments = attachments[rec.dateStr].orEmpty()
                )
            }.getOrNull()
        }

    private fun legacyAlarms(context: Context, json: String?, today: LocalDate): List<ShiftAlarm> {
        val items = if (json == null) {
            // 和舊版一樣：沒有存過鬧鐘時的兩個預設鬧鐘
            listOf(
                SavedAlarmItem(101, "夜班出門準備 (第一段)", 21, 30, true),
                SavedAlarmItem(102, "夜班最後確認 (防賴床)", 22, 0, true, maxSnoozeCount = 1)
            )
        } else {
            runCatching { gson.fromJson(json, Array<SavedAlarmItem>::class.java).toList() }.getOrNull().orEmpty()
        }
        val alarms = items.mapNotNull { item ->
            val parts = item.label.split("|", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val stage = runCatching { AlarmStage.valueOf(parts[1]) }.getOrDefault(AlarmStage.CUSTOM)
            ShiftAlarm(
                requestCode = item.id,
                date = runCatching { LocalDate.parse(parts[0]) }.getOrDefault(today),
                triggerTime = LocalTime.of(item.hour, item.minute),
                stage = stage,
                label = parts[2],
                enabled = item.enabled,
                ringtoneUri = item.ringtoneUri,
                ringtoneName = item.ringtoneName,
                maxSnoozeCount = if (stage == AlarmStage.LEAVE) 0 else item.maxSnoozeCount
            )
        }.toMutableList()
        // 舊版的鬧鐘編號可能重複（同一毫秒建立的起床＋出門鬧鐘會互相覆蓋），在這裡重新編號
        alarms.forEach { NativeAlarmScheduler.cancelLegacy(context, it.requestCode) }
        val seenCodes = HashSet<Int>()
        for (i in alarms.indices) {
            val a = alarms[i]
            if (!seenCodes.add(a.requestCode)) {
                val fresh = a.copy(requestCode = kotlin.random.Random.nextInt(1, Int.MAX_VALUE))
                alarms[i] = fresh
                seenCodes.add(fresh.requestCode)
            }
        }
        return alarms
    }
}
