package com.reverie.alarm

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime

// ============================================================================
// 匯入安全：備份檔、音檔、附件網址
//
// Android 版用 Compose 顯示純文字、不執行 HTML／JS，Gson 也不會執行程式碼，所以沒有 XSS。
// 這裡要擋的是另外幾種風險：
//   1. 檔案網址：備份檔裡的附件或鈴聲可以指向「本 App 自己的檔案」（FileProvider），
//      按「以系統相簿開啟」時會把讀取權限交給別的 App → 只接受使用者自己挑的外部檔案（content://）
//   2. 鈴聲網址：http(s) 等網路網址會在響鈴時連線到外部伺服器 → 只接受內建鈴聲或手機裡的音檔
//   3. 壞掉或惡意的備份：Gson 會把 null 塞進 Kotlin 的非 null 欄位，存起來之後 App 每次開啟都閃退；
//      還原到一半失敗會把資料清掉一半；超大檔案或幾萬個鬧鐘會讓 App 當掉
//      → 先完整驗證、整理成乾淨的資料，全部沒問題才一次套用
// ============================================================================
object UriSafety {
    private val BUILT_IN_RAW = Regex("^rt_[a-z0-9_]{1,40}$")

    private class Parts(val scheme: String, val authority: String, val path: String)

    // 自己拆網址（不依賴 Android 的 Uri），電腦上的單元測試也能跑
    private fun parse(s: String?): Parts? {
        if (s.isNullOrBlank() || s.length > 2000) return null
        val i = s.indexOf("://")
        if (i <= 0) return null
        val rest = s.substring(i + 3)
        val j = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, j)
        // 擋掉空白、帳號密碼（user@host）等容易混淆的寫法
        if (authority.isBlank() || authority.any { it.isWhitespace() || it == '@' || it == '\\' }) return null
        return Parts(s.substring(0, i).lowercase(), authority, rest.substring(j))
    }

    /** 使用者從手機挑的檔案（別的 App 提供的 content://）；本 App 自己的 FileProvider 不算 */
    fun isExternalContent(pkg: String, s: String?): Boolean {
        val u = parse(s) ?: return false
        return u.scheme == "content" && !u.authority.lowercase().startsWith(pkg.lowercase())
    }

    /** 本 App 內建的鈴聲（android.resource://套件名/raw/rt_xxx） */
    fun isBuiltInRingtone(pkg: String, s: String?): Boolean {
        val u = parse(s) ?: return false
        val seg = u.path.trim('/').split('/')
        return u.scheme == "android.resource" && u.authority == pkg &&
            seg.size == 2 && seg[0] == "raw" && BUILT_IN_RAW.matches(seg[1])
    }

    /** 鈴聲只接受內建鈴聲或手機裡的音檔，其他（http、file、本 App 的檔案）一律不收 */
    fun safeRingtoneUri(pkg: String, s: String?): String? =
        s?.takeIf { isBuiltInRingtone(pkg, it) || isExternalContent(pkg, it) }

    fun isExternalContent(context: Context, s: String?) = isExternalContent(context.packageName, s)
    fun safeRingtoneUri(context: Context, s: String?) = safeRingtoneUri(context.packageName, s)
}

/** 驗證、整理過的備份內容（全部通過才會套用） */
data class CleanBackup(
    val themeName: String?,
    val prefs: AppPreferences,
    val partnerSync: PartnerSyncState,
    val templates: List<ShiftTemplate>,
    val events: List<ShiftEvent>,
    val alarms: List<ShiftAlarm>,
    val ringtones: List<RingtoneEntry>,
    val skipped: Int
)

class ImportException(message: String) : Exception(message)

object ImportGuard {
    const val MAX_BACKUP_BYTES = 20L * 1024 * 1024
    const val MAX_EVENTS = 3000
    /** Android 每個 App 最多同時排 500 個鬧鐘，留一些空間 */
    const val MAX_ALARMS = 400
    const val MAX_TEMPLATES = 50
    const val MAX_RINGTONES = 100
    const val MAX_ATTACHMENTS = 30
    const val MAX_AUDIO_NAME = 80

