package com.open115.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open115.tv.player.mimeOfUrl
import com.open115.tv.receiver.PlayBus
import com.open115.tv.receiver.PlayRequest
import com.open115.tv.receiver.StatusResponse
import com.open115.tv.receiver.SystemPlayerProxy
import com.open115.tv.receiver.TvProtocol
import com.open115.tv.util.TvPrefs

/**
 * 「电视自带播放器」模式：把流经本机代理（补 UA）交给电视系统自带的播放器去放。
 *
 * 这条路的能力边界必须说清楚：
 * - **拿不到进度、也控不了它**——那是另一个 App，我们只能通过"关掉代理"让它断流；
 * - **本进程必须活着**（流是我们转发的），所以电视端 App 别被杀；
 * - 位置续播做不到（系统播放器不接受我们指定起播点），从头开始。
 *
 * 它的价值只有一个：内置播放器放不了的片（DV / DTS / 冷门封装）用电视自己的播放器往往能放。
 */
@Composable
internal fun SystemPlayScreen(
    request: PlayRequest,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val proxy = remember { SystemPlayerProxy() }
    var notice by remember { mutableStateOf<String?>(null) }
    val builtinFocus = remember { FocusRequester() }

    BackHandler { onExit() }

    DisposableEffect(request) {
        val mime = request.mime?.takeIf { it.isNotBlank() } ?: mimeOfUrl(request.url) ?: "video/*"
        val fail = runCatching {
            val local = proxy.start(
                sourceUrl = request.url,
                fileName = request.title.ifBlank { "video" },
                mime = mime,
                ua = request.ua,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(local), mime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.exceptionOrNull()

        notice = when {
            fail is ActivityNotFoundException -> "这台电视上找不到能播放该格式的自带播放器"
            fail != null -> "交给电视自带播放器失败：${fail.message}"
            else -> null
        }
        // 上报给平板：external = 已交给外部播放器，平板那边据此把遥控按钮置灰
        PlayBus.updateStatus(
            StatusResponse(
                state = if (fail == null) TvProtocol.STATE_EXTERNAL else TvProtocol.STATE_ERROR,
                title = request.title,
                error = notice,
                decode = TvPrefs.decode.value,
                player = TvProtocol.PLAYER_SYSTEM,
            ),
        )

        onDispose {
            // 离开这层就关代理：系统播放器随之断流停下（我们没有别的手段让它停）
            proxy.stop()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF152540), Color(0xFF07080B))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .widthIn(max = 720.dp),
        ) {
            Text(
                if (notice == null) "已交给电视自带播放器" else "没能交给电视自带播放器",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                request.title.ifBlank { "投屏播放" },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                notice ?: "播放、暂停、进度请用电视遥控器；平板上只剩「停止投屏」。\n" +
                    "流经本机中转（要补 115 的 UA），播放期间电视端 App 需要保持运行。",
                color = if (notice == null) Color.White.copy(alpha = 0.55f) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvActionButton(
                    label = "切回内置播放器",
                    focusRequester = builtinFocus,
                    onClick = { TvPrefs.setPlayer(context, TvProtocol.PLAYER_BUILTIN) },
                )
                TvActionButton(label = "退出投屏", onClick = onExit)
            }
        }
    }

    // 遥控器进来先落到第一个动作上，否则按键无处可去
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { builtinFocus.requestFocus() }
    }
}

@Composable
private fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        color = Color.White.copy(alpha = if (focused) 0.24f else 0.12f),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(50))
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
