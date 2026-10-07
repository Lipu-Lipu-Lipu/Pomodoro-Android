package com.pomodoro.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 提示音播放器：在内存中合成带泛音的 PCM（无资源文件），通过 AudioTrack 播放。
 *
 * 结构移植自桌面版 `BeepPlayer.cs`（基音叠加 2/3 次泛音 + 峰值归一化 + 淡入淡出），
 * 但**波形参数经过重新调整以消除刺耳感**，详见 [HARMONIC_2] 与 [FADE_MS] 的说明。
 * 桌面端播放失败时降级为 `Console.Beep`，Android 端降级为系统通知音。
 */
object TonePlayer {

    const val RATE = 44100

    /**
     * 淡入淡出时长（毫秒）。桌面版为 10ms。
     *
     * 10ms 的起音/收音太陡，听感上带「咔」的冲击，是刺耳感的来源之一。
     * 这里放宽到 30ms，让音头音尾圆滑过渡。
     * 注意：淡入淡出只改变包络形状，不改变采样数，因此总时长不受影响。
     */
    private const val FADE_MS = 30

    /**
     * 2 次泛音系数。桌面版为 0.40。
     *
     * 人耳在 2–5kHz 最敏感（外耳道共振 + 等响曲线），而桌面版的高音基频叠加 2/3 次泛音后
     * 正好落在这个区间：1568Hz 的 3 次泛音已达 4704Hz。0.40 / 0.18 的权重等于往
     * 最刺耳的频段大量灌能量，听感接近尖锐的方波。
     *
     * 这里大幅削弱高次泛音，音色由「方波式」转为接近纯正弦的柔和音色。
     */
    private const val HARMONIC_2 = 0.12

    /** 3 次泛音系数。桌面版为 0.18，削弱原因见 [HARMONIC_2]。 */
    private const val HARMONIC_3 = 0.04

    /** 单个音：基频、持续毫秒、其后静音间隔毫秒。 */
    data class Tone(val freq: Double, val ms: Int, val gapAfterMs: Int)

    /**
     * 开始/切换阶段：轻快上行双音（C5 → F5，上行纯四度）。
     *
     * 基频落在 500–800Hz：手机小扬声器在这个频段响应良好，
     * 又避开了 2kHz 以上的人耳敏感区。
     */
    private val startTones = arrayOf(
        Tone(523.25, 90, 40),
        Tone(698.46, 150, 0),
    )

    /** 提前 1 分钟提醒：两声柔和短音（F5 ×2）。 */
    private val warnTones = arrayOf(
        Tone(698.46, 180, 130),
        Tone(698.46, 180, 0),
    )

    /**
     * 到时提醒：C-E-G 大三和弦琶音 × 3 轮，约 3.3 秒。
     *
     * 桌面版用的是 880/1175/1568Hz 门铃音，最高音的泛音直达 4.7kHz；
     * 这里整体下移到 523–784Hz，并配合削弱后的泛音，听感明显柔和。
     */
    private val endTones = arrayOf(
        Tone(523.25, 150, 60), Tone(659.25, 180, 60), Tone(783.99, 420, 350),
        Tone(523.25, 150, 60), Tone(659.25, 180, 60), Tone(783.99, 420, 350),
        Tone(523.25, 150, 60), Tone(659.25, 180, 60), Tone(783.99, 420, 0),
    )

    /**
     * 各音的音量（峰值占满量程比例）。
     *
     * 削弱泛音与下移基频后，同等振幅下的听感响度会下降（人耳对高频更敏感），
     * 因此音量只做小幅下调，避免走到另一个极端——轻到听不见。
     */
    const val START_VOLUME = 0.26
    const val WARN_VOLUME = 0.46
    const val END_VOLUME = 0.47

    /** 合成后的 PCM。对应桌面版 `BeepPlayer.StartWav` / `WarnWav` / `EndWav`，供播放与自测共用。 */
    val startPcm: ShortArray by lazy { buildPcm(START_VOLUME, startTones) }
    val warnPcm: ShortArray by lazy { buildPcm(WARN_VOLUME, warnTones) }
    val endPcm: ShortArray by lazy { buildPcm(END_VOLUME, endTones) }

    fun playStart(context: Context): Boolean = playOrFallback(context, startPcm)

    fun playWarn(context: Context): Boolean = playOrFallback(context, warnPcm)

    fun playEnd(context: Context): Boolean = playOrFallback(context, endPcm)

    /**
     * 生成 16-bit 单声道 PCM。
     * 每个音可带独立的后置静音间隔，带 [FADE_MS] 淡入淡出防爆音，最后做峰值归一化。
     */
    fun buildPcm(volume: Double, tones: Array<Tone>): ShortArray {
        val fadeMax = RATE * FADE_MS / 1000
        val raw = ArrayList<Double>(RATE * 4)
        for (tone in tones) {
            val count = RATE * tone.ms / 1000
            // 淡入淡出最多占音长的 1/3，避免短音被包络吃掉大半而听不清
            val fade = minOf(fadeMax, count / 3).coerceAtLeast(1)
            for (i in 0 until count) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > count - fade -> (count - i).toDouble() / fade
                    else -> 1.0
                }
                val t = i.toDouble() / RATE
                val wave =
                    sin(2.0 * PI * tone.freq * t) +
                        HARMONIC_2 * sin(4.0 * PI * tone.freq * t) +
                        HARMONIC_3 * sin(6.0 * PI * tone.freq * t)
                raw.add(wave * env)
            }
            val gapCount = RATE * tone.gapAfterMs / 1000
            for (i in 0 until gapCount) {
                raw.add(0.0)
            }
        }

        var peak = 0.0
        for (v in raw) {
            val a = abs(v)
            if (a > peak) {
                peak = a
            }
        }
        if (peak < 1e-9) {
            peak = 1.0
        }

        return ShortArray(raw.size) { i ->
            (raw[i] / peak * volume * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    /** 时长（毫秒），供自测使用。 */
    fun durationMs(pcm: ShortArray): Int = pcm.size * 1000 / RATE

    /** 峰值占满量程的比例，供自测使用。 */
    fun peakRatio(pcm: ShortArray): Double {
        var peak = 0.0
        for (s in pcm) {
            val a = abs(s.toDouble() / Short.MAX_VALUE)
            if (a > peak) {
                peak = a
            }
        }
        return peak
    }

    private fun playOrFallback(context: Context, pcm: ShortArray): Boolean {
        if (play(pcm)) {
            return true
        }
        return playSystemNotificationSound(context)
    }

    /** 用 AudioTrack 静态模式播放内存中的 PCM，不写磁盘、不依赖资源文件。 */
    private fun play(pcm: ShortArray): Boolean {
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    // 走媒体音量（STREAM_MUSIC），而不是闹钟音量。
                    // 桌面版用 Console.Beep，不涉及音量通道；Android 上原先用 USAGE_ALARM，
                    // 会绕过媒体音量、按「闹钟」通道播放，用户在静音媒体时仍会被大声提示。
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val written = track.write(pcm, 0, pcm.size)
            if (written <= 0) {
                track.release()
                return false
            }
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        try {
                            t?.release()
                        } catch (_: Throwable) {
                        }
                    }

                    override fun onPeriodicNotification(t: AudioTrack?) {
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            track.play()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 降级方案：系统默认通知音（对应桌面版的 `Console.Beep` 兜底）。 */
    private fun playSystemNotificationSound(context: Context): Boolean = try {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return false
        RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        true
    } catch (_: Throwable) {
        false
    }
}