    /** 純文字：去掉控制字元（保留換行）、限制長度 */
    fun text(s: String?, max: Int): String = s.orEmpty().filter { it == '\n' || !it.isISOControl() }.take(max)

    private fun dateOk(d: LocalDate) = d.year in 2000..2100

    /** 讀檔時限制大小，避免超大檔案把記憶體吃光 */
    fun readText(context: Context, uri: Uri, maxBytes: Long = MAX_BACKUP_BYTES): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw ImportException(tr("無法讀取檔案"))
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw ImportException(tr("備份檔太大（超過 {0} MB）", maxBytes / 1024 / 1024))
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * 只把備份裡「型別正確」的欄位蓋到預設值上。
     * Gson 不會檢查 Kotlin 的非 null 型別，直接 fromJson 可能把 null 塞進去，之後一用就閃退。
     */
    private fun <T> mergeOntoDefaults(gson: Gson, defaults: T, raw: JsonElement?, cls: Class<T>, nullableStrings: Set<String> = emptySet()): T {
        val base: JsonObject = gson.toJsonTree(defaults).asJsonObject
        val src = raw?.takeIf { it.isJsonObject }?.asJsonObject ?: return defaults
        for ((key, v) in src.entrySet()) {
            if (!v.isJsonPrimitive) continue // 物件、陣列（例如訂閱清單）不從備份讀
            val vp = v.asJsonPrimitive
            val d = base.get(key)
            val ok = when {
                d == null -> key in nullableStrings && vp.isString
                !d.isJsonPrimitive -> false
                else -> {
                    val dp = d.asJsonPrimitive
                    (dp.isBoolean && vp.isBoolean) || (dp.isNumber && vp.isNumber) || (dp.isString && vp.isString)
                }
            }
            if (ok) base.add(key, if (vp.isString) com.google.gson.JsonPrimitive(vp.asString.take(500)) else vp)
        }
        return gson.fromJson(base, cls)
    }

