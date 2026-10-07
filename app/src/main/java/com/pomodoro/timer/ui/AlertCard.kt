package com.pomodoro.timer.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.pomodoro.timer.AlertKind
import com.pomodoro.timer.AlertRequest
import com.pomodoro.timer.Phase
import com.pomodoro.timer.R
import kotlinx.coroutines.delay

private val CardHeight = 280.dp

/**
 * 强制置顶提醒卡片。对应桌面版 `AlertForm`：
 * - 顶部 6dp 强调色条 + 圆角 + 深色底；
 * - 大号 emoji、标题、副标题、单个「知道了」按钮；
 * - 提前提醒 10 秒自动关闭，到时提醒必须手动关闭；
 * - 返回键只关闭卡片，不触发任何业务操作；
 * - 卡片水平居中，垂直位于屏幕高度的 30% 处。
 */
@Composable
fun AlertCard(request: AlertRequest, onDismiss: () -> Unit) {
    val isWarn = request.kind == AlertKind.WARN
    val phaseName = if (request.phase == Phase.WORK) "工作" else "休息"
    val endOfWork = request.phase == Phase.WORK

    val emoji = if (isWarn) "⏳" else if (endOfWork) "🌿" else "🍅"
    val title = when {
        isWarn -> stringResource(R.string.alert_title_warn)
        endOfWork -> stringResource(R.string.alert_title_work_end)
        else -> stringResource(R.string.alert_title_rest_end)
    }
    val subtitle = when {
        isWarn -> stringResource(R.string.alert_subtitle_warn, phaseName)
        endOfWork -> stringResource(R.string.alert_subtitle_work_end)
        else -> stringResource(R.string.alert_subtitle_rest_end)
    }
    val accent = when {
        isWarn -> if (request.phase == Phase.WORK) Palette.Work else Palette.Rest
        endOfWork -> Palette.Rest
        else -> Palette.Work
    }

    // 提前提醒 10 秒自动关闭（对应 AlertSpec.AutoCloseSeconds = 10）
    LaunchedEffect(request) {
        if (isWarn) {
            delay(10_000L)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        LaunchedEffect(Unit) {
            // 让卡片所在窗口占满屏幕，才能按屏幕高度的 30% 定位
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val topOffset = (screenHeight * 0.30f) - (CardHeight / 2)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .padding(top = topOffset.coerceAtLeast(24.dp))
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 460.dp)
                    .height(CardHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.AlertBackground),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(accent),
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = emoji, fontSize = pt(38f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title,
                        color = accent,
                        fontSize = pt(19f),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = subtitle,
                        color = Palette.Dim,
                        fontSize = pt(10.5f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    AccentButton(
                        text = stringResource(R.string.btn_got_it),
                        accent = accent,
                        onClick = onDismiss,
                        minWidth = 190.dp,
                        fontSizePt = 12f,
                        horizontalPadding = 26.dp,
                    )
                }
            }
        }
    }
}

/** 供预览/测试使用的强调色查询。 */
internal fun accentFor(kind: AlertKind, phase: Phase): Color = when {
    kind == AlertKind.WARN -> if (phase == Phase.WORK) Palette.Work else Palette.Rest
    phase == Phase.WORK -> Palette.Rest
    else -> Palette.Work
}
