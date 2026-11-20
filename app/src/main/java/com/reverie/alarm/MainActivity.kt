package com.reverie.alarm

import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.lifecycle.lifecycleScope
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ============================================================================
// 1. 預設色票常數 (作為初始值，實際渲染動態跟隨 MorandiTheme + 自訂色碼)
// ============================================================================
val AccentSlateBlue = Color(0xFF5B7C99)

// ============================================================================
// 2. 資料模型與班別色系定義
// ============================================================================
enum class ShiftType(
    val displayName: String,
    val badgeBg: Color,
    val badgeText: Color,
    val accentDot: Color
) {
    EARLY("早班", Color(0x33F59E0B), Color(0xFFFCD34D), Color(0xFFF59E0B)),
    EVENING("晚班", Color(0x336366F1), Color(0xFFA5B4FC), Color(0xFF6366F1)),
    NIGHT("夜班", Color(0x3314B8A6), Color(0xFF5EEAD4), Color(0xFF14B8A6)),
    OFF("休假", Color(0x3310B981), Color(0xFF6EE7B7), Color(0xFF10B981)),
    CUSTOM("日程", Color(0x333B82F6), Color(0xFF93C5FD), Color(0xFF3B82F6))
}

data class ShiftTemplate(
    val id: String,
    val title: String,
    val type: ShiftType,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val wakeOffsetMinutes: Long,
    val leaveOffsetMinutes: Long,
    val isOff: Boolean = false
)

data class ShiftAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uriString: String,
    val isImage: Boolean
)

data class ShiftEvent(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val title: String,
    val type: ShiftType,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val note: String = "",
    val attachments: List<ShiftAttachment> = emptyList()
)

enum class AlarmStage(val label: String) {
    WAKE("第一段・強效起床"),
    LEAVE("第二段・出門倒數"),
    CUSTOM("獨立日期鬧鐘")
}

data class ShiftAlarm(
    val id: String = UUID.randomUUID().toString(),
    val requestCode: Int = kotlin.random.Random.nextInt(1, Int.MAX_VALUE),
    val eventId: String? = null,
    val date: LocalDate,
    val triggerTime: LocalTime,
    val stage: AlarmStage,
    val label: String,
    val enabled: Boolean = true,
    val ringtoneUri: String? = null,
    val ringtoneName: String = "晨空漫步",
    val maxSnoozeCount: Int = 2,
    val snoozeMinutes: Int = 5
)

data class RingtoneEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uriString: String?,
    val isBuiltIn: Boolean = false
)

data class AppPreferences(
    val weekStartsOnMonday: Boolean = false,
    val fontScale: Float = 1.0f,
    val customAccentHex: String = "",
    val customGridBgHex: String = "",
    val defaultWakeRingtoneUri: String? = null,
    val defaultWakeRingtoneName: String = "晨空漫步",
    val defaultLeaveRingtoneUri: String? = null,
    val defaultLeaveRingtoneName: String = "清脆八音盒",
    // ---- 與網頁版一致的外觀設定 ----
    val colorMode: String = "dark",          // light / dark / system
    val contrast: String = "standard",       // soft / standard / high
    val surfaceStyle: String = "tinted",     // tinted / graphite / warm / crisp
    // ---- 鬧鐘規則 ----
    val defaultWakeOffset: Int = 90,
    val defaultLeaveOffset: Int = 40,
    val maxSnoozeCount: Int = 2,
    val noSnoozeOnLeave: Boolean = true,
    val fadeInVolume: Boolean = true,
    val autoHolidaySafeguard: Boolean = true,
    val autoCheckUpdate: Boolean = true,
    // ---- 介面動態效果 ----
    val animationsEnabled: Boolean = true,
    val animationSpeed: String = "normal"   // fast / normal / slow
)

data class PartnerSyncState(
    val googleAccountEmail: String? = null,
    val myShareCode: String = "REV-${(100000..999999).random()}",
    val subscribedPartnerCode: String = "",
    val partnerName: String = "伴侶 / 家人班表",
    val partnerOffDates: List<String> = emptyList(),
    // ---- 與網頁版一致的共享設定（舊資料沒有這些欄位時用預設值） ----
    val publicLinkEnabled: Boolean = true,
    val holidayCompare: Boolean = false,
    val subscriptions: List<PartnerSubscription>? = null
) {
    /** 6 位數共享邀請碼（網頁版 inviteCode） */
    val inviteCode: String get() = myShareCode.filter { it.isDigit() }.padStart(6, '0').takeLast(6)

    /** 已訂閱的班表（Firebase 即時同步；未登入時是空的） */
    @Suppress("UNUSED_PARAMETER")
    fun subscriptionList(today: LocalDate): List<PartnerSubscription> = CloudSync.subscriptions.toList()

    /** 共同休假對照開啟時，所有訂閱對象的休假日 */
    fun compareOffDates(today: LocalDate): List<String> =
        if (!holidayCompare) emptyList()
        else subscriptionList(today).flatMap { sub -> sub.shifts.orEmpty().filter { it.type == ShiftType.OFF.name }.map { it.date } }
}

data class PartnerShift(
    val date: String = "",
    val title: String = "",
    val type: String = "CUSTOM",
    val start: String = "",
    val end: String = ""
)

data class PartnerSubscription(
    val name: String = "",
    val code: String = "",
    val shifts: List<PartnerShift>? = null,
    /** 對方的 Firebase uid（雲端訂閱） */
    val uid: String = "",
    /** 對方已關閉共享，讀不到班表 */
    val unavailable: Boolean = false
)

/** 示範用的對方班表（沒有真的伺服器，和網頁版一樣用假資料） */
fun demoPartnerShifts(today: LocalDate, offDates: List<String>): List<PartnerShift> {
    val pattern = listOf(
        PartnerShift(title = "早班", type = ShiftType.EARLY.name, start = "09:00", end = "18:00"),
        PartnerShift(title = "早班", type = ShiftType.EARLY.name, start = "09:00", end = "18:00"),
        PartnerShift(title = "休假", type = ShiftType.OFF.name, start = "00:00", end = "23:59"),
        PartnerShift(title = "晚班", type = ShiftType.EVENING.name, start = "16:00", end = "00:00"),
        PartnerShift(title = "晚班", type = ShiftType.EVENING.name, start = "16:00", end = "00:00"),
        PartnerShift(title = "休假", type = ShiftType.OFF.name, start = "00:00", end = "23:59"),
        PartnerShift(title = "夜班", type = ShiftType.NIGHT.name, start = "00:00", end = "08:00")
    )
    return (-3..27).map { offset ->
        val d = today.plusDays(offset.toLong())
        val base = pattern[Math.floorMod(offset, pattern.size)]
        if (d.toString() in offDates) base.copy(date = d.toString(), title = "休假", type = ShiftType.OFF.name, start = "00:00", end = "23:59")
        else base.copy(date = d.toString())
    }
}

data class FullBackupPayload(
    val version: Int = 2,
    val exportedAt: String,
    val themeName: String,
    val events: List<SavedShiftRecord>,
    val alarms: List<SavedAlarmItem>,
    val templates: List<SavedShiftTemplate>,
    val preferences: AppPreferences,
    val ringtoneLibrary: List<RingtoneEntry>,
    val partnerSync: PartnerSyncState,
    val eventsV2: List<EventDto>? = null,
    val alarmsV2: List<AlarmDto>? = null
)

fun parseHexColorOrNull(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    return if (clean.length == 6 || clean.length == 8) {
        runCatching {
            val colorLong = clean.toLong(16)
            if (clean.length == 6) Color(colorLong or 0xFF000000) else Color(colorLong)
        }.getOrNull()
    } else null
}

// ============================================================================
// 3. 鬧鐘排程橋接：把畫面上的鬧鐘交給原生鬧鐘引擎（AlarmEngine.kt）
//    響鈴相關程式在 AlarmEngine.kt / AlarmReceivers.kt / AlarmRingService.kt
// ============================================================================
object NativeAlarmScheduler {
    /** 設定頁的鬧鐘規則（貪睡上限、出門禁止貪睡、漸進音量），每次儲存時更新 */
    @Volatile
    var rules: AppPreferences = AppPreferences()

    fun toSpec(alarm: ShiftAlarm): AlarmSpec {
        val r = rules
        val maxSnooze = when {
            alarm.snoozeMinutes <= 0 -> 0
            alarm.stage == AlarmStage.LEAVE && r.noSnoozeOnLeave -> 0
            else -> r.maxSnoozeCount
        }
        return AlarmSpec(
            key = "alarm-" + alarm.requestCode,
            triggerLocal = LocalDateTime.of(alarm.date, alarm.triggerTime).toString(),
            label = alarm.label,
            stage = alarm.stage.name,
            stageLabel = alarm.stage.label,
            ringtoneUri = alarm.ringtoneUri,
            snoozeMinutes = if (alarm.snoozeMinutes > 0) alarm.snoozeMinutes else 5,
            maxSnooze = maxSnooze,
            fadeIn = r.fadeInVolume
        )
    }

    fun scheduleAlarm(context: Context, alarm: ShiftAlarm) {
        if (alarm.enabled) {
            AlarmEngine.schedule(context, toSpec(alarm))
        } else {
            AlarmEngine.cancel(context, toSpec(alarm).key)
        }
    }

    fun cancelAlarm(context: Context, alarm: ShiftAlarm) {
        AlarmEngine.cancel(context, toSpec(alarm).key)
    }

    /** 讓系統排程與目前的鬧鐘清單完全一致（每次儲存資料時呼叫） */
    fun syncAll(context: Context, alarms: List<ShiftAlarm>) {
        AlarmEngine.syncAll(context, alarms.filter { it.enabled }.map { toSpec(it) })
    }

    /** 取消舊版（Gemini 版本）用 requestCode 排的系統鬧鐘，避免殘留 */
    fun cancelLegacy(context: Context, requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        AlarmEngine.alarmManager(context).cancel(pi)
        pi.cancel()
    }
}

// ============================================================================
// 4. MainActivity 進入點
// ============================================================================
class MainActivity : ComponentActivity() {
    private var appRecomposer: androidx.compose.runtime.Recomposer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.ensure(applicationContext)
        AppSecurity.installAppCheck(applicationContext) // 要在任何 Firebase 請求之前
        // 動畫速度由 App 內「介面動態效果」決定，不受手機「開發者選項 → 動畫縮放 = 關閉」影響
        // （系統縮放為 0 時，Compose 預設會讓所有動畫瞬間完成）
        val ctx = androidx.compose.ui.platform.AndroidUiDispatcher.Main + AppMotionDurationScale
        val recomposer = androidx.compose.runtime.Recomposer(ctx)
        appRecomposer = recomposer
        lifecycleScope.launch(ctx) { recomposer.runRecomposeAndApplyChanges() }
        val view = androidx.compose.ui.platform.ComposeView(this).apply { setParentCompositionContext(recomposer) }
        setContentView(view)
        view.setContent {
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    baseDensity.density,
                    UiScale.fontScale // 不跟隨手機系統字級，與網頁版大小一致；可在設定「舒適大字」放大
                )
            ) {
                ReverieAlarmApp()
            }
        }
    }

    override fun onDestroy() {
        appRecomposer?.cancel()
        appRecomposer = null
        super.onDestroy()
    }
}

