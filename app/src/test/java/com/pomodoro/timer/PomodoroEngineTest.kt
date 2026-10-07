package com.pomodoro.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对应桌面版 `Program.cs --selftest` 的自动化自测，场景逐条照搬。
 *
 * 桌面版用 `--selftest` 参数 + exit code 表示结果，Android 端用 JVM 单元测试承载同一套断言：
 * `gradle :app:testDebugUnitTest`
 */
class PomodoroEngineTest {

    /** 用模拟时钟驱动状态机，与桌面版 `RunSelfTest` 的推进方式一致。 */
    @Test
    fun engineStateMachineMatchesDesktopSelfTest() {
        var now = 1_000_000L
        val engine = PomodoroEngine { now }
        engine.workSeconds = 120
        engine.restSeconds = 60

        engine.start()
        assertEquals("Start enters Work", Phase.WORK, engine.currentPhase)

        var s = engine.poll()
        assertFalse("Initial snapshot: no warn", s.justFiredWarn)
        assertFalse("Initial snapshot: no end", s.justFiredEnd)
        assertEquals("Initial snapshot is 120s remaining", 120, s.remainingSeconds)
        assertFalse("Initial snapshot is not overtime", s.isOvertime)

        now += 60_000
        s = engine.poll()
        assertTrue("Warn fires once at T-60s", s.justFiredWarn)
        assertFalse("Warn fires once at T-60s (no end)", s.justFiredEnd)

        s = engine.poll()
        assertFalse("Warn does not fire twice", s.justFiredWarn)
        assertFalse("Warn does not fire twice (no end)", s.justFiredEnd)

        now += 59_000
        s = engine.poll()
        assertFalse("Not ended at T-1s", s.justFiredEnd)
        assertFalse("Not overtime at T-1s", s.isOvertime)

        now += 1_000
        s = engine.poll()
        assertTrue("End fires at T-0", s.justFiredEnd)
        assertTrue("End fires at T-0 and enters overtime", s.isOvertime)
        assertEquals("Overtime starts at 0", 0, s.overtimeSeconds)

        now += 90_000
        s = engine.poll()
        assertTrue("Overtime counts up", s.isOvertime)
        assertEquals("Overtime counts up to +90s", 90, s.overtimeSeconds)
        assertFalse("End does not fire again", s.justFiredEnd)

        engine.endCurrentPhase()
        assertEquals("End switches to Rest", Phase.REST, engine.currentPhase)

        s = engine.poll()
        assertFalse("Rest auto-starts with flags reset (warn)", s.justFiredWarn)
        assertFalse("Rest auto-starts with flags reset (end)", s.justFiredEnd)
        assertFalse("Rest auto-starts with flags reset (overtime)", s.isOvertime)
        assertEquals("Rest auto-starts with 60s remaining", 60, s.remainingSeconds)

        now += 60_000
        s = engine.poll()
        assertFalse("No warn for 60s rest phase", s.justFiredWarn)
        assertTrue("No warn for 60s rest phase; end fires", s.justFiredEnd)

        engine.endCurrentPhase()
        assertEquals("Switch back to Work", Phase.WORK, engine.currentPhase)

        engine.reset()
        assertEquals("Reset returns to Idle", Phase.IDLE, engine.currentPhase)
        assertFalse("Reset stops running", engine.isRunning)
        assertEquals("Idle snapshot", Phase.IDLE, engine.poll().phase)

        engine.workSeconds = 30
        engine.start()
        now += 29_000
        s = engine.poll()
        assertFalse("No warn for 30s phase before end", s.justFiredWarn)
        assertFalse("30s phase not ended at T-1s", s.justFiredEnd)

        now += 1_000
        s = engine.poll()
        assertFalse("30s phase ends without warn", s.justFiredWarn)
        assertTrue("30s phase ends", s.justFiredEnd)
        assertTrue("30s phase enters overtime", s.isOvertime)
    }

