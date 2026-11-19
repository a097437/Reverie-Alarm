package com.reverie.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

// ============================================================================
// 內建鬧鐘鈴聲（res/raw/rt_*.wav，由 tools/ringtones/generate_ringtones.py 程式合成，無版權問題）
//   - 取代舊版「系統預設／系統鬧鐘／系統通知」三個借用手機系統音效的項目
//   - 舊資料（null、content://settings/system/...）在讀取時自動換成新的內建鈴聲
// ============================================================================
object BuiltInRingtones {

    data class Tone(val id: String, val name: String, val rawName: String, val style: String)

    val WAKE_STRONG = Tone("rt-wake-strong", "強效雙音階喚醒", "rt_wake_strong", "有穿透力但不刺耳")
    val LEAVE_CHIME = Tone("rt-leave-chime", "出門倒數清脆提醒", "rt_leave_chime", "清脆鐘聲")
    val GENTLE_CHORD = Tone("rt-gentle-chord", "晨光柔和和弦", "rt_gentle_chord", "漸進式柔和喚醒")
    val DIGITAL_BEEP = Tone("rt-digital-beep", "清脆電子雙音", "rt_digital_beep", "清脆悅耳的電子音")
    val SUNRISE_RISE = Tone("rt-sunrise-rise", "日出漸進琶音", "rt_sunrise_rise", "柔和上行琶音")

    val ALL = listOf(WAKE_STRONG, LEAVE_CHIME, GENTLE_CHORD, DIGITAL_BEEP, SUNRISE_RISE)

    /** 舊版內建項目（借用手機系統音效），讀取舊資料時移除 */
    private val LEGACY_IDS = setOf("rt-default", "rt-alarm", "rt-notif")
    private val LEGACY_NAMES = setOf("系統預設鬧鐘鈴聲", "清晨清脆喚醒音", "輕柔出門提示音")

    fun uri(context: Context, tone: Tone): String = "android.resource://${context.packageName}/raw/${tone.rawName}"

    fun entries(context: Context): List<RingtoneEntry> =
        ALL.map { RingtoneEntry(id = it.id, name = it.name, uriString = uri(context, it), isBuiltIn = true) }

    fun defaultFor(stage: AlarmStage): Tone = if (stage == AlarmStage.LEAVE) LEAVE_CHIME else WAKE_STRONG

    /** 是否為舊版「借用系統音效」的設定（需要換成新鈴聲） */
    fun isLegacy(uri: String?, name: String?): Boolean =
        uri.isNullOrBlank() || uri.startsWith("content://settings/system/") || name in LEGACY_NAMES

    fun isLegacyEntry(e: RingtoneEntry): Boolean = e.id in LEGACY_IDS || (e.isBuiltIn && ALL.none { it.id == e.id })

    /** 合併鈴聲庫：新的內建鈴聲在前，保留使用者自己匯入的音檔 */
    fun mergeLibrary(context: Context, saved: List<RingtoneEntry>): List<RingtoneEntry> =
        entries(context) + saved.filter { !it.isBuiltIn && !isLegacyEntry(it) }

    /** 鬧鐘的鈴聲若是舊版系統音效，改成該段落的新預設鈴聲 */
    fun migrateAlarm(context: Context, alarm: ShiftAlarm): ShiftAlarm {
        if (!isLegacy(alarm.ringtoneUri, alarm.ringtoneName)) return alarm
        val t = defaultFor(alarm.stage)
        return alarm.copy(ringtoneUri = uri(context, t), ringtoneName = t.name)
    }

    fun migratePrefs(context: Context, p: AppPreferences): AppPreferences {
        var out = p
        if (isLegacy(p.defaultWakeRingtoneUri, p.defaultWakeRingtoneName)) {
            out = out.copy(defaultWakeRingtoneUri = uri(context, WAKE_STRONG), defaultWakeRingtoneName = WAKE_STRONG.name)
        }
        if (isLegacy(p.defaultLeaveRingtoneUri, p.defaultLeaveRingtoneName)) {
            out = out.copy(defaultLeaveRingtoneUri = uri(context, LEAVE_CHIME), defaultLeaveRingtoneName = LEAVE_CHIME.name)
        }
        return out
    }
}

/**
 * 試聽播放器：和真正響鈴一樣用「鬧鐘音量」與循環播放，試聽聽到的就是響鈴時的效果。
 * 同一時間只播一首；再按一次或離開畫面就停止。
 */
object RingtonePreviewer {
    private var player: MediaPlayer? = null
    var playingUri: String? = null
        private set

    fun play(context: Context, uriStr: String?): Boolean {
        stop()
        val uri = Uri.parse(uriStr ?: BuiltInRingtones.uri(context, BuiltInRingtones.WAKE_STRONG))
        val mp = MediaPlayer()
        val ok = runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context.applicationContext, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
        }.isSuccess
        if (!ok) {
            runCatching { mp.release() }
            return false
        }
        player = mp
        playingUri = uri.toString()
        return true
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playingUri = null
    }
}
