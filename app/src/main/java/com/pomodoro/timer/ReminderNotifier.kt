package com.pomodoro.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 通知的统一出口：常驻计时通知 + 提醒通知。
 *
 * 由两处共用，保证行为完全一致：
 * - [PomodoroService] 正常运行时的轮询；
 * - [ReminderReceiver] 由精确闹钟唤起时（进程还在 / 已被回收都能投递）。
 */
internal object ReminderNotifier {

    const val CHANNEL_ALERTS = "timer_alerts"
    const val CHANNEL_STATUS = "timer_status"
    const val NOTIF_ID_STATUS = 1
    private const val NOTIF_ID_ALERT = 2

    /** 上一次画进常驻通知的内容签名：只有它在变时才重发通知，避免无意义刷新。 */
    private var lastStatusSignature: String? = null

    /** 建立通知渠道。渠道由系统持久化，重复调用无副作用。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_timer_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_timer_desc)
                // 提示音由 TonePlayer 合成播放，避免与渠道音重复
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
                enableLights(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    // ------------------------------------------------------------ 常驻计时通知

    /**
     * 常驻通知。
     *
     * 倒计时**不写进文案**，而是交给系统 Chronometer 渲染（`setUsesChronometer` + `setWhen`）：
     * 应用退到后台被厂商 ROM 冻结、或息屏后 CPU 挂起时，「应用自己每秒改文案」必然停摆，
     * 表现为下拉状态栏后倒计时一动不动；而 Chronometer 由 SystemUI 按秒刷新，不依赖应用进程。
     * 应用只在**状态变化**时重发通知（阶段切换、进入超时）。
     */
    @Suppress("DEPRECATION")
    fun buildStatusNotification(context: Context, snapshot: PhaseSnapshot): Notification {
        lastStatusSignature = statusSignature(snapshot)
        val openIntent = contentIntent(context)
        // Chronometer 的基准时刻 = 阶段结束时刻（墙钟）。
        // 未超时时基准在未来 → 倒计时；超时后基准在过去 → 正计时，与桌面版 +MM:SS 语义一致。
        val phaseEndWallClock = System.currentTimeMillis() + snapshot.remainingMillis

        // 这里刻意用平台 Builder 而不是 NotificationCompat：状态栏芯片所需的
        // `setShortCriticalText`（API 36）androidx 没有封装（1.12 ~ 1.15 均无）。
        // minSdk 26 起平台 Builder 可直接使用，其余调用与 NotificationCompat 等价。
        val builder = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(TimeFormat.statusLine(snapshot))
            .setContentIntent(openIntent)
            .setOngoing(true)
            // 渠道本身已静音（IMPORTANCE_LOW + 无声音/震动）；这里再声明「只提醒一次」，
            // 避免 API 36+ 每秒重发芯片文字时反复打扰。
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(phaseEndWallClock)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(!snapshot.isOvertime)
            .addAction(0, context.getString(R.string.action_open), openIntent)
            .addAction(
                0,
                context.getString(R.string.action_skip),
                servicePendingIntent(context, PomodoroService.ACTION_SKIP, 1),
            )
            .addAction(
                0,
                context.getString(R.string.action_exit),
                servicePendingIntent(context, PomodoroService.ACTION_EXIT, 2),
            )

        // Android 16+ 额外把秒数放进状态栏左上角的芯片里。
        // 芯片文字是静态的，只能由应用刷新，所以下面 statusSignature 会在 API 36+ 带上秒数，
        // 让轮询每秒重发一次；更低版本的状态栏只能显示图标，签名只取阶段文案。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            @Suppress("NewApi")
            builder.setShortCriticalText(TimeFormat.chipText(snapshot))
        }

        return builder.build()
    }

    /**
     * 刷新常驻通知。内容签名没变就直接返回。
     *
     * 下拉通知栏里的秒数由系统 Chronometer 走字（应用被冻结也不停），不需要应用刷新；
     * 但 Android 16+ 状态栏芯片里的秒数只能由应用刷新，所以那种机型上签名会带上秒数、逐秒重发。
     */
    fun updateStatusNotification(context: Context, snapshot: PhaseSnapshot) {
        if (statusSignature(snapshot) == lastStatusSignature) {
            return
        }
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIF_ID_STATUS, buildStatusNotification(context, snapshot))
        } catch (_: Throwable) {
        }
    }

    private fun statusSignature(snapshot: PhaseSnapshot): String {
        val line = TimeFormat.statusLine(snapshot)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            "$line|${TimeFormat.chipText(snapshot)}"
        } else {
            line
        }
    }

    // ------------------------------------------------------------ 提醒通知

    /** 投递一次提醒。服务与闹钟接收器共用这一条路径。 */
    fun deliver(context: Context, kind: AlertKind, phase: Phase) {
        val app = context.applicationContext
        ensureChannels(app)
        when (kind) {
            AlertKind.WARN -> deliverWarn(app, phase)
            AlertKind.END -> deliverEnd(app, phase)
        }
    }

    fun clearAlertNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID_ALERT)
        } catch (_: Throwable) {
        }
    }

    private fun deliverWarn(context: Context, phase: Phase) {
        // 桌面版：BeepPlayer.PlayWarn + 气泡「还剩 1 分钟」+ 10 秒自动关闭且不抢焦点的置顶卡片 + 任务栏闪烁
        TonePlayer.playWarn(context)
        val phaseName = if (phase == Phase.WORK) "工作" else "休息"
        postAlert(
            context = context,
            title = context.getString(R.string.balloon_title_warn),
            body = context.getString(R.string.balloon_body_warn, phaseName),
            // 同样走全屏 Intent：后台/息屏时也要能亮屏弹出，不能只发声音
            fullScreen = true,
        )
        // 提醒卡片：界面可见时立刻显示；界面不可见时也先挂上，
        // 等全屏 Intent 把界面拉起来（或用户点开应用）后补显示
        if (PomodoroController.isRunning) {
            PomodoroController.requestAlert(AlertRequest(AlertKind.WARN, phase))
        }
    }

    private fun deliverEnd(context: Context, phase: Phase) {
        // 桌面版：BeepPlayer.PlayEnd + 气泡「时间到」+ 还原并激活主窗口 + 不自动关闭的置顶卡片
        TonePlayer.playEnd(context)
        val endOfWork = phase == Phase.WORK
        postAlert(
            context = context,
            title = context.getString(R.string.balloon_title_end),
            body = context.getString(
                if (endOfWork) R.string.balloon_body_work_end else R.string.balloon_body_rest_end
            ),
            // 到点提醒在锁屏上亮屏弹出，对应桌面版「还原并激活主窗口」
            fullScreen = true,
        )
        if (PomodoroController.isRunning) {
            PomodoroController.requestAlert(AlertRequest(AlertKind.END, phase))
            bringAppToFront(context)
        }
    }

    private fun postAlert(context: Context, title: String, body: String, fullScreen: Boolean) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (fullScreen && canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(contentIntent(context), true)
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_ALERT, builder.build())
        } catch (_: Throwable) {
        }
    }

    /** 桌面版「还原并激活主窗口」的等价物。后台启动 Activity 可能被系统拦截，失败时静默。 */
    private fun bringAppToFront(context: Context) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
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

    /** Android 14+ 只对闹钟/来电类应用自动授予全屏 Intent，未授权时降级为 Heads-up。 */
    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getService(
        context,
        requestCode,
        Intent(context, PomodoroService::class.java).apply { this.action = action },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
