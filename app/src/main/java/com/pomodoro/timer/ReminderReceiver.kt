package com.pomodoro.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 精确闹钟的落点（由 [ReminderScheduler] 排程，系统在息屏/Doze 下唤醒进程后投递到这里）。
 *
 * 两种情形分别处理：
 * - 进程还活着（前台服务在跑）：推进一次状态机，由 `justFired*` 标志位保证「整个阶段只投递一次」，
 *   因此即使闹钟与 100ms 轮询同时到达，也只会响一次；
 * - 进程已被系统回收：状态机已经不在，只能按闹钟携带的阶段信息直接投递，
 *   保证「息屏期间被杀」时提醒不丢。
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMINDER = "com.pomodoro.timer.action.REMINDER"
        const val EXTRA_KIND = "kind"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_DEADLINE_ELAPSED = "deadline_elapsed"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMINDER) {
            return
        }

        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { AlertKind.valueOf(it) }.getOrNull() } ?: return
        val phase = intent.getStringExtra(EXTRA_PHASE)
            ?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: return
        val deadlineElapsed = intent.getLongExtra(EXTRA_DEADLINE_ELAPSED, Long.MAX_VALUE)

        if (PomodoroController.isRunning) {
            // 补齐可能因挂起而漏掉的推进；justFired* 保证只投递一次
            val snapshot = PomodoroController.tickAndDeliver()
            // 到点后常驻通知要从「倒计时」切成「正计时」；应用被冻结时只有靠这次闹钟唤醒才能切
            if (PomodoroService.isActive) {
                ReminderNotifier.updateStatusNotification(context, snapshot)
            }
            if (PomodoroController.reminderSink != null) {
                return
            }
            // 服务意外缺席（理论上不该发生）：退到下面的直投兜底
        }

        // 过期提醒不补发：Doze 里被延后的提前提醒如果已经过点，就不再打扰
        if (kind == AlertKind.WARN && SystemClock.elapsedRealtime() > deadlineElapsed) {
            return
        }

        ReminderNotifier.deliver(context.applicationContext, kind, phase)
    }
}
