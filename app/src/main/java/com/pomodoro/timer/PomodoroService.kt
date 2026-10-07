package com.pomodoro.timer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * 前台计时服务。
 *
 * 承担桌面版里「窗口隐藏到托盘后计时继续」的职责，并对应托盘图标与右键菜单：
 * - 常驻通知 = 托盘图标（显示当前阶段与剩余时间）
 * - 通知上的三个操作 = 托盘右键菜单的「打开番茄钟」「结束当前阶段」「退出」
 * - 到时提醒的「还原并激活主窗口」= 尝试把界面带到前台（后台启动 Activity 可能被系统拒绝，
 *   此时高优先级通知仍然可见，与桌面版「焦点夺取失败也有兜底」的思路一致）
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
         * 通知已按文案变化节流，也不会因此频繁刷新。
         */
        private const val TICK_MS = 100L
        private const val CHANNEL_ALERTS = "timer_alerts"
        private const val CHANNEL_STATUS = "timer_status"
        private const val NOTIF_ID_STATUS = 1
        private const val NOTIF_ID_ALERT = 2

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
        fun clearAlertNotification(context: Context) {
            try {
                NotificationManagerCompat.from(context).cancel(NOTIF_ID_ALERT)
            } catch (_: Throwable) {
            }
        }

        /** 供界面判断服务是否已在运行（避免重复 startForegroundService）。 */
        var isActive: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStatusLine: String? = null
    private var foregroundStarted = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            val snapshot = PomodoroController.tick()

            if (snapshot.phase == Phase.IDLE) {
                stopTimer()
                return
            }

            handleEvents(snapshot)
            updateStatusNotification(snapshot)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
                    try {
                        NotificationManagerCompat.from(this).cancel(NOTIF_ID_ALERT)
                    } catch (_: Throwable) {
                    }
                    ensureForeground()
                    PomodoroController.tick().let { updateStatusNotification(it) }
                }
                return START_STICKY
            }

            ACTION_START -> {
                val work = intent.getIntExtra(EXTRA_WORK_SECONDS, 25 * 60)
                val rest = intent.getIntExtra(EXTRA_REST_SECONDS, 5 * 60)
                PomodoroController.start(work, rest)
                ensureForeground()
                lastStatusLine = null
                updateStatusNotification(PomodoroController.tick())
                startTicking()
                return START_STICKY
            }
        }

        // 服务被系统重启（intent 为 null）时若仍在计时，恢复前台与轮询
        if (PomodoroController.isRunning) {
            ensureForeground()
            updateStatusNotification(PomodoroController.tick())
            startTicking()
            return START_STICKY
        }
        stopTimer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
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
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        NotificationManagerCompat.from(this).cancel(NOTIF_ID_ALERT)
        isActive = false
        stopSelf()
    }

    /** 阶段推进时的提醒：提示音 + 通知 + 提醒卡片，与桌面版 `OnTick` 一一对应。 */
    private fun handleEvents(snapshot: PhaseSnapshot) {
        if (snapshot.justFiredWarn) {
            // 桌面版：BeepPlayer.PlayWarn + 气泡「还剩 1 分钟」+ 10 秒自动关闭且不抢焦点的置顶卡片 + 任务栏闪烁
            TonePlayer.playWarn(this)
            val phaseName = if (snapshot.phase == Phase.WORK) "工作" else "休息"
            postAlertNotification(
                title = getString(R.string.balloon_title_warn),
                body = getString(R.string.balloon_body_warn, phaseName),
                ongoing = false,
            )
            if (PomodoroController.isUiVisible) {
                PomodoroController.requestAlert(AlertRequest(AlertKind.WARN, snapshot.phase))
            }
        }

        if (snapshot.justFiredEnd) {
            // 桌面版：BeepPlayer.PlayEnd + 气泡「时间到」+ 还原并激活主窗口 + 不自动关闭的置顶卡片 + 任务栏闪烁
            TonePlayer.playEnd(this)
            val endOfWork = snapshot.phase == Phase.WORK
            postAlertNotification(
                title = getString(R.string.balloon_title_end),
                body = getString(
                    if (endOfWork) R.string.balloon_body_work_end else R.string.balloon_body_rest_end
                ),
                ongoing = false,
            )
            PomodoroController.requestAlert(AlertRequest(AlertKind.END, snapshot.phase))
            bringAppToFront()
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.channel_timer_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_timer_desc)
            // 提示音由 TonePlayer 合成播放，避免与渠道音重复
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 120, 180)
            enableLights(true)
        }

        val status = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_service_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(alerts)
        manager.createNotificationChannel(status)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PomodoroService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildStatusNotification(snapshot: PhaseSnapshot): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(TimeFormat.statusLine(snapshot))
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.action_open), contentIntent())
            .addAction(0, getString(R.string.action_skip), servicePendingIntent(ACTION_SKIP, 1))
            .addAction(0, getString(R.string.action_exit), servicePendingIntent(ACTION_EXIT, 2))
            .build()

    private fun ensureForeground() {
        val notification = buildStatusNotification(PomodoroController.snapshot.value)
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

    /** 只在展示文案变化时更新通知，避免 250ms 一次的无意义刷新。 */
    private fun updateStatusNotification(snapshot: PhaseSnapshot) {
        val line = TimeFormat.statusLine(snapshot)
        if (line == lastStatusLine) {
            return
        }
        lastStatusLine = line
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_STATUS, buildStatusNotification(snapshot))
        } catch (_: Throwable) {
        }
    }

    private fun postAlertNotification(title: String, body: String, ongoing: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_ALERT, notification)
        } catch (_: Throwable) {
        }
    }

    /** 桌面版「还原并激活主窗口」的等价物。后台启动 Activity 可能被系统拦截，失败时静默。 */
    private fun bringAppToFront() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
            )
        } catch (_: Throwable) {
        }
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
            // 单次番茄阶段最长 120 分钟，按 12 小时上限持有足够覆盖连续多轮
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
