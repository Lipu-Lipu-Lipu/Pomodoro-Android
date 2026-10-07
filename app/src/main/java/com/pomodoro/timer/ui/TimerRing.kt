package com.pomodoro.timer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * 计时圆环。对应桌面版 `MainForm.TimerRing`：
 * - 外圈整圆轨道 + 从 12 点方向顺时针递减的进度弧；
 * - 环粗 `max(8px, 宽/17)`；
 * - 超时状态画满圈并做 500ms 周期脉冲（满色 ↔ 50% 透明度）；
 * - 中央文字自适应缩放，保证文本对角线不压到圆弧。
 */
@Composable
fun TimerRing(
    text: String,
    progress: Double,
    overtime: Boolean,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight).coerceAtLeast(160.dp)
        Box(modifier = Modifier.size(side), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val sidePx = with(density) { side.toPx() }
            val thicknessPx = max(8f, sidePx / 17f)
            val fontSizeSp = remember(measurer, text, sidePx, thicknessPx, density.density) {
                computeFittedFontSizeSp(measurer, text, sidePx, thicknessPx, density.density)
            }

            // 进度弧的帧间插值。
            // 服务每 250ms 推一次快照，若直接把进度画出来，弧线会以 4Hz 一顿一顿地走；
            // 这里把两次快照之间补齐，渲染端每帧都在推进，视觉上是连续流动的。
            // 用线性缓动让角速度恒定（不用弹簧/加减速，否则会看出「一顿一冲」）。
            //
            // 刻意不写 `by`：把状态的读取推迟到下面的 Canvas 绘制阶段，
            // 这样每帧只触发「重绘」，不会触发「重组」。若在组合阶段读取（`by` 委托），
            // 动画会以 60fps 驱动整棵 TimerRing 重组，单帧开销可达数百毫秒（实测）。
            val smoothProgress = animateFloatAsState(
                targetValue = progress.toFloat(),
                animationSpec = tween(durationMillis = 250, easing = LinearEasing),
                label = "ringProgress",
            )

            // 桌面版 pulse = _tickCount / 2 % 2（250ms 轮询 → 每 500ms 翻转一次）
            // 同样留到绘制阶段读取，理由同上。
            val pulse = remember { mutableStateOf(true) }
            LaunchedEffect(overtime) {
                if (!overtime) {
                    pulse.value = true
                    return@LaunchedEffect
                }
                while (true) {
                    delay(500)
                    pulse.value = !pulse.value
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                // 在绘制阶段才读取动画状态：这样每帧只重绘，不重组。
                val progressNow = smoothProgress.value
                val pulseOn = pulse.value

                val thickness = thicknessPx
                val inset = thickness / 2f + 1f
                val arcTopLeft = Offset(inset, inset)
                val arcSize = Size(
                    size.width - thickness - 2f,
                    size.height - thickness - 2f,
                )

                drawArc(
                    color = Palette.Track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = thickness),
                )

                if (overtime) {
                    val color = if (pulseOn) Palette.Overtime else Palette.Overtime.copy(alpha = 130f / 255f)
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 359.9f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = thickness, cap = StrokeCap.Round),
                    )
                } else if (progressNow > 0.002f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = min(1.0f, progressNow) * 359.9f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = thickness, cap = StrokeCap.Round),
                    )
                }
            }

            Text(
                text = text,
                style = TextStyle(
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    color = if (overtime) Palette.Overtime else Palette.Foreground,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
        }
    }
}

/**
 * 文字自适应字号（单位 sp）。
 *
 * 与桌面版 `GetFittedFont` 同一套约束：
 * - 起始字号对应桌面版 `max(9pt, 宽×0.24×72/96)`，换算到 sp 即 `max(12, 宽dp×0.24)`；
 * - 内接正方形内径 `inner = max(10, 宽 - 2×环粗 - 4)`，允许的对角线为 `inner × 0.92`；
 * - 不满足则按 `max(0.5pt, 6%)` 递减（换算到 sp 即 `max(0.667, 6%)`），下限 8pt = 10.67sp。
 */
private fun computeFittedFontSizeSp(
    measurer: TextMeasurer,
    text: String,
    sidePx: Float,
    thicknessPx: Float,
    density: Float,
): Float {
    val inner = max(10f, sidePx - 2f * thicknessPx - 4f)
    val maxDiag = inner * 0.92f
    val maxDiagSq = maxDiag * maxDiag

    val floorSp = 8f * 4f / 3f
    var sp = max(9f * 4f / 3f, 0.24f * (sidePx / density))
    var layout: TextLayoutResult = measureText(measurer, text, sp)

    while (sp > floorSp) {
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        if (w * w + h * h <= maxDiagSq) {
            break
        }
        sp = max(floorSp, sp - max(0.5f * 4f / 3f, sp * 0.06f))
        layout = measureText(measurer, text, sp)
    }
    return sp
}

private fun measureText(measurer: TextMeasurer, text: String, sp: Float): TextLayoutResult =
    measurer.measure(
        text = AnnotatedString(text),
        style = TextStyle(
            fontSize = sp.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
