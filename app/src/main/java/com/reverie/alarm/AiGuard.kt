package com.reverie.alarm

import android.content.Context
import java.time.LocalDate

// ============================================================================
// AI 防濫用（App 端）
//
// 注意：App 端的限制只能擋「一般使用者不小心用太多」與「AI 回傳奇怪的東西」。
// 會拆 APK 的人可以繞過這裡，直接打 Firebase AI Logic；擋這種人要靠伺服器端：
//   1. Firebase App Check（上架 Google Play 後改成「強制執行」）→ 見 AppSecurity
//   2. Firebase AI Logic 的「每位使用者速率限制」
//   3. 留在免費方案，或在 Google Cloud 設預算警示／API 配額上限
// ============================================================================
object AiGuard {
    /** 每則文字最多幾個字（排班的句子用不到這麼長） */
    const val MAX_INPUT_CHARS = 500
    /** 每天最多送幾次給 AI、其中最多幾張照片 */
    const val DAILY_MESSAGES = 30
    const val DAILY_PHOTOS = 10
    /** 兩次送出至少間隔幾秒 */
    const val COOLDOWN_SECONDS = 3
    /** 送給 AI 的對話最多帶幾輪（太舊的不帶，省流量也省額度） */
    const val MAX_TURNS = 10
    /** AI 回覆的長度上限 */
    const val MAX_OUTPUT_TOKENS = 4096
    const val MAX_REPLY_CHARS = 400
    const val MAX_SHIFT_NAME = 20
    /** 一次最多排幾天；只收「今天前 60 天～後 400 天」內的日期 */
    const val MAX_DAYS = 120
    private const val PAST_DAYS = 60L
    private const val FUTURE_DAYS = 400L

    private const val PREFS = "reverie_ai_quota"

    fun dateAllowed(date: LocalDate, today: LocalDate): Boolean =
        !date.isBefore(today.minusDays(PAST_DAYS)) && !date.isAfter(today.plusDays(FUTURE_DAYS))

    /** 名字會放進給 AI 的指示：只留一行、去掉引號與括號類符號、限制長度 */
    fun cleanName(raw: String?): String =
        raw.orEmpty().replace(Regex("[\\r\\n\\t\"'`「」『』{}\\[\\]<>]"), " ").replace(Regex("\\s+"), " ").trim().take(30)

    /** 只帶最近 MAX_TURNS 輪；照片只留最新一張（舊照片換成一句說明） */
    fun trimHistory(turns: List<AiTurn>): List<AiTurn> {
        var recent = turns.takeLast(MAX_TURNS)
        while (recent.isNotEmpty() && !recent.first().fromUser) recent = recent.drop(1) // 對話要從使用者開始
        val lastImage = recent.indexOfLast { it.image != null }
        return recent.mapIndexed { i, t ->
            if (t.image != null && i != lastImage) t.copy(image = null, text = t.text + "\n" + tr("（之前傳過一張班表照片）")) else t
        }
    }

    /** 還能不能送：可以回傳 null，不行回傳原因 */
    fun check(context: Context, hasPhoto: Boolean): String? {
        val q = load(context)
        val now = System.currentTimeMillis()
        if (now - q.lastAt < COOLDOWN_SECONDS * 1000L) return tr("送太快了，請等幾秒再送")
        if (q.messages >= DAILY_MESSAGES) return tr("今天的 AI 次數用完了（每天 {0} 次），明天會再恢復", DAILY_MESSAGES)
        if (hasPhoto && q.photos >= DAILY_PHOTOS) return tr("今天的照片辨識次數用完了（每天 {0} 張），明天會再恢復", DAILY_PHOTOS)
        return null
    }

    /** 送出時記一次 */
    fun record(context: Context, hasPhoto: Boolean) {
        val q = load(context)
        save(context, q.copy(messages = q.messages + 1, photos = q.photos + if (hasPhoto) 1 else 0, lastAt = System.currentTimeMillis()))
    }

    /** AI 沒有回應（服務忙碌、斷線）時退回這次，不算使用者的額度 */
    fun refund(context: Context, hasPhoto: Boolean) {
        val q = load(context)
        save(context, q.copy(messages = (q.messages - 1).coerceAtLeast(0), photos = (q.photos - if (hasPhoto) 1 else 0).coerceAtLeast(0)))
    }

    fun remaining(context: Context): Int = (DAILY_MESSAGES - load(context).messages).coerceAtLeast(0)

    private data class Quota(val day: String, val messages: Int, val photos: Int, val lastAt: Long)

    private fun load(context: Context): Quota {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val lastAt = sp.getLong("last_at", 0L)
        return if (sp.getString("day", null) != today) Quota(today, 0, 0, lastAt)
        else Quota(today, sp.getInt("messages", 0), sp.getInt("photos", 0), lastAt)
    }

    private fun save(context: Context, q: Quota) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("day", q.day).putInt("messages", q.messages).putInt("photos", q.photos).putLong("last_at", q.lastAt).apply()
    }
}