    @Suppress("USELESS_CAST", "UNCHECKED_CAST")
    fun parseBackup(pkg: String, gson: Gson, content: String, today: LocalDate): CleanBackup {
        val root = runCatching { JsonParser.parseString(content).asJsonObject }.getOrNull()
            ?: throw ImportException(tr("❌ 還原失敗：格式不相容"))
        val payload = runCatching { gson.fromJson(root, FullBackupPayload::class.java) }.getOrNull()
            ?: throw ImportException(tr("❌ 還原失敗：格式不相容"))
        var skipped = 0

        // ---- 偏好設定：型別正確的欄位才採用，數值限制在合理範圍，鈴聲網址要安全 ----
        val p0 = mergeOntoDefaults(gson, AppPreferences(), root.get("preferences"), AppPreferences::class.java,
            setOf("defaultWakeRingtoneUri", "defaultLeaveRingtoneUri"))
        val prefs = p0.copy(
            fontScale = p0.fontScale.coerceIn(0.8f, 1.6f),
            customAccentHex = p0.customAccentHex.takeIf { it.isEmpty() || parseHexColorOrNull(it) != null } ?: "",
            customGridBgHex = p0.customGridBgHex.takeIf { it.isEmpty() || parseHexColorOrNull(it) != null } ?: "",
            defaultWakeRingtoneUri = UriSafety.safeRingtoneUri(pkg, p0.defaultWakeRingtoneUri),
            defaultWakeRingtoneName = text(p0.defaultWakeRingtoneName, MAX_AUDIO_NAME),
            defaultLeaveRingtoneUri = UriSafety.safeRingtoneUri(pkg, p0.defaultLeaveRingtoneUri),
            defaultLeaveRingtoneName = text(p0.defaultLeaveRingtoneName, MAX_AUDIO_NAME),
            defaultWakeOffset = p0.defaultWakeOffset.coerceIn(0, 720),
            defaultLeaveOffset = p0.defaultLeaveOffset.coerceIn(0, 720),
            maxSnoozeCount = p0.maxSnoozeCount.coerceIn(0, 10)
        )
        val partner = mergeOntoDefaults(gson, PartnerSyncState(), root.get("partnerSync"), PartnerSyncState::class.java)
            .let { it.copy(partnerName = text(it.partnerName, 60), myShareCode = text(it.myShareCode, 20), subscribedPartnerCode = text(it.subscribedPartnerCode, 20), googleAccountEmail = null, subscriptions = null) }

        // ---- 班別模板 ----
        val templates = (payload.templates as List<SavedShiftTemplate?>?).orEmpty().take(MAX_TEMPLATES).mapNotNull { item ->
            runCatching {
                val parts = item!!.name.split("|", limit = 4)
                require(parts.size == 4)
                ShiftTemplate(
                    id = text(item.id, 80).ifBlank { java.util.UUID.randomUUID().toString() },
                    title = text(parts[1], 30).ifBlank { "班別" },
                    type = runCatching { ShiftType.valueOf(parts[0]) }.getOrDefault(ShiftType.CUSTOM),
                    startTime = LocalTime.parse(item.startTime),
                    endTime = LocalTime.parse(item.endTime),
                    wakeOffsetMinutes = (parts[2].toLongOrNull() ?: 90L).coerceIn(0L, 720L),
                    leaveOffsetMinutes = (parts[3].toLongOrNull() ?: 40L).coerceIn(0L, 720L),
                    isOff = item.isOvernight
                )
            }.getOrNull().also { if (it == null) skipped++ }
        }

        // ---- 排班與鬧鐘：新版格式（V2）優先，沒有才用舊格式 ----
        val v2Events = (payload.eventsV2 as List<EventDto?>?)
        val v2Alarms = (payload.alarmsV2 as List<AlarmDto?>?)
        val events: List<ShiftEvent>
        val alarms: List<ShiftAlarm>
        if (v2Events != null && v2Alarms != null) {
            events = v2Events.take(MAX_EVENTS).mapNotNull { dto ->
                runCatching { cleanEvent(pkg, dto!!.toModel()!!) }.getOrNull().also { if (it == null) skipped++ }
            }
            alarms = v2Alarms.take(MAX_ALARMS).mapNotNull { dto ->
                runCatching { cleanAlarm(pkg, dto!!.toModel()!!) }.getOrNull().also { if (it == null) skipped++ }
            }
        } else {
            events = (payload.events as List<SavedShiftRecord?>?).orEmpty().take(MAX_EVENTS).mapNotNull { rec ->
                runCatching {
                    cleanEvent(pkg, ShiftEvent(
                        date = LocalDate.parse(rec!!.dateStr),
                        title = rec.title,
                        type = runCatching { ShiftType.valueOf(rec.templateId) }.getOrDefault(ShiftType.CUSTOM),
                        startTime = LocalTime.parse(rec.startTime),
                        endTime = LocalTime.parse(rec.endTime),
                        note = rec.note
                    ))
                }.getOrNull().also { if (it == null) skipped++ }
            }
            alarms = (payload.alarms as List<SavedAlarmItem?>?).orEmpty().take(MAX_ALARMS).mapNotNull { item ->
                runCatching {
                    val parts = item!!.label.split("|", limit = 3)
                    require(parts.size == 3)
                    val stage = runCatching { AlarmStage.valueOf(parts[1]) }.getOrDefault(AlarmStage.CUSTOM)
                    cleanAlarm(pkg, ShiftAlarm(
                        requestCode = item.id,
                        date = runCatching { LocalDate.parse(parts[0]) }.getOrDefault(today),
                        triggerTime = LocalTime.of(item.hour, item.minute),
                        stage = stage,
                        label = parts[2],
                        enabled = item.enabled,
                        ringtoneUri = item.ringtoneUri,
                        ringtoneName = item.ringtoneName,
                        maxSnoozeCount = if (stage == AlarmStage.LEAVE) 0 else item.maxSnoozeCount
                    ))
                }.getOrNull().also { if (it == null) skipped++ }
            }
        }

        // ---- 鈴聲庫：只留使用者自己的音檔，網址要是手機裡的檔案 ----
        val ringtones = (payload.ringtoneLibrary as List<RingtoneEntry?>?).orEmpty().take(MAX_RINGTONES).mapNotNull { rt ->
            runCatching {
                val r = rt!!
                if (r.isBuiltIn) return@runCatching null // 內建鈴聲由 App 自己提供
                val uri = UriSafety.safeRingtoneUri(pkg, r.uriString)?.takeIf { UriSafety.isExternalContent(pkg, it) }
                    ?: return@runCatching null
                RingtoneEntry(id = text(r.id, 80).ifBlank { java.util.UUID.randomUUID().toString() }, name = text(r.name, MAX_AUDIO_NAME).ifBlank { tr("匯入音檔") }, uriString = uri)
            }.getOrNull()
        }

        val themeName = runCatching { MorandiTheme.valueOf(payload.themeName).name }.getOrNull()
        return CleanBackup(themeName, prefs, partner, templates, events, alarms, ringtones, skipped)
    }

