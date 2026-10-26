package com.open115.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open115.tv.BuildConfig
import com.open115.tv.receiver.TvProtocol
import com.open115.tv.util.deviceName
import com.open115.tv.util.lanIpv4

/**
 * 待机页：App 打开后就停在这里等平板投屏。
 *
 * 信息只给排查用的三样：设备名（平板菜单里显示的就是它）、本机地址与端口、
 * 以及"投屏后平板可以退出"这句——它是这个 App 存在的理由。
 */
@Composable
internal fun StandbyScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ip = remember { lanIpv4() }
    val name = remember { deviceName(context) }

    // 遥控器进来要能一次按到「设置」：待机页其它内容都不可聚焦，先把焦点放它上面
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { settingsFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF101B2E), MaterialTheme.colorScheme.background),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Text(
                text = "115 openpad电视端",
                fontSize = 46.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "等待投屏…",
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 20.dp)) {
                    Text(
                        text = "本机：$name",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (ip != null) {
                            "地址：$ip · 端口 ${TvProtocol.HTTP_PORT}"
                        } else {
                            "未连接局域网（请连上 Wi-Fi 或有线网）"
                        },
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Text(
                text = "在平板的播放页点投屏键，选择本设备。开始播放后平板即可退出，投屏不会中断。",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            // 设置入口：遥控器唯一能聚焦的控件（软硬解 / 播放器两项，见 SettingsScreen）
            val settingsInteraction = remember { MutableInteractionSource() }
            val settingsFocused by settingsInteraction.collectIsFocusedAsState()
            Surface(
                color = Color.White.copy(alpha = if (settingsFocused) 0.24f else 0.10f),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .focusRequester(settingsFocus)
                    .clip(RoundedCornerShape(50))
                    .border(
                        width = 2.dp,
                        color = if (settingsFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(interactionSource = settingsInteraction, indication = null, onClick = onOpenSettings),
            ) {
                Text(
                    "设置",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                fontSize = 14.sp,
                color = Color(0xFF6E727C),
            )
        }
    }
}
