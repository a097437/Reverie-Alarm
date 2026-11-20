package com.reverie.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

// ============================================================================
// 內建鬧鐘鈴聲（res/raw/rt_*.ogg）：11 首授權明確的柔和音樂（CC0／CC BY 4.0），約 128 kbps
//   - 由 tools/ringtones/make_tones.py 從原始音檔產生：本身可無縫循環的整首保留，
//     其他取 60 秒並交叉淡入接回開頭；音量統一
//   - 舊版或已移除的鈴聲，在讀取舊資料與響鈴時自動換成對應的新鈴聲
//   - CC BY 的曲目需要標註作者，見 CREDITS（「關於應用」裡會顯示）
// ============================================================================
object BuiltInRingtones {

    data class Tone(val id: String, val name: String, val rawName: String, val style: String)

    data class Credit(val toneName: String, val title: String, val author: String, val license: String, val url: String)

    val MORNING_SKY = Tone("rt-morning-sky", "晨空漫步", "rt_morning_sky", "輕鬆的晨間曲")
    val MORNING_SKY_PIANO = Tone("rt-morning-sky-piano", "鋼琴晨空", "rt_morning_sky_piano", "同一首的鋼琴版")
    val LIGHT_PARTICLES = Tone("rt-light-particles", "微光粒子", "rt_light_particles", "音符稀疏的氛圍鋼琴")
    val BUDDING = Tone("rt-budding", "緩緩甦醒", "rt_budding", "極簡氛圍鋼琴")
    val MUSIC_BOX = Tone("rt-music-box", "清脆八音盒", "rt_music_box", "清脆但不尖")
    val HAPPY_LULLABY = Tone("rt-happy-lullaby", "晨鈴琶音", "rt_happy_lullaby", "鈴聲加琶音")

    val DEFAULT_WAKE = MORNING_SKY
    val DEFAULT_LEAVE = MUSIC_BOX

    val ALL = listOf(
        MORNING_SKY,
        MORNING_SKY_PIANO,
        LIGHT_PARTICLES,
        BUDDING,
        Tone("rt-sunset-plains", "草原微風", "rt_sunset_plains", "溫柔吉他加柔和襯底"),
        Tone("rt-small-fire", "營火吉他", "rt_small_fire", "溫暖的木吉他"),
        Tone("rt-etirwer", "晨間吉他", "rt_etirwer", "木吉他"),
        Tone("rt-dawn-break", "破曉時分", "rt_dawn_break", "溫和的氛圍鋼琴"),
        Tone("rt-morning-sun", "鳥鳴晨光", "rt_morning_sun", "柔和鋼琴加鳥叫"),
        HAPPY_LULLABY,
        MUSIC_BOX,
    )

    /** CC BY 4.0 授權要求在 App 內標註（CC0 的曲目不需要） */
    val CREDITS = listOf(
        Credit("破曉時分", "Dawn's Break", "Shane Ivers", "CC BY 4.0", "https://www.silvermansound.com"),
        Credit("鳥鳴晨光", "Wake up in the morning sun", "東月 (dongnguyet)", "CC BY 4.0", "https://dongnguyet.itch.io/morning-sun"),
    )
    const val CC_BY_URL = "https://creativecommons.org/licenses/by/4.0/"

    /** 舊版或已移除的鈴聲 → 對應的新鈴聲（沒列到的換成該段落的預設鈴聲） */
    private val REPLACED = mapOf(
        "rt_wake_strong" to MORNING_SKY,
        "rt_leave_chime" to MUSIC_BOX,
        "rt_gentle_chord" to LIGHT_PARTICLES,
        "rt_digital_beep" to HAPPY_LULLABY,
        "rt_sunrise_rise" to BUDDING,
        "rt_piano_morning" to MORNING_SKY_PIANO,
    )

    private fun replacedTone(uri: String?, fallback: Tone = DEFAULT_WAKE): Tone? {
        if (uri == null || !uri.startsWith("android.resource://")) return null
        val raw = uri.substringAfterLast('/')
        if (!raw.startsWith("rt_") || ALL.any { it.rawName == raw }) return null
        return REPLACED[raw] ?: fallback
    }

    /** 響鈴時用：舊版鈴聲的網址換成新鈴聲，其餘照舊 */
    fun resolve(context: Context, uri: String?): String? = replacedTone(uri)?.let { uri(context, it) } ?: uri

    /** 舊版內建項目（借用手機系統音效），讀取舊資料時移除 */
    private val LEGACY_IDS = setOf("rt-default", "rt-alarm", "rt-notif")
    private val LEGACY_NAMES = setOf("系統預設鬧鐘鈴聲", "清晨清脆喚醒音", "輕柔出門提示音")

    fun uri(context: Context, tone: Tone): String = "android.resource://${context.packageName}/raw/${tone.rawName}"

    fun entries(context: Context): List<RingtoneEntry> =
        ALL.map { RingtoneEntry(id = it.id, name = it.name, uriString = uri(context, it), isBuiltIn = true) }

    fun defaultFor(stage: AlarmStage): Tone = if (stage == AlarmStage.LEAVE) DEFAULT_LEAVE else DEFAULT_WAKE

    /** 是否為舊版「借用系統音效」的設定（需要換成新鈴聲） */
    fun isLegacy(uri: String?, name: String?): Boolean =
        uri.isNullOrBlank() || uri.startsWith("content://settings/system/") || name in LEGACY_NAMES

