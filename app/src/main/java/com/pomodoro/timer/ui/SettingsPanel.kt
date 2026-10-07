package com.pomodoro.timer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pomodoro.timer.AppSettings

/**
 * 设置面板。对应桌面版 `MainForm.BuildSettingsPanel`：
 * 两行「工作 / 休息」，每行是「分钟 + 秒」两个数值输入。
 *
 * 桌面版用 NumericUpDown（上下微调箭头），Android 上换成「− 数值 +」步进器，
 * 中间的数值可以直接点进去输入；输入范围与桌面版一致（工作分 0–120、休息分 0–60、秒 0–59）。
 *
 * [locked] 为 true 时（计时进行中）任何输入都会被拒绝并回弹到锁定值，
 * 与桌面版 `RevertIfChanged` 的行为一致。
 */
@Composable
fun SettingsPanel(
    workMinutes: Int,
    workSeconds: Int,
    restMinutes: Int,
    restSeconds: Int,
    locked: Boolean,
    onChange: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow(
            label = "工作",
            minutes = workMinutes,
            seconds = workSeconds,
            maxMinutes = 120,
            locked = locked,
            onMinutes = { raw ->
                val accepted = if (locked) workMinutes else raw.coerceIn(0, 120)
                if (accepted != workMinutes) {
                    onChange(AppSettings(accepted, workSeconds, restMinutes, restSeconds))
                }
                accepted
            },
            onSeconds = { raw ->
                val accepted = if (locked) workSeconds else raw.coerceIn(0, 59)
                if (accepted != workSeconds) {
                    onChange(AppSettings(workMinutes, accepted, restMinutes, restSeconds))
                }
                accepted
            },
        )
        SettingRow(
            label = "休息",
            minutes = restMinutes,
            seconds = restSeconds,
            maxMinutes = 60,
            locked = locked,
            onMinutes = { raw ->
                val accepted = if (locked) restMinutes else raw.coerceIn(0, 60)
                if (accepted != restMinutes) {
                    onChange(AppSettings(workMinutes, workSeconds, accepted, restSeconds))
                }
                accepted
            },
            onSeconds = { raw ->
                val accepted = if (locked) restSeconds else raw.coerceIn(0, 59)
                if (accepted != restSeconds) {
                    onChange(AppSettings(workMinutes, workSeconds, restMinutes, accepted))
                }
                accepted
            },
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    minutes: Int,
    seconds: Int,
    maxMinutes: Int,
    locked: Boolean,
    onMinutes: (Int) -> Int,
    onSeconds: (Int) -> Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Palette.Foreground,
            fontSize = pt(10.5f),
            modifier = Modifier.width(40.dp),
        )
        NumberStepper(value = minutes, max = maxMinutes, onCommit = onMinutes)
        UnitLabel("分")
        NumberStepper(value = seconds, max = 59, onCommit = onSeconds)
        UnitLabel("秒")
    }
}

@Composable
private fun UnitLabel(text: String) {
    Text(
        text = text,
        color = Palette.Dim,
        fontSize = pt(9.5f),
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

/**
 * 「− 数值 +」步进器。
 * [onCommit] 接收期望值并返回最终被接受的值：被拒绝（锁定时）返回原值即产生回弹效果。
 */
@Composable
private fun NumberStepper(
    value: Int,
    max: Int,
    onCommit: (Int) -> Int,
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) {
            text = value.toString()
        }
    }

    Row(
        modifier = Modifier
            .width(100.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.ControlBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(symbol = "−") {
            text = onCommit(value - 1).toString()
        }
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(3)
                val parsed = digits.toIntOrNull()
                text = if (parsed == null) digits else onCommit(parsed).toString()
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(Palette.Work),
            textStyle = TextStyle(
                color = Palette.Foreground,
                fontSize = pt(11f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    inner()
                }
            },
        )
        StepButton(symbol = "+") {
            text = onCommit((value + 1).coerceAtMost(max)).toString()
        }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .width(30.dp)
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = Palette.Dim,
            fontSize = pt(12f),
            fontWeight = FontWeight.Bold,
        )
    }
}
