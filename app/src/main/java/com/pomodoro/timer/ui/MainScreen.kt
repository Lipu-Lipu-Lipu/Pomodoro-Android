package com.pomodoro.timer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pomodoro.timer.AppSettings
import com.pomodoro.timer.Phase
import com.pomodoro.timer.PomodoroController
import com.pomodoro.timer.TimeFormat
import com.pomodoro.timer.totalSecondsOf

/**
 * 主界面。布局与桌面版 `MainForm.BuildUi` 的行顺序一致：
 * 标题 → 阶段 → 圆环（占满剩余空间）→ 设置 → 按钮 → 返回设置链接。
 */
@Composable
fun MainScreen(
    initialSettings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onStart: (AppSettings) -> Unit,
    onEndPhase: () -> Unit,
    onReset: () -> Unit,
) {
    val snapshot by PomodoroController.snapshot.collectAsState()
    val running = snapshot.phase != Phase.IDLE
    var settings by remember { mutableStateOf(initialSettings) }

    val isWork = snapshot.phase == Phase.WORK
    val phaseColor: Color = when (snapshot.phase) {
        Phase.IDLE -> Palette.Dim
        Phase.WORK -> Palette.Work
        Phase.REST -> Palette.Rest
    }
    val phaseText = when (snapshot.phase) {
        Phase.IDLE -> "准备开始"
        Phase.WORK -> "工作中"
        Phase.REST -> "休息中"
    }

    val ringText: String
    val ringProgress: Double
    val ringColor: Color
    if (snapshot.phase == Phase.IDLE) {
        // 空闲时按当前设置预览工作总时长，整圈满环 + 空闲灰
        ringText = TimeFormat.remaining(totalSecondsOf(settings.workMinutes, settings.workSeconds))
        ringProgress = 1.0
        ringColor = Palette.IdleRing
    } else if (snapshot.isOvertime) {
        ringText = TimeFormat.overtime(snapshot.overtimeSeconds)
        ringProgress = 1.0
        ringColor = Palette.Overtime
    } else {
        val target = if (isWork) PomodoroController.workSeconds else PomodoroController.restSeconds
        ringText = TimeFormat.remaining(snapshot.remainingSeconds)
        // 用亚秒级剩余时间算进度：秒级整数会让圆环每秒跳一格，
        // 服务每 250ms 推一次快照，配合这里就能让弧线平缓流动。
        ringProgress = if (target > 0) {
            (snapshot.remainingMillis.toDouble() / 1000.0 / target).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        ringColor = phaseColor
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 让出系统状态栏高度，避开真机上的挖孔屏/灵动岛；
            // 之后再叠加原设计的 22dp 视觉留白。
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 36.dp, top = 22.dp, end = 36.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "🍅 Pomodoro",
            color = Palette.Foreground,
            fontSize = pt(16f),
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = phaseText,
            color = phaseColor,
            fontSize = pt(13f),
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        TimerRing(
            text = ringText,
            progress = ringProgress,
            overtime = snapshot.isOvertime,
            ringColor = ringColor,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(modifier = Modifier.height(20.dp))

        SettingsPanel(
            workMinutes = settings.workMinutes,
            workSeconds = settings.workSeconds,
            restMinutes = settings.restMinutes,
            restSeconds = settings.restSeconds,
            locked = running,
            onChange = {
                settings = it
                onSettingsChanged(it)
            },
        )
        Spacer(modifier = Modifier.height(18.dp))

        AccentButton(
            text = when {
                !running -> "开始"
                isWork -> "结束，进入休息"
                else -> "结束，进入工作"
            },
            accent = if (running && isWork) Palette.Rest else Palette.Work,
            onClick = {
                if (running) {
                    onEndPhase()
                } else {
                    onStart(settings)
                }
            },
        )
        // 桌面版此处为 10px 间距；手机上按钮与链接的触摸区域相邻，加大到 16dp 避免误触
        Spacer(modifier = Modifier.height(16.dp))

        // 「返回设置」链接：仅运行中可见、运行中可点击。
        // 但 Text 永远占位（idle 时 alpha=0），避免点击「开始」后底部新增一行把整个界面往上顶。
        Text(
            text = "返回设置",
            color = Palette.Link,
            fontSize = pt(9.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(enabled = running) { onReset() }
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .alpha(if (running) 1f else 0f),
        )
    }
}