    fun isLegacyEntry(e: RingtoneEntry): Boolean = e.id in LEGACY_IDS || (e.isBuiltIn && ALL.none { it.id == e.id })

    /** 合併鈴聲庫：新的內建鈴聲在前，保留使用者自己匯入的音檔 */
    fun mergeLibrary(context: Context, saved: List<RingtoneEntry>): List<RingtoneEntry> =
        entries(context) + saved.filter { !it.isBuiltIn && !isLegacyEntry(it) }

    /** 目前內建鈴聲的網址 → 那首鈴聲（用來把改過的名稱同步到舊資料） */
    private fun currentTone(uri: String?): Tone? =
        uri?.takeIf { it.startsWith("android.resource://") }?.substringAfterLast('/')?.let { raw -> ALL.firstOrNull { it.rawName == raw } }

    /** 鬧鐘的鈴聲若是舊版系統音效，改成該段落的新預設鈴聲；內建鈴聲改名時同步名稱 */
    fun migrateAlarm(context: Context, alarm: ShiftAlarm): ShiftAlarm {
        currentTone(alarm.ringtoneUri)?.let { return if (alarm.ringtoneName == it.name) alarm else alarm.copy(ringtoneName = it.name) }
        replacedTone(alarm.ringtoneUri, defaultFor(alarm.stage))?.let { return alarm.copy(ringtoneUri = uri(context, it), ringtoneName = it.name) }
        if (!isLegacy(alarm.ringtoneUri, alarm.ringtoneName)) return alarm
        val t = defaultFor(alarm.stage)
        return alarm.copy(ringtoneUri = uri(context, t), ringtoneName = t.name)
    }

    fun migratePrefs(context: Context, p: AppPreferences): AppPreferences {
        var out = p
        currentTone(p.defaultWakeRingtoneUri)?.let { if (it.name != p.defaultWakeRingtoneName) out = out.copy(defaultWakeRingtoneName = it.name) }
        currentTone(p.defaultLeaveRingtoneUri)?.let { if (it.name != p.defaultLeaveRingtoneName) out = out.copy(defaultLeaveRingtoneName = it.name) }
        replacedTone(p.defaultWakeRingtoneUri)?.let { out = out.copy(defaultWakeRingtoneUri = uri(context, it), defaultWakeRingtoneName = it.name) }
        replacedTone(p.defaultLeaveRingtoneUri, DEFAULT_LEAVE)?.let { out = out.copy(defaultLeaveRingtoneUri = uri(context, it), defaultLeaveRingtoneName = it.name) }
        if (isLegacy(p.defaultWakeRingtoneUri, p.defaultWakeRingtoneName)) {
            out = out.copy(defaultWakeRingtoneUri = uri(context, DEFAULT_WAKE), defaultWakeRingtoneName = DEFAULT_WAKE.name)
        }
        if (isLegacy(p.defaultLeaveRingtoneUri, p.defaultLeaveRingtoneName)) {
            out = out.copy(defaultLeaveRingtoneUri = uri(context, DEFAULT_LEAVE), defaultLeaveRingtoneName = DEFAULT_LEAVE.name)
        }
        return out
    }
}

/**
 * 淡入音量曲線：從幾乎無聲（-40 dB）開始，以 dB 等速上升，人耳聽起來是平均地慢慢變大。
 * MediaPlayer.setVolume 是線性倍率，直接線性增加的話開頭會「一下子就很大聲」。
 */
object VolumeFade {
    private const val FLOOR_DB = -40f
    const val TICK_MS = 100L

    /** fraction：0＝剛開始、1＝淡入結束 */
    fun gainAt(fraction: Float): Float {
        val p = fraction.coerceIn(0f, 1f)
        return if (p >= 1f) 1f else Math.pow(10.0, (FLOOR_DB * (1f - p) / 20f).toDouble()).toFloat()
    }
}

/**
 * 試聽播放器：和真正響鈴一樣用「鬧鐘音量」與循環播放，試聽聽到的就是響鈴時的效果。
 * 開頭 2 秒淡入，不會一按下去就很大聲。
 * 同一時間只播一首；再按一次或離開畫面就停止。
 */
object RingtonePreviewer {
    private var player: MediaPlayer? = null
    var playingUri: String? = null
        private set
    private const val PREVIEW_FADE_MS = 2000L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var fadeStart = 0L
    private val fadeTick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            val frac = (android.os.SystemClock.elapsedRealtime() - fadeStart).toFloat() / PREVIEW_FADE_MS
            val v = VolumeFade.gainAt(frac)
            runCatching { mp.setVolume(v, v) }
            if (frac < 1f) handler.postDelayed(this, VolumeFade.TICK_MS)
        }
    }

    fun play(context: Context, uriStr: String?): Boolean {
        stop()
        val uri = Uri.parse(BuiltInRingtones.resolve(context, uriStr) ?: BuiltInRingtones.uri(context, BuiltInRingtones.DEFAULT_WAKE))
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
            VolumeFade.gainAt(0f).let { mp.setVolume(it, it) }
            mp.start()
        }.isSuccess
        if (!ok) {
            runCatching { mp.release() }
            return false
        }
        player = mp
        playingUri = uri.toString()
        fadeStart = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(fadeTick)
        handler.postDelayed(fadeTick, VolumeFade.TICK_MS)
        return true
    }

    fun stop() {
        handler.removeCallbacks(fadeTick)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playingUri = null
    }
}
