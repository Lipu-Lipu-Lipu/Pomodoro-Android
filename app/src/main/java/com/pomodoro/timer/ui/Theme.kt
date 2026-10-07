package com.pomodoro.timer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 配色常量。数值直接取自桌面版 `MainForm.cs` / `AlertForm.cs`，保证两端视觉一致。
 */
object Palette {
    val Background = Color(0xFF1E1E1E)
    val Foreground = Color(0xFFEAEAEA)
    val Dim = Color(0xFF9A9A9A)
    val Work = Color(0xFFE74C3C)
    val Rest = Color(0xFF2ECC71)
    val Overtime = Color(0xFFF39C12)
    val Track = Color(0xFF2E2E2E)
    val IdleRing = Color(0xFF45454D)
    val ControlBackground = Color(0xFF2D2D2D)
    val Link = Color(0xFF8AB4F8)
    val AlertBackground = Color(0xFF26262B)
    val Separator = Color(0xFF45454D)
}

/**
 * 字号换算：桌面版用 GDI 点（1pt = 1/72 英寸，96dpi 下 = 1.333px），
 * 而 1 个桌面像素按 1dp 映射（桌面窗口宽 400px，主流手机宽约 411dp，比例几乎相同），
 * 因此 sp = pt × 4/3，dp = 桌面像素值。
 */
private const val PT_TO_SP = 4f / 3f

fun pt(value: Float): TextUnit = (value * PT_TO_SP).sp

@Composable
fun PomodoroTheme(content: @Composable () -> Unit) {
    // 应用始终使用桌面版那套深色配色，不跟随系统浅色模式
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Work,
            onPrimary = Color.White,
            secondary = Palette.Rest,
            background = Palette.Background,
            onBackground = Palette.Foreground,
            surface = Palette.Background,
            onSurface = Palette.Foreground,
        ),
        content = content,
    )
}
