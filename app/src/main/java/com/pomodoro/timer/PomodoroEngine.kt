package com.pomodoro.timer

import android.os.SystemClock

/**
 * 番茄钟阶段。与桌面版 `PomodoroEngine.cs` 的 `Phase` 一一对应。
 */
enum class Phase {
    IDLE,
    WORK,
    REST,
}

/**
 * 一次轮询的不可变快照。字段与桌面版 `PhaseSnapshot` 完全一致。
 */
data class PhaseSnapshot(
    val phase: Phase,
    val remainingSeconds: Int,
    val isOvertime: Boolean,
    val overtimeSeconds: Int,
    val justFiredWarn: Boolean,
    val justFiredEnd: Boolean,
    /**
     * 亚秒级剩余毫秒（超时后为负值）。
     *
     * 仅用于界面把进度弧渲染得平缓连续——[remainingSeconds] 是整数秒，界面若直接拿它算进度，
     * 圆环会每秒跳一格。计时、提醒、超时判定等一切语义仍以 [remainingSeconds] 为准，
     * 本字段不参与任何逻辑判断。
     */
    val remainingMillis: Long = remainingSeconds * 1000L,
)

/**
 * 番茄钟核心状态机（纯逻辑，无 UI）。
 *
 * 移植自桌面版 `PomodoroEngine.cs`，语义逐条对齐：
 * - 工作与休息阶段交替；
 * - 到时不自动切换，进入超时正计时，仅当用户主动结束时才切换到下一阶段；
 * - 提前 1 分钟提醒仅在阶段时长超过 60 秒时触发，且整个阶段只触发一次；
 * - 到时提醒只触发一次；
 * - elapsed 由时钟差值得出，与轮询频率无关，保证精度。
 *
 * @param clock 单调时钟（毫秒）。默认使用 `SystemClock.elapsedRealtime()`，测试可注入假时钟。
 */
class PomodoroEngine(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var currentPhase: Phase = Phase.IDLE
        private set

    var workSeconds: Int = 25 * 60
    var restSeconds: Int = 5 * 60

    private var phaseStartMs: Long = 0L
    private var warnFired: Boolean = false
    private var endFired: Boolean = false

    val isRunning: Boolean
        get() = currentPhase != Phase.IDLE

    /** 从空闲进入工作阶段。 */
    fun start() {
        if (isRunning) {
            return
        }
        beginPhase(Phase.WORK)
    }

    /** 结束当前阶段，自动开始下一阶段（工作↔休息）。 */
    fun endCurrentPhase() {
        if (!isRunning) {
            return
        }
        beginPhase(if (currentPhase == Phase.WORK) Phase.REST else Phase.WORK)
    }

    /** 回到空闲状态。 */
    fun reset() {
        currentPhase = Phase.IDLE
        warnFired = false
        endFired = false
    }

    /** 根据墙钟计算当前状态。 */
    fun poll(): PhaseSnapshot {
        if (!isRunning) {
            return PhaseSnapshot(Phase.IDLE, 0, false, 0, false, false)
        }

        val elapsedMs = clock() - phaseStartMs
        val elapsed = (elapsedMs / 1000L).toInt()
        val target = if (currentPhase == Phase.WORK) workSeconds else restSeconds
        val remaining = target - elapsed

        var warn = false
        var end = false

        // 前 1 分钟提醒：仅在阶段时长超过 1 分钟时触发，且只触发一次
        if (!warnFired && target > 60 && remaining <= 60 && remaining > 0) {
            warnFired = true
            warn = true
        }

        // 到时间提醒：只触发一次；之后进入超时正计时
        if (!endFired && remaining <= 0) {
            endFired = true
            end = true
        }

        val overtime = elapsed >= target
        val overtimeSeconds = if (overtime) elapsed - target else 0
        val displayRemaining = remaining.coerceAtLeast(0)

        return PhaseSnapshot(currentPhase, displayRemaining, overtime, overtimeSeconds, warn, end)
    }

    private fun beginPhase(phase: Phase) {
        currentPhase = phase
        phaseStartMs = clock()
        warnFired = false
        endFired = false
    }
}
