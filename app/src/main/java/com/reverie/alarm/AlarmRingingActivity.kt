package com.reverie.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// ============================================================================
// 全螢幕響鈴畫面（對應網頁版的「強效鬧鐘響鈴視窗」）
// 畫面本身不播聲音，聲音由 AlarmRingService 負責；按鈕只是通知服務貪睡或關閉
// ============================================================================
class AlarmRingingActivity : ComponentActivity() {

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeScreen()

        ContextCompat.registerReceiver(
            this, closeReceiver,
            IntentFilter(AlarmRingService.ACTION_CLOSE_UI),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 響鈴時按返回鍵不會誤關（必須按貪睡或關閉）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* 忽略 */ }
        })

        showSpec(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showSpec(intent)
    }

    private fun showSpec(intent: Intent?) {
        val spec = AlarmEngine.specFromJson(intent?.getStringExtra(AlarmEngine.EXTRA_SPEC_JSON))
        if (spec == null) {
            finish()
            return
        }
        setContent {
            RingingScreen(
                spec = spec,
                onSnooze = { AlarmRingService.sendAction(this, AlarmRingService.ACTION_SNOOZE) },
                onDismiss = { AlarmRingService.sendAction(this, AlarmRingService.ACTION_DISMISS) }
            )
        }
    }

    private fun wakeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 不強制解鎖：在鎖屏上就能直接貪睡或關閉
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(closeReceiver) }
        super.onDestroy()
    }
}

private fun formatDiff(from: LocalDateTime, to: LocalDateTime): String {
    val mins = Duration.between(from, to).toMinutes()
    if (mins <= 0) return "已到達時間"
    val h = mins / 60
    val m = mins % 60
    return when {
        h > 0 && m > 0 -> "$h 小時 $m 分鐘"
        h > 0 -> "$h 小時"
        else -> "$m 分鐘"
    }
}

@Composable
fun RingingScreen(
    spec: AlarmSpec,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1000)
        }
    }

    val isWake = spec.stage == "WAKE"
    val isLeave = spec.stage == "LEAVE"
    val badgeText = when {
        isWake -> "🔔 第一段：強效起床喚醒"
        isLeave -> "🚪 第二段：出門倒數提醒"
        else -> "⏰ 日期指定強效鬧鐘"
    }
    val subText = spec.shiftStartLocal?.let { startStr ->
        runCatching {
            val start = LocalDateTime.parse(startStr)
            "距離上班時間（${start.format(DateTimeFormatter.ofPattern("HH:mm"))}）還有 ${formatDiff(LocalDateTime.now(), start)}"
        }.getOrNull()
    } ?: "指定日期：${spec.triggerLocal.take(10)}"
    val canSnooze = AlarmRingService.canSnooze(spec)

    val rose = Color(0xFFF43F5E)
    val transition = rememberInfiniteTransition(label = "bell")
    val angle by transition.animateFloat(
        initialValue = -14f, targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(250), RepeatMode.Reverse),
        label = "bellAngle"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2020617))
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .background(rose.copy(alpha = 0.2f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(badgeText, color = Color(0xFFFB7185), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(spec.label, color = Color(0xFFE2E8F0), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(subText, color = Color(0xFFFBBF24), fontSize = 12.sp, textAlign = TextAlign.Center)
            if (spec.fadeIn) {
                Spacer(Modifier.height(8.dp))
                Text("🔊 漸進式音量增強已啟用", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(
            modifier = Modifier
                .size(96.dp)
                .background(rose.copy(alpha = 0.2f), CircleShape)
                .border(2.dp, rose, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Notifications, contentDescription = null,
                tint = Color(0xFFFB7185),
                modifier = Modifier.size(48.dp).rotate(angle)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (canSnooze) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0x66F59E0B)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0x33F59E0B),
                        contentColor = Color(0xFFFCD34D)
                    )
                ) {
                    Text("貪睡 ${spec.snoozeMinutes} 分鐘（剩餘 ${spec.remainingSnooze} 次）", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rose.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, rose.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "已達防賴床上限／出門倒數禁止貪睡，請立即解除！",
                        color = Color(0xFFFDA4AF), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
            ) {
                Text("點擊解除鬧鐘", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
    }
}