// ============================================================================
// 5. 核心狀態管理 (整合完整原生功能：雙區手勢、懸浮膠囊、附件、鈴聲庫、共享、備份)
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReverieAlarmApp() {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    val repository = remember { AppRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val gson = remember { Gson() }

    val keyTemplates = remember { stringPreferencesKey("saved_shift_templates") }
    val keyTheme = remember { stringPreferencesKey("saved_morandi_theme") }
    val keyPrefs = remember { stringPreferencesKey("saved_app_preferences") }
    val keyRingtones = remember { stringPreferencesKey("saved_ringtone_library") }
    val keyAttachments = remember { stringPreferencesKey("saved_event_attachments") }
    val keyPartner = remember { stringPreferencesKey("saved_partner_sync") }
    val keyWeekMonday = remember { booleanPreferencesKey("saved_week_monday") }
    val keyFontScale = remember { floatPreferencesKey("saved_font_scale") }
    val keyEventsV2 = remember { stringPreferencesKey("v2_events") }
    val keyAlarmsV2 = remember { stringPreferencesKey("v2_alarms") }

    val runtimeLogs = remember { mutableStateListOf<String>() }
    fun logEvent(msg: String) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        runtimeLogs.add("[$stamp] $msg")
        if (runtimeLogs.size > 300) runtimeLogs.removeAt(0)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) { CloudSync.start(context.applicationContext) }
    LaunchedEffect(Unit) {
        WeatherRepo.load(context.applicationContext)
        WeatherRepo.refresh(context.applicationContext)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var themePreset by remember { mutableStateOf(MorandiTheme.SLATE_NIGHT) }
    var appPrefs by remember { mutableStateOf(AppPreferences()) }
    // 檢查更新：等資料載入、畫面穩定後再查（一天最多一次）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500)
        UpdateChecker.checkOnLaunch(context.applicationContext, appPrefs.autoCheckUpdate)
    }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val currentTheme = buildThemePalette(appPrefs, systemDark)
    SideEffect {
        UiScale.fontScale = appPrefs.fontScale
        Motion.enabled = appPrefs.animationsEnabled
        Motion.speedFactor = Motion.speedFactorOf(appPrefs.animationSpeed)
    }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showSafeguard by remember { mutableStateOf(false) }
    var safeguardDismissKey by remember { mutableStateOf("") }
    var partnerSync by remember { mutableStateOf(PartnerSyncState()) }
    var viewMode by remember { mutableStateOf(CalendarViewMode.MONTH) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabPagerState = rememberPagerState(initialPage = 0) { 5 }
    // 點底部分頁、搜尋跳轉等改 selectedTab → 平滑滑過去
    LaunchedEffect(selectedTab) {
        if (tabPagerState.currentPage != selectedTab || tabPagerState.currentPageOffsetFraction != 0f) {
            tabPagerState.animateScrollToPage(
                selectedTab,
                animationSpec = androidx.compose.animation.core.tween(340, easing = Motion.EnterEasing)
            )
        }
    }
    // 手指拖曳換分頁並停穩 → 更新 selectedTab
    LaunchedEffect(tabPagerState) {
        snapshotFlow { tabPagerState.settledPage }.collect { selectedTab = it }
    }
    var currentYearMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    var quickCapsuleDate by remember { mutableStateOf<LocalDate?>(null) }
    var batchMode by remember { mutableStateOf(false) }
    val batchSelectedDates = remember { mutableStateListOf<LocalDate>() }
    var showEditSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showAddAlarmDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val aiChat = remember { AiChatState() }
    var showAlarmCheck by remember { mutableStateOf(false) }
    var showPreferencesDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showSharingPage by remember { mutableStateOf(false) }
    var showWidgetPage by remember { mutableStateOf(false) }
    var lightboxImages by remember { mutableStateOf<List<ShiftAttachment>>(emptyList()) }
    var lightboxIndex by remember { mutableIntStateOf(0) }
    var editingTemplateTarget by remember { mutableStateOf<ShiftTemplate?>(null) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    // 檢視日曆來源：null = 我的日曆；否則為訂閱對象的邀請碼（唯讀）
    var viewingOwnerCode by remember { mutableStateOf<String?>(null) }

    val defaultTemplates = remember {
        listOf(
            ShiftTemplate("tpl-early", tr("早班"), ShiftType.EARLY, LocalTime.of(8, 0), LocalTime.of(17, 0), 90, 40),
            ShiftTemplate("tpl-evening", tr("晚班"), ShiftType.EVENING, LocalTime.of(16, 0), LocalTime.of(0, 0), 120, 45),
            ShiftTemplate("tpl-night", tr("夜班"), ShiftType.NIGHT, LocalTime.of(0, 0), LocalTime.of(8, 0), 90, 40),
            ShiftTemplate("tpl-off", tr("休假"), ShiftType.OFF, LocalTime.of(0, 0), LocalTime.of(23, 59), 0, 0, isOff = true)
        )
    }

    val templates = remember { mutableStateListOf<ShiftTemplate>().apply { addAll(defaultTemplates) } }
    val events = remember { mutableStateListOf<ShiftEvent>() }
    val alarms = remember { mutableStateListOf<ShiftAlarm>() }

    // 日曆上實際顯示的排班：唯讀檢視別人的班表時換成對方的資料
    val viewingSubscription = viewingOwnerCode?.let { code -> partnerSync.subscriptionList(today).firstOrNull { it.code == code } }
    val displayEvents: List<ShiftEvent> = if (viewingSubscription == null) events else remember(viewingSubscription) {
        viewingSubscription.shifts.orEmpty().mapNotNull { sh ->
            runCatching {
                ShiftEvent(
                    id = "shared-${sh.date}",
                    date = LocalDate.parse(sh.date),
                    title = sh.title,
                    type = ShiftType.valueOf(sh.type),
                    startTime = LocalTime.parse(sh.start),
                    endTime = LocalTime.parse(sh.end)
                )
            }.getOrNull()
        }
    }
    val ringtoneLibrary = remember {
        mutableStateListOf<RingtoneEntry>().apply { addAll(BuiltInRingtones.entries(context)) }
    }

    // 同步寫入 DataStore 本機硬碟
    fun persistAllData() {
        val currentEvents = events.toList()
        val currentAlarms = alarms.toList()
        val currentTemplates = templates.toList()
        val themeName = themePreset.name
        val prefsSnapshot = appPrefs
        val ringtonesSnapshot = ringtoneLibrary.toList()
        val partnerSnapshot = partnerSync
        val attachmentsMap = currentEvents.associate { it.date.toString() to it.attachments }

        coroutineScope.launch {
            val savedRecords = currentEvents.map { ev ->
                SavedShiftRecord(
                    dateStr = ev.date.toString(),
                    templateId = ev.type.name,
                    title = ev.title,
                    startTime = ev.startTime.toString(),
                    endTime = ev.endTime.toString(),
                    colorHex = 0L,
                    isOvernight = ev.endTime.isBefore(ev.startTime),
                    note = ev.note
                )
            }
            repository.saveShiftRecords(savedRecords)

            val savedAlarms = currentAlarms.map { al ->
                SavedAlarmItem(
                    id = al.requestCode,
                    label = "${al.date}|${al.stage.name}|${al.label}",
                    hour = al.triggerTime.hour,
                    minute = al.triggerTime.minute,
                    enabled = al.enabled,
                    ringtoneUri = al.ringtoneUri,
                    ringtoneName = al.ringtoneName,
                    maxSnoozeCount = if (al.stage == AlarmStage.LEAVE) 0 else al.maxSnoozeCount
                )
            }
            repository.saveAlarms(savedAlarms)
            NativeAlarmScheduler.rules = prefsSnapshot
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                NativeAlarmScheduler.syncAll(context.applicationContext, currentAlarms)
                ShiftWidgetProvider.saveSnapshot(context.applicationContext, currentEvents)
            }
            // 已登入就把公開欄位（日期、班別、時間）同步到雲端
            CloudSync.schedulePush(currentEvents)

            val savedTplList = currentTemplates.map { tpl ->
                SavedShiftTemplate(
                    id = tpl.id,
                    name = "${tpl.type.name}|${tpl.title}|${tpl.wakeOffsetMinutes}|${tpl.leaveOffsetMinutes}",
                    startTime = tpl.startTime.toString(),
                    endTime = tpl.endTime.toString(),
                    colorHex = 0L,
                    isOvernight = tpl.isOff
                )
            }
            context.dataStore.edit { prefs ->
                prefs[keyTemplates] = gson.toJson(savedTplList)
                prefs[keyTheme] = themeName
                prefs[keyPrefs] = gson.toJson(prefsSnapshot)
                prefs[keyRingtones] = gson.toJson(ringtonesSnapshot)
                prefs[keyAttachments] = gson.toJson(attachmentsMap)
                prefs[keyPartner] = gson.toJson(partnerSnapshot)
                prefs[keyWeekMonday] = prefsSnapshot.weekStartsOnMonday
                prefs[keyFontScale] = prefsSnapshot.fontScale
                prefs[keyEventsV2] = gson.toJson(currentEvents.map { it.toDto() })
                prefs[keyAlarmsV2] = gson.toJson(currentAlarms.map { it.toDto() })
            }
        }
    }

    fun addStageAlarmsFor(newEvent: ShiftEvent, wakeOffsetMinutes: Long, leaveOffsetMinutes: Long) {
        val date = newEvent.date
        val shiftTitle = newEvent.title
        run {
            val shiftStartDt = LocalDateTime.of(date, newEvent.startTime)
            if (wakeOffsetMinutes > 0) {
                val wakeDt = shiftStartDt.minusMinutes(wakeOffsetMinutes)
                val isPrevDayNight = wakeDt.toLocalDate().isBefore(date)
                val wakeLabel = if (isPrevDayNight) {
                    "${shiftTitle}・強效起床（明日夜班預備）"
                } else {
                    "${shiftTitle}・強效起床喚醒"
                }
                val wakeAlarm = ShiftAlarm(
                    eventId = newEvent.id,
                    date = wakeDt.toLocalDate(),
                    triggerTime = wakeDt.toLocalTime(),
                    stage = AlarmStage.WAKE,
                    label = wakeLabel,
                    enabled = true,
                    ringtoneUri = appPrefs.defaultWakeRingtoneUri,
                    ringtoneName = appPrefs.defaultWakeRingtoneName,
                    maxSnoozeCount = 2
                )
                alarms.add(wakeAlarm)
                NativeAlarmScheduler.scheduleAlarm(context, wakeAlarm)
            }
            if (leaveOffsetMinutes > 0) {
                val leaveDt = shiftStartDt.minusMinutes(leaveOffsetMinutes)
                val leaveAlarm = ShiftAlarm(
                    eventId = newEvent.id,
                    date = leaveDt.toLocalDate(),
                    triggerTime = leaveDt.toLocalTime(),
                    stage = AlarmStage.LEAVE,
                    label = "${shiftTitle}・出門倒數提醒（禁止貪睡）",
                    enabled = true,
                    ringtoneUri = appPrefs.defaultLeaveRingtoneUri,
                    ringtoneName = appPrefs.defaultLeaveRingtoneName,
                    maxSnoozeCount = 0
                )
                alarms.add(leaveAlarm)
                NativeAlarmScheduler.scheduleAlarm(context, leaveAlarm)
            }
        }
    }

    /**
     * 儲存某天的排班（網頁版 handleSaveDateEditModal / createShiftFromTemplate）
     * regenerateAlarms = true：刪掉這天班別的舊鬧鐘，依提前分鐘數重新建立起床＋出門兩段鬧鐘
     * regenerateAlarms = false：只改班別內容，原本的鬧鐘保持不動
     */
    fun upsertShift(
        date: LocalDate,
        title: String,
        type: ShiftType,
        start: LocalTime,
        end: LocalTime,
        wakeOffset: Long,
        leaveOffset: Long,
        note: String,
        attachments: List<ShiftAttachment>,
        regenerateAlarms: Boolean,
        autoSave: Boolean = true
    ) {
        val existingIdx = events.indexOfFirst { it.date == date }
        if (!regenerateAlarms && existingIdx != -1) {
            events[existingIdx] = events[existingIdx].copy(
                title = title, type = type, startTime = start, endTime = end, note = note, attachments = attachments
            )
            logEvent(tr("排班修改：{0} -> {1}（鬧鐘不變）", date, title))
            if (autoSave) persistAllData()
            return
        }
        val oldEventIds = events.filter { it.date == date }.map { it.id }
        alarms.filter { it.eventId in oldEventIds }.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
        events.removeAll { it.date == date }
        alarms.removeAll { it.eventId in oldEventIds }

        val newEvent = ShiftEvent(
            date = date, title = title, type = type, startTime = start, endTime = end,
            note = note, attachments = attachments
        )
        events.add(newEvent)
        if (regenerateAlarms && type != ShiftType.OFF) {
            addStageAlarmsFor(newEvent, wakeOffset, leaveOffset)
        }
        logEvent(tr("排班儲存：{0} -> {1}", date, title))
        if (autoSave) persistAllData()
    }

    fun applyTemplateToDate(
        date: LocalDate,
        tpl: ShiftTemplate,
        customNote: String? = null,
        customAttachments: List<ShiftAttachment>? = null,
        autoSave: Boolean = true
    ) {
        val existingEv = events.firstOrNull { it.date == date }
        upsertShift(
            date = date,
            title = tpl.title,
            type = if (tpl.isOff) ShiftType.OFF else tpl.type,
            start = tpl.startTime,
            end = tpl.endTime,
            wakeOffset = tpl.wakeOffsetMinutes,
            leaveOffset = tpl.leaveOffsetMinutes,
            note = customNote ?: existingEv?.note.orEmpty(),
            attachments = customAttachments ?: existingEv?.attachments.orEmpty(),
            regenerateAlarms = true,
            autoSave = autoSave
        )
    }

    /** AI 快速排班：只改有變動的日子，排好後跳到日曆的第一天 */
    fun applySchedulePlan(plan: Map<LocalDate, ShiftTemplate>) {
        val changed = plan.filter { (d, tpl) -> events.firstOrNull { it.date == d }?.title != tpl.title }
        changed.forEach { (d, tpl) -> applyTemplateToDate(d, tpl, autoSave = false) }
        persistAllData()
        changed.keys.minOrNull()?.let { first ->
            currentYearMonth = YearMonth.from(first)
            selectedDate = first
        }
        selectedTab = 0
        AppToast.show(tr("✅ 已排好 {0} 天的班與鬧鐘", changed.size))
    }

    /** 改單一鬧鐘（時間、開關）並重新排程 */
    fun updateAlarm(alarm: ShiftAlarm, transform: (ShiftAlarm) -> ShiftAlarm) {
        val idx = alarms.indexOfFirst { it.id == alarm.id }
        if (idx == -1) return
        val updated = transform(alarms[idx])
        alarms[idx] = updated
        NativeAlarmScheduler.scheduleAlarm(context, updated)
        persistAllData()
    }

    fun deleteAlarm(alarm: ShiftAlarm) {
        NativeAlarmScheduler.cancelAlarm(context, alarm)
        alarms.removeAll { it.id == alarm.id }
        persistAllData()
    }

    fun importTaiwanHolidays2026() {
        val holidays2026 = listOf(
            LocalDate.of(2026, 1, 1) to "元旦國定休假",
            LocalDate.of(2026, 2, 16) to "除夕春節休假",
            LocalDate.of(2026, 2, 17) to "大年初一休假",
            LocalDate.of(2026, 2, 18) to "大年初二休假",
            LocalDate.of(2026, 2, 19) to "大年初三休假",
            LocalDate.of(2026, 2, 28) to "和平紀念日休假",
            LocalDate.of(2026, 4, 4) to "兒童節休假",
            LocalDate.of(2026, 4, 5) to "清明節休假",
            LocalDate.of(2026, 5, 1) to "勞動節休假",
            LocalDate.of(2026, 6, 19) to "端午節休假",
            LocalDate.of(2026, 9, 25) to "中秋節休假",
            LocalDate.of(2026, 10, 10) to "國慶日休假"
        )
        val offTpl = templates.firstOrNull { it.isOff } ?: defaultTemplates[3]
        var count = 0
        holidays2026.forEach { (hDate, hLabel) ->
            if (events.none { it.date == hDate }) {
                applyTemplateToDate(hDate, offTpl, hLabel, autoSave = false)
                count++
            }
        }
        persistAllData()
        logEvent(tr("匯入國定假日：新增 {0} 筆", count))
        AppToast.show(tr("🇹🇼 已匯入 2026 台灣國定假日（新增 {0} 筆，不覆蓋既有班表）", count))
    }

    // 備份與還原 Launcher
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val payload = FullBackupPayload(
                    exportedAt = LocalDateTime.now().toString(),
                    themeName = themePreset.name,
                    events = events.map { ev ->
                        SavedShiftRecord(
                            dateStr = ev.date.toString(),
                            templateId = ev.type.name,
                            title = ev.title,
                            startTime = ev.startTime.toString(),
                            endTime = ev.endTime.toString(),
                            colorHex = 0L,
                            isOvernight = ev.endTime.isBefore(ev.startTime),
                            note = ev.note
                        )
                    },
                    alarms = alarms.map { al ->
                        SavedAlarmItem(
                            id = al.requestCode,
                            label = "${al.date}|${al.stage.name}|${al.label}",
                            hour = al.triggerTime.hour,
                            minute = al.triggerTime.minute,
                            enabled = al.enabled,
                            ringtoneUri = al.ringtoneUri,
                            ringtoneName = al.ringtoneName,
                            maxSnoozeCount = al.maxSnoozeCount
                        )
                    },
                    templates = templates.map { tpl ->
                        SavedShiftTemplate(
                            id = tpl.id,
                            name = "${tpl.type.name}|${tpl.title}|${tpl.wakeOffsetMinutes}|${tpl.leaveOffsetMinutes}",
                            startTime = tpl.startTime.toString(),
                            endTime = tpl.endTime.toString(),
                            colorHex = 0L,
                            isOvernight = tpl.isOff
                        )
                    },
                    preferences = appPrefs,
                    ringtoneLibrary = ringtoneLibrary.toList(),
                    partnerSync = partnerSync,
                    eventsV2 = events.map { it.toDto() },
                    alarmsV2 = alarms.map { it.toDto() }
                )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(gson.toJson(payload).toByteArray(Charsets.UTF_8))
                }
                logEvent(tr("成功匯出 JSON 完整備份"))
                AppToast.show(tr("✅ JSON 完整備份已匯出"))
            }.onFailure {
                AppToast.show(tr("❌ 匯出失敗：{0}", it.message))
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                } ?: error(tr("無法讀取檔案"))
                val payload = gson.fromJson(content, FullBackupPayload::class.java)
                runCatching { MorandiTheme.valueOf(payload.themeName) }.getOrNull()?.let { themePreset = it }
                appPrefs = payload.preferences
                partnerSync = payload.partnerSync

                if (payload.templates.isNotEmpty()) {
                    templates.clear()
                    payload.templates.forEach { item ->
                        val parts = item.name.split("|", limit = 4)
                        if (parts.size == 4) {
                            templates.add(
                                ShiftTemplate(
                                    id = item.id,
                                    title = parts[1],
                                    type = runCatching { ShiftType.valueOf(parts[0]) }.getOrDefault(ShiftType.CUSTOM),
                                    startTime = LocalTime.parse(item.startTime),
                                    endTime = LocalTime.parse(item.endTime),
                                    wakeOffsetMinutes = parts[2].toLongOrNull() ?: 90L,
                                    leaveOffsetMinutes = parts[3].toLongOrNull() ?: 40L,
                                    isOff = item.isOvernight
                                )
                            )
                        }
                    }
                }

                alarms.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
                events.clear()
                payload.events.forEach { rec ->
                    events.add(
                        ShiftEvent(
                            date = LocalDate.parse(rec.dateStr),
                            title = rec.title,
                            type = runCatching { ShiftType.valueOf(rec.templateId) }.getOrDefault(ShiftType.CUSTOM),
                            startTime = LocalTime.parse(rec.startTime),
                            endTime = LocalTime.parse(rec.endTime),
                            note = rec.note
                        )
                    )
                }

                alarms.clear()
                payload.alarms.forEach { item ->
                    val parts = item.label.split("|", limit = 3)
                    if (parts.size == 3) {
                        val stage = runCatching { AlarmStage.valueOf(parts[1]) }.getOrDefault(AlarmStage.CUSTOM)
                        val restored = ShiftAlarm(
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
                        alarms.add(restored)
                        NativeAlarmScheduler.scheduleAlarm(context, restored)
                    }
                }

                val restoredEventsV2 = payload.eventsV2?.mapNotNull { it.toModel() }
                val restoredAlarmsV2 = payload.alarmsV2?.mapNotNull { it.toModel() }
                if (restoredEventsV2 != null && restoredAlarmsV2 != null) {
                    alarms.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
                    events.clear()
                    events.addAll(restoredEventsV2)
                    alarms.clear()
                    alarms.addAll(restoredAlarmsV2)
                }

                if (payload.ringtoneLibrary.isNotEmpty()) {
                    ringtoneLibrary.clear()
                    ringtoneLibrary.addAll(payload.ringtoneLibrary)
                }
                // 舊備份裡的系統音效設定換成新的內建鈴聲
                val mergedRt = BuiltInRingtones.mergeLibrary(context, ringtoneLibrary.toList())
                ringtoneLibrary.clear()
                ringtoneLibrary.addAll(mergedRt)
                appPrefs = BuiltInRingtones.migratePrefs(context, appPrefs)
                for (i in alarms.indices) alarms[i] = BuiltInRingtones.migrateAlarm(context, alarms[i])

                persistAllData()
                logEvent(tr("成功從 JSON 還原 {0} 筆排班與 {1} 個鬧鐘", events.size, alarms.size))
                AppToast.show(tr("✅ 備份還原完成！"))
            }.onFailure {
                AppToast.show(tr("❌ 還原失敗：格式不相容"))
            }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val report = buildString {
                    appendLine(tr("=== Reverie Alarm 原生運行診斷日誌 ==="))
                    appendLine(tr("匯出時間：{0}", LocalDateTime.now()))
                    appendLine(tr("總排班筆數：{0} | 總鬧鐘數：{1}", events.size, alarms.size))
                    appendLine(tr("目前主題：{0} | 週一起始：{1}", themePreset.name, appPrefs.weekStartsOnMonday))
                    appendLine("----------------------------------------")
                    runtimeLogs.forEach { appendLine(it) }
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                }
                AppToast.show(tr("📄 診斷日誌 .txt 已匯出"))
            }
        }
    }

    // 啟動時從 DataStore 載入所有資料
    LaunchedEffect(Unit) {
        logEvent(tr("Reverie Alarm 原生核心啟動"))
        AlarmEngine.ensureChannel(context)
        val prefs = context.dataStore.data.first()
        prefs[keyTheme]?.let { savedThemeName ->
            runCatching { MorandiTheme.valueOf(savedThemeName) }.getOrNull()?.let { themePreset = it }
        }
        prefs[keyPrefs]?.let { pJson ->
            runCatching { gson.fromJson(pJson, AppPreferences::class.java) }.getOrNull()?.let {
                // 舊版沒有 colorMode：把原本選的莫蘭迪主題轉成新設定
                appPrefs = if (pJson.contains("\"colorMode\"")) it else migrateLegacyTheme(it, prefs[keyTheme])
            }
        }
        prefs[keyPartner]?.let { partnerJson ->
            runCatching { gson.fromJson(partnerJson, PartnerSyncState::class.java) }.getOrNull()?.let { partnerSync = it }
        }
        prefs[keyRingtones]?.let { rtJson ->
            val parsedRt = runCatching { gson.fromJson(rtJson, Array<RingtoneEntry>::class.java).toList() }.getOrDefault(emptyList())
            if (parsedRt.isNotEmpty()) {
                ringtoneLibrary.clear()
                ringtoneLibrary.addAll(parsedRt)
            }
        }
        prefs[keyTemplates]?.let { tplJson ->
            val parsedList = runCatching {
                gson.fromJson(tplJson, Array<SavedShiftTemplate>::class.java).toList()
            }.getOrDefault(emptyList())
            if (parsedList.isNotEmpty()) {
                templates.clear()
                parsedList.forEach { item ->
                    val parts = item.name.split("|", limit = 4)
                    if (parts.size == 4) {
                        templates.add(
                            ShiftTemplate(
                                id = item.id,
                                title = parts[1],
                                type = runCatching { ShiftType.valueOf(parts[0]) }.getOrDefault(ShiftType.CUSTOM),
                                startTime = LocalTime.parse(item.startTime),
                                endTime = LocalTime.parse(item.endTime),
                                wakeOffsetMinutes = parts[2].toLongOrNull() ?: 90L,
                                leaveOffsetMinutes = parts[3].toLongOrNull() ?: 40L,
                                isOff = item.isOvernight
                            )
                        )
                    }
                }
            }
        }

        val savedAttMap: Map<String, List<ShiftAttachment>> = prefs[keyAttachments]?.let { attJson ->
            runCatching {
                val rawMap = gson.fromJson(attJson, Map::class.java) as? Map<*, *>
                rawMap?.mapNotNull { (k, v) ->
                    val keyStr = k as? String ?: return@mapNotNull null
                    val arr = gson.fromJson(gson.toJson(v), Array<ShiftAttachment>::class.java).toList()
                    keyStr to arr
                }?.toMap()
            }.getOrNull()
        } ?: emptyMap()

        val v2Events: List<ShiftEvent>? = prefs[keyEventsV2]?.let { raw ->
            runCatching { gson.fromJson(raw, Array<EventDto>::class.java).mapNotNull { it.toModel() } }.getOrNull()
        }
        val v2Alarms: List<ShiftAlarm>? = prefs[keyAlarmsV2]?.let { raw ->
            runCatching { gson.fromJson(raw, Array<AlarmDto>::class.java).mapNotNull { it.toModel() } }.getOrNull()
        }
        val savedRecords = repository.shiftRecordsFlow.first()
        val savedAlarms = repository.alarmsFlow.first()

        if (v2Events != null && v2Alarms != null) {
            // v2 完整格式：保留排班與鬧鐘的關聯
            events.clear()
            events.addAll(v2Events)
            alarms.clear()
            alarms.addAll(v2Alarms)
            persistAllData()
        } else if (savedRecords.isNotEmpty()) {
            events.clear()
            savedRecords.forEach { rec ->
                val parsedType = runCatching { ShiftType.valueOf(rec.templateId) }.getOrDefault(ShiftType.CUSTOM)
                events.add(
                    ShiftEvent(
                        date = LocalDate.parse(rec.dateStr),
                        title = rec.title,
                        type = parsedType,
                        startTime = LocalTime.parse(rec.startTime),
                        endTime = LocalTime.parse(rec.endTime),
                        note = rec.note,
                        attachments = savedAttMap[rec.dateStr].orEmpty()
                    )
                )
            }
            alarms.clear()
            savedAlarms.forEach { item ->
                val parts = item.label.split("|", limit = 3)
                if (parts.size == 3) {
                    val parsedDate = runCatching { LocalDate.parse(parts[0]) }.getOrDefault(today)
                    val parsedStage = runCatching { AlarmStage.valueOf(parts[1]) }.getOrDefault(AlarmStage.CUSTOM)
                    alarms.add(
                        ShiftAlarm(
                            requestCode = item.id,
                            date = parsedDate,
                            triggerTime = LocalTime.of(item.hour, item.minute),
                            stage = parsedStage,
                            label = parts[2],
                            enabled = item.enabled,
                            ringtoneUri = item.ringtoneUri,
                            ringtoneName = item.ringtoneName,
                            maxSnoozeCount = if (parsedStage == AlarmStage.LEAVE) 0 else item.maxSnoozeCount
                        )
                    )
                }
            }
            // 舊版的鬧鐘編號可能重複（同一毫秒建立的起床＋出門鬧鐘會互相覆蓋），在這裡重新編號
            alarms.forEach { NativeAlarmScheduler.cancelLegacy(context, it.requestCode) }
            val seenCodes = mutableSetOf<Int>()
            for (i in alarms.indices) {
                val a = alarms[i]
                if (!seenCodes.add(a.requestCode)) {
                    val fresh = a.copy(requestCode = kotlin.random.Random.nextInt(1, Int.MAX_VALUE))
                    alarms[i] = fresh
                    seenCodes.add(fresh.requestCode)
                }
            }
            persistAllData()
        } else if (events.isEmpty()) {
            applyTemplateToDate(
                today,
                templates[0],
                tr("【交接事項】確認早班交接本簽核\n【攜帶物品】員工識別證、置物櫃鑰匙"),
                autoSave = false
            )
            applyTemplateToDate(today.plusDays(1), templates[3], autoSave = false)
            applyTemplateToDate(today.plusDays(2), templates[1], autoSave = false)
            applyTemplateToDate(today.plusDays(3), templates[2], autoSave = false)
            applyTemplateToDate(today.plusDays(4), templates[3], autoSave = false)
            persistAllData()
        }

        // 鈴聲換新：移除舊版借用手機系統音效的項目，舊設定自動改成新的內建合成鈴聲
        val mergedRingtones = BuiltInRingtones.mergeLibrary(context, ringtoneLibrary.toList())
        ringtoneLibrary.clear()
        ringtoneLibrary.addAll(mergedRingtones)
        appPrefs = BuiltInRingtones.migratePrefs(context, appPrefs)
        for (i in alarms.indices) alarms[i] = BuiltInRingtones.migrateAlarm(context, alarms[i])
        persistAllData()
    }

    // 動態計算主題色（支援自訂強調色碼與日曆底色）
    // 換分頁、換月份、換檢視模式、開啟批次模式時收起「編輯」膠囊
    LaunchedEffect(selectedTab, currentYearMonth, viewMode, batchMode) { quickCapsuleDate = null }

    // 返回鍵：先收起開著的東西，再回到排班日曆，最後才離開 App
    val backConsumes = drawerState.isOpen || batchMode || quickCapsuleDate != null ||
        viewingOwnerCode != null || selectedTab != 0
    androidx.activity.compose.BackHandler(enabled = backConsumes) {
        when {
            drawerState.isOpen -> coroutineScope.launch { drawerState.close() }
            batchMode -> { batchMode = false; batchSelectedDates.clear() }
            quickCapsuleDate != null -> quickCapsuleDate = null
            viewingOwnerCode != null -> viewingOwnerCode = null
            else -> selectedTab = 0
        }
    }

    val effectiveAccent = currentTheme.accent
    val effectiveCardBg = currentTheme.calCellBg

    val colorScheme = if (!currentTheme.isDark) {
        lightColorScheme(
            primary = effectiveAccent,
            background = currentTheme.bgBase,
            surface = effectiveCardBg,
            surfaceVariant = currentTheme.bgElevated,
            onPrimary = Color.White,
            onBackground = currentTheme.textMain,
            onSurface = currentTheme.textMain
        )
    } else {
        darkColorScheme(
            primary = effectiveAccent,
            background = currentTheme.bgBase,
            surface = effectiveCardBg,
            surfaceVariant = currentTheme.bgElevated,
            onPrimary = Color.White,
            onBackground = currentTheme.textMain,
            onSurface = currentTheme.textMain
        )
    }

    val tomorrow = today.plusDays(1)
    val isTomorrowOff = events.any { it.date == tomorrow && it.type == ShiftType.OFF }
    val tomorrowRiskyAlarms = if (isTomorrowOff) {
        alarms.filter { it.date == tomorrow && it.enabled }
    } else emptyList()

    val safeguardKey = "$tomorrow|${tomorrowRiskyAlarms.size}"
    LaunchedEffect(safeguardKey, appPrefs.autoHolidaySafeguard) {
        if (appPrefs.autoHolidaySafeguard && tomorrowRiskyAlarms.isNotEmpty() && safeguardDismissKey != safeguardKey) {
            kotlinx.coroutines.delay(900)
            showSafeguard = true
        }
    }

    fun pauseTomorrowRiskyAlarms() {
        var count = 0
        tomorrowRiskyAlarms.forEach { al ->
            val idx = alarms.indexOfFirst { it.id == al.id }
            if (idx != -1) {
                val updated = alarms[idx].copy(enabled = false)
                alarms[idx] = updated
                NativeAlarmScheduler.cancelAlarm(context, updated)
                count++
            }
        }
        persistAllData()
        AppToast.show(tr("🛡️ 已為您一鍵暫停明日 {0} 個上班鬧鐘，祝您休假愉快！", count))
    }

    MaterialTheme(colorScheme = colorScheme) {
    CompositionLocalProvider(LocalSurface provides SurfaceInfo(currentTheme.bgCard, !currentTheme.isDark)) {
        TopToastHost(currentTheme)
        AlarmPermissionGuard(forceShow = showAlarmCheck, onClose = { showAlarmCheck = false })
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ReverieDrawerContent(
                    currentYearMonth = currentYearMonth,
                    events = displayEvents,
                    viewMode = viewMode,
                    isCalendarTab = selectedTab == 0,
                    accountEmail = CloudSync.user?.email,
                    accentHex = appPrefs.customAccentHex,
                    conflictCount = tomorrowRiskyAlarms.size,
                    autoCheckUpdate = appPrefs.autoCheckUpdate,
                    palette = currentTheme,
                    subscriptions = partnerSync.subscriptionList(today),
                    viewingCode = viewingOwnerCode,
                    holidayCompare = partnerSync.holidayCompare,
                    onSelectSource = { code ->
                        viewingOwnerCode = code
                        selectedTab = 0
                        if (code != null) {
                            batchMode = false
                            batchSelectedDates.clear()
                            val name = partnerSync.subscriptionList(today).firstOrNull { it.code == code }?.name ?: tr("共享對象")
                            AppToast.show(tr("👀 已切換至「{0}」的唯讀班表", name))
                        }
                    },
                    onToggleCompare = {
                        val on = !partnerSync.holidayCompare
                        partnerSync = partnerSync.copy(holidayCompare = on, subscriptions = partnerSync.subscriptionList(today))
                        if (on) viewingOwnerCode = null
                        persistAllData()
                        AppToast.show(if (on) tr("💚 已開啟「雙方共同休假日對照」，綠色標記為兩人同休日！") else tr("已關閉雙方共同休假對照"))
                    },
                    onOpenAlarmCheck = { showAlarmCheck = true },
                    onSelectViewMode = { mode ->
                        viewMode = mode
                        selectedTab = 0
                    },
                    onOpenSearch = { showSearchDialog = true },
                    onOpenAccount = { showSharingPage = true },
                    onOpenSharing = { showSharingPage = true },
                    onRunSafeguardCheck = {
                        if (tomorrowRiskyAlarms.isEmpty()) {
                            AppToast.show(tr("🛡️ 防呆檢測通過：明日無休假與上班鬧鐘衝突！"))
                        } else {
                            showSafeguard = true
                        }
                    },
                    onImportHolidays = { importTaiwanHolidays2026() },
                    onOpenSettings = { showPreferencesDialog = true },
                    onOpenAbout = { showAboutSheet = true },
                    onCloseDrawer = { coroutineScope.launch { drawerState.close() } }
                )
            }
        ) {
            Scaffold(
                containerColor = currentTheme.bgBase,
                topBar = {
                    Column {
                    WebTopBar(
                        yearMonth = currentYearMonth,
                        selectedDate = selectedDate,
                        showBatchButton = selectedTab == 0 && viewingOwnerCode == null,
                        batchMode = batchMode,
                        safeguardAlert = tomorrowRiskyAlarms.isNotEmpty(),
                        theme = currentTheme,
                        showMoreMenu = showMoreMenu,
                        onToggleMoreMenu = { showMoreMenu = it },
                        onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                        onPrev = { currentYearMonth = currentYearMonth.minusMonths(1) },
                        onNext = { currentYearMonth = currentYearMonth.plusMonths(1) },
                        onToday = {
                            currentYearMonth = YearMonth.from(today)
                            selectedDate = today
                            viewMode = CalendarViewMode.MONTH
                        },
                        onToggleBatch = {
                            batchMode = !batchMode
                            if (!batchMode) batchSelectedDates.clear()
                        },
                        onSearch = { showSearchDialog = true },
                        onAddShift = { showEditSheet = true },
                        onAddAlarm = { showAddAlarmDialog = true },
                        onOpenWidgets = { showWidgetPage = true }
                    )
                    // 唯讀共享日曆警示橫幅（網頁版 shared-readonly-banner）
                    if (viewingOwnerCode != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF9333EA))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconText(
                                tr("👀 正在唯讀檢視共享班表（對方鬧鐘與私人備註已隱藏）"),
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("切回我的"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.2f))
                                    .clickable { viewingOwnerCode = null }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    }
                },
                bottomBar = {
                    WebBottomBar(
                        selectedTab = selectedTab,
                        theme = currentTheme,
                        position = { tabPagerState.currentPage + tabPagerState.currentPageOffsetFraction },
                        onSelect = { selectedTab = it }
                    )
                }
            ) { innerPadding ->
                // 5 大分頁：左右拖曳空白處／摘要卡即時跟手切換（月曆格子區由月份分頁器接手）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    HorizontalPager(
                        state = tabPagerState,
                        modifier = Modifier.fillMaxSize(),
                        // 批次模式時鎖住分頁滑動，避免選日期時誤切分頁
                        userScrollEnabled = !batchMode,
                        key = { it }
                    ) { page ->
                    when (page) {
                        0 -> {
                            if (viewMode == CalendarViewMode.MONTH) {
                                CalendarScreen(
                                    onOpenWeather = { selectedTab = 3 },
                                    yearMonth = currentYearMonth,
                                    today = today,
                                    selectedDate = selectedDate,
                                    quickCapsuleDate = if (viewingOwnerCode != null) null else quickCapsuleDate,
                                    events = displayEvents,
                                    alarms = if (viewingOwnerCode != null) emptyList() else alarms,
                                    readOnly = viewingOwnerCode != null,
                                    batchSelectedDates = batchSelectedDates,
                                    tomorrowConflictCount = tomorrowRiskyAlarms.size,
                                    partnerOffDates = partnerSync.compareOffDates(today),
                                    weekStartsOnMonday = appPrefs.weekStartsOnMonday,
                                    fontScale = 1f, // 字級已由全域縮放處理
                                    theme = currentTheme,
                                    effectiveAccent = effectiveAccent,
                                    effectiveCardBg = effectiveCardBg,
                                    onMonthChange = { currentYearMonth = it },
                                    onPauseTomorrowAlarms = {
                                        tomorrowRiskyAlarms.forEach { al ->
                                            val idx = alarms.indexOfFirst { it.id == al.id }
                                            if (idx != -1) {
                                                val updated = alarms[idx].copy(enabled = false)
                                                alarms[idx] = updated
                                                NativeAlarmScheduler.cancelAlarm(context, updated)
                                            }
                                        }
                                        persistAllData()
                                        AppToast.show(tr("🛡️ 已一鍵暫停明日休假日上班鬧鐘"))
                                    },
                                    onDateClick = { clickedDate ->
                                        if (viewingOwnerCode != null) {
                                            selectedDate = clickedDate
                                        } else if (batchMode) {
                                            if (batchSelectedDates.contains(clickedDate)) {
                                                batchSelectedDates.remove(clickedDate)
                                            } else {
                                                batchSelectedDates.add(clickedDate)
                                            }
                                        } else {
                                            selectedDate = clickedDate
                                            quickCapsuleDate = if (quickCapsuleDate == clickedDate) null else clickedDate
                                        }
                                    },
                                    onDismissQuickCapsule = { quickCapsuleDate = null },
                                    onOpenEditSheet = {
                                        quickCapsuleDate = null
                                        showEditSheet = true
                                    },
                                    onOpenImageLightbox = { uriStr ->
                                        val imgs = events.flatMap { it.attachments }.filter { it.isImage }
                                        val owner = events.firstOrNull { ev -> ev.attachments.any { it.uriString == uriStr } }
                                        val list = owner?.attachments?.filter { it.isImage } ?: imgs
                                        lightboxImages = list
                                        lightboxIndex = list.indexOfFirst { it.uriString == uriStr }.coerceAtLeast(0)
                                    },
                                    onDuplicateNextDay = { srcDate ->
                                        val srcEv = events.firstOrNull { it.date == srcDate }
                                        if (srcEv != null) {
                                            val matchedTpl = templates.firstOrNull { it.title == srcEv.title }
                                                ?: templates.firstOrNull { it.type == srcEv.type }
                                                ?: templates[0]
                                            val nextDate = srcDate.plusDays(1)
                                            applyTemplateToDate(nextDate, matchedTpl)
                                            quickCapsuleDate = null
                                            selectedDate = nextDate
                                            currentYearMonth = YearMonth.from(nextDate)
                                            AppToast.show(tr("📋 已複製班別至 {0}", nextDate))
                                        }
                                    },
                                    onToggleAlarm = { alarm -> updateAlarm(alarm) { it.copy(enabled = !it.enabled) } },
                                    onTestRing = { al ->
                                        NativeAlarmScheduler.rules = appPrefs
                                        AlarmEngine.ringNow(context, NativeAlarmScheduler.toSpec(al))
                                    },
                                    onAddAlarm = { showAddAlarmDialog = true }
                                )
                            } else {
                                ExtraCalendarViews(
                                    viewMode = viewMode,
                                    selectedDate = selectedDate,
                                    yearMonth = currentYearMonth,
                                    events = displayEvents,
                                    alarms = if (viewingOwnerCode != null) emptyList() else alarms,
                                    theme = currentTheme,
                                    onSelectDateAndSwitchMonth = { targetDate ->
                                        selectedDate = targetDate
                                        currentYearMonth = YearMonth.from(targetDate)
                                        viewMode = CalendarViewMode.MONTH
                                    }
                                )
                            }
                        }

                        1 -> WebAlarmsScreen(
                            alarms = alarms,
                            events = events,
                            ringtones = ringtoneLibrary,
                            defaultWakeUri = appPrefs.defaultWakeRingtoneUri,
                            defaultLeaveUri = appPrefs.defaultLeaveRingtoneUri,
                            theme = currentTheme,
                            onAddAlarm = { showAddAlarmDialog = true },
                            onToggle = { alarm -> updateAlarm(alarm) { it.copy(enabled = !it.enabled) } },
                            onDelete = { alarm -> deleteAlarm(alarm) },
                            onBatchDelete = { ids ->
                                alarms.filter { it.id in ids }.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
                                alarms.removeAll { it.id in ids }
                                persistAllData()
                                AppToast.show(tr("🗑️ 已刪除 {0} 個鬧鐘", ids.size))
                            },
                            onClearDate = { targetDate ->
                                alarms.filter { it.date == targetDate }.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
                                alarms.removeAll { it.date == targetDate }
                                persistAllData()
                                AppToast.show(tr("🧹 已清空 {0} 所有鬧鐘", targetDate))
                            },
                            onTestRing = { al ->
                                NativeAlarmScheduler.rules = appPrefs
                                AlarmEngine.ringNow(context, NativeAlarmScheduler.toSpec(al))
                            },
                            onSetAlarmRingtone = { alarm, entry ->
                                updateAlarm(alarm) { it.copy(ringtoneUri = entry.uriString, ringtoneName = entry.name) }
                                AppToast.show(tr("🎵 已設定鈴聲：{0}", tr(entry.name)))
                            },
                            onImportRingtone = { uri, fileName ->
                                val entry = RingtoneEntry(name = fileName, uriString = uri.toString(), isBuiltIn = false)
                                ringtoneLibrary.add(entry)
                                persistAllData()
                                AppToast.show(tr("🎵 已加入鈴聲庫：{0}", fileName))
                                entry
                            },
                            onDeleteRingtone = { entry ->
                                if (!entry.isBuiltIn) {
                                    ringtoneLibrary.removeAll { it.id == entry.id }
                                    persistAllData()
                                }
                            },
                            onSetDefaultRingtone = { stage, entry ->
                                appPrefs = if (stage == AlarmStage.WAKE) {
                                    appPrefs.copy(defaultWakeRingtoneUri = entry.uriString, defaultWakeRingtoneName = entry.name)
                                } else {
                                    appPrefs.copy(defaultLeaveRingtoneUri = entry.uriString, defaultLeaveRingtoneName = entry.name)
                                }
                                persistAllData()
                                AppToast.show(tr("✅ 已設為{0}預設鈴聲", tr(stage.label)))
                            }
                        )

                        2 -> WebTemplatesScreen(
                            templates = templates,
                            defaultWakeOffset = appPrefs.defaultWakeOffset,
                            defaultLeaveOffset = appPrefs.defaultLeaveOffset,
                            theme = currentTheme,
                            onImportHolidays = { importTaiwanHolidays2026() },
                            onUpdateTemplate = { tpl ->
                                val idx = templates.indexOfFirst { it.id == tpl.id }
                                if (idx != -1) {
                                    templates[idx] = tpl
                                    persistAllData()
                                }
                            },
                            onDeleteTemplate = { tpl ->
                                templates.removeAll { it.id == tpl.id }
                                persistAllData()
                                AppToast.show(tr("已刪除模板「{0}」", tr(tpl.title)))
                            },
                            onAddTemplate = { tpl ->
                                templates.add(tpl)
                                persistAllData()
                                AppToast.show(tr("✅ 已新增自訂模板「{0}」", tr(tpl.title)))
                            }
                        )

                        3 -> WeatherScreen(theme = currentTheme, events = events, alarms = alarms)

                        4 -> AiScheduleScreen(
                            state = aiChat,
                            templates = templates,
                            events = events,
                            baseMonth = currentYearMonth,
                            theme = currentTheme,
                            onApply = { plan -> applySchedulePlan(plan) }
                        )
                    }
                    }

                    if (selectedTab == 0 && batchMode) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = currentTheme.bgElevated,
                            border = BorderStroke(1.dp, currentTheme.borderColor),
                            shadowElevation = 12.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconText(
                                        text = tr("⚡ 已選取 {0} 天 — 點擊班別一鍵填入：", batchSelectedDates.size),
                                        color = Color(0xFFFBBF24).readable(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = tr("完成"),
                                        color = currentTheme.textMuted,
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable {
                                            batchMode = false
                                            batchSelectedDates.clear()
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    templates.forEach { tpl ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(tpl.type.chipBg())
                                                .clickable {
                                                    if (batchSelectedDates.isEmpty()) {
                                                        AppToast.show(tr("請先點選日曆上的日期"))
                                                    } else {
                                                        val count = batchSelectedDates.size
                                                        batchSelectedDates.forEach { d -> applyTemplateToDate(d, tpl, autoSave = false) }
                                                        persistAllData()
                                                        batchSelectedDates.clear()
                                                        batchMode = false
                                                        AppToast.show(tr("⚡ 已批次排入 {0} 天「{1}」", count, tr(tpl.title)))
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = tr(tpl.title),
                                                color = tpl.type.chipText(),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 編輯排班與私人多媒體備忘 BottomSheet
        MotionHost(showEditSheet) {
            // 檔案挑選完成後要把附件交回面板
            var pendingMediaCallback by remember { mutableStateOf<((ShiftAttachment) -> Unit)?>(null) }
            fun handlePicked(uri: Uri?, isImage: Boolean) {
                if (uri == null) return
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                pendingMediaCallback?.invoke(
                    ShiftAttachment(name = getFileNameFromUri(context, uri), uriString = uri.toString(), isImage = isImage)
                )
            }
            val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { handlePicked(it, true) }
            val docPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { handlePicked(it, false) }

            val editDate = selectedDate
            val currentEv = events.firstOrNull { it.date == editDate }
            val dayAlarms = alarms.filter { it.date == editDate }.sortedBy { it.triggerTime }
            DateEditSheet(
                date = editDate,
                event = currentEv,
                dayAlarms = dayAlarms,
                templates = templates,
                theme = currentTheme,
                onApplyTemplate = { tpl, note, atts ->
                    applyTemplateToDate(editDate, tpl, note, atts)
                    AppToast.show(tr("✅ 已切換為「{0}」並重設兩段鬧鐘", tr(tpl.title)))
                },
                onSave = { title, type, start, end, note, atts, sync ->
                    val matchedTpl = templates.firstOrNull { it.type == type } ?: templates.firstOrNull()
                    upsertShift(
                        date = editDate, title = title, type = type, start = start, end = end,
                        wakeOffset = matchedTpl?.wakeOffsetMinutes ?: appPrefs.defaultWakeOffset.toLong(),
                        leaveOffset = matchedTpl?.leaveOffsetMinutes ?: appPrefs.defaultLeaveOffset.toLong(),
                        note = note, attachments = atts, regenerateAlarms = sync
                    )
                    showEditSheet = false
                    AppToast.show(if (sync && type != ShiftType.OFF) tr("✅ 已儲存 {0} 並重設兩段鬧鐘", editDate) else tr("✅ 已儲存 {0} 排班", editDate))
                },
                onClearDay = {
                    val oldIds = events.filter { it.date == editDate }.map { it.id }
                    val toRemove = alarms.filter { it.eventId in oldIds || it.date == editDate }
                    toRemove.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
                    events.removeAll { it.date == editDate }
                    alarms.removeAll { al -> toRemove.any { it.id == al.id } }
                    persistAllData()
                    showEditSheet = false
                    AppToast.show(tr("🧹 已清除當日排班與鬧鐘"))
                },
                onAlarmTimeChange = { al, t -> updateAlarm(al) { it.copy(triggerTime = t) } },
                onAlarmToggle = { al -> updateAlarm(al) { it.copy(enabled = !it.enabled) } },
                onAlarmDelete = { al -> deleteAlarm(al) },
                onAlarmTestRing = { al ->
                    NativeAlarmScheduler.rules = appPrefs
                    AlarmEngine.ringNow(context, NativeAlarmScheduler.toSpec(al))
                },
                onAddAlarm = {
                    showEditSheet = false
                    showAddAlarmDialog = true
                },
                onPickImage = { cb -> pendingMediaCallback = cb; imagePickerLauncher.launch(arrayOf("image/*")) },
                onPickFile = { cb ->
                    pendingMediaCallback = cb
                    docPickerLauncher.launch(arrayOf("application/pdf", "text/plain", "application/*"))
                },
                onOpenImage = { imgs, idx -> lightboxImages = imgs; lightboxIndex = idx },
                onDismiss = { showEditSheet = false }
            )
        }

        // 圖片燈箱：可左右滑動切換同一天的所有圖片
        var lastLightbox by remember { mutableStateOf<List<ShiftAttachment>>(emptyList()) }
        if (lightboxImages.isNotEmpty()) lastLightbox = lightboxImages
        MotionHost(lightboxImages.isNotEmpty()) {
            ImageLightbox(
                images = lastLightbox,
                startIndex = lightboxIndex,
                theme = currentTheme,
                onDismiss = { lightboxImages = emptyList() }
            )
        }

        // 系統與質感主題設定（對應網頁版設定頁）
        // 桌面小工具（從右上角「⋮」打開的全螢幕頁面）
        MotionHost(showWidgetPage) {
            MotionFullscreen(onDismiss = { showWidgetPage = false }) {
                SubPageScaffold(title = tr("桌面小工具"), theme = currentTheme, onBack = { showWidgetPage = false }) {
                    WidgetsPreviewScreen(
                        todayShift = events.firstOrNull { it.date == today },
                        tomorrowShift = events.firstOrNull { it.date == today.plusDays(1) },
                        nextAlarm = LocalDateTime.now().let { now ->
                            alarms.filter { it.enabled && LocalDateTime.of(it.date, it.triggerTime).isAfter(now) }
                                .minByOrNull { LocalDateTime.of(it.date, it.triggerTime) }
                        },
                        theme = currentTheme,
                        effectiveAccent = effectiveAccent,
                        showTitle = false
                    )
                }
            }
        }

        // 共享帳號（從側邊欄打開的全螢幕頁面）
        MotionHost(showSharingPage) {
            MotionFullscreen(onDismiss = { showSharingPage = false }) {
                SubPageScaffold(title = tr("共享帳號"), theme = currentTheme, onBack = { showSharingPage = false }) {
                        WebSharingScreen(
                            events = events,
                            alarmsCount = alarms.size,
                            partnerSync = partnerSync,
                            theme = currentTheme,
                            onUpdate = { updated ->
                                partnerSync = updated
                                persistAllData()
                            },
                            onOpenPartnerCalendar = { sub ->
                                if (viewingOwnerCode == sub.code) {
                                    viewingOwnerCode = null
                                } else {
                                    viewingOwnerCode = sub.code
                                    batchMode = false
                                    batchSelectedDates.clear()
                                    selectedTab = 0
                                    showSharingPage = false
                                    AppToast.show(tr("👀 已切換至「{0}」的唯讀班表", sub.name))
                                }
                            },
                            viewingCode = viewingOwnerCode
                        )
                }
            }
        }

        MotionHost(showPreferencesDialog) {
            SettingsSheet(
                prefs = appPrefs,
                palette = currentTheme,
                onUpdate = { updated ->
                    appPrefs = updated
                    persistAllData()
                },
                onExportBackup = { exportJsonLauncher.launch("ReverieAlarm_Backup_${today}.json") },
                onImportBackup = { importJsonLauncher.launch(arrayOf("application/json")) },
                onDismiss = { showPreferencesDialog = false }
            )
        }

        // 關於應用
        MotionHost(showAboutSheet) {
            AboutAppSheet(
                prefs = appPrefs,
                palette = currentTheme,
                onToggleAutoCheck = { on ->
                    appPrefs = appPrefs.copy(autoCheckUpdate = on)
                    persistAllData()
                    AppToast.show(if (on) tr("🔄 已開啟啟動時自動檢查更新") else tr("已關閉自動檢查更新"))
                },
                onOpenChangelog = { showChangelogDialog = true },
                onExportLogs = { exportLogLauncher.launch("ReverieAlarm_Logs_${today}.txt") },
                onDismiss = { showAboutSheet = false }
            )
        }

        // 有新版本
        MotionHost(UpdateChecker.available != null) {
            UpdateChecker.available?.let { UpdateDialog(it, currentTheme, context) }
        }

        // 休假日鬧鐘防呆
        MotionHost(showSafeguard && tomorrowRiskyAlarms.isNotEmpty()) {
            HolidaySafeguardDialog(
                tomorrowLabel = tomorrow.toString(),
                holidayName = events.firstOrNull { it.date == tomorrow && it.type == ShiftType.OFF }?.title ?: tr("排定休假"),
                riskyAlarms = tomorrowRiskyAlarms,
                palette = currentTheme,
                onKeep = {
                    showSafeguard = false
                    safeguardDismissKey = safeguardKey
                },
                onPauseAll = {
                    pauseTomorrowRiskyAlarms()
                    showSafeguard = false
                    safeguardDismissKey = safeguardKey
                }
            )
        }

        // 版本更新日誌
        MotionHost(showChangelogDialog) {
            ChangelogDialog(
                palette = currentTheme,
                onExportLogs = { exportLogLauncher.launch("ReverieAlarm_Logs_${today}.txt") },
                onDismiss = { showChangelogDialog = false }
            )
        }

        MotionHost(showSearchDialog) {
            GlobalSearchDialog(
                events = events,
                alarms = alarms,
                theme = currentTheme,
                onJumpToDate = { targetDate ->
                    selectedDate = targetDate
                    currentYearMonth = YearMonth.from(targetDate)
                    selectedTab = 0
                    viewMode = CalendarViewMode.MONTH
                },
                onDismiss = { showSearchDialog = false },
                onEditDate = { targetDate ->
                    selectedDate = targetDate
                    currentYearMonth = YearMonth.from(targetDate)
                    selectedTab = 0
                    showEditSheet = true
                }
            )
        }

        MotionHost(showAddAlarmDialog) {
            AddAlarmSheet(
                defaultDate = selectedDate,
                ringtones = ringtoneLibrary,
                defaultWakeUri = appPrefs.defaultWakeRingtoneUri,
                defaultLeaveUri = appPrefs.defaultLeaveRingtoneUri,
                theme = currentTheme,
                onAdd = { date, time, label, stage, snoozeMin, ringtone ->
                    val newAl = ShiftAlarm(
                        eventId = events.firstOrNull { it.date == date }?.id?.takeIf { stage != AlarmStage.CUSTOM },
                        date = date,
                        triggerTime = time,
                        stage = stage,
                        label = label,
                        enabled = true,
                        ringtoneUri = ringtone?.uriString,
                        ringtoneName = ringtone?.name ?: BuiltInRingtones.defaultFor(stage).name,
                        maxSnoozeCount = if (snoozeMin == 0) 0 else appPrefs.maxSnoozeCount,
                        snoozeMinutes = snoozeMin
                    )
                    alarms.add(newAl)
                    NativeAlarmScheduler.scheduleAlarm(context, newAl)
                    persistAllData()
                    AppToast.show(tr("⏰ 已建立 {0} {1} 鬧鐘", date, time.toString().take(5)))
                },
                onDismiss = { showAddAlarmDialog = false }
            )
        }

        MotionHost(showTemplateDialog) {
            TemplateEditorDialog(
                initialTemplate = editingTemplateTarget,
                theme = currentTheme,
                onSave = { savedTpl ->
                    val idx = templates.indexOfFirst { it.id == savedTpl.id }
                    if (idx != -1) {
                        templates[idx] = savedTpl
                    } else {
                        templates.add(savedTpl)
                    }
                    persistAllData()
                    AppToast.show(tr("✅ 已儲存班別模板「{0}」", tr(savedTpl.title)))
                },
                onDismiss = { showTemplateDialog = false }
            )
        }
    }
    }
}

// ============================================================================
// 7. 分頁一：排班日曆主畫面 (月曆格子獨立滑動換月 + 懸浮膠囊 + 同休標記 + 時段簡標)
// ============================================================================
@Composable
fun CalendarScreen(
    yearMonth: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate,
    quickCapsuleDate: LocalDate?,
    events: List<ShiftEvent>,
    alarms: List<ShiftAlarm>,
    batchSelectedDates: List<LocalDate>,
    tomorrowConflictCount: Int,
    partnerOffDates: List<String>,
    weekStartsOnMonday: Boolean,
    fontScale: Float,
    theme: ThemePalette,
    effectiveAccent: Color,
    effectiveCardBg: Color,
    onMonthChange: (YearMonth) -> Unit,
    onOpenWeather: () -> Unit = {},
    onPauseTomorrowAlarms: () -> Unit,
    onDateClick: (LocalDate) -> Unit,
    onDismissQuickCapsule: () -> Unit,
    onOpenEditSheet: () -> Unit,
    onOpenImageLightbox: (String) -> Unit,
    onDuplicateNextDay: (LocalDate) -> Unit,
    onToggleAlarm: (ShiftAlarm) -> Unit,
    onTestRing: (ShiftAlarm) -> Unit = {},
    onAddAlarm: () -> Unit = {},
    readOnly: Boolean = false
) {
    // 表頭存星期幾本身（不是文字），週末上色不受語言影響
    val weekHeaders = if (weekStartsOnMonday) {
        java.time.DayOfWeek.entries.toList()
    } else {
        listOf(java.time.DayOfWeek.SUNDAY) + java.time.DayOfWeek.entries.dropLast(1)
    }

    // ---- 月份分頁器：跟手拖曳、吸附／回彈；頂部 < > 也用同一個動畫 ----
    val monthPagerState = rememberPagerState(initialPage = pageOfMonth(yearMonth)) { MONTH_PAGE_COUNT }
    val latestYearMonth by rememberUpdatedState(yearMonth)
    val latestOnMonthChange by rememberUpdatedState(onMonthChange)
    // 外部（頂部 < >、回到今天、搜尋跳轉）改月份 → 平滑滑過去；跨太多月就直接跳
    LaunchedEffect(yearMonth) {
        val target = pageOfMonth(yearMonth)
        if (monthPagerState.currentPage != target || monthPagerState.currentPageOffsetFraction != 0f) {
            if (kotlin.math.abs(target - monthPagerState.currentPage) <= 2) {
                monthPagerState.animateScrollToPage(
                    target,
                    animationSpec = androidx.compose.animation.core.tween(380, easing = Motion.EnterEasing)
                )
            } else {
                monthPagerState.scrollToPage(target)
            }
        }
    }
    // 手指拖曳換頁並停穩 → 回報新月份（同步頂部年月標題）
    LaunchedEffect(monthPagerState) {
        snapshotFlow { monthPagerState.settledPage }.collect { page ->
            val ym = monthOfPage(page)
            if (ym != latestYearMonth) latestOnMonthChange(ym)
        }
    }

    @Composable
    fun MonthGridPage(ym: YearMonth) {
        val firstDayOfMonth = ym.atDay(1)
        val leadingEmpty = if (weekStartsOnMonday) firstDayOfMonth.dayOfWeek.value - 1 else firstDayOfMonth.dayOfWeek.value % 7
        val daysInMonth = ym.lengthOfMonth()
        val rows = (leadingEmpty + daysInMonth + 6) / 7
        Column(verticalArrangement = Arrangement.spacedBy(CAL_ROW_GAP.dp), modifier = Modifier.fillMaxWidth()) {
            for (r in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (c in 0 until 7) {
                        val index = r * 7 + c
                        val dayNum = index - leadingEmpty + 1
                        val inMonth = dayNum in 1..daysInMonth
                        // 上／下個月的日子也畫出來（淡化、不能點），月曆看起來比較完整
                        val cellDate = firstDayOfMonth.plusDays((index - leadingEmpty).toLong())
                        val isToday = inMonth && cellDate == today
                        val isSelected = inMonth && cellDate == selectedDate
                        val isBatchSel = inMonth && batchSelectedDates.contains(cellDate)
                        val cellEvent = events.firstOrNull { it.date == cellDate }
                        val hasActiveAlarm = inMonth && alarms.any { it.date == cellDate && it.enabled }
                        val hasNote = !cellEvent?.note.isNullOrBlank() || !cellEvent?.attachments.isNullOrEmpty()
                        val isMutualOff = inMonth && cellEvent?.type == ShiftType.OFF && partnerOffDates.contains(cellDate.toString())
                        val gridBg = effectiveCardBg
                        val light = theme.isCalCellLight

                        // 底塊：選取 = 強調色實心；有排班 = 班別色淡淡的底；沒排班 = 透明
                        val tileBg = when {
                            isSelected -> effectiveAccent.darkenForWhiteText()
                            cellEvent != null -> mixColor(cellEvent.type.accentDot, if (light) 0.16f else 0.22f, gridBg)
                            else -> Color.Transparent
                        }
                        val surfaceBg = if (tileBg == Color.Transparent) gridBg else tileBg
                        val dow = cellDate.dayOfWeek
                        val numberColor = when {
                            isSelected -> Color.White
                            isToday -> effectiveAccent.readableOn(surfaceBg, 4.5)
                            dow == java.time.DayOfWeek.SUNDAY -> ROSE.readableOn(surfaceBg, 4.5)
                            dow == java.time.DayOfWeek.SATURDAY -> Color(0xFF3B82F6).readableOn(surfaceBg, 4.5)
                            else -> theme.calCellText.readableOn(surfaceBg, 4.5)
                        }
                        val ring = when {
                            isBatchSel -> Color(0xFFF59E0B)
                            isMutualOff && !isSelected -> EMERALD.readableOn(gridBg, 3.0)
                            isToday && !isSelected -> effectiveAccent.readableOn(gridBg, 3.0)
                            else -> Color.Transparent
                        }
                        val bounce by rememberBounceScale(
                            key = cellEvent?.let { "${it.id}|${it.title}|${it.type}" },
                            resetKey = cellDate
                        )
                        val shape = RoundedCornerShape(14.dp)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(CAL_CELL_HEIGHT.dp)
                                .graphicsLayer {
                                    alpha = if (inMonth) 1f else 0.32f
                                    if (inMonth) { scaleX = bounce; scaleY = bounce }
                                }
                                .then(
                                    if (isSelected) Modifier.shadow(6.dp, shape, ambientColor = effectiveAccent, spotColor = effectiveAccent)
                                    else Modifier
                                )
                                .clip(shape)
                                .background(tileBg)
                                .border(if (ring != Color.Transparent) 1.5.dp else 0.dp, ring, shape)
                                .then(if (inMonth) Modifier.clickable { onDateClick(cellDate) } else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            // 選取格子時浮出「編輯」膠囊（第一排改放到格子下方）
                            if (inMonth) {
                                MotionHost(quickCapsuleDate == cellDate) {
                                    EditCapsulePopup(isFirstRow = r == 0, theme = theme, onEdit = onOpenEditSheet)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = cellDate.dayOfMonth.toString(),
                                    color = numberColor,
                                    fontSize = (19 * fontScale).sp,
                                    fontWeight = if (isSelected || isToday) FontWeight.Black else FontWeight.Bold,
                                    lineHeight = (21 * fontScale).sp
                                )
                                // 數字下方：班別名稱（沒排班就留白，讓數字保持同一高度）
                                Text(
                                    text = cellEvent?.title?.let { shortShiftName(it) } ?: " ",
                                    color = when {
                                        cellEvent == null -> Color.Transparent
                                        isSelected -> Color.White.copy(alpha = 0.92f)
                                        else -> cellEvent.type.chipText(light).readableOn(surfaceBg, 4.5)
                                    },
                                    fontSize = (11 * fontScale).sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = (13 * fontScale).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 3.dp)
                                )
                            }
                            // 右上角：當天有開啟的鬧鐘
                            if (hasActiveAlarm) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 3.dp, end = 3.dp)
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color.White else ROSE.darkenForWhiteText()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Tabler.Bell, contentDescription = tr("有鬧鐘"),
                                        tint = if (isSelected) effectiveAccent.darkenForWhiteText() else Color.White,
                                        modifier = Modifier.size(9.dp)
                                    )
                                }
                            }
                            // 左上角：未來幾天的天氣小圖示
                            val dayWx = if (inMonth && !isMutualOff && !cellDate.isBefore(today)) {
                                WeatherRepo.data?.takeIf { it.place == WeatherRepo.place }?.day(cellDate)
                            } else null
                            if (dayWx != null) {
                                Icon(
                                    wxIcon(dayWx.code), contentDescription = wxText(dayWx.code),
                                    tint = if (isSelected) Color.White.copy(alpha = 0.9f) else Color(0xFF38BDF8).readableOn(surfaceBg, 3.0),
                                    modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp).size(11.dp)
                                )
                            }
                            // 左上角：雙方共同休假
                            if (isMutualOff) {
                                Icon(
                                    Tabler.Hearts, contentDescription = tr("共同休假"),
                                    tint = if (isSelected) Color.White else EMERALD.readableOn(surfaceBg, 3.0),
                                    modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp).size(11.dp)
                                )
                            }
                            // 底部小圓點：有私人備註或附件
                            if (hasNote) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 4.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color.White else Color(0xFF7DD3FC).readableOn(surfaceBg, 3.0))
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selectedEvent = events.firstOrNull { it.date == selectedDate }
    val selectedDayAlarms = alarms.filter { it.date == selectedDate }.sortedBy { it.triggerTime }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val shortTimeFormatter = remember { DateTimeFormatter.ofPattern("HH") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // 點到格子、按鈕以外的空白處 → 收起「編輯」膠囊（按鈕會先吃掉點擊，不會誤觸）
            .pointerInput(quickCapsuleDate) {
                if (quickCapsuleDate != null) detectTapGestures { onDismissQuickCapsule() }
            },
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 所在地區有天氣特報（颱風、豪大雨、強風…）時提示，點一下看天氣
        val wxAlerts = WeatherRepo.data?.takeIf { it.place == WeatherRepo.place }?.alerts.orEmpty()
        if (wxAlerts.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x33F59E0B))
                        .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(16.dp))
                        .clickable { onOpenWeather() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconText(
                        if (I18n.isEn) "⚠️ ${tr(WeatherRepo.place?.county ?: "")}: " + wxAlerts.joinToString(", ") { trAlert(it.title) }
                        else "⚠️ ${WeatherRepo.place?.county ?: ""}${wxAlerts.joinToString("、") { it.title }}" + if (wxAlerts.size > 1) "等 ${wxAlerts.size} 則特報" else if (wxAlerts[0].title.contains("特報") || wxAlerts[0].title.contains("停班")) "" else "特報",
                        color = Color(0xFFFCD34D).readable(),
                        fontSize = (12 * fontScale).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Tabler.ChevronRight, contentDescription = tr("看天氣"), tint = Color(0xFFFCD34D).readable(), modifier = Modifier.size(16.dp))
                }
            }
        }

        if (tomorrowConflictCount > 0) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x33F59E0B))
                        .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconText(
                        text = tr("⚠️ 明日為休假但仍有 {0} 個上班鬧鐘開啟", tomorrowConflictCount),
                        color = Color(0xFFFCD34D).readable(),
                        fontSize = (12 * fontScale).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onPauseTomorrowAlarms, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(tr("一鍵暫停"), color = Color(0xFFFBBF24).readable(), fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = CAL_GRID_PAD.dp)) {
                weekHeaders.forEach { dow ->
                    Text(
                        text = I18n.weekdayNarrow(dow),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        // 週末標題上色，一眼看出星期六、日
                        color = when (dow) {
                            java.time.DayOfWeek.SUNDAY -> ROSE.readable(4.5)
                            java.time.DayOfWeek.SATURDAY -> Color(0xFF3B82F6).readable(4.5)
                            else -> theme.textMuted
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // 月曆格子：跟手雙月並排拖曳（左右拖曳時帶出上／下個月，放開依距離與速度吸附或回彈）
        item {
            val pager = monthPagerState
            val density = LocalDensity.current
            // 高度隨拖曳在兩個月的列數之間平滑變化（5 列 ↔ 6 列）
            val frac = pager.currentPageOffsetFraction
            val fromRows = monthRows(monthOfPage(pager.currentPage), weekStartsOnMonday)
            val toRows = monthRows(monthOfPage(pager.currentPage + if (frac >= 0f) 1 else -1), weekStartsOnMonday)
            val gridHeight = gridHeightFor(fromRows) + (gridHeightFor(toRows) - gridHeightFor(fromRows)) * kotlin.math.abs(frac)
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .clip(RoundedCornerShape(22.dp))
                    .background(effectiveCardBg)
                    .border(
                        1.dp,
                        if (contrastRatio(effectiveCardBg, theme.bgBase) < 1.25) theme.borderColor.readableOn(effectiveCardBg, 1.5) else Color.Transparent,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(CAL_GRID_PAD.dp),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
                key = { it }
            ) { page ->
                MonthGridPage(monthOfPage(page))
            }
        }

        // 當日排班與鬧鐘摘要卡（網頁版月視圖下方摘要）
        item {
            val isCrossDay = selectedEvent != null && selectedEvent.type != ShiftType.OFF &&
                !selectedEvent.endTime.isAfter(selectedEvent.startTime)
            WebCard(theme, radius = 28.dp, padding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(effectiveAccent.copy(alpha = 0.18f))
                                .border(1.dp, effectiveAccent.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(selectedDate.dayOfMonth.toString(), color = theme.accentText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selectedDate.toString(), color = theme.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, softWrap = false)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedEvent != null) {
                                    Icon(selectedEvent.type.icon(), contentDescription = null, tint = theme.textMain, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(tr(selectedEvent.title), color = theme.textMain, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                                    if (isCrossDay) {
                                        Spacer(Modifier.width(6.dp))
                                        WebPill(tr("跨隔日"), Color(0xFF14B8A6), fontSize = 9.sp)
                                    }
                                } else {
                                    Text(tr("尚未排班"), color = theme.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (selectedEvent != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (selectedEvent.type == ShiftType.OFF) tr("全天休假")
                                        else "${selectedEvent.startTime.format(timeFormatter)}-${selectedEvent.endTime.format(timeFormatter)}",
                                        color = theme.textMain.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, softWrap = false
                                    )
                                }
                            }
                        }
                        if (readOnly) {
                            WebPill(tr("唯讀共享檢視"), Color(0xFF9333EA), fontSize = 10.sp)
                        } else {
                            if (selectedEvent != null) {
                                WebButton(tr("複製隔日"), theme, filled = false, fontSize = 11.sp, vertical = 8.dp, radius = 14.dp) { onDuplicateNextDay(selectedDate) }
                            }
                        }
                    }

                    if (!selectedEvent?.note.isNullOrBlank()) {
                        Text(
                            text = selectedEvent!!.note,
                            color = theme.textMain.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(theme.bgElevated)
                                .border(1.dp, theme.borderColor, RoundedCornerShape(14.dp))
                                .padding(10.dp)
                        )
                    }

                    val images = selectedEvent?.attachments.orEmpty().filter { it.isImage }
                    val files = selectedEvent?.attachments.orEmpty().filter { !it.isImage }
                    if (images.isNotEmpty() || files.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images) { img ->
                                UriImage(
                                    img.uriString, maxSide = 200,
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenImageLightbox(img.uriString) }
                                )
                            }
                            items(files) { att ->
                                Box(
                                    modifier = Modifier
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(theme.bgElevated)
                                        .border(1.dp, theme.borderColor, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) { IconText("📄 ${att.name}", fontSize = 11.sp, color = theme.accentText, fontWeight = FontWeight.Bold, maxLines = 1) }
                            }
                        }
                    }

                    HorizontalDivider(color = theme.borderColor.copy(alpha = 0.7f))

                    if (readOnly) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF9333EA).copy(alpha = 0.10f))
                                .border(1.dp, Color(0xFF9333EA).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconText(
                                tr("🛡️ 基於隱私隔離機制，對方的私人備註、排班圖片、附件與起床鬧鐘均已隱藏。"),
                                fontSize = 12.sp, color = Color(0xFFA855F7).readable(), fontWeight = FontWeight.Bold
                            )
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Tabler.Alarm, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("當日兩段式鬧鐘（{0}）", selectedDayAlarms.size), color = theme.textMain.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconText(
                            tr("+ 新增鬧鐘"), color = effectiveAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAddAlarm() }.padding(4.dp)
                        )
                    }

                    if (readOnly) {
                        // 唯讀：不顯示對方鬧鐘
                    } else if (selectedDayAlarms.isEmpty()) {
                        Text(tr("此日無設定鬧鐘"), color = theme.textMuted.copy(alpha = 0.7f), fontSize = 12.sp)
                    } else {
                        selectedDayAlarms.forEach { al ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(theme.bgElevated)
                                    .border(1.dp, theme.borderColor, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = al.triggerTime.format(timeFormatter),
                                    color = theme.textMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = trLabel(al.label),
                                    color = if (al.enabled) theme.textMain else theme.textMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textDecoration = if (al.enabled) TextDecoration.None else TextDecoration.LineThrough,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(theme.bgCard)
                                        .border(1.dp, theme.borderColor, RoundedCornerShape(12.dp))
                                        .clickable { onTestRing(al) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Tabler.Volume, contentDescription = tr("試響"), tint = theme.textMain, modifier = Modifier.size(17.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                WebToggle(al.enabled, theme) { onToggleAlarm(al) }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hint = theme.textMuted.copy(alpha = 0.7f)
                Icon(Tabler.ChevronLeft, contentDescription = null, tint = hint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("左右滑動下方區域切換功能分頁"), fontSize = 11.sp, color = hint, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(Tabler.ChevronRight, contentDescription = null, tint = hint, modifier = Modifier.size(13.dp))
            }
        }
    }
}

// ---- 月份分頁器工具：第 N 頁 = 2000 年 1 月起算第 N 個月 ----
private val MONTH_PAGE_BASE: YearMonth = YearMonth.of(2000, 1)
const val MONTH_PAGE_COUNT = 12 * 200
fun pageOfMonth(ym: YearMonth): Int =
    ((ym.year - MONTH_PAGE_BASE.year) * 12 + (ym.monthValue - 1)).coerceIn(0, MONTH_PAGE_COUNT - 1)
fun monthOfPage(page: Int): YearMonth = MONTH_PAGE_BASE.plusMonths(page.coerceIn(0, MONTH_PAGE_COUNT - 1).toLong())
fun monthRows(ym: YearMonth, weekStartsOnMonday: Boolean): Int {
    val d = ym.atDay(1).dayOfWeek.value
    val lead = if (weekStartsOnMonday) d - 1 else d % 7
    return (lead + ym.lengthOfMonth() + 6) / 7
}
/** 日曆格子高度與列距（dp） */
const val CAL_CELL_HEIGHT = 62
const val CAL_ROW_GAP = 4
/** 月曆網格底板的內距（dp） */
const val CAL_GRID_PAD = 6
fun gridHeightFor(rows: Int): androidx.compose.ui.unit.Dp =
    (rows * CAL_CELL_HEIGHT + (rows - 1) * CAL_ROW_GAP + CAL_GRID_PAD * 2).dp

// ============================================================================
// 8. 共用小工具：讀取檔案名稱
// ============================================================================

private fun getFileNameFromUri(context: Context, uri: Uri): String {
    var name = tr("自訂本機鈴聲")
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

// ============================================================================
// 11. 分頁五：原生桌面小工具預覽與一鍵釘選
// ============================================================================
@Composable
fun WidgetsPreviewScreen(todayShift: ShiftEvent?, tomorrowShift: ShiftEvent?, nextAlarm: ShiftAlarm?, theme: ThemePalette, effectiveAccent: Color, showTitle: Boolean = true) {
    val context = LocalContext.current
    val hm = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val today = LocalDate.now()
    val week = listOf(tr("週一"), tr("週二"), tr("週三"), tr("週四"), tr("週五"), tr("週六"), tr("週日"))

    // 桌面上已經有幾個小工具（回到這頁時重新檢查）
    var pinnedCount by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        fun check() {
            pinnedCount = runCatching {
                AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ShiftWidgetProvider::class.java)).size
            }.getOrDefault(0)
        }
        check()
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) check() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun requestPin() {
        val mgr = AppWidgetManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.isRequestPinAppWidgetSupported) {
            runCatching { mgr.requestPinAppWidget(ComponentName(context, ShiftWidgetProvider::class.java), null, null) }
                .onFailure { AppToast.show(tr("這個桌面不支援一鍵新增，請照下方步驟手動加入")) }
        } else {
            AppToast.show(tr("這個桌面不支援一鍵新增，請照下方步驟手動加入"))
        }
    }

    // 和 ShiftWidget.kt 的文字一致
    fun describe(s: ShiftEvent?): String = when {
        s == null -> tr("尚未排班")
        s.type == ShiftType.OFF -> tr("{0} · 全天休假", tr(s.title))
        else -> "${tr(s.title)} · ${s.startTime.format(hm)} - ${s.endTime.format(hm)}"
    }
    fun dotColor(s: ShiftEvent?): Color = when (s?.type) {
        null -> Color(0xFF94A3B8)
        ShiftType.EARLY -> Color(0xFFF59E0B)
        ShiftType.EVENING -> Color(0xFF6366F1)
        ShiftType.NIGHT -> Color(0xFF14B8A6)
        ShiftType.OFF -> Color(0xFF10B981)
        ShiftType.CUSTOM -> Color(0xFF3B82F6)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showTitle) Text(tr("桌面小工具"), fontSize = 17.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                Text(tr("不用打開 App，在手機桌面就看得到今天的班和下一個鬧鐘"), fontSize = 11.sp, color = theme.textMuted, lineHeight = 16.sp)
            }
        }

        // 預覽：放在像桌布的背景上，樣子和實際小工具相同
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(effectiveAccent.copy(alpha = 0.55f), Color(0xFF1E293B), Color(0xFF0F172A))
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xF0131A2A))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reverie Alarm", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), modifier = Modifier.weight(1f))
                        Text("${today.monthValue}/${today.dayOfMonth} ${week[today.dayOfWeek.value - 1]}", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(dotColor(todayShift)))
                        Spacer(Modifier.width(7.dp))
                        Text(describe(todayShift), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF1F5F9), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(tr("明日：") + describe(tomorrowShift), fontSize = 11.sp, color = Color(0xFF94A3B8), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            nextAlarm?.triggerTime?.format(hm) ?: "--:--",
                            fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFFF1F5F9)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                nextAlarm == null -> tr("目前沒有排定的鬧鐘")
                                else -> {
                                    val day = when (nextAlarm.date) {
                                        today -> tr("今天")
                                        today.plusDays(1) -> tr("明天")
                                        else -> "${nextAlarm.date.monthValue}/${nextAlarm.date.dayOfMonth}"
                                    }
                                    "$day · ${nextAlarm.label}"
                                }
                            },
                            fontSize = 11.sp, color = Color(0xFFCBD5E1), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // 加到桌面
        item {
            WebCard(theme, radius = 24.dp, padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val on = pinnedCount > 0
                        Box(
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                                .background((if (on) EMERALD else effectiveAccent).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (on) Tabler.CircleCheck else Tabler.DeviceMobile, contentDescription = null,
                                tint = (if (on) EMERALD else effectiveAccent).readable(3.0), modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (on) tr("已經在桌面上了") else tr("還沒加到桌面"), fontSize = 14.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                            Text(
                                if (on) tr("共 {0} 個，班表和鬧鐘有變動會自動更新", pinnedCount) else tr("大小 4×2，點一下小工具就能打開 App"),
                                fontSize = 11.sp, color = theme.textMuted
                            )
                        }
                    }
                    WebButton(
                        if (pinnedCount > 0) tr("再加一個到桌面") else tr("加到桌面"), theme, Modifier.fillMaxWidth(),
                        filled = pinnedCount == 0, icon = Tabler.Pin, fontSize = 14.sp, vertical = 12.dp
                    ) { requestPin() }
                }
            }
        }

        // 手動加入的步驟
        item {
            WebCard(theme, radius = 24.dp, padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("按了沒反應？也可以手動加入"), fontSize = 13.sp, fontWeight = FontWeight.Black, color = theme.textMain)
                    listOf(
                        tr("回到手機桌面，長按空白的地方"),
                        tr("點選「小工具」（有些手機叫「Widgets」）"),
                        tr("找到 Reverie Alarm，把 4×2 卡片拖到桌面")
                    ).forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(24.dp).clip(CircleShape).background(theme.bgElevated).border(1.dp, theme.borderColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Black, color = theme.accentText) }
                            Spacer(Modifier.width(10.dp))
                            Text(step, fontSize = 13.sp, color = theme.textMain.copy(alpha = 0.9f), lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
    }
}
