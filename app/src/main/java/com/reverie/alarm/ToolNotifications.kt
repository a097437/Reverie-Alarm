package com.reverie.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.format.DateTimeFormatter

// ============================================================================
// 碼錶／計時的常駐通知（像系統時鐘 App）
//   - 碼錶跑的時候：通知裡的時間自己往上跳（系統的 Chronometer），有「計次」「暫停」
//   - 計時倒數的時候：通知裡的時間自己往下數，有「暫停」「+1 分鐘」「取消」，時間到自動消失（改由響鈴接手）
//   - 暫停時：顯示停住的時間，有「繼續」「重設／取消」，可以滑掉
//   - 點通知直接打開 App 的「工具 → 碼錶／計時」
// 只用一般通知（不需要常駐服務），時間是系統自己算的，不耗電
// ============================================================================
object ToolNotifications {
    // v2：重要性改成「預設」（不出聲、不震動），小米等系統才不會把它收進「不重要通知」
    private const val CHANNEL = "reverie_tools_v2"
    private const val OLD_CHANNEL = "reverie_tools_v1"
    private const val ID_STOPWATCH = 7401
    private const val ID_TIMER = 7402

    const val SW_LAP = "com.reverie.alarm.tool.SW_LAP"
    const val SW_PAUSE = "com.reverie.alarm.tool.SW_PAUSE"
    const val SW_RESUME = "com.reverie.alarm.tool.SW_RESUME"
    const val SW_RESET = "com.reverie.alarm.tool.SW_RESET"
    const val TM_PAUSE = "com.reverie.alarm.tool.TM_PAUSE"
    const val TM_RESUME = "com.reverie.alarm.tool.TM_RESUME"
    const val TM_ADD = "com.reverie.alarm.tool.TM_ADD"
    const val TM_CANCEL = "com.reverie.alarm.tool.TM_CANCEL"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("碼錶與計時"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr("碼錶與計時進行中的時間")
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun canPost(context: Context) = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code, Intent(context, ToolActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openTool(context: Context, section: ToolSection, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java)
                .putExtra(ToolNav.EXTRA, section.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @android.annotation.SuppressLint("MissingPermission") // canPost 已經檢查過
    private fun post(context: Context, id: Int, b: NotificationCompat.Builder) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
            .onFailure { AppLog.w("Tools", "通知顯示失敗", it) }
    }

    private fun cancel(context: Context, id: Int) = runCatching { NotificationManagerCompat.from(context).cancel(id) }

    // ------------------------------------------------------------------ 碼錶

    fun updateStopwatch(context: Context) {
        val s = StopwatchState
        val elapsed = s.elapsed()
        if (!s.running && elapsed == 0L) { cancel(context, ID_STOPWATCH); return }
        ensureChannel(context)
        val laps = s.laps
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(openTool(context, ToolSection.STOPWATCH, 41))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (s.running) {
            val lastSplit = if (laps.isEmpty()) null else laps.last() - (if (laps.size >= 2) laps[laps.size - 2] else 0L)
            b.setContentTitle(tr("碼錶"))
                .setContentText(if (lastSplit != null) tr("第 {0} 圈・上一圈 {1}", laps.size + 1, formatStopwatch(lastSplit)) else tr("計時中"))
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - elapsed)
                .setOngoing(true)
                .addAction(0, tr("計次"), action(context, SW_LAP, 1))
                .addAction(0, tr("暫停"), action(context, SW_PAUSE, 2))
        } else {
            b.setContentTitle(tr("碼錶已暫停・{0}", formatStopwatch(elapsed)))
                .setContentText(if (laps.isNotEmpty()) tr("共 {0} 圈", laps.size) else tr("點一下回到碼錶"))
                .setShowWhen(false)
                .setOngoing(false)
                .addAction(0, tr("繼續"), action(context, SW_RESUME, 3))
                .addAction(0, tr("重設"), action(context, SW_RESET, 4))
        }
        post(context, ID_STOPWATCH, b)
    }

    // ------------------------------------------------------------------ 計時

    fun updateTimer(context: Context) {
        val t = CountdownTimerState
        val now = System.currentTimeMillis()
        if (t.status == TimerStatus.IDLE || t.isFinished(now)) { cancel(context, ID_TIMER); return }
        ensureChannel(context)
        val remaining = t.remaining(now)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openTool(context, ToolSection.TIMER, 42))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (t.status == TimerStatus.RUNNING) {
            b.setContentTitle(tr("計時 {0}", formatTimer(t.durationMs)))
                .setContentText(tr("{0} 結束", t.endTime().format(DateTimeFormatter.ofPattern("HH:mm"))))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(now + remaining)
                .setOngoing(true)
                .setTimeoutAfter(remaining + 1_000L) // 時間到自己消失，改由響鈴接手
                .addAction(0, tr("暫停"), action(context, TM_PAUSE, 11))
                .addAction(0, tr("+1 分鐘"), action(context, TM_ADD, 12))
                .addAction(0, tr("取消"), action(context, TM_CANCEL, 13))
        } else {
            b.setContentTitle(tr("計時已暫停・剩 {0}", formatTimer(remaining)))
                .setContentText(tr("點一下回到計時"))
                .setShowWhen(false)
                .setOngoing(false)
                .addAction(0, tr("繼續"), action(context, TM_RESUME, 14))
                .addAction(0, tr("取消"), action(context, TM_CANCEL, 15))
        }
        post(context, ID_TIMER, b)
    }

    fun cancelTimer(context: Context) { cancel(context, ID_TIMER) }

    /** App 開啟、開機或更新後：還在跑的碼錶／計時把通知補回來 */
    fun restore(context: Context) {
        StopwatchState.load(context)
        CountdownTimerState.load(context)
        if (StopwatchState.running) updateStopwatch(context)
        if (CountdownTimerState.status == TimerStatus.RUNNING && !CountdownTimerState.isFinished()) updateTimer(context)
    }
}

/** 通知上的按鈕 */
class ToolActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        I18n.ensure(context.applicationContext)
        StopwatchState.load(context)
        CountdownTimerState.load(context)
        when (intent.action) {
            ToolNotifications.SW_LAP -> StopwatchState.lap(context)
            ToolNotifications.SW_PAUSE -> StopwatchState.pause(context)
            ToolNotifications.SW_RESUME -> StopwatchState.start(context)
            ToolNotifications.SW_RESET -> StopwatchState.reset(context)
            ToolNotifications.TM_PAUSE -> CountdownTimerState.pause(context)
            ToolNotifications.TM_RESUME -> CountdownTimerState.resume(context)
            ToolNotifications.TM_ADD -> CountdownTimerState.addMinute(context)
            ToolNotifications.TM_CANCEL -> CountdownTimerState.reset(context)
        }
    }
}
