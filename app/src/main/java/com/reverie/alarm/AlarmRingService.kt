package com.reverie.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.time.format.DateTimeFormatter

// ============================================================================
// 響鈴服務：真正負責「響」的地方
//   - 前景服務 + 全螢幕通知：鎖屏、App 被關掉也能亮屏跳出畫面
//   - 鈴聲走「鬧鐘音量」，12 秒內由小到大
//   - 貪睡會真的排程 N 分鐘後再響；響超過 10 分鐘自動停止
// ============================================================================
class AlarmRingService : Service() {

    companion object {
        const val ACTION_START = "com.reverie.alarm.RING_START"
        const val ACTION_SNOOZE = "com.reverie.alarm.RING_SNOOZE"
        const val ACTION_DISMISS = "com.reverie.alarm.RING_DISMISS"
        const val ACTION_CLOSE_UI = "com.reverie.alarm.RING_CLOSE_UI"
        private const val NOTIFICATION_ID = 7301
        private const val AUTO_STOP_MS = 10 * 60 * 1000L
        private const val FADE_STEPS = 12
        private const val FADE_STEP_MS = 1000L

        fun canSnooze(spec: AlarmSpec): Boolean = spec.snoozeMinutes > 0 && spec.remainingSnooze > 0

        fun sendAction(context: Context, action: String) {
            val i = Intent(context, AlarmRingService::class.java).apply { this.action = action }
            context.startService(i)
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentSpec: AlarmSpec? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fadeStep = 0

    private val autoStopRunnable = Runnable { finishRinging(showToast = false) }
    private val fadeRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            fadeStep++
            val vol = (0.15f + 0.85f * fadeStep / FADE_STEPS).coerceAtMost(1f)
            runCatching { mp.setVolume(vol, vol) }
            if (fadeStep < FADE_STEPS) handler.postDelayed(this, FADE_STEP_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        I18n.ensure(applicationContext)
        when (intent?.action) {
            ACTION_START -> {
                val spec = AlarmEngine.specFromJson(intent.getStringExtra(AlarmEngine.EXTRA_SPEC_JSON))
                if (spec == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRinging(spec)
            }
            ACTION_SNOOZE -> {
                val spec = currentSpec
                if (spec != null && canSnooze(spec)) {
                    val next = AlarmEngine.scheduleSnooze(this, spec)
                    val timeText = next.triggerDateTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                    Toast.makeText(this, tr("😴 已貪睡，{0} 會再響（剩 {1} 次）", timeText, next.remainingSnooze), Toast.LENGTH_LONG).show()
                }
                finishRinging(showToast = false)
            }
            ACTION_DISMISS -> finishRinging(showToast = true)
            else -> if (currentSpec == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(spec: AlarmSpec) {
        // 如果上一個鬧鐘還在響，先停掉它
        stopSoundAndVibration()
        currentSpec = spec

        AlarmEngine.ensureChannel(this)
        val notification = buildNotification(spec)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReverieAlarm:ringing").apply {
                setReferenceCounted(false)
                acquire(AUTO_STOP_MS + 60_000L)
            }
        }

        startSound(spec.ringtoneUri, spec.fadeIn)
        startVibration()

        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
    }

    private fun ringingActivityIntent(spec: AlarmSpec): Intent =
        Intent(this, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmEngine.EXTRA_SPEC_JSON, AlarmEngine.specToJson(spec))
        }

    private fun buildNotification(spec: AlarmSpec): Notification {
        val fullScreenPi = PendingIntent.getActivity(
            this, 1, ringingActivityIntent(spec),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismissPi = PendingIntent.getService(
            this, 2, Intent(this, AlarmRingService::class.java).apply { action = ACTION_DISMISS },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val timeText = runCatching {
            spec.triggerDateTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrDefault("")

        val builder = NotificationCompat.Builder(this, AlarmEngine.CHANNEL_RINGING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $timeText ${spec.stageLabel}")
            .setContentText(trLabel(spec.label))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)

        if (canSnooze(spec)) {
            val snoozePi = PendingIntent.getService(
                this, 3, Intent(this, AlarmRingService::class.java).apply { action = ACTION_SNOOZE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, tr("貪睡 {0} 分鐘", spec.snoozeMinutes), snoozePi)
        }
        builder.addAction(0, tr("關閉鬧鐘"), dismissPi)
        return builder.build()
    }

    private fun startSound(uriStr: String?, fadeIn: Boolean) {
        val candidates = listOfNotNull(
            BuiltInRingtones.resolve(this, uriStr)?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() },
            Uri.parse(BuiltInRingtones.uri(this, BuiltInRingtones.DEFAULT_WAKE)),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in candidates) {
            val player = MediaPlayer()
            val ok = runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(applicationContext, uri)
                player.isLooping = true
                player.prepare()
                val startVol = if (fadeIn) 0.15f else 1f
                player.setVolume(startVol, startVol)
                player.start()
            }.isSuccess
            if (ok) {
                mediaPlayer = player
                fadeStep = 0
                handler.removeCallbacks(fadeRunnable)
                if (fadeIn) handler.postDelayed(fadeRunnable, FADE_STEP_MS)
                return
            }
            runCatching { player.release() }
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 400, 800, 1200)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        }
    }

    private fun stopSoundAndVibration() {
        handler.removeCallbacks(fadeRunnable)
        mediaPlayer?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
    }

    private fun finishRinging(showToast: Boolean) {
        handler.removeCallbacks(autoStopRunnable)
        stopSoundAndVibration()
        if (showToast && currentSpec != null) {
            Toast.makeText(this, tr("☀️ 鬧鐘已關閉"), Toast.LENGTH_SHORT).show()
        }
        currentSpec = null
        // 通知響鈴畫面關閉
        sendBroadcast(Intent(ACTION_CLOSE_UI).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { wakeLock?.release() }
        wakeLock = null
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSoundAndVibration()
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }
}
