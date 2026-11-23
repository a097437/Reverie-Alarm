package com.reverie.alarm

import android.content.Context
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.roundToInt

// ============================================================================
// 天氣資料
//  - 預報：Open-Meteo（免費、免金鑰，CC BY 4.0）
//  - 特報：國家災害防救科技中心 NCDR 災害示警公開資料（Public Domain）
// 只送出地區的經緯度，不會送出任何個人資料。結果存在手機上，沒網路時顯示上次的資料。
// ============================================================================

data class WeatherPlace(val name: String, val lat: Double, val lon: Double, val county: String, val isCurrent: Boolean = false)

/** 22 縣市（以縣市政府所在地為代表點） */
val TW_PLACES = listOf(
    WeatherPlace("基隆市", 25.128, 121.741, "基隆市"),
    WeatherPlace("臺北市", 25.038, 121.564, "臺北市"),
    WeatherPlace("新北市", 25.012, 121.465, "新北市"),
    WeatherPlace("桃園市", 24.994, 121.301, "桃園市"),
    WeatherPlace("新竹市", 24.804, 120.968, "新竹市"),
    WeatherPlace("新竹縣", 24.839, 121.004, "新竹縣"),
    WeatherPlace("苗栗縣", 24.560, 120.821, "苗栗縣"),
    WeatherPlace("臺中市", 24.148, 120.674, "臺中市"),
    WeatherPlace("彰化縣", 24.076, 120.542, "彰化縣"),
    WeatherPlace("南投縣", 23.910, 120.684, "南投縣"),
    WeatherPlace("雲林縣", 23.709, 120.431, "雲林縣"),
    WeatherPlace("嘉義市", 23.480, 120.449, "嘉義市"),
    WeatherPlace("嘉義縣", 23.459, 120.333, "嘉義縣"),
    WeatherPlace("臺南市", 22.999, 120.227, "臺南市"),
    WeatherPlace("高雄市", 22.627, 120.301, "高雄市"),
    WeatherPlace("屏東縣", 22.669, 120.488, "屏東縣"),
    WeatherPlace("宜蘭縣", 24.757, 121.753, "宜蘭縣"),
    WeatherPlace("花蓮縣", 23.977, 121.604, "花蓮縣"),
    WeatherPlace("臺東縣", 22.756, 121.144, "臺東縣"),
    WeatherPlace("澎湖縣", 23.571, 119.579, "澎湖縣"),
    WeatherPlace("金門縣", 24.437, 118.319, "金門縣"),
    WeatherPlace("連江縣", 26.160, 119.951, "連江縣")
)

/** 目前位置 → 最近的縣市（用來比對特報地區） */
fun nearestCounty(lat: Double, lon: Double): WeatherPlace = TW_PLACES.minBy { p ->
    val dLat = p.lat - lat
    val dLon = (p.lon - lon) * cos(Math.toRadians(lat))
    dLat * dLat + dLon * dLon
}

data class CurrentWx(val temp: Double, val feels: Double, val humidity: Int, val code: Int, val wind: Double, val isDay: Boolean)
data class HourWx(val time: LocalDateTime, val temp: Double, val pop: Int, val code: Int, val isDay: Boolean)
data class DayWx(
    val date: LocalDate, val code: Int, val tMax: Double, val tMin: Double, val pop: Int,
    val sunrise: LocalTime?, val sunset: LocalTime?, val uv: Double
)
data class WxAlert(val title: String, val summary: String, val source: String, val expires: LocalDateTime?)
data class WeatherData(
    val place: WeatherPlace,
    val fetchedAt: Long,
    val current: CurrentWx,
    val hours: List<HourWx>,
    val days: List<DayWx>,
    val alerts: List<WxAlert>
) {
    fun hourAt(t: LocalDateTime): HourWx? = hours.firstOrNull { it.time == t.withMinute(0).withSecond(0).withNano(0) }
    fun day(d: LocalDate): DayWx? = days.firstOrNull { it.date == d }
}

