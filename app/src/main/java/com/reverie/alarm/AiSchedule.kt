package com.reverie.alarm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

// ============================================================================
// AI 快速排班：Gemini 透過 Firebase AI Logic
//   - Gemini 的金鑰留在 Google／Firebase 那邊，App 裡沒有任何 AI 金鑰
//   - App 只讓登入 Google 帳號的人使用
//   - 只有使用者按下送出時，才會把文字或照片送到 Google
// ============================================================================

/** 送給 Gemini 的一輪對話 */
data class AiTurn(val fromUser: Boolean, val text: String, val image: Bitmap? = null)

data class AiReply(val reply: String, val person: String, val days: Map<LocalDate, String>, val raw: String)

class AiException(message: String) : Exception(message)

/** 記住使用者在班表上的名字（下次拍班表時提示 Gemini） */
object AiPrefs {
    private const val PREFS = "reverie_ai"
    fun rosterName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("roster_name", "").orEmpty()
    fun setRosterName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("roster_name", name.trim()).apply()
    }
}

object AiSchedule {
    /** Google 改版模型名稱時改這裡就好 */
    private const val FAST = "gemini-3.5-flash-lite" // 主要：快
    private const val SHARP = "gemini-3.8-flash"     // 備用

    /** 讀照片：轉正方向、縮到長邊 2048px（班表小字需要夠高的解析度） */
    suspend fun loadPhoto(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longSide / (sample * 2) >= 2048) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw AiException(tr("讀不到這張照片"))
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val scale = 2048f / maxOf(bmp.width, bmp.height)
        if (scale >= 1f && rotation == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }, true)
    }

    /** 系統指示：今天日期、日期對照表（避免算錯星期）、可用班別、回覆格式 */
    fun systemPrompt(templates: List<ShiftTemplate>, today: LocalDate, baseMonth: YearMonth, googleName: String?, rosterName: String): String {
        val week = listOf("一", "二", "三", "四", "五", "六", "日")
        fun monthTable(ym: YearMonth) = "${ym.year}-%02d：".format(ym.monthValue) +
            (1..ym.lengthOfMonth()).joinToString(" ") { d -> "$d(${week[ym.atDay(d).dayOfWeek.value - 1]})" }
        val months = listOf(YearMonth.from(today), baseMonth, baseMonth.plusMonths(1)).distinct().sorted()
        val shifts = templates.joinToString("\n") { t ->
            if (t.isOff) "- ${t.title}：整天休假" else "- ${t.title}：%02d:%02d–%02d:%02d".format(t.startTime.hour, t.startTime.minute, t.endTime.hour, t.endTime.minute)
        }
        val replyLang = if (I18n.isEn) "reply 欄位一律用英文（English）" else "用繁體中文"
        val who = buildList {
            if (!googleName.isNullOrBlank()) add("使用者的 Google 帳號名字是「$googleName」（可能和班表上的名字相同）")
            if (rosterName.isNotBlank()) add("使用者上次在班表上的名字是「$rosterName」")
        }.joinToString("；")
        return """
            你是「Reverie Alarm」輪班鬧鐘 App 的排班助手，$replyLang、口語、簡短地回覆（一到三句）。
            使用者會用文字，或傳紙本／公司公告班表的照片，告訴你要怎麼排班。
            今天是 $today（週${week[today.dayOfWeek.value - 1]}）。沒講月份時以 ${baseMonth.year} 年 ${baseMonth.monthValue} 月為準。
            日期與星期對照表：
            ${months.joinToString("\n            ") { monthTable(it) }}

            只能使用這些班別名稱：
            $shifts

            規則：
            1. 照片是很多人的班表時，找出使用者那一列（或那一欄）。$who
               找不到、或有好幾個人無法確定時不要猜：在 reply 列出班表上的名字問使用者是哪一位，days 照舊回傳目前清單。
            2. 班表上的代號（早、晚、夜、休、O、A/B/C 或時間 8-17）依照班表的圖例或上下班時間，對應到上面最接近的班別名稱。
            3. 看不清楚、空白或沒排的日子不要猜，不要放進 days，並在 reply 說明是哪幾天。
            4. days 永遠是「整段對話到目前為止」要排的完整清單：之前說過、沒被改掉的日子也要包含。
            5. 和排班無關的問題簡短回答，再把話題帶回排班。
            6. shift 一定要用上面的中文班別名稱（不要翻譯），只有 reply 依照上面的語言。
            7. 只回傳 JSON，不要其他文字：
            {"reply":"給使用者的回覆","person":"在班表上找到的名字，沒有就空字串","days":[{"date":"YYYY-MM-DD","shift":"班別名稱"}]}
        """.trimIndent()
    }

    /** 送出整段對話，回傳 Gemini 的 JSON 回覆 */
    suspend fun send(context: Context, system: String, turns: List<AiTurn>): AiReply {
        if (!CloudSync.isConfigured(context)) throw AiException(tr("Firebase 尚未設定，無法使用 AI"))
        var last: Exception? = null
        val hasImage = turns.any { it.image != null }
        // 實測：flash-lite 讀班表照片又快又準（約 7 秒）；3.8-flash 常常逾時，留作備用
        val models = listOf(FAST, SHARP)
        val timeout = com.google.firebase.ai.type.RequestOptions(timeoutInMillis = if (hasImage) 75_000L else 40_000L)
        var noThinking = false
        // 每個模型最多試兩次：忙碌（high demand、503）等一下再試；逾時或還是不行就換另一個模型
        for (name in models) for (attempt in 0..1) {
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = name,
                    generationConfig = generationConfig {
                        responseMimeType = "application/json"
                        temperature = 0.2f
                        // 排班不需要長考：限制思考量，回應快很多
                        if (!noThinking) thinkingConfig = com.google.firebase.ai.type.ThinkingConfig.Builder()
                            .setThinkingBudget(if (hasImage) 1024 else 256).build()
                    },
                    systemInstruction = content { text(system) },
                    requestOptions = timeout
                )
                val contents = turns.map { t ->
                    content(role = if (t.fromUser) "user" else "model") {
                        t.image?.let { image(it) }
                        text(t.text)
                    }
                }
                val text = model.generateContent(contents).text
                    ?: throw AiException(tr("Gemini 沒有回覆內容，請再試一次"))
                android.util.Log.i("AiSchedule", "Gemini ($name) 成功")
                return parse(text)
            } catch (e: AiException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
                android.util.Log.w("AiSchedule", "Gemini ($name #$attempt) 失敗：${e.javaClass.name} $msg")
                if (e is com.google.firebase.ai.type.ServiceDisabledException)
                    throw AiException(tr("AI 服務還沒在 Firebase 啟用（Firebase 主控台 → AI Logic → 開始使用）"))
                if (msg.contains("Permission denied", true))
                    throw AiException(tr("AI 服務權限不足（請到 Firebase 主控台的 AI Logic，確認已選擇 Gemini Developer API）"))
                last = e
                when {
                    msg.contains("not found", true) || msg.contains("404") -> break // 模型名稱不存在 → 直接換下一個
                    isTimeout(msg) || e is kotlinx.coroutines.TimeoutCancellationException -> break // 逾時 → 換另一個模型
                    noThinking == false && msg.contains("thinking", true) -> { noThinking = true; continue } // 不支援思考設定 → 拿掉再試
                    isBusy(msg) -> { if (attempt == 0) kotlinx.coroutines.delay(1500); continue } // 暫時忙碌 → 等一下再試
                    else -> throw AiException(friendly(msg))
                }
            }
        }
        throw AiException(friendly(last?.message ?: tr("找不到可用的 Gemini 模型")))
    }

    private fun isTimeout(msg: String) = msg.contains("Deadline", true) || msg.contains("timeout", true) || msg.contains("timed out", true)

    private fun isBusy(msg: String) = msg.contains("high demand", true) || msg.contains("overloaded", true) ||
        msg.contains("UNAVAILABLE") || msg.contains("503") || msg.contains("500") || msg.contains("try again later", true)

    private fun friendly(msg: String): String = when {
        isBusy(msg) || isTimeout(msg) -> tr("AI 現在太多人使用、回應太慢，請過一會兒再試")
        msg.contains("quota", true) || msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") -> tr("AI 的免費額度暫時用完了，請稍後再試")
        msg.contains("App Check", true) || msg.contains("AppCheck", true) -> tr("AI 服務被 App Check 擋下了（需要在 Firebase 調整 App Check 設定）")
        msg.contains("to be enabled", true) || msg.contains("has not been used", true) || msg.contains("SERVICE_DISABLED") ->
            tr("AI 服務還沒在 Firebase 啟用（Firebase 主控台 → AI Logic → 開始使用）")
        msg.contains("blocked", true) || msg.contains("SAFETY", true) -> tr("Gemini 拒絕處理這則內容，請換個說法或換張照片")
        msg.contains("Unable to resolve host", true) || msg.contains("network", true) || msg.contains("connect", true) ->
            tr("連不上 AI 服務，請確認網路連線")
        msg.contains("not found", true) -> tr("找不到 Gemini 模型，可能需要更新 App")
        else -> tr("AI 暫時無法使用，請稍後再試")
    }

    /** Gemini 的 JSON → 回覆文字與日期 → 班別 */
    fun parse(text: String): AiReply {
        val raw = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = runCatching { JSONObject(raw) }.getOrElse {
            val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
            if (s >= 0 && e > s) JSONObject(raw.substring(s, e + 1)) else throw AiException(tr("AI 的回覆看不懂，請再試一次"))
        }
        val days = LinkedHashMap<LocalDate, String>()
        val arr = o.optJSONArray("days") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val date = runCatching { LocalDate.parse(d.optString("date")) }.getOrNull() ?: continue
            val shift = d.optString("shift").trim()
            if (shift.isNotEmpty()) days[date] = shift
        }
        return AiReply(o.optString("reply").ifBlank { tr("整理好了，共 {0} 天。", days.size) }, o.optString("person"), days.toSortedMap(), raw)
    }
}
