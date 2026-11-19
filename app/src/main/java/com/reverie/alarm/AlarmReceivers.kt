package com.reverie.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat

// ============================================================================
// 時間到 → 啟動響鈴服務（服務負責亮屏、播鈴聲、跳出全螢幕畫面）
// ============================================================================
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 舊版（Gemini 版本）排的鬧鐘沒有 key，新引擎已重新排程，這裡直接略過
        if (intent.action != AlarmEngine.ACTION_FIRE) return
        val key = intent.getStringExtra(AlarmEngine.EXTRA_KEY) ?: return
        val spec = AlarmEngine.getSpec(context, key) ?: return

        // 先抓住 CPU，避免服務還沒啟動手機又睡回去
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReverieAlarm:fire").acquire(30_000L)

        // 已觸發的鬧鐘從排程清單移除（避免重開機後重複響）
        AlarmEngine.removeSpec(context, key)
        // 桌面小工具改顯示下一個鬧鐘
        runCatching { ShiftWidgetProvider.refresh(context) }

        val serviceIntent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_START
            putExtra(AlarmEngine.EXTRA_SPEC_JSON, AlarmEngine.specToJson(spec))
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            // 極少數情況（例如沒有精準鬧鐘權限時）系統不允許從背景啟動服務
            android.util.Log.e("ReverieAlarm", "無法啟動響鈴服務", e)
        }
    }
}

// ============================================================================
// 開機、App 更新、手動改時間或時區後 → 把所有鬧鐘重新排回系統
// ============================================================================
class AlarmRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                AlarmEngine.rescheduleAll(context)
                runCatching { ShiftWidgetProvider.refresh(context) }
            }
        }
    }
}
