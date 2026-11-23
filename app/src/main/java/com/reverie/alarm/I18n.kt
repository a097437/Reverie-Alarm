package com.reverie.alarm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// ============================================================================
// 多語言（繁體中文／English）
//   - 程式裡照常寫中文，包一層 tr("中文")；英文模式時查 I18nEn 的對照表，查不到就顯示中文
//   - 有變數的句子用 {0}、{1}：tr("已排好 {0} 天", n)
//   - 使用者的資料（班別名稱、鬧鐘標籤、鈴聲名稱、縣市）一律用中文存，只在顯示時翻譯
//   - lang 是 Compose 狀態，切換語言後畫面會立刻重畫
// ============================================================================

enum class AppLang { ZH, EN }

object I18n {
    private const val PREFS = "reverie_lang"
    private const val KEY = "language" // "system"／"zh"／"en"

    var lang by mutableStateOf(AppLang.ZH)
        private set
    /** 使用者在設定裡選的：system、zh、en */
    var choice by mutableStateOf("system")
        private set
    private var ready = false

    val isEn: Boolean get() = lang == AppLang.EN

    /** 每個入口（App、小工具、響鈴畫面、廣播）呼叫一次即可 */
    fun ensure(context: Context) {
        if (ready) return
        ready = true
        choice = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"
        lang = resolve(context, choice)
    }

    fun setChoice(context: Context, value: String) {
        choice = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
        lang = resolve(context, value)
        val app = context.applicationContext
        WidgetWork.post { ShiftWidgetProvider.refresh(app) }
    }

    /** 跟隨系統：中文（任何 zh）用繁體中文，其他語言都用英文 */
    private fun resolve(context: Context, value: String): AppLang = when (value) {
        "zh" -> AppLang.ZH
        "en" -> AppLang.EN
        else -> {
            val sys = context.resources.configuration.locales.get(0)?.language.orEmpty()
            if (sys.startsWith("zh") || sys.isEmpty()) AppLang.ZH else AppLang.EN
        }
    }

    // ---------------- 日期 ----------------

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val WD_ZH = listOf("一", "二", "三", "四", "五", "六", "日")
    private val WD_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val WD_EN_NARROW = listOf("M", "T", "W", "T", "F", "S", "S")

    fun monthName(m: Int): String = MONTHS[(m - 1).coerceIn(0, 11)]

    /** 「2026年 11月」／「Nov 2026」 */
    fun yearMonth(ym: YearMonth): String = if (isEn) "${monthName(ym.monthValue)} ${ym.year}" else "${ym.year}年 ${ym.monthValue}月"

    /** 「11月20日」／「Nov 20」 */
    fun monthDay(d: LocalDate): String = if (isEn) "${monthName(d.monthValue)} ${d.dayOfMonth}" else "${d.monthValue}月${d.dayOfMonth}日"

    /** 「週五」／「Fri」 */
    fun weekday(d: DayOfWeek): String = if (isEn) WD_EN[d.value - 1] else "週" + WD_ZH[d.value - 1]

    /** 日曆表頭用的一個字：「五」／「F」 */
    fun weekdayNarrow(d: DayOfWeek): String = if (isEn) WD_EN_NARROW[d.value - 1] else WD_ZH[d.value - 1]
}

/** 翻譯一段中文；英文模式查不到時顯示原文 */
fun tr(zh: String): String = if (I18n.lang == AppLang.EN) I18nEn.MAP[zh] ?: zh else zh

/** 有變數的句子：tr("已排好 {0} 天", n)；英文可以用 {0:mon} 把月份數字顯示成 Nov */
fun tr(zh: String, vararg args: Any?): String {
    var s = tr(zh)
    args.forEachIndexed { i, a ->
        if (s.contains("{$i:mon}")) s = s.replace("{$i:mon}", (a as? Int)?.let { I18n.monthName(it) } ?: a.toString())
        s = s.replace("{$i}", a?.toString() ?: "")
    }
    return s
}

/** 鬧鐘標籤是「晚班・強效起床喚醒」這種組合字，拆開來各自翻譯 */
fun trLabel(label: String): String = if (I18n.lang == AppLang.EN) label.split("・").joinToString(" · ") { tr(it.trim()) } else label

/** 日曆格子很窄：英文用短的班別名稱 */
fun shortShiftName(title: String): String = if (I18n.lang != AppLang.EN) title else when (title) {
    "晚班" -> "Eve"
    "休假" -> "Off"
    "中班" -> "Mid"
    else -> tr(title)
}

/** 天氣特報標題（來源只有中文）：常見的種類翻成英文，其他保留原文 */
fun trAlert(title: String): String {
    if (I18n.lang != AppLang.EN) return title
    val kinds = listOf(
        "颱風" to "Typhoon", "豪雨" to "Torrential rain", "大雨" to "Heavy rain", "強風" to "Strong wind", "陸上強風" to "Strong wind",
        "低溫" to "Low temperature", "高溫" to "High temperature", "濃霧" to "Dense fog", "大雷雨" to "Severe thunderstorm",
        "停班停課" to "Work & school closure", "停班" to "Work closure", "停課" to "School closure", "海上" to "Marine"
    )
    val hit = kinds.sortedByDescending { it.first.length }.firstOrNull { title.contains(it.first) } ?: return title
    return if (title.contains("停")) hit.second else "${hit.second} warning"
}