/** WMO 天氣代碼 → 中文與 Tabler 圖示 */
fun wxText(code: Int): String = when (code) {
    0 -> tr("晴天")
    1 -> tr("大致晴朗")
    2 -> tr("多雲時晴")
    3 -> tr("陰天")
    45, 48 -> tr("有霧")
    51, 53, 55, 56, 57 -> tr("毛毛雨")
    61 -> tr("小雨")
    63 -> tr("中雨")
    65, 66, 67 -> tr("大雨")
    71, 73, 75, 77, 85, 86 -> tr("下雪")
    80, 81 -> tr("陣雨")
    82 -> tr("強陣雨")
    95, 96, 99 -> tr("雷雨")
    else -> "—"
}

fun wxIcon(code: Int, isDay: Boolean = true): ImageVector = when (code) {
    0, 1 -> if (isDay) Tabler.Sun else Tabler.Moon
    2, 3 -> Tabler.Cloud
    45, 48 -> Tabler.CloudFog
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Tabler.CloudRain
    71, 73, 75, 77, 85, 86 -> Tabler.CloudSnow
    95, 96, 99 -> Tabler.CloudStorm
    else -> Tabler.Cloud
}

fun isRainy(code: Int) = code in 51..67 || code in 80..82 || code >= 95

object WeatherRepo {
    private const val PREFS = "reverie_weather"
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    var data by mutableStateOf<WeatherData?>(null)
        private set
    var place by mutableStateOf<WeatherPlace?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loaded = false