    /**
     * 对应桌面版自测里的 WAV 头部、时长区间与峰值占比检查。
     *
     * 桌面版 `--selftest` 只断言「落在区间内」，这里进一步收紧成**与桌面版实测值逐值相等**：
     * 桌面版真实输出见 `desktop-selftest-reference.txt`（同一份参考同时被 README 引用）。
     *   Start WAV duration 280ms in [150,600]
     *   Warn  WAV duration 490ms in [200,900]
     *   End   WAV duration 3310ms in [2500,4500]
     * 桌面版还断言 Warn/End 峰值 ≥ 45% 满量程；归一化后峰值应恰等于各自音量常量。
     */
    @Test
    fun synthesizedTonesMatchDesktopSelfTest() {
        val startPcm = TonePlayer.startPcm
        val warnPcm = TonePlayer.warnPcm
        val endPcm = TonePlayer.endPcm

        assertTrue("Start PCM is non-empty", startPcm.isNotEmpty())
        assertTrue("Warn PCM is non-empty", warnPcm.isNotEmpty())
        assertTrue("End PCM is non-empty", endPcm.isNotEmpty())

        // 时长：与桌面版 --selftest 实测输出逐值相等（含音之间的静音间隔，不含末尾间隔）
        assertEquals("Start duration matches desktop 280ms", 280, TonePlayer.durationMs(startPcm))
        assertEquals("Warn duration matches desktop 490ms", 490, TonePlayer.durationMs(warnPcm))
        assertEquals("End duration matches desktop 3310ms", 3310, TonePlayer.durationMs(endPcm))

        // 桌面版仍保留的区间断言，这里一并复核，保证没有偏离它自己的容差
        assertTrue("Start duration in desktop range [150,600]", TonePlayer.durationMs(startPcm) in 150..600)
        assertTrue("Warn duration in desktop range [200,900]", TonePlayer.durationMs(warnPcm) in 200..900)
        assertTrue("End duration in desktop range [2500,4500]", TonePlayer.durationMs(endPcm) in 2500..4500)

        // 峰值：归一化后应等于音量常量（Int16 量化误差范围内）
        assertTrue("Warn peak >= 45% full scale", TonePlayer.peakRatio(warnPcm) >= 0.45)
        assertTrue("End peak >= 45% full scale", TonePlayer.peakRatio(endPcm) >= 0.45)
        assertEquals("Start peak equals START_VOLUME", TonePlayer.START_VOLUME, TonePlayer.peakRatio(startPcm), 0.001)
        assertEquals("Warn peak equals WARN_VOLUME", TonePlayer.WARN_VOLUME, TonePlayer.peakRatio(warnPcm), 0.001)
        assertEquals("End peak equals END_VOLUME", TonePlayer.END_VOLUME, TonePlayer.peakRatio(endPcm), 0.001)
    }

    /** 对应桌面版自测里的 `TotalSecondsOf` 与设置钳制 / 存取往返检查。 */
    @Test
    fun settingsAndDurationsMatchDesktopSelfTest() {
        assertEquals("TotalSecondsOf converts minutes+seconds", 150, totalSecondsOf(2, 30))
        assertEquals("TotalSecondsOf converts minutes+seconds", 45, totalSecondsOf(0, 45))
        assertEquals("TotalSecondsOf clamps to 1 second minimum", 1, totalSecondsOf(0, 0))

        val clamped = SettingsCodec.normalize(AppSettings(200, 75, -3, 99))
        assertEquals(
            "Settings Normalize clamps to control ranges",
            AppSettings(120, 59, 0, 59),
            clamped,
        )

        val sample = AppSettings(30, 15, 10, 45)
        assertEquals(
            "Settings save/load round-trip",
            sample,
            SettingsCodec.decode(SettingsCodec.encode(sample)),
        )

        assertEquals("Missing settings fall back to default", AppSettings.DEFAULT, SettingsCodec.decode(null))
        assertEquals("Corrupt settings fall back to default", AppSettings.DEFAULT, SettingsCodec.decode("oops"))
    }

    /**
     * 交叉校验：直接读取桌面版 `--selftest` 的**真实输出文件**，断言 Android 端合成音的时长
     * 与桌面版自己打印的毫秒数完全一致。
     *
     * 这条比上面那条更强 —— 上面的期望值是人抄过来的，这条是让两边机器各算一遍再比。
     * 参考文件由 `dotnet PomodoroTimer.dll --selftest > desktop-selftest-reference.txt` 生成。
     * 找不到时跳过（便于把 app 模块单独拷出去构建）。
     */
    @Test
    fun toneDurationsMatchDesktopSelfTestOutput() {
        val ref = File("../desktop-selftest-reference.txt")
        if (!ref.exists()) {
            println("SKIP: 未找到 ${ref.absolutePath}，跳过与桌面版的交叉校验")
            return
        }
        val text = ref.readText()
        assertTrue("桌面版自测必须是全绿（ALL PASS）", text.contains("ALL PASS"))

        fun desktopMs(label: String): Int {
            val match = Regex("""$label WAV duration (\d+)ms""").find(text)
                ?: error("参考文件中找不到 $label 的时长行")
            return match.groupValues[1].toInt()
        }

        assertEquals("Start 时长应与桌面版一致", desktopMs("Start"), TonePlayer.durationMs(TonePlayer.startPcm))
        assertEquals("Warn 时长应与桌面版一致", desktopMs("Warn"), TonePlayer.durationMs(TonePlayer.warnPcm))
        assertEquals("End 时长应与桌面版一致", desktopMs("End"), TonePlayer.durationMs(TonePlayer.endPcm))
    }

    /** 桌面版 `FormatRemaining` / `FormatOvertime` 的等价性检查。 */
    @Test
    fun timeFormattingMatchesDesktop() {
        assertEquals("02:30", TimeFormat.remaining(150))
        assertEquals("00:05", TimeFormat.remaining(5))
        assertEquals("+01:30", TimeFormat.overtime(90))
        assertEquals("+00:00", TimeFormat.overtime(0))
        assertEquals("+1h40", TimeFormat.overtime(100 * 60))
    }
}
