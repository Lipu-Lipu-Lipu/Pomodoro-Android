package com.pomodoro.timer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 「全屏通知」授权引导。
 *
 * Android 14（API 34）起，`USE_FULL_SCREEN_INTENT` 默认只授予通话/闹钟类应用，
 * 计时类应用会被系统收回。没有它，到点提醒只能是 Heads-up：
 * 后台不会把界面带到前台，息屏也不会亮屏——表现就是「只有声音」。
 * 这里是唯一的解法：引导用户到系统设置里把「允许全屏通知」打开。
 */
@Composable
fun FullScreenIntentPrompt(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("像闹钟一样提醒") },
        text = {
            Text(
                "到点提醒要在后台弹出来、在息屏时点亮屏幕，需要系统允许本应用使用「全屏通知」。\n\n" +
                    "请到系统设置里打开：允许全屏通知（可能在「通知」或「闹钟和提醒」里）。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("去开启") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("暂不") }
        },
    )
}
