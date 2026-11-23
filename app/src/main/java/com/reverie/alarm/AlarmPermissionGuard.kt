package com.reverie.alarm

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

// ============================================================================
// 鬧鐘權限檢查：缺少任何一項會導致鬧鐘不響，啟動時自動提醒
// ============================================================================

private data class AlarmHealth(
    val notifications: Boolean,
    val exactAlarm: Boolean,
    val fullScreen: Boolean,
    val batteryUnrestricted: Boolean
) {
    val allCriticalOk: Boolean get() = notifications && exactAlarm && fullScreen
}

private fun readHealth(context: Context): AlarmHealth {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return AlarmHealth(
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        exactAlarm = AlarmEngine.canScheduleExact(context),
        fullScreen = AlarmEngine.canUseFullScreen(context),
        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
    )
}

private fun openSafely(context: Context, vararg intents: Intent) {
    for (i in intents) {
        val ok = runCatching {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
    AppToast.show(tr("無法開啟設定頁，請到手機「設定 → 應用程式」手動調整"))
}

private fun appDetailsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

/**
 * @param forceShow 從選單手動打開時為 true（即使權限都正常也顯示，方便測試）
 * @param onClose 關閉對話框
 */
@Composable
fun AlarmPermissionGuard(forceShow: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var health by remember { mutableStateOf(readHealth(context)) }
    var dismissedThisSession by remember { mutableStateOf(false) }
    val prefs = remember { context.applicationContext.getSharedPreferences("reverie_alarm_engine", Context.MODE_PRIVATE) }
    // 品牌自己的背景執行設定沒辦法偵測，使用者按「我已設定」後記住（舊版的小米紀錄也算）
    var oemAcked by remember {
        mutableStateOf(prefs.getBoolean("oem_background_acked", false) || prefs.getBoolean("xiaomi_autostart_acked", false))
    }

    // 使用者跑去設定頁再回來時，自動重新檢查
    LaunchedEffect(Unit) {
        while (true) {
            health = readHealth(context)
            delay(1500)
        }
    }

    val needsAttention = !health.allCriticalOk || !health.batteryUnrestricted || (OemBackground.needsExtraStep && !oemAcked)
    val visible = forceShow || (needsAttention && !dismissedThisSession)
    MotionHost(visible) { AlarmHealthDialog(health, oemAcked, onAckOem = {
        oemAcked = true
        prefs.edit().putBoolean("oem_background_acked", true).apply()
    }, onClose = {
        dismissedThisSession = true
        onClose()
    }) }
}

@Composable
private fun AlarmHealthDialog(health: AlarmHealth, oemAcked: Boolean, onAckOem: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val oem = remember { OemBackground.guide(context) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    val close = onClose

    MotionAlertDialog(
        onDismissRequest = close,
        title = { IconText(tr("🛡️ 鬧鐘可靠度檢查"), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (health.allCriticalOk) tr("必要權限都已開啟。可以按下方「1 分鐘後測試」，然後鎖屏等它響。")
                    else tr("以下紅色項目沒開，鬧鐘可能不會響。請逐一點「前往開啟」。"),
                    fontSize = 13.sp
                )
                HealthRow(
                    ok = health.notifications,
                    title = tr("通知權限"),
                    desc = tr("響鈴畫面要靠通知跳出來"),
                    onFix = {
                        openSafely(
                            context,
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            appDetailsIntent(context)
                        )
                    }
                )
                HealthRow(
                    ok = health.exactAlarm,
                    title = tr("精準鬧鐘"),
                    desc = tr("沒開的話鬧鐘可能晚好幾分鐘"),
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            openSafely(
                                context,
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)),
                                appDetailsIntent(context)
                            )
                        }
                    }
                )
                HealthRow(
                    ok = health.fullScreen,
                    title = tr("全螢幕通知"),
                    desc = tr("鎖屏時亮屏跳出響鈴畫面"),
                    onFix = {
                        if (Build.VERSION.SDK_INT >= 34) {
                            openSafely(
                                context,
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)),
                                appDetailsIntent(context)
                            )
                        }
                    }
                )
                HealthRow(
                    ok = health.batteryUnrestricted,
                    title = tr("不限制電池用量"),
                    desc = tr("避免系統省電時把鬧鐘殺掉"),
                    onFix = {
                        openSafely(
                            context,
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)),
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            appDetailsIntent(context)
                        )
                    }
                )
                // 各品牌自己的省電機制（小米、華為、OPPO、vivo、三星…）；原生 Android 不需要
                if (oem != null) {
                    HealthRow(
                        ok = oemAcked,
                        title = tr("{0}：背景執行與自動啟動", oem.brand),
                        desc = oem.steps + tr("。這一項系統查不到，開好後請按「我已設定」"),
                        onFix = { openSafely(context, *oem.intents.toTypedArray()) },
                        extraLabel = if (oemAcked) null else tr("我已設定"),
                        onExtra = onAckOem
                    )
                }
                // 每支手機設定的位置不太一樣：附上社群整理的各品牌教學
                Text(
                    if (oem != null) tr("找不到設定？查看 {0} 的詳細教學 →", oem.brand) else tr("鬧鐘偶爾不響？查看各品牌手機的設定教學 →"),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { runCatching { uriHandler.openUri(oem?.helpUrl ?: OemBackground.GENERIC_HELP) } }
                        .padding(vertical = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val spec = AlarmEngine.scheduleTestAlarm(context, 1)
                AppToast.show(tr("⏰ 已設定 {0} 的測試鬧鐘，現在可以鎖屏等它響", spec.triggerLocal.substring(11, 16)))
                close()
            }) { Text(tr("1 分鐘後測試"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = close) { Text(tr("稍後")) }
        }
    )
}

@Composable
private fun HealthRow(
    ok: Boolean,
    title: String,
    desc: String,
    fixLabel: String = tr("前往開啟"),
    onFix: () -> Unit,
    extraLabel: String? = null,
    onExtra: () -> Unit = {}
) {
    val color = if (ok) Color(0xFF10B981) else Color(0xFFF43F5E)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Tabler.CircleCheck else Tabler.CircleX, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Text(desc, fontSize = 11.sp)
        if (!ok || extraLabel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!ok) TextButton(onClick = onFix) { Text(fixLabel, fontSize = 12.sp) }
                if (extraLabel != null) TextButton(onClick = onExtra) { Text(extraLabel, fontSize = 12.sp) }
            }
        }
    }
}
