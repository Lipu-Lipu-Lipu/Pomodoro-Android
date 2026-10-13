package com.pomodoro.timer

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/**
 * 前台计时服务。
 *
 * 承担桌面版里「窗口隐藏到托盘后计时继续」的职责，并对应托盘图标与右键菜单：
 * - 常驻通知 = 托盘图标（显示当前阶段与剩余时间）
 * - 通知上的三个操作 = 托盘右键菜单的「打开番茄钟」「结束当前阶段」「退出」
 * - 到时提醒的「还原并激活主窗口」= 尝试把界面带到前台（后台启动 Activity 可能被系统拒绝，
 *   此时高优先级通知仍然可见，与桌面版「焦点夺取失败也有兜底」的思路一致）
 *
 * 提醒的触发有两条路径，由 [PomodoroController.tickAndDeliver] 收敛成一条：
 * 1. 服务存活时的 100ms 轮询（界面在前台、CPU 未挂起时最快）；
 * 2. [ReminderScheduler] 排的精确闹钟（息屏 / Doze / 进程被冻结时由系统唤醒）。
 * 两条路径都靠引擎内的 `warnFired` / `endFired` 标志位保证「整个阶段只提醒一次」。
 */
class PomodoroService : Service() {

    companion object {
        const val ACTION_START = "com.pomodoro.timer.action.START"
        const val ACTION_SKIP = "com.pomodoro.timer.action.SKIP"
        const val ACTION_EXIT = "com.pomodoro.timer.action.EXIT"
        const val EXTRA_WORK_SECONDS = "work_seconds"
        const val EXTRA_REST_SECONDS = "rest_seconds"

        /**
         * 状态推进间隔。
         *
         * 桌面版是 250ms 轮询；Android 端收到 100ms，目的是让圆环进度走得更平缓——
         * 界面是按墙钟差值（`remainingMillis`）算进度的，轮询越快，弧线推进越连续，
         * 不会出现「每秒跳一格」的观感。
         *
         * 计时精度本身不受影响：引擎用墙钟差值计时，与轮询频率无关（见 FEATURES.md 1.3）。
         * 息屏 / Doze 下轮询可能被系统挂起，此时提醒由精确闹钟兜底，见 [ReminderScheduler]。
         * 通知已按文案变化节流，也不会因此频繁刷新。
         */
        private const val TICK_MS = 100L
        private const val NOTIF_ID_STATUS = ReminderNotifier.NOTIF_ID_STATUS

        fun start(context: Context, workSeconds: Int, restSeconds: Int) {
            val intent = Intent(context, PomodoroService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WORK_SECONDS, workSeconds)
                putExtra(EXTRA_REST_SECONDS, restSeconds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun skip(context: Context) {
            context.startService(Intent(context, PomodoroService::class.java).apply {
                action = ACTION_SKIP
            })
        }

        fun exit(context: Context) {
            context.startService(Intent(context, PomodoroService::class.java).apply {
                action = ACTION_EXIT
            })
        }

        /**
         * 在应用内点掉提醒卡片时，同步清掉对应的通知，
         * 避免同一次提醒在通知栏留下一份「已读」的残留。
         */
        fun clearAlertNotification(context: Context) =
            ReminderNotifier.clearAlertNotification(context)

        /** 供界面判断服务是否已在运行（避免重复 startForegroundService）。 */
        var isActive: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            val snapshot = PomodoroController.tickAndDeliver()

            if (snapshot.phase == Phase.IDLE) {
                stopTimer()
                return
            }

            // 秒数由系统 Chronometer 走字，这里只在阶段文案变化（进入超时等）时重发
            ReminderNotifier.updateStatusNotification(this@PomodoroService, snapshot)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReminderNotifier.ensureChannels(this)
        // 把状态机推进出来的 justFired* 事件接到真正的投递上；进程死亡后随之失效
        PomodoroController.reminderSink = { kind, phase ->
            ReminderNotifier.deliver(this, kind, phase)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                stopTimer()
                // 桌面版的「退出」会结束整个进程，计时状态随之消失；
                // Android 进程会被系统缓存下来，必须显式把状态机复位，
                // 否则下次打开应用会看到一个「冻结在运行中」的界面。
                PomodoroController.reset()
                PomodoroController.requestExit()
                return START_NOT_STICKY
            }

            ACTION_SKIP -> {
                if (PomodoroController.isRunning) {
                    PomodoroController.endCurrentPhase()
                    TonePlayer.playStart(this)
                    // 阶段已推进，上一条「请点击…」的提醒通知随之作废
                    ReminderNotifier.clearAlertNotification(this)
                    // 新阶段重新排提醒闹钟（旧闹钟会被同 requestCode 的新闹钟顶替）
                    ReminderScheduler.schedule(this)
                    ensureForeground()
                    ReminderNotifier.updateStatusNotification(this, PomodoroController.tick())
                }
                return START_STICKY
            }

            ACTION_START -> {
                val work = intent.getIntExtra(EXTRA_WORK_SECONDS, 25 * 60)
                val rest = intent.getIntExtra(EXTRA_REST_SECONDS, 5 * 60)
                PomodoroController.start(work, rest)
                ensureForeground()
                ReminderNotifier.updateStatusNotification(this, PomodoroController.tick())
                ReminderScheduler.schedule(this)
                startTicking()
                return START_STICKY
            }
        }

        // 服务被系统重启（intent 为 null）时若仍在计时，恢复前台、轮询与提醒闹钟
        if (PomodoroController.isRunning) {
            ensureForeground()
            ReminderNotifier.updateStatusNotification(this, PomodoroController.tick())
            ReminderScheduler.schedule(this)
            startTicking()
            return START_STICKY
        }
        stopTimer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        PomodoroController.reminderSink = null
        isActive = false
        foregroundStarted = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 计时驱动

    private fun startTicking() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun stopTimer() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        // 计时结束，提醒闹钟一并撤销，避免残留闹钟在退出后补响
        ReminderScheduler.cancel(this)
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        ReminderNotifier.clearAlertNotification(this)
        isActive = false
        stopSelf()
    }

    // ---------------------------------------------------------------- 通知

    private fun ensureForeground() {
        val notification = ReminderNotifier.buildStatusNotification(this, PomodoroController.snapshot.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID_STATUS, notification, type)
        foregroundStarted = true
        isActive = true
        acquireWakeLock()
    }

    // ---------------------------------------------------------------- 唤醒锁

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PomodoroTimer::tick")
                wakeLock?.setReferenceCounted(false)
            }
            // 单次番茄阶段最长 120 分钟，按 12 小时上限持有足够覆盖连续多轮；
            // 提醒本身已由精确闹钟兜底，唤醒锁只用来让前台轮询尽量连续。
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
    }
}