    /** 讀取上次選的地區與快取的天氣（App 啟動、響鈴畫面都會用） */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = sp.getString("place", null)?.let { runCatching { placeFrom(JSONObject(it)) }.getOrNull() }
        val raw = sp.getString("forecast", null)
        val alerts = sp.getString("alerts", null)
        val at = sp.getLong("fetchedAt", 0L)
        val p = place
        if (p != null && sp.getString("districtKey", null) == keyOf(p)) district = sp.getString("district", null)
        if (raw != null && p != null) {
            data = runCatching { parseForecast(p, at, JSONObject(raw), alertsFrom(alerts)) }.getOrNull()
        }
    }

    fun setPlace(context: Context, p: WeatherPlace, district: String? = null) {
        place = p
        this.district = district
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("place", placeJson(p).toString())
            .putString("district", district)
            .putString("districtKey", if (district != null) keyOf(p) else null)
            .apply()
    }

    /** 地區（例如「板橋區」）：用手機內建的地址查詢把經緯度轉成行政區，查不到就是 null */
    var district by mutableStateOf<String?>(null)
        private set

    private var triedKey: String? = null

    private fun keyOf(p: WeatherPlace) = String.format(java.util.Locale.US, "%.3f,%.3f", p.lat, p.lon)

    data class Geo(val county: String?, val district: String?)

    /** 經緯度 → 縣市＋鄉鎮市區（Android Geocoder；沒有網路或裝置不支援時回傳空值） */
    suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): Geo = withContext(Dispatchers.IO) {
        if (!android.location.Geocoder.isPresent()) return@withContext Geo(null, null)
        val addr = runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(context, java.util.Locale.TAIWAN).getFromLocation(lat, lon, 1)?.firstOrNull()
        }.getOrNull() ?: return@withContext Geo(null, null)
        val norm = { s: String? -> s?.replace('台', '臺')?.trim().orEmpty() }
        val line = norm(addr.getAddressLine(0))
        val fields = listOf(addr.adminArea, addr.subAdminArea, addr.locality, addr.subLocality).map(norm)
        val county = TW_PLACES.map { it.county }.firstOrNull { c -> fields.any { it == c } || line.contains(c) }
        val district = fields.firstOrNull { f ->
            f.isNotEmpty() && f != county && (f.endsWith("區") || f.endsWith("市") || f.endsWith("鎮") || f.endsWith("鄉"))
        } ?: county?.let { c ->
            Regex("${Regex.escape(c)}(\\S{1,4}?[區市鎮鄉])").find(line)?.groupValues?.get(1)
        }
        Geo(county, district)
    }

    private suspend fun ensureDistrict(context: Context, p: WeatherPlace) {
        if (district != null || triedKey == keyOf(p)) return
        triedKey = keyOf(p)
        val d =reverseGeocode(context, p.lat, p.lon).district ?: return
        if (place != p) return
        district = d
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("district", d).putString("districtKey", keyOf(p)).apply()
    }

    fun isStale(): Boolean {
        val d = data ?: return true
        return d.place != place || System.currentTimeMillis() - d.fetchedAt > MAX_AGE_MS
    }

    /** 重新抓預報與特報；失敗時保留舊資料並記下錯誤 */
    suspend fun refresh(context: Context, force: Boolean = false) {
        val p = place ?: return
        ensureDistrict(context, p)
        if (loading || (!force && !isStale())) return
        loading = true
        error = null
        try {
            val now = System.currentTimeMillis()
            val forecastRaw = withContext(Dispatchers.IO) { httpGet(forecastUrl(p)) }
            val alerts = withContext(Dispatchers.IO) { runCatching { fetchAlerts(p.county) }.getOrDefault(emptyList()) }
            val parsed = parseForecast(p, now, JSONObject(forecastRaw), alerts)
            data = parsed
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("forecast", forecastRaw)
                .putString("alerts", alertsJson(alerts).toString())
                .putLong("fetchedAt", now)
                .apply()
        } catch (e: Exception) {
            error = tr("無法取得天氣資料，請確認網路連線")
        } finally {
            loading = false
        }
    }

    // ---------------- 網路 ----------------

    private fun forecastUrl(p: WeatherPlace) = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=${String.format(java.util.Locale.US, "%.3f", p.lat)}&longitude=${String.format(java.util.Locale.US, "%.3f", p.lon)}" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
        "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max" +
        "&timezone=Asia%2FTaipei&forecast_days=8"

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "ReverieAlarm-Android")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** NCDR 示警：只留中央氣象署的天氣特報（不含地震）與人事行政總處的停班停課，且要提到所在縣市 */
    private fun fetchAlerts(county: String): List<WxAlert> {
        val xml = httpGet("https://alerts.ncdr.nat.gov.tw/RssAtomFeed.ashx")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        val out = mutableListOf<WxAlert>()
        var inEntry = false
        var inAuthor = false
        var title = ""; var summary = ""; var source = ""; var expires = ""
        var tag = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "entry") { inEntry = true; title = ""; summary = ""; source = ""; expires = "" }
                    if (tag == "author") inAuthor = true
                }
                XmlPullParser.TEXT -> if (inEntry) {
                    val t = parser.text ?: ""
                    when {
                        tag == "title" && !inAuthor -> title += t
                        tag == "summary" -> summary += t
                        tag == "name" && inAuthor -> source += t
                        tag == "cap:expires" -> expires += t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "author") inAuthor = false
                    if (parser.name == "entry") {
                        inEntry = false
                        val relevantSource = source.contains("氣象") || source.contains("人事行政")
                        if (relevantSource && !title.contains("地震") && mentionsCounty(summary + title, county)) {
                            out += WxAlert(title.trim(), summary.trim(), source.trim(), parseCapTime(expires))
                        }
                    }
                    tag = ""
                }
            }
            parser.next()
        }
        val now = LocalDateTime.now()
        // 同一種特報只留最新一則，且還沒過期
        return out.filter { it.expires == null || it.expires.isAfter(now) }.distinctBy { it.title }
    }

    private fun mentionsCounty(text: String, county: String): Boolean {
        val a = county.replace('臺', '台')
        val b = county.replace('台', '臺')
        return text.contains(a) || text.contains(b)
    }

    /** 「2026/11/20 下午 11:00:00」→ LocalDateTime */
    private fun parseCapTime(s: String): LocalDateTime? {
        val m = Regex("""(\d{4})/(\d{1,2})/(\d{1,2})\s*(上午|下午)?\s*(\d{1,2}):(\d{2})""").find(s) ?: return null
        val (y, mo, d, ap, h, mi) = m.destructured
        var hour = h.toInt() % 12
        if (ap == "下午") hour += 12 else if (ap.isEmpty()) hour = h.toInt()
        return runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), hour, mi.toInt()) }.getOrNull()
    }

    // ---------------- 解析與快取格式 ----------------

    private fun parseForecast(p: WeatherPlace, at: Long, o: JSONObject, alerts: List<WxAlert>): WeatherData {
        val c = o.getJSONObject("current")
        val current = CurrentWx(
            temp = c.getDouble("temperature_2m"),
            feels = c.getDouble("apparent_temperature"),
            humidity = c.getInt("relative_humidity_2m"),
            code = c.getInt("weather_code"),
            wind = c.getDouble("wind_speed_10m"),
            isDay = c.optInt("is_day", 1) == 1
        )
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = (0 until ht.length()).map { i ->
            HourWx(
                time = LocalDateTime.parse(ht.getString(i)),
                temp = h.getJSONArray("temperature_2m").optDouble(i, 0.0),
                pop = h.getJSONArray("precipitation_probability").optInt(i, 0),
                code = h.getJSONArray("weather_code").optInt(i, 0),
                isDay = h.optJSONArray("is_day")?.optInt(i, 1) != 0
            )
        }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            DayWx(
                date = LocalDate.parse(dt.getString(i)),
                code = d.getJSONArray("weather_code").optInt(i, 0),
                tMax = d.getJSONArray("temperature_2m_max").optDouble(i, 0.0),
                tMin = d.getJSONArray("temperature_2m_min").optDouble(i, 0.0),
                pop = d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                sunrise = d.getJSONArray("sunrise").optString(i).takeIf { it.length >= 16 }?.let { LocalDateTime.parse(it).toLocalTime() },
                sunset = d.getJSONArray("sunset").optString(i).takeIf { it.length >= 16 }?.let { LocalDateTime.parse(it).toLocalTime() },
                uv = d.getJSONArray("uv_index_max").optDouble(i, 0.0)
            )
        }
        return WeatherData(p, at, current, hours, days, alerts)
    }

    private fun placeJson(p: WeatherPlace) = JSONObject()
        .put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("county", p.county).put("current", p.isCurrent)

    private fun placeFrom(o: JSONObject) = WeatherPlace(
        o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getString("county"), o.optBoolean("current", false)
    )

    private fun alertsJson(list: List<WxAlert>) = JSONArray().apply {
        list.forEach { a ->
            put(JSONObject().put("t", a.title).put("s", a.summary).put("src", a.source).put("exp", a.expires?.toString() ?: ""))
        }
    }

    private fun alertsFrom(raw: String?): List<WxAlert> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WxAlert(o.getString("t"), o.getString("s"), o.getString("src"), o.getString("exp").takeIf { it.isNotBlank() }?.let(LocalDateTime::parse))
            }.filter { it.expires == null || it.expires.isAfter(LocalDateTime.now()) }
        }.getOrDefault(emptyList())
    }
}

/** 通勤時段要不要帶傘（給響鈴畫面與天氣頁用） */
fun umbrellaHint(data: WeatherData?, from: LocalDateTime, hours: Long = 2): String? {
    data ?: return null
    val window = (0..hours).mapNotNull { data.hourAt(from.plusHours(it)) }
    val maxPop = window.maxOfOrNull { it.pop } ?: return null
    return when {
        maxPop >= 50 -> tr("通勤時段降雨機率 {0}%，記得帶傘", maxPop)
        window.any { isRainy(it.code) } -> tr("通勤時段可能有短暫降雨，建議帶把傘")
        else -> null
    }
}

fun Double.tempText(): String = "${roundToInt()}°"
