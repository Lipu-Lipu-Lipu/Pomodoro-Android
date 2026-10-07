package com.pomodoro.timer

import android.content.Context

/**
 * 应用设置。对应桌面版 `SettingsStore.cs` 的 `AppSettings` record。
 */
data class AppSettings(
    val workMinutes: Int,
    val workSeconds: Int,
    val restMinutes: Int,
    val restSeconds: Int,
) {
    companion object {
        val DEFAULT = AppSettings(25, 0, 5, 0)
    }
}

/** 把「分 + 秒」换算为总秒数，至少 1 秒。对应桌面版 `MainForm.TotalSecondsOf`。 */
fun totalSecondsOf(minutes: Int, seconds: Int): Int = maxOf(1, minutes * 60 + seconds)

/**
 * 设置的序列化与钳制（纯函数，不依赖 Android，便于单元测试）。
 *
 * 编码格式：`workMinutes,workSeconds,restMinutes,restSeconds`
 */
object SettingsCodec {

    /** 按控件输入范围钳制，防止手改配置文件产生非法值。对应桌面版 `SettingsStore.Normalize`。 */
    fun normalize(s: AppSettings): AppSettings = AppSettings(
        s.workMinutes.coerceIn(0, 120),
        s.workSeconds.coerceIn(0, 59),
        s.restMinutes.coerceIn(0, 60),
        s.restSeconds.coerceIn(0, 59),
    )

    fun encode(s: AppSettings): String {
        val n = normalize(s)
        return listOf(n.workMinutes, n.workSeconds, n.restMinutes, n.restSeconds).joinToString(",")
    }

    /** 解析失败或非法时回退默认值，与桌面版 `Load` 的容错行为一致。 */
    fun decode(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) {
            return AppSettings.DEFAULT
        }
        val parts = raw.split(",")
        if (parts.size != 4) {
            return AppSettings.DEFAULT
        }
        val values = parts.map { it.trim().toIntOrNull() ?: return AppSettings.DEFAULT }
        return normalize(AppSettings(values[0], values[1], values[2], values[3]))
    }
}

/**
 * 设置持久化：对应桌面版读写 `%APPDATA%\PomodoroTimer\settings.json`。
 * Android 端使用 `SharedPreferences`（平台等价的键值持久化）。
 * 保存失败静默忽略，不影响本次使用。
 */
object SettingsStore {
    private const val PREFS_NAME = "pomodoro_settings"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_TRAY_TIP_SHOWN = "tray_tip_shown"

    fun load(context: Context): AppSettings = try {
        SettingsCodec.decode(prefs(context).getString(KEY_SETTINGS, null))
    } catch (_: Throwable) {
        AppSettings.DEFAULT
    }

    fun save(context: Context, settings: AppSettings) {
        try {
            prefs(context).edit()
                .putString(KEY_SETTINGS, SettingsCodec.encode(settings))
                .apply()
        } catch (_: Throwable) {
            // 保存失败不影响本次使用
        }
    }

    /** 桌面版「首次隐藏到托盘」的气泡提示只弹一次，用同样的方式记标记。 */
    fun isTrayTipShown(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_TRAY_TIP_SHOWN, false)
    } catch (_: Throwable) {
        false
    }

    fun markTrayTipShown(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_TRAY_TIP_SHOWN, true).apply()
        } catch (_: Throwable) {
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
