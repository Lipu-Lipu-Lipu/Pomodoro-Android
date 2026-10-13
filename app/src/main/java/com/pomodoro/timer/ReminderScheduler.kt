package com.pomodoro.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 用系统闹钟精确排程「提前 1 分钟」与「到点」两个提醒时刻。
 *
 * 为什么不只靠前台服务里的 100ms 轮询：
 * - 息屏后 CPU 会挂起，而 `Handler.postDelayed` 基于 `SystemClock.uptimeMillis()`，挂起期间不推进；
 * - 进入 Doze 后系统会**忽略唤醒锁**，原有的部分唤醒锁不再能阻止挂起；
 * - 部分厂商 ROM 还会进一步冻结后台进程。
 * 三者叠加的结果是：息屏或退到后台后轮询停摆，提醒要么被拖到重新亮屏才补发，要么彻底不响。
 *
 * `AlarmManager.setAlarmClock()` 是系统级、可唤醒设备、且不受 Doze 限流的投递方式（闹钟类应用的标准做法），
 * 所以这里把「什么时候该提醒」交给系统闹钟；「提醒时做什么」仍复用 [ReminderNotifier] 那一条投递路径，
 * 由 [ReminderReceiver] 在闹钟触发时唤起进程执行。
 */
internal object ReminderScheduler {

    /**
     * 两个提醒各自固定一个 requestCode。
     * 同一个 requestCode + 同一个 Intent（filterEquals 比较，忽略 extras）会命中同一个 PendingIntent，
     * 因此重新排程时新闹钟会直接顶替旧闹钟，阶段切换不需要额外去重。
     */
    private const val RC_WARN = 0x101
    private const val RC_END = 0x102

    /** 按当前阶段排程提醒闹钟；空闲时等价于全部取消。 */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val phase = PomodoroController.currentPhase
        if (phase == Phase.IDLE) {
            cancel(app)
            return
        }

        val (warnElapsed, endElapsed) = reminderTriggerTimes(
            phaseStartElapsedMs = PomodoroController.phaseStartElapsedMs,
            targetSeconds = PomodoroController.phaseTargetSeconds,
        )

        // 到点提醒
        set(app, RC_END, phase, AlertKind.END, endElapsed, endElapsed)

        if (warnElapsed != null) {
            // 提前提醒：以到点时刻为有效期，Doze 里被延后到过点之后就不再补发这条过期提醒
            set(app, RC_WARN, phase, AlertKind.WARN, warnElapsed, endElapsed)
        } else {
            // 阶段时长不足 1 分钟时不设提前提醒，同时清掉上一阶段可能残留的那条
            cancelOne(app, RC_WARN)
        }
    }

    /**
     * 纯计算（便于单测）：给定阶段起点与总时长，算出两个提醒应触发的单调时刻。
     *
     * @return `(提前提醒时刻, 到点时刻)`；阶段时长不超过 60 秒时提前提醒为 null，
     *   与引擎「阶段 ≤ 60 秒不发提前提醒」的语义一致。
     */
    internal fun reminderTriggerTimes(
        phaseStartElapsedMs: Long,
        targetSeconds: Int,
    ): Pair<Long?, Long> {
        val endElapsed = phaseStartElapsedMs + targetSeconds * 1000L
        val warnElapsed = if (targetSeconds > 60) endElapsed - 60_000L else null
        return warnElapsed to endElapsed
    }

    /** 结束计时或退出时清掉两个闹钟。 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        cancelOne(app, RC_WARN)
        cancelOne(app, RC_END)
    }

    private fun set(
        context: Context,
        requestCode: Int,
        phase: Phase,
        kind: AlertKind,
        triggerElapsed: Long,
        deadlineElapsed: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (triggerElapsed <= now) {
            // 已经过点：不排「过去的闹钟」，否则恢复时会在过点状态下立刻补响一条过期提醒
            cancelOne(context, requestCode)
            return
        }

        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context, requestCode, phase, kind, deadlineElapsed)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            try {
                val triggerWall = System.currentTimeMillis() + (triggerElapsed - now)
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerWall, showIntent(context)),
                    operation,
                )
                return
            } catch (_: SecurityException) {
                // 个别 ROM 在未授权时仍抛异常，退到下面的兜底
            } catch (_: Throwable) {
            }
        }

        // 无精确闹钟权限时的降级：仍带 WAKEUP，但可能被 Doze 延后
        try {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerElapsed,
                operation,
            )
        } catch (_: Throwable) {
        }
    }

    private fun pendingIntent(
        context: Context,
        requestCode: Int,
        phase: Phase,
        kind: AlertKind,
        deadlineElapsed: Long,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMINDER
            putExtra(ReminderReceiver.EXTRA_KIND, kind.name)
            putExtra(ReminderReceiver.EXTRA_PHASE, phase.name)
            putExtra(ReminderReceiver.EXTRA_DEADLINE_ELAPSED, deadlineElapsed)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelOne(context: Context, requestCode: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_REMINDER
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
