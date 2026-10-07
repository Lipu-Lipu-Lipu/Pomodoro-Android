package com.pomodoro.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 提醒卡片类型。对应桌面版 `AlertForm` 的两种卡片。 */
enum class AlertKind {
    /** 提前 1 分钟提醒：10 秒自动关闭、不抢焦点。 */
    WARN,

    /** 到时提醒：不自动关闭、抢焦点。 */
    END,
}

/** 一次提醒卡片请求。同一时刻只保留一个，新请求顶替旧请求。 */
data class AlertRequest(
    val kind: AlertKind,
    /** 触发提醒时所属的阶段（用于决定文案与强调色）。 */
    val phase: Phase,
)

/**
 * 进程级单例状态持有者。
 *
 * 桌面版的计时器活在主窗口进程里，窗口隐藏到托盘后仍在跑；
 * Android 上界面可能被销毁而服务仍在跑，所以把引擎与 UI 状态提到进程级单例，
 * 由 [PomodoroService] 负责推进，[MainActivity] 只做渲染与转发操作。
 */
object PomodoroController {

    private val engine = PomodoroEngine()

    private val _snapshot = MutableStateFlow(
        PhaseSnapshot(Phase.IDLE, 0, false, 0, false, false)
    )
    val snapshot: StateFlow<PhaseSnapshot> = _snapshot.asStateFlow()

    private val _alert = MutableStateFlow<AlertRequest?>(null)
    val alert: StateFlow<AlertRequest?> = _alert.asStateFlow()

    private val _exitRequested = MutableStateFlow(false)
    val exitRequested: StateFlow<Boolean> = _exitRequested.asStateFlow()

    /**
     * 界面当前是否可见。
     * 桌面版的提前提醒卡片总能在屏幕上弹出；Android 上界面在后台时无法弹卡片，
     * 此时只发高优先级通知，回到前台后也不补弹（与卡片 10 秒自动关闭的语义一致）。
     */
    @Volatile
    var isUiVisible: Boolean = false

    val isRunning: Boolean
        get() = engine.isRunning

    val currentPhase: Phase
        get() = engine.currentPhase

    /** 本轮生效的工作时长（秒）。对应桌面版 `_engine.WorkSeconds`。 */
    val workSeconds: Int
        get() = engine.workSeconds

    /** 本轮生效的休息时长（秒）。对应桌面版 `_engine.RestSeconds`。 */
    val restSeconds: Int
        get() = engine.restSeconds

    /** 从空闲进入工作阶段，同时写入本次使用的阶段时长。 */
    fun start(workSeconds: Int, restSeconds: Int) {
        engine.workSeconds = workSeconds
        engine.restSeconds = restSeconds
        engine.start()
        publish()
    }

    /** 结束当前阶段并立即开始下一阶段。 */
    fun endCurrentPhase() {
        engine.endCurrentPhase()
        publish()
        // 阶段已被用户显式推进，此前那条「需要休息请点击"结束，进入休息"」的提醒卡片已经过期。
        // 桌面版会让卡片继续留在屏幕上，但那会出现「已经进入休息、卡片还在叫你进入休息」的矛盾文案，
        // 所以在 Android 端随阶段推进一起清掉。
        _alert.value = null
    }

    /** 回到空闲状态。 */
    fun reset() {
        engine.reset()
        publish()
        _alert.value = null
    }

    /** 由服务按 250ms 节奏调用，推进状态机并发布快照。 */
    fun tick(): PhaseSnapshot {
        val snap = engine.poll()
        _snapshot.value = snap
        return snap
    }

    fun requestAlert(request: AlertRequest) {
        _alert.value = request
    }

    fun dismissAlert() {
        _alert.value = null
    }

    fun requestExit() {
        _exitRequested.value = true
    }

    fun clearExitRequest() {
        _exitRequested.value = false
    }

    private fun publish() {
        _snapshot.value = engine.poll()
    }
}