    private fun cleanEvent(pkg: String, ev: ShiftEvent): ShiftEvent? {
        if (!dateOk(ev.date)) return null
        return ev.copy(
            id = text(ev.id, 80).ifBlank { java.util.UUID.randomUUID().toString() },
            title = text(ev.title, 40).ifBlank { "班別" },
            note = text(ev.note, 4000),
            attachments = (ev.attachments as List<ShiftAttachment?>?).orEmpty().take(MAX_ATTACHMENTS).mapNotNull { a ->
                runCatching {
                    val att = a!!
                    // 附件只接受手機裡的檔案；指向本 App 自己檔案或網路的一律丟掉
                    if (!UriSafety.isExternalContent(pkg, att.uriString)) null
                    else att.copy(id = text(att.id, 80).ifBlank { java.util.UUID.randomUUID().toString() }, name = text(att.name, 120).ifBlank { tr("附件") })
                }.getOrNull()
            }
        )
    }

    private fun cleanAlarm(pkg: String, al: ShiftAlarm): ShiftAlarm? {
        if (!dateOk(al.date)) return null
        return al.copy(
            id = text(al.id, 80).ifBlank { java.util.UUID.randomUUID().toString() },
            requestCode = if (al.requestCode > 0) al.requestCode else kotlin.random.Random.nextInt(1, Int.MAX_VALUE),
            eventId = al.eventId?.let { text(it, 80) },
            label = text(al.label, 80).ifBlank { tr("鬧鐘") },
            ringtoneUri = UriSafety.safeRingtoneUri(pkg, al.ringtoneUri),
            ringtoneName = text(al.ringtoneName, MAX_AUDIO_NAME),
            maxSnoozeCount = al.maxSnoozeCount.coerceIn(0, 10),
            snoozeMinutes = al.snoozeMinutes.coerceIn(0, 60)
        )
    }

    /** 匯入音檔：確認真的是音檔，名稱限制長度、去掉控制字元。不是音檔回傳 null */
    fun checkAudio(context: Context, uri: Uri, rawName: String?): String? {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val ext = rawName.orEmpty().substringAfterLast('.', "").lowercase()
        val audioExt = ext in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi", "3gp")
        if (!(type.startsWith("audio/") || type == "application/ogg" || (type.isEmpty() || type == "application/octet-stream") && audioExt)) return null
        if (!UriSafety.isExternalContent(context, uri.toString())) return null
        return text(rawName, MAX_AUDIO_NAME).ifBlank { tr("匯入音檔") }
    }
}
