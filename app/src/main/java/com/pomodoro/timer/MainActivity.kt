package com.pomodoro.timer

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pomodoro.timer.ui.AlertCard
import com.pomodoro.timer.ui.FullScreenIntentPrompt
import com.pomodoro.timer.ui.MainScreen
import com.pomodoro.timer.ui.PomodoroTheme
import kotlinx.coroutines.launch

/**
 * 唯一的界面入口。对应桌面版的主窗口：
 * 负责渲染 [MainScreen]、弹出提醒卡片，并把操作转发给前台服务。
 *
 * 桌面版把窗口隐藏到托盘后计时继续；Android 上界面可以随时被销毁，
 * 所以计时状态放在 [PomodoroController]，由 [PomodoroService] 持续推进。
 */
class MainActivity : ComponentActivity() {

    /** 本进程内只提示一次「全屏通知」授权，避免反复打扰。 */
    private var fullScreenPromptShown = false
    private var showFullScreenPrompt by mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notif_perm_body, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 到点提醒要「像闹钟一样」：允许在锁屏之上显示，并点亮屏幕。
        // 配合提醒通知上的全屏 Intent，息屏时系统会把界面直接拉到锁屏前面。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val loadedSettings = SettingsStore.load(this)

        setContent {
            PomodoroTheme {
                val alert by PomodoroController.alert.collectAsState()
                val exitRequested by PomodoroController.exitRequested.collectAsState()

                MainScreen(
                    initialSettings = loadedSettings,
                    onSettingsChanged = { SettingsStore.save(this, it) },
                    onStart = { settings ->
                        ensureNotificationPermission()
                        PomodoroService.start(
                            context = this,
                            workSeconds = totalSecondsOf(settings.workMinutes, settings.workSeconds),
                            restSeconds = totalSecondsOf(settings.restMinutes, settings.restSeconds),
                        )
                        TonePlayer.playStart(this)
                    },
                    onEndPhase = { PomodoroService.skip(this) },
                    onReset = { PomodoroController.reset() },
                )

                alert?.let { request ->
                    AlertCard(
                        request = request,
                        onDismiss = {
                            PomodoroController.dismissAlert()
                            PomodoroService.clearAlertNotification(this)
                        },
                    )
                }

                LaunchedEffect(exitRequested) {
                    if (exitRequested) {
                        PomodoroController.clearExitRequest()
                        finishAndRemoveTask()
                    }
                }

                if (showFullScreenPrompt) {
                    FullScreenIntentPrompt(
                        onConfirm = { openFullScreenIntentSettings() },
                        onDismiss = { showFullScreenPrompt = false },
                    )
                }
            }
        }

        // 计时进行中保持屏幕常亮：对应桌面版「主窗口可见时进度始终可见」
        lifecycleScope.launch {
            PomodoroController.snapshot.collect { snapshot ->
                if (snapshot.phase != Phase.IDLE) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        ensureNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        PomodoroController.isUiVisible = true
        promptFullScreenIntentIfNeeded()
    }

    override fun onPause() {
        PomodoroController.isUiVisible = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // 对应桌面版首次隐藏到托盘时的一次性气泡提示
        if (PomodoroController.isRunning && !SettingsStore.isTrayTipShown(this)) {
            SettingsStore.markTrayTipShown(this)
            Toast.makeText(this, R.string.tray_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Android 14+ 起「全屏通知」默认不授予计时类应用，而未授权时到点提醒：
     * 后台不会弹到前台、息屏不会亮屏——表现就是「只有声音」。
     * 这里检测到未授权就引导一次，用户开启后不再提示。
     */
    private fun promptFullScreenIntentIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        if (fullScreenPromptShown) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.canUseFullScreenIntent()) {
            return
        }
        fullScreenPromptShown = true
        showFullScreenPrompt = true
    }

    private fun openFullScreenIntentSettings() {
        showFullScreenPrompt = false
        val packageUri = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri))
        } catch (_: Throwable) {
            // 个别 ROM 没有这个页面，退到应用详情页
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            } catch (_: Throwable) {
            }
        }
    }
}
