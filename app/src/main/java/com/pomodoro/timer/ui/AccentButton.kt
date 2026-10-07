package com.pomodoro.timer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆角胶囊强调按钮。对应桌面版 `AccentButton`：
 * 半径等于高度的一半、按下时颜色压暗 8%、文本居中且不换行。
 */
@Composable
fun AccentButton(
    text: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 250.dp,
    fontSizePt: Float = 12.5f,
    horizontalPadding: Dp = 36.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 对应桌面版 ControlPaint.Dark(Accent, 0.08f)
    val fill = if (pressed) darken(accent, 0.08f) else accent

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth)
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = pt(fontSizePt),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun darken(color: Color, amount: Float): Color {
    val factor = (1f - amount).coerceIn(0f, 1f)
    return Color(
        red = color.red * factor,
        green = color.green * factor,
        blue = color.blue * factor,
        alpha = color.alpha,
    )
}
