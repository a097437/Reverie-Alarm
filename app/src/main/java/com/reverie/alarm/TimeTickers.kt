package com.reverie.alarm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDateTime

// ============================================================================
// 會隨時間更新的「現在」
//
// 原則（120Hz 每一幀只有 8.3ms）：每秒變很多次的值，只讓「真的要顯示它」的小元件去讀。
//   - rememberFrameClock：碼錶、計時進度環用，每一幀更新。回傳 State，呼叫的畫面自己不要讀 .longValue，
//     交給時間數字那個小元件或 Canvas 的繪製區塊去讀，才不會整個畫面每幀重組
//   - rememberMinuteNow：只顯示到「分」的地方用，對齊每分鐘的 0 秒更新一次
//     （以前各處每 10～30 秒輪詢，不但多做工，顯示還會慢半拍）
// ============================================================================

/**
 * 所在的分頁現在看不看得到。主畫面的分頁都一直組好（切換時才不用臨時組整頁），
 * 所以碼錶、計時這類「每一幀都在動」的元件要自己看這個值：看不到時停止更新，App 才能真正閒置
 */
val LocalTabVisible = compositionLocalOf { true }

/** 每一幀更新的目前時間（System.currentTimeMillis）；active 為 false 時停止更新 */
@Composable
fun rememberFrameClock(active: Boolean): LongState {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        now.longValue = System.currentTimeMillis()
        // App 不在畫面上時 withFrameMillis 會自動暫停
        while (active) withFrameMillis { now.longValue = System.currentTimeMillis() }
    }
    return now
}

/** 對齊整分鐘更新的「現在」（State：可以交給子元件，在真正顯示的地方才讀） */
@Composable
fun rememberMinuteNowState(): State<LocalDateTime> {
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 30L)
            now.value = LocalDateTime.now()
        }
    }
    return now
}

/** 對齊整分鐘更新的「現在」 */
@Composable
fun rememberMinuteNow(): LocalDateTime = rememberMinuteNowState().value

/**
 * 讓螢幕一直維持高更新率（120Hz）。
 *
 * 很多手機（例如小米 HyperOS）畫面約 1 秒沒有新內容，就把螢幕降到 60Hz 省電；
 * 等下一個動畫開始才升回 120Hz，升頻的那一下會停住一格，過場動畫的開頭看起來就像頓了、撕裂。
 *
 * 這裡每 0.25 秒重畫一個看不見的 1 像素點，螢幕就一直有新畫面、不會降頻。
 * 刻意不讓整個畫面每秒重畫 120 次：那樣手機會變熱，熱到一定程度系統會強制降到 60Hz，反而更卡。
 * App 在背景時自動停止；省電模式時不做（交給系統省電）。
 */
@Composable
fun KeepHighRefreshRate() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        while (true) {
            delay(HEARTBEAT_MS)
            if (power?.isPowerSaveMode == true) continue
            // App 不在畫面上時 withFrameMillis 會自動暫停
            withFrameMillis { tick.intValue++ }
        }
    }
    androidx.compose.foundation.layout.Spacer(
        androidx.compose.ui.Modifier
            .size(1.dp)
            .drawBehind {
                tick.intValue // 在繪製階段讀：每次只重畫這 1 像素，不會重組
                drawRect(Color.Transparent)
            }
    )
}

private const val HEARTBEAT_MS = 250L
