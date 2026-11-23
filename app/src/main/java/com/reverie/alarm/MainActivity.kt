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
import androidx.compose.ui.layout.layout
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
import kotlinx.coroutines.flow.collectLatest
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
    // 色相刻意拉開：早班琥珀、晚班藍、夜班紫、休假綠、日程灰（夜班原本的青綠色和休假的綠色太像）
    EVENING("晚班", Color(0x333B82F6), Color(0xFF93C5FD), Color(0xFF3B82F6)),
    NIGHT("夜班", Color(0x33A855F7), Color(0xFFD8B4FE), Color(0xFFA855F7)),
    OFF("休假", Color(0x3310B981), Color(0xFF6EE7B7), Color(0xFF10B981)),
    CUSTOM("日程", Color(0x3394A3B8), Color(0xFFCBD5E1), Color(0xFF94A3B8))
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
    // ---- 國定假日（規則見 HolidayRules.kt）----
    val holidayLabels: Boolean = true,          // 日曆標示國定假日
    val holidayMode: String = "roster",         // roster＝照班表（輪班制）／office＝見紅休（一般上班族）
    val holidayIncludeMakeUp: Boolean = true,   // 見紅休：政府的補假日也放
    val holidaySkipInBatch: Boolean = true,     // 見紅休：批次／AI 排班略過放假的國定假日
    // ---- 休假防呆：前一晚自動提醒（見 EveningGuard.kt）----
    val eveningGuard: Boolean = true,           // 每天晚上檢查明天，有衝突才發通知
    val eveningGuardTime: String = "21:00",     // 檢查時間
    val autoCheckUpdate: Boolean = true,
    // ---- 介面動態效果 ----
    val animationsEnabled: Boolean = true,
    val animationSpeed: String = "normal",  // fast / normal / slow
    // ---- 背景星星（見 StarfieldBackground.kt）----
    val backgroundStars: Boolean = true,
    val starDensity: String = "normal",     // sparse / normal / dense
    val starSeed: Int = 0                   // 「換一批」時加 1，星星重新隨機分布
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
        // Firebase 登入模組第一次取用時會讀好幾個檔案：App Check 裝好後先在背景初始化，
        // 畫面開始用（CloudSync.start）時就不必在主執行緒等硬碟。沒有設定 Firebase 時會失敗，忽略即可
        Thread({ runCatching { com.google.firebase.auth.FirebaseAuth.getInstance() } }, "ReverieFirebaseWarmup").start()
        ToolNav.fromIntent(intent) // 從碼錶／計時的通知點進來
        GuardBridge.fromIntent(intent) // 從前一晚提醒的通知點進來
        runCatching { ToolNotifications.restore(applicationContext) } // 還在跑的碼錶／計時把通知補回來
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ToolNav.fromIntent(intent) // 從碼錶／計時的通知點進來
        GuardBridge.fromIntent(intent) // 從前一晚提醒的通知點進來
    }

    override fun onStop() {
        // 進背景（回桌面、切 App、鎖屏）：還在等合併的修改立刻寫入，不等 300ms
        AppPersistence.flush()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        UserActivity.touched() // 回到 App 時星星閃一下（之後沒碰就停住，見 StarfieldBackground）
        requestHighRefreshRate()
    }

    /**
     * 高更新率：Android 沒有「120Hz 權限」，App 只能向系統「要求」顯示模式，最後由系統決定。
     *   - 挑「同解析度、不超過 120Hz」裡最高的模式；用更新率挑，不能寫死編號（每支手機的編號不同，
     *     例如小米 14T Pro 的 3 號才是 120Hz、2 號是 144Hz）
     *   - 有些系統會忽略 preferredRefreshRate（HyperOS 3 就是），preferredDisplayModeId 才有效
     *   - 省電模式時不要求，交給系統；手機過熱時系統仍會降到 60Hz（保護手機，App 不該也無法蓋過）
     */
    private fun requestHighRefreshRate() {
        val d = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        }.getOrNull() ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && it.refreshRate <= 121f }
            .maxByOrNull { it.refreshRate }
        val powerSave = getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        val want = if (powerSave || best == null) 0 else best.modeId
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != want) {
            attrs.preferredDisplayModeId = want
            window.attributes = attrs
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // 只記按下與放開（不記每一個移動事件），讓背景星星知道使用者還在操作
        val action = ev.actionMasked
        if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_UP) UserActivity.touched()
        return super.dispatchTouchEvent(ev)
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
    // 「今天」：App 放在背景過了午夜，回到前景時換成新的一天（休假防呆檢查的是「明天」）
    var today by remember { mutableStateOf(LocalDate.now()) }
    val appLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(appLifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) LocalDate.now().let { if (it != today) today = it }
        }
        appLifecycle.addObserver(obs)
        onDispose { appLifecycle.removeObserver(obs) }
    }
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val gson = remember { Gson() }

    val runtimeLogs = remember { mutableStateListOf<String>() }
    fun logEvent(msg: String) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        runtimeLogs.add("[$stamp] $msg")
        if (runtimeLogs.size > 300) runtimeLogs.removeAt(0)
        AppLog.i("App", msg) // 同時寫進手機裡的診斷日誌（重開 App 也還在）
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
    // 主題色只在設定或深淺色改變時才重算（裡面有對比度計算；以前主畫面每次重組都算一次）
    val currentTheme = remember(appPrefs, systemDark) { buildThemePalette(appPrefs, systemDark) }
    SideEffect {
        UiScale.fontScale = appPrefs.fontScale
        Motion.enabled = appPrefs.animationsEnabled
        Motion.speedFactor = Motion.speedFactorOf(appPrefs.animationSpeed)
    }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showSafeguard by remember { mutableStateOf(false) }
    // 按過「全部保留」的那組問題（存在手機裡，重開 App 也不會再問）
    var safeguardDismissKey by remember { mutableStateOf(GuardPrefs.dismissed(context)) }
    var showHolidaySheet by remember { mutableStateOf(false) }
    var partnerSync by remember { mutableStateOf(PartnerSyncState()) }
    var viewMode by remember { mutableStateOf(CalendarViewMode.MONTH) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabPagerState = rememberPagerState(initialPage = 0) { 5 }
    // 目前畫面兩側要一直保持組好的分頁數（開 App 後逐步加到 4＝全部分頁都組好）
    var tabsKeptComposed by remember { mutableIntStateOf(0) }
    // 點底部分頁、搜尋跳轉等改 selectedTab → 平滑滑過去。
    // selectedTab 只在協程裡讀（不當 LaunchedEffect 的鍵）：換分頁時不會讓整個主畫面重組
    LaunchedEffect(tabPagerState) {
        snapshotFlow { selectedTab }.collectLatest { target ->
            if (tabPagerState.currentPage != target || tabPagerState.currentPageOffsetFraction != 0f) {
                tabPagerState.animateScrollToPage(
                    target,
                    animationSpec = androidx.compose.animation.core.tween(340, easing = Motion.EnterEasing)
                )
            }
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
    // 「工具」分頁目前顯示哪個模組（天氣／碼錶／桌面小工具）
    var toolSection by remember { mutableStateOf(ToolNav.last(context)) }
    // 從通知點進來：切到「工具」分頁的那個工具
    LaunchedEffect(ToolNav.pending) {
        ToolNav.pending?.let { s ->
            toolSection = s
            ToolNav.save(context, s)
            selectedTab = 3
            ToolNav.pending = null
        }
    }
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

    // 存檔：拍一張快照交給 AppPersistence（合併 300ms 內的修改、JSON 在背景轉、只寫一次，見 AppPersistence.kt）
    fun persistAllData() {
        // 鬧鐘規則馬上更新（畫面上的試響、立即響鈴也要用最新的規則）
        NativeAlarmScheduler.rules = appPrefs
        AppPersistence.persist(
            context,
            PersistSnapshot(
                events = events.toList(),
                alarms = alarms.toList(),
                templates = templates.toList(),
                themeName = themePreset.name,
                prefs = appPrefs,
                ringtones = ringtoneLibrary.toList(),
                partner = partnerSync
            )
        )
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

    /** AI 快速排班：只改有變動的日子、清除要清除的日子（連同那天的兩段鬧鐘），完成後跳到日曆的第一天 */
    fun applySchedulePlan(plan: Map<LocalDate, ShiftTemplate>, clears: Set<LocalDate> = emptySet()) {
        val changed = plan.filter { (d, tpl) -> events.firstOrNull { it.date == d }?.title != tpl.title }
        val toClear = clears.filter { d -> d !in plan && events.any { it.date == d } }
        toClear.forEach { d ->
            val ids = events.filter { it.date == d }.map { it.id }
            alarms.filter { it.eventId in ids }.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
            events.removeAll { it.date == d }
            alarms.removeAll { it.eventId in ids }
            logEvent(tr("AI 排班：清除 {0}", d))
        }
        changed.forEach { (d, tpl) -> applyTemplateToDate(d, tpl, autoSave = false) }
        persistAllData()
        (changed.keys + toClear).minOrNull()?.let { first ->
            currentYearMonth = YearMonth.from(first)
            selectedDate = first
        }
        selectedTab = 0
        AppToast.show(
            if (toClear.isEmpty()) tr("✅ 已排好 {0} 天的班與鬧鐘", changed.size)
            else tr("✅ 已排好 {0} 天、清除 {1} 天", changed.size, toClear.size)
        )
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

    /**
     * 把幾天的上班改成休假（那天的上班鬧鐘一起移除，備註與附件保留），不會自己存檔。
     * 回傳「復原」要做的事：放回原本的班和鬧鐘。
     * 國定假日本身不會呼叫這裡（不寫進班表）；只有使用者在防呆視窗或國定假日面板勾選確認後才會改。
     */
    fun convertShiftsToOff(targets: List<ShiftEvent>): () -> Unit {
        val offTpl = templates.firstOrNull { it.isOff } ?: defaultTemplates[3]
        val before = targets.mapNotNull { t -> events.firstOrNull { it.id == t.id } }
        val beforeAlarms = alarms.filter { a -> before.any { it.id == a.eventId } }
        before.forEach { ev ->
            upsertShift(
                date = ev.date, title = offTpl.title, type = ShiftType.OFF, start = offTpl.startTime, end = offTpl.endTime,
                wakeOffset = 0, leaveOffset = 0, note = ev.note, attachments = ev.attachments,
                regenerateAlarms = true, autoSave = false
            )
        }
        val createdIds = before.mapNotNull { ev -> events.firstOrNull { it.date == ev.date }?.id }
        return {
            events.removeAll { it.id in createdIds }
            before.forEach { ev ->
                events.removeAll { it.date == ev.date }
                events.add(ev)
            }
            beforeAlarms.forEach { a ->
                if (alarms.none { it.id == a.id }) {
                    alarms.add(a)
                    NativeAlarmScheduler.scheduleAlarm(context, a)
                }
            }
            logEvent(tr("復原：{0} 天改回原本的班", before.size))
        }
    }

    /** 國定假日面板：勾選的國定假日上班改成休假，提示可以「復原」 */
    fun convertHolidayShiftsToOff(targets: List<ShiftEvent>) {
        if (targets.isEmpty()) return
        val undo = convertShiftsToOff(targets)
        persistAllData()
        logEvent(tr("國定假日：{0} 天上班改成休假", targets.size))
        AppToast.show(tr("🌿 已把 {0} 天改成休假，那天的上班鬧鐘一起關掉", targets.size), tr("復原")) {
            undo()
            persistAllData()
            AppToast.show(tr("✅ 已復原"))
        }
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
            // 備份檔可能來自別人：先完整驗證、整理成乾淨的資料（見 ImportGuard），全部沒問題才一次套用，
            // 失敗時原本的資料完全不動
            runCatching {
                val content = ImportGuard.readText(context, uri)
                val clean = ImportGuard.parseBackup(context.packageName, gson, content, today)

                clean.themeName?.let { name -> runCatching { MorandiTheme.valueOf(name) }.getOrNull()?.let { themePreset = it } }
                appPrefs = clean.prefs
                partnerSync = clean.partnerSync
                if (clean.templates.isNotEmpty()) {
                    templates.clear()
                    templates.addAll(clean.templates)
                }
                alarms.forEach { NativeAlarmScheduler.cancelAlarm(context, it) }
                events.clear()
                events.addAll(clean.events)
                alarms.clear()
                alarms.addAll(clean.alarms)
                // 鈴聲庫：內建鈴聲在前，加上備份裡使用者自己的音檔
                val mergedRt = BuiltInRingtones.mergeLibrary(context, clean.ringtones)
                ringtoneLibrary.clear()
                ringtoneLibrary.addAll(mergedRt)
                // 舊備份裡的系統音效設定換成新的內建鈴聲
                appPrefs = BuiltInRingtones.migratePrefs(context, appPrefs)
                for (i in alarms.indices) alarms[i] = BuiltInRingtones.migrateAlarm(context, alarms[i])

                persistAllData() // 會重新排程所有鬧鐘
                logEvent(tr("成功從 JSON 還原 {0} 筆排班與 {1} 個鬧鐘", events.size, alarms.size))
                if (clean.skipped > 0) AppLog.w("Import", "備份還原：略過 ${clean.skipped} 筆格式不正確的資料")
                AppToast.show(
                    if (clean.skipped > 0) tr("✅ 備份還原完成（略過 {0} 筆格式不正確的資料）", clean.skipped)
                    else tr("✅ 備份還原完成！")
                )
            }.onFailure {
                AppLog.w("Import", "備份還原失敗", it)
                AppToast.show(if (it is ImportException) it.message ?: tr("❌ 還原失敗：格式不相容") else tr("❌ 還原失敗：格式不相容"))
            }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                // App 目前的資料摘要（不含備註內容、附件與 Email）
                val now = LocalDateTime.now()
                val appState = buildString {
                    val sorted = events.sortedBy { it.date }
                    appendLine("排班：${events.size} 天（${sorted.firstOrNull()?.date ?: "-"} ～ ${sorted.lastOrNull()?.date ?: "-"}）；" +
                        events.groupingBy { it.type }.eachCount().entries.joinToString("、") { "${it.key.name} ${it.value}" })
                    appendLine("班別模板：" + templates.joinToString("；") { "${it.title}(${it.type.name}${if (it.isOff) "/休" else ""} ${it.startTime}-${it.endTime} 起床-${it.wakeOffsetMinutes} 出門-${it.leaveOffsetMinutes})" })
                    val future = alarms.filter { it.enabled && LocalDateTime.of(it.date, it.triggerTime).isAfter(now) }.sortedBy { LocalDateTime.of(it.date, it.triggerTime) }
                    appendLine("鬧鐘：共 ${alarms.size} 個，開啟 ${alarms.count { it.enabled }} 個，未來會響 ${future.size} 個")
                    future.take(15).forEach { appendLine("  - ${it.date} ${it.triggerTime} ${it.stage.name} ${it.label.take(30)}  鈴聲=${it.ringtoneName}  貪睡上限=${it.maxSnoozeCount}") }
                    appendLine("設定：漸進音量=${appPrefs.fadeInVolume}、貪睡上限=${appPrefs.maxSnoozeCount}、出門禁止貪睡=${appPrefs.noSnoozeOnLeave}、" +
                        "休假防呆=${appPrefs.autoHolidaySafeguard}、自動檢查更新=${appPrefs.autoCheckUpdate}、主題=${themePreset.name}/${appPrefs.colorMode}、" +
                        "語言=${if (I18n.isEn) "English" else "中文"}、週一起始=${appPrefs.weekStartsOnMonday}")
                    appendLine("鈴聲庫：${ringtoneLibrary.size} 首（自己匯入 ${ringtoneLibrary.count { !it.isBuiltIn }} 首）；預設起床=${appPrefs.defaultWakeRingtoneName}、出門=${appPrefs.defaultLeaveRingtoneName}")
                    appendLine("雲端：${if (CloudSync.user != null) "已登入" else "未登入"}、共享=${CloudSync.profile?.publicLinkEnabled ?: "-"}、" +
                        "訂閱 ${CloudSync.subscriptions.size} 個、訂閱我的人 ${CloudSync.readers.size} 個")
                    appendLine("這次開啟 App 的畫面紀錄：")
                    runtimeLogs.takeLast(100).forEach { appendLine("  $it") }
                }
                val report = AppLog.buildReport(context, appState)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                }
                AppToast.show(tr("📄 診斷日誌 .txt 已匯出"))
            }
        }
    }

    // 啟動時載入所有資料：讀檔與解析都在背景執行緒（見 AppPersistence.load），解析完一次套用到畫面
    LaunchedEffect(Unit) {
        logEvent(tr("Reverie Alarm 原生核心啟動"))
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            AlarmEngine.ensureChannel(context)
            AppPersistence.load(context.applicationContext, today)
        }
        loaded.theme?.let { themePreset = it }
        appPrefs = loaded.prefs
        loaded.partner?.let { partnerSync = it }
        ringtoneLibrary.clear()
        ringtoneLibrary.addAll(loaded.ringtones)
        loaded.templates?.let {
            templates.clear()
            templates.addAll(it)
        }
        if (loaded.source != LoadSource.EMPTY) {
            events.clear()
            events.addAll(loaded.events)
            alarms.clear()
            alarms.addAll(loaded.alarms)
        } else if (events.isEmpty() && templates.size >= 4) {
            // 第一次安裝：放幾天示範排班
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
            for (i in alarms.indices) alarms[i] = BuiltInRingtones.migrateAlarm(context, alarms[i])
        }
        // 套用完才開放存檔（之前的空畫面不會蓋掉存檔）
        AppPersistence.markLoaded()
        persistAllData()
        // 第一個畫面出來之後，再把其他分頁一頁一頁組好：之後左右切換分頁不用臨時組出整頁
        for (n in 1..4) {
            kotlinx.coroutines.delay(250)
            tabsKeptComposed = n
        }
    }

    // 換分頁、換月份、換檢視模式、開啟批次模式時收起「編輯」膠囊。
    // 這些值只在協程裡讀：換分頁、換月份時不會讓整個主畫面重組（以前當 LaunchedEffect 的鍵，每次都整個重組）
    LaunchedEffect(Unit) {
        snapshotFlow { listOf<Any>(selectedTab, currentYearMonth, viewMode, batchMode) }.collect { quickCapsuleDate = null }
    }

    // 返回鍵：先收起開著的東西，再回到排班日曆，最後才離開 App
    // （判斷交給小元件去讀：點日期、換分頁時不必讓整個主畫面重組）
    AppBackHandler(
        enabled = { drawerState.isOpen || batchMode || quickCapsuleDate != null || viewingOwnerCode != null || selectedTab != 0 }
    ) {
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

    val colorScheme = remember(currentTheme) { if (!currentTheme.isDark) {
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
    } }

    val tomorrow = today.plusDays(1)
    val holidayPolicy = remember(appPrefs) { appPrefs.holidayPolicy() }
    // 明天的休假防呆（規則見 HolidayRules.kt）：有排班就以排班為準、鬧鐘看歸屬不看日期、只建議不自動關。
    // 只在資料改了（或每分鐘，已經響過的鬧鐘不算）才重新檢查；結果一樣時不會讓畫面重組
    val guardClock = rememberMinuteNowState()
    val guard by remember(tomorrow, holidayPolicy) {
        derivedStateOf { HolidayGuard.check(tomorrow, guardClock.value, events, alarms, holidayPolicy) }
    }
    // 有要確認的事，而且不是已經按過「全部保留」的同一組
    val guardPending = guard.attention.isNotEmpty() && guard.attentionKey != safeguardDismissKey
    LaunchedEffect(guard.attentionKey, safeguardDismissKey, appPrefs.autoHolidaySafeguard) {
        if (appPrefs.autoHolidaySafeguard && guardPending) {
            kotlinx.coroutines.delay(900)
            showSafeguard = true
        }
    }

    fun rememberGuardDismissed(key: String) {
        if (key.isEmpty()) return
        safeguardDismissKey = key
        GuardPrefs.setDismissed(context, key)
    }

    /** 防呆視窗：「全部保留」（或點外面關掉）＝這組問題不再追問 */
    fun keepAllGuard() {
        showSafeguard = false
        rememberGuardDismissed(guard.attentionKey)
        if (guard.attention.isNotEmpty()) EveningGuard.cancelNotice(context) // 前一晚提醒的通知也收掉
    }

    /** 防呆視窗：只處理勾選的項目（暫停鬧鐘、國定假日上班改成休假），提示可以「復原」 */
    fun applyGuard(selected: List<GuardItem>) {
        val fullKey = guard.attentionKey
        val paused = mutableListOf<String>()
        selected.filter { it.kind != GuardKind.HOLIDAY_SHIFT }.flatMap { it.alarms }.forEach { a ->
            val idx = alarms.indexOfFirst { it.id == a.id }
            if (idx != -1 && alarms[idx].enabled) {
                val updated = alarms[idx].copy(enabled = false)
                alarms[idx] = updated
                NativeAlarmScheduler.cancelAlarm(context, updated)
                paused += updated.id
            }
        }
        val shiftsOff = selected.filter { it.kind == GuardKind.HOLIDAY_SHIFT }.mapNotNull { it.event }
        val undoOff = if (shiftsOff.isNotEmpty()) convertShiftsToOff(shiftsOff) else null
        persistAllData()
        showSafeguard = false
        EveningGuard.cancelNotice(context) // 已經在 App 裡處理了，前一晚提醒的通知收掉
        // 沒勾的＝決定保留：剩下的那組不再追問
        rememberGuardDismissed(guard.attentionKeyWithout(selected))
        logEvent(tr("休假防呆：暫停 {0} 個鬧鐘、{1} 天改成休假", paused.size, shiftsOff.size))
        val msg = when {
            shiftsOff.isEmpty() -> tr("🛡️ 已暫停 {0} 個鬧鐘", paused.size)
            paused.isEmpty() -> tr("🛡️ 已把 {0} 天改成休假，那天的鬧鐘一起關掉", shiftsOff.size)
            else -> tr("🛡️ 已暫停 {0} 個鬧鐘、{1} 天改成休假", paused.size, shiftsOff.size)
        }
        AppToast.show(msg, tr("復原")) {
            paused.forEach { id ->
                val idx = alarms.indexOfFirst { it.id == id }
                if (idx != -1) {
                    val updated = alarms[idx].copy(enabled = true)
                    alarms[idx] = updated
                    NativeAlarmScheduler.scheduleAlarm(context, updated)
                }
            }
            undoOff?.invoke()
            persistAllData()
            rememberGuardDismissed(fullKey) // 復原了就是決定保留，不要馬上又跳出來
            logEvent(tr("休假防呆：復原"))
            AppToast.show(tr("✅ 已復原"))
        }
    }

    // 前一晚提醒的通知按鈕：App 開著時由這裡改資料，畫面和存檔才會一致（見 EveningGuard.kt）
    DisposableEffect(Unit) {
        GuardBridge.host = object : GuardBridge.Host {
            override fun events(): List<ShiftEvent> = events.toList()
            override fun alarms(): List<ShiftAlarm> = alarms.toList()
            override fun prefs(): AppPreferences = appPrefs
            override fun setAlarmsEnabled(ids: Collection<String>, enabled: Boolean): Int {
                var changed = 0
                for (i in alarms.indices) {
                    val a = alarms[i]
                    if (a.id in ids && a.enabled != enabled) {
                        val updated = a.copy(enabled = enabled)
                        alarms[i] = updated
                        NativeAlarmScheduler.scheduleAlarm(context, updated)
                        changed++
                    }
                }
                if (changed > 0) {
                    persistAllData()
                    logEvent(tr("前一晚提醒：{0} {1} 個鬧鐘", if (enabled) tr("恢復") else tr("暫停"), changed))
                }
                return changed
            }
            override fun markDismissed(key: String) = rememberGuardDismissed(key)
            override fun toast(text: String) = AppToast.show(text)
        }
        onDispose { GuardBridge.host = null }
    }
    // 點前一晚提醒的通知進來：直接打開防呆視窗
    LaunchedEffect(GuardBridge.openRequested) {
        if (GuardBridge.openRequested) {
            GuardBridge.openRequested = false
            selectedTab = 0
            showSafeguard = true
        }
    }

    // 日曆上方的提醒列（按過「全部保留」就收起來）
    val guardBanner: String? = when {
        !guardPending -> null
        guard.attention.any { it.defaultChecked } -> tr("⚠️ 明天休假，但還有 {0} 個上班鬧鐘開著", guard.attention.count { it.defaultChecked })
        else -> guard.day.event?.let { ev -> tr("🇹🇼 明天是{0}，你排了{1}，要照常上班嗎？", tr(guard.day.holiday?.name ?: ""), tr(ev.title)) }
    }

    // 訂閱清單：內容變了才換新清單（以前每次重組都複製一份新的，側邊選單因此每次都跟著重組）
    val drawerSubscriptions by remember { derivedStateOf { CloudSync.subscriptions.toList() } }
    // 批次模式的底部選單：只有「是否顯示」改變時才需要重組
    val showBatchBar by remember { derivedStateOf { selectedTab == 0 && batchMode } }

    MaterialTheme(colorScheme = colorScheme) {
    CompositionLocalProvider(LocalSurface provides SurfaceInfo(currentTheme.bgCard, !currentTheme.isDark)) {
        TopToastHost(currentTheme)
        AlarmPermissionGuard(forceShow = showAlarmCheck, onClose = { showAlarmCheck = false })
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ReverieDrawerContent(
                    // 月份、目前分頁用函式傳入：換月份、換分頁時只有選單裡用到的那一小塊重組
                    currentYearMonth = { currentYearMonth },
                    events = displayEvents,
                    viewMode = viewMode,
                    isCalendarTab = { selectedTab == 0 },
                    accountEmail = CloudSync.user?.email,
                    accentHex = appPrefs.customAccentHex,
                    guard = guard,
                    holidayPolicy = holidayPolicy,
                    autoCheckUpdate = appPrefs.autoCheckUpdate,
                    palette = currentTheme,
                    subscriptions = drawerSubscriptions,
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
                    // 手動檢測：一律打開視窗，沒有衝突時也列出明天哪些鬧鐘會照常響
                    onRunSafeguardCheck = { showSafeguard = true },
                    onOpenHolidays = { showHolidaySheet = true },
                    onOpenSettings = { showPreferencesDialog = true },
                    onOpenAbout = { showAboutSheet = true },
                    onCloseDrawer = { coroutineScope.launch { drawerState.close() } }
                )
            }
        ) {
            // 背景：底色＋（可關閉的）隨機小星星；畫面本身透明，星星從卡片之間透出來
            Box(Modifier.fillMaxSize().background(currentTheme.bgBase)) {
            if (appPrefs.backgroundStars) {
                StarfieldBackground(
                    amount = appPrefs.starDensity,
                    seed = appPrefs.starSeed,
                    accent = currentTheme.accentRaw,
                    isDark = currentTheme.isDark,
                    twinkle = appPrefs.animationsEnabled,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    Column {
                    WebTopBar(
                        yearMonth = currentYearMonth,
                        selectedDate = selectedDate,
                        showBatchButton = selectedTab == 0 && viewingOwnerCode == null,
                        batchMode = batchMode,
                        safeguardAlert = guard.attention.isNotEmpty(),
                        theme = currentTheme,
                        showMoreMenu = showMoreMenu,
                        onToggleMoreMenu = { showMoreMenu = it },
                        onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                        // 週／日視圖時，頂部箭頭改成上一週／下一週（上一天／下一天）
                        onPrev = {
                            when {
                                selectedTab == 0 && viewMode == CalendarViewMode.WEEK -> selectedDate = selectedDate.minusWeeks(1)
                                selectedTab == 0 && viewMode == CalendarViewMode.DAY -> selectedDate = selectedDate.minusDays(1)
                                else -> currentYearMonth = currentYearMonth.minusMonths(1)
                            }
                            if (selectedTab == 0 && (viewMode == CalendarViewMode.WEEK || viewMode == CalendarViewMode.DAY)) currentYearMonth = YearMonth.from(selectedDate)
                        },
                        onNext = {
                            when {
                                selectedTab == 0 && viewMode == CalendarViewMode.WEEK -> selectedDate = selectedDate.plusWeeks(1)
                                selectedTab == 0 && viewMode == CalendarViewMode.DAY -> selectedDate = selectedDate.plusDays(1)
                                else -> currentYearMonth = currentYearMonth.plusMonths(1)
                            }
                            if (selectedTab == 0 && (viewMode == CalendarViewMode.WEEK || viewMode == CalendarViewMode.DAY)) currentYearMonth = YearMonth.from(selectedDate)
                        },
                        onToday = {
                            currentYearMonth = YearMonth.from(today)
                            selectedDate = today
                            // 週／日視圖時留在原本的檢視，只跳回今天
                            if (viewMode != CalendarViewMode.WEEK && viewMode != CalendarViewMode.DAY) viewMode = CalendarViewMode.MONTH
                        },
                        onToggleBatch = {
                            batchMode = !batchMode
                            if (!batchMode) batchSelectedDates.clear()
                        },
                        onSearch = { showSearchDialog = true },
                        onAddShift = { showEditSheet = true },
                        onAddAlarm = { showAddAlarmDialog = true }
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
                        // 5 個分頁都一直組好：開始滑的那一幀不用再從頭組出新分頁（以前那一幀要 20ms）。
                        // App 開啟後再一頁一頁補上，不拖慢第一個畫面
                        beyondViewportPageCount = tabsKeptComposed,
                        key = { it }
                    ) { page ->
                    when (page) {
                        0 -> {
                            if (viewMode == CalendarViewMode.MONTH) {
                                CalendarScreen(
                                    onOpenWeather = { toolSection = ToolSection.WEATHER; selectedTab = 3 },
                                    yearMonth = currentYearMonth,
                                    today = today,
                                    selectedDate = selectedDate,
                                    quickCapsuleDate = if (viewingOwnerCode != null) null else quickCapsuleDate,
                                    events = displayEvents,
                                    alarms = if (viewingOwnerCode != null) emptyList() else alarms,
                                    readOnly = viewingOwnerCode != null,
                                    batchSelectedDates = batchSelectedDates,
                                    guardBanner = if (viewingOwnerCode != null) null else guardBanner,
                                    holidayPolicy = holidayPolicy,
                                    partnerOffDates = partnerSync.compareOffDates(today),
                                    weekStartsOnMonday = appPrefs.weekStartsOnMonday,
                                    fontScale = 1f, // 字級已由全域縮放處理
                                    theme = currentTheme,
                                    effectiveAccent = effectiveAccent,
                                    effectiveCardBg = effectiveCardBg,
                                    onMonthChange = { currentYearMonth = it },
                                    // 提醒列只打開防呆視窗，逐項確認，不會一鍵全關
                                    onOpenGuard = { showSafeguard = true },
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
                                    holidayPolicy = holidayPolicy,
                                    theme = currentTheme,
                                    onSelectDateAndSwitchMonth = { targetDate ->
                                        selectedDate = targetDate
                                        currentYearMonth = YearMonth.from(targetDate)
                                        viewMode = CalendarViewMode.MONTH
                                    },
                                    weekStartsOnMonday = appPrefs.weekStartsOnMonday,
                                    onDateChange = { d ->
                                        selectedDate = d
                                        currentYearMonth = YearMonth.from(d)
                                    },
                                    onOpenDay = { d ->
                                        selectedDate = d
                                        currentYearMonth = YearMonth.from(d)
                                        viewMode = CalendarViewMode.DAY
                                    },
                                    onEditDate = { d ->
                                        selectedDate = d
                                        currentYearMonth = YearMonth.from(d)
                                        // 看別人的班表時唯讀，只選取日期
                                        if (viewingOwnerCode == null) showEditSheet = true
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
                                val entry = RingtoneEntry(name = ImportGuard.text(fileName, ImportGuard.MAX_AUDIO_NAME), uriString = uri.toString(), isBuiltIn = false)
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
                            holidaySummary = holidaySummary(holidayPolicy),
                            onOpenHolidays = { showHolidaySheet = true },
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

                        3 -> {
                            // 分頁一直組好，所以要告訴碼錶／計時「現在看不看得到」：看不到時停止每幀更新，App 才能真正閒置
                            val toolsVisible by remember {
                                derivedStateOf { kotlin.math.abs(tabPagerState.currentPage + tabPagerState.currentPageOffsetFraction - 3f) < 1f }
                            }
                            CompositionLocalProvider(LocalTabVisible provides toolsVisible) {
                                ToolsScreen(
                                    section = toolSection,
                                    theme = currentTheme,
                                    onSectionChange = { toolSection = it; ToolNav.save(context, it) },
                                    weather = { WeatherScreen(theme = currentTheme, events = events, alarms = alarms) },
                                    stopwatch = { StopwatchScreen(currentTheme) },
                                    timer = { TimerScreen(currentTheme) },
                                    widgets = {
                                        WidgetsPreviewScreen(
                                            events = events,
                                            holidayPolicy = holidayPolicy,
                                            weekStartsOnMonday = appPrefs.weekStartsOnMonday,
                                            nextAlarm = LocalDateTime.now().let { now ->
                                                alarms.filter { it.enabled && LocalDateTime.of(it.date, it.triggerTime).isAfter(now) }
                                                    .minByOrNull { LocalDateTime.of(it.date, it.triggerTime) }
                                            },
                                            theme = currentTheme,
                                            effectiveAccent = effectiveAccent,
                                            showTitle = false
                                        )
                                    }
                                )
                            }
                        }

                        4 -> AiScheduleScreen(
                            state = aiChat,
                            templates = templates,
                            events = events,
                            baseMonth = { currentYearMonth },
                            holidayPolicy = holidayPolicy,
                            theme = currentTheme,
                            onApply = { plan, clears -> applySchedulePlan(plan, clears) }
                        )
                    }
                    }

                    if (showBatchBar) {
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
                                                        // 見紅休：上班的班別先略過放假的國定假日，提示上可以一鍵加回
                                                        val isWork = !tpl.isOff && tpl.type.isWorkShift
                                                        val skipped = if (isWork) batchSelectedDates.filter { holidayPolicy.skipsShiftOn(it) }.sorted() else emptyList()
                                                        val targets = batchSelectedDates.filter { it !in skipped }
                                                        targets.forEach { d -> applyTemplateToDate(d, tpl, autoSave = false) }
                                                        persistAllData()
                                                        batchSelectedDates.clear()
                                                        batchMode = false
                                                        if (skipped.isEmpty()) {
                                                            AppToast.show(tr("⚡ 已批次排入 {0} 天「{1}」", targets.size, tr(tpl.title)))
                                                        } else {
                                                            AppToast.show(
                                                                if (targets.isEmpty()) tr("⚡ 選的 {0} 天都是國定假日，先略過了", skipped.size)
                                                                else tr("⚡ 已排入 {0} 天「{1}」，略過 {2} 天國定假日", targets.size, tr(tpl.title), skipped.size),
                                                                tr("也排假日")
                                                            ) {
                                                                skipped.forEach { d -> applyTemplateToDate(d, tpl, autoSave = false) }
                                                                persistAllData()
                                                                AppToast.show(tr("⚡ 國定假日也排入「{0}」（{1} 天）", tr(tpl.title), skipped.size))
                                                            }
                                                        }
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
            } // 背景（底色＋星星）
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
                holiday = holidayPolicy.holidayOn(editDate),
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
                    // 這天的班的鬧鐘＋這天響、不屬於任何現有班的鬧鐘；
                    // 這天晚上響、但屬於隔天大夜班的起床鬧鐘不能一起刪（鬧鐘看歸屬，不看日期）
                    val toRemove = alarms.filter { a ->
                        a.eventId in oldIds || (a.date == editDate && (a.eventId == null || events.none { it.id == a.eventId }))
                    }
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
                // 前一晚提醒：現在檢查一次（和晚上自動檢查走同一條路）
                onTestEveningGuard = {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        EveningGuard.runCheck(context.applicationContext)
                    }
                },
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

        // 休假日鬧鐘防呆：逐項勾選、寫清楚原因（關閉動畫期間用最後一次的內容撐住）
        val guardHolder = remember { arrayOfNulls<GuardReport>(1) }
        if (showSafeguard) guardHolder[0] = guard
        MotionHost(showSafeguard) {
            (if (showSafeguard) guard else guardHolder[0])?.let { report ->
                HolidayGuardDialog(
                    report = report,
                    palette = currentTheme,
                    onKeepAll = { keepAllGuard() },
                    onApply = { applyGuard(it) },
                    onEditDay = { d ->
                        showSafeguard = false
                        selectedDate = d
                        currentYearMonth = YearMonth.from(d)
                        selectedTab = 0
                        showEditSheet = true
                    }
                )
            }
        }

        // 國定假日設定面板（側邊欄、班別模板頁都從這裡進來）
        MotionHost(showHolidaySheet) {
            HolidaySettingsSheet(
                prefs = appPrefs,
                events = events,
                today = today,
                palette = currentTheme,
                onUpdate = { updated ->
                    appPrefs = updated
                    persistAllData()
                },
                onConvertToOff = { convertHolidayShiftsToOff(it) },
                onDismiss = { showHolidaySheet = false }
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
                        // 只掛在上班或行程上；休假日自己加的鬧鐘不掛在休假上，防呆才不會把它當成「忘了關的上班鬧鐘」
                        eventId = events.firstOrNull { it.date == date && it.type != ShiftType.OFF }?.id?.takeIf { stage != AlarmStage.CUSTOM },
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
    /** 日曆上方的休假防呆提醒列（null＝不顯示） */
    guardBanner: String?,
    holidayPolicy: HolidayPolicy,
    partnerOffDates: List<String>,
    weekStartsOnMonday: Boolean,
    fontScale: Float,
    theme: ThemePalette,
    effectiveAccent: Color,
    effectiveCardBg: Color,
    onMonthChange: (YearMonth) -> Unit,
    onOpenWeather: () -> Unit = {},
    onOpenGuard: () -> Unit,
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

    // 月曆用的查表：資料變了才重建，格子裡 O(1) 查（原本每一格都掃一次整份班表與鬧鐘清單）。
    // events／alarms 是 SnapshotStateList 時，derivedStateOf 會在內容改變時自動重算
    val eventByDate by remember(events) { derivedStateOf { firstEventByDate(events) } }
    val activeAlarmDates by remember(alarms) { derivedStateOf { activeAlarmDatesOf(alarms) } }
    val batchSet by remember(batchSelectedDates) { derivedStateOf { batchSelectedDates.toHashSet() } }
    val partnerOffSet = remember(partnerOffDates) {
        partnerOffDates.mapNotNullTo(HashSet()) { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    val weather = WeatherRepo.data?.takeIf { it.place == WeatherRepo.place }

    val selectedEvent = eventByDate[selectedDate]
    val selectedStatus = DayResolver.resolveDay(selectedDate, selectedEvent, holidayPolicy)
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

        if (guardBanner != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x33F59E0B))
                        .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(16.dp))
                        .clickable { onOpenGuard() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconText(
                        text = guardBanner,
                        color = Color(0xFFFCD34D).readable(),
                        fontSize = (12 * fontScale).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    // 只打開防呆視窗逐項確認，不會一鍵全關
                    TextButton(onClick = onOpenGuard, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(tr("查看"), color = Color(0xFFFBBF24).readable(), fontWeight = FontWeight.Black, fontSize = 12.sp)
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
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxWidth()
                    // 高度隨拖曳在兩個月的列數之間平滑變化（5 列 ↔ 6 列）。
                    // 拖曳位置每一幀都在變，所以只在「排版階段」讀：滑動時只重新排版，不會整個月曆重組
                    .layout { measurable, constraints ->
                        val frac = pager.currentPageOffsetFraction
                        val from = gridHeightFor(monthRows(monthOfPage(pager.currentPage), weekStartsOnMonday))
                        val to = gridHeightFor(monthRows(monthOfPage(pager.currentPage + if (frac >= 0f) 1 else -1), weekStartsOnMonday))
                        val h = (from + (to - from) * kotlin.math.abs(frac)).roundToPx()
                        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
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
                MonthGrid(
                    ym = monthOfPage(page),
                    weekStartsOnMonday = weekStartsOnMonday,
                    today = today,
                    selectedDate = selectedDate,
                    quickCapsuleDate = quickCapsuleDate,
                    eventByDate = eventByDate,
                    activeAlarmDates = activeAlarmDates,
                    batchSelected = batchSet,
                    partnerOff = partnerOffSet,
                    holidayPolicy = holidayPolicy,
                    weather = weather,
                    fontScale = fontScale,
                    theme = theme,
                    effectiveAccent = effectiveAccent,
                    effectiveCardBg = effectiveCardBg,
                    onDateClick = onDateClick,
                    onOpenEditSheet = onOpenEditSheet
                )
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
                                selectedStatus.holiday?.let { h ->
                                    Spacer(Modifier.width(6.dp))
                                    WebPill(tr(h.name), ROSE, fontSize = 9.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedEvent == null && selectedStatus.kind == DayKind.HOLIDAY_OFF) {
                                    // 見紅休：沒排班的國定假日＝放假
                                    Icon(Tabler.Leaf, contentDescription = null, tint = EMERALD.readable(), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(tr("國定假日放假"), color = theme.textMain, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                                } else if (selectedEvent != null) {
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

/** 返回鍵：要不要攔截由這個小元件自己讀（點日期、換分頁時只有它重組，不是整個主畫面） */
@Composable
private fun AppBackHandler(enabled: () -> Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled(), onBack = onBack)
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

// 桌面小工具頁（預覽與一鍵加到桌面）在 WidgetsScreen.kt
