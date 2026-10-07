package com.pomodoro.timer

import java.util.Locale

/**
 * 时间格式化。与桌面版 `MainForm.FormatRemaining` / `FormatOvertime` 一致：
 * 剩余时间 `MM:SS`；超时 `+MM:SS`，超过 100 分钟时 `+HhMM`。
 */
object TimeFormat {

    fun remaining(seconds: Int): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    fun overtime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins >= 100) {
            String.format(Locale.US, "+%dh%02d", mins / 60, mins % 60)
        } else {
            String.format(Locale.US, "+%02d:%02d", mins, secs)
        }
    }

    /** 常驻通知里的一行描述。 */
    fun statusLine(snapshot: PhaseSnapshot): String {
        val name = when (snapshot.phase) {
            Phase.WORK -> "工作中"
            Phase.REST -> "休息中"
            Phase.IDLE -> "已停止"
        }
        return if (snapshot.isOvertime) {
            "$name · 已超时 ${overtime(snapshot.overtimeSeconds)}"
        } else {
            "$name · 剩余 ${remaining(snapshot.remainingSeconds)}"
        }
    }
}
