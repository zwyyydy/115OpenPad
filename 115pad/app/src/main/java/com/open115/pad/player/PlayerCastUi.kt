package com.open115.pad.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.open115.pad.cast.CastDevice
import com.open115.pad.cast.CastProgress
import com.open115.pad.cast.CastSession
import com.open115.pad.cast.PadTv
import com.open115.pad.cast.padTvDeviceOf
import com.open115.pad.cast.parsePadTvAddress
import java.util.Locale

/**
 * 投屏设备选择面板（挂在控制排顶部，与 VR/滤镜/弹幕面板同一个位置与交互）。
 *
 * 搜索是异步的：面板先出现、转圈、设备陆续回来；没搜到时给出"同一 Wi-Fi"的提示，
 * 因为这是投屏失败最常见的真实原因（电视走 2.4G、平板走 5G 且路由器开了隔离）。
 */
@Composable
internal fun CastMenu(
    devices: List<CastDevice>,
    scanning: Boolean,
    error: String?,
    onPick: (CastDevice) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /** 手动连接弹层（地址输入走 [PlayerTextEditDialog]，与弹幕源地址同一套交互） */
    var manualOpen by remember { mutableStateOf(false) }
    Surface(
        color = Color.Black.copy(alpha = 0.78f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 520.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "投屏到电视",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在搜索同一 Wi-Fi 下的电视…",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                devices.isNotEmpty() -> Column(
                    Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    devices.forEach { device ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(device) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Tv,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    device.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!device.model.isNullOrBlank()) {
                                    Text(
                                        device.model!!,
                                        color = Color.White.copy(alpha = 0.55f),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                else -> Text(
                    error ?: "没找到可投屏设备",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRescan, enabled = !scanning) {
                    Text("重新搜索", color = if (scanning) Color.White.copy(alpha = 0.4f) else Color.White)
                }
                // 兜底通道：广播发现在"路由器开了隔离 / 两台设备不在同一网段"时本来就没戏
                TextButton(onClick = { manualOpen = true }) {
                    Text("手动连接", color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                // 有设备时换掉提示：投的是**原始文件**，电视得自己会解——这条比"同一 Wi-Fi"
                // 更值得说，因为用户此刻已经搜到设备了，MKV/HEVC 放不了最容易被当成"投屏坏了"
                Text(
                    if (devices.isEmpty()) "电视需与本机在同一 Wi-Fi"
                    else "投原始文件，电视需支持该格式",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    if (manualOpen) {
        PlayerTextEditDialog(
            title = "手动连接电视端",
            label = "电视端地址",
            hint = "地址在电视端 App 的待机页上；只填 IP 也行（端口默认 48701）。" +
                "搜不到设备时用它——比如路由器开了隔离、或电视端与本机不在同一网段。",
            initial = "",
            singleLine = true,
            onSave = { input ->
                manualOpen = false
                parsePadTvAddress(input)?.let { (host, port) -> onPick(padTvDeviceOf(host, port)) }
            },
            onDismiss = { manualOpen = false },
        )
    }
}

/**
 * 投屏中的整屏遥控界面。
 *
 * 为什么做成整屏而不是底部一条：投屏时本地播放是暂停的，屏幕上那块定格画面既没有信息量，
 * 又容易被当成"还在本机播放"；整屏换成遥控面板后，视线所及都在回答两件事——
 * 电视在播什么、我能对它做什么。退出投屏后这层消失，本地播放画面照旧接回来。
 *
 * 触摸在这层被吃掉：背景是一整块"点了不做任何事"的可点区域，避免误触穿透到下面的
 * 播放器手势层（投屏时那些手势操作的是本地播放器，用户不该以为在控制电视）。
 */
@Composable
internal fun CastRemoteScreen(
    deviceName: String,
    title: String,
    playing: Boolean,
    connecting: Boolean,
    /** PadTv 通道才有倍速/音量（DLNA 只有播放/暂停/seek） */
    supportsExtras: Boolean,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    /** 解码方式（hw/sw）与播放器（builtin/system）：改的是电视端的播放行为 */
    onDecode: (String) -> Unit,
    onPlayer: (String) -> Unit,
    /** 画质档（def 编号，0 = 原盘）与字幕开关：改的是电视内置播放器的行为 */
    onQuality: (Int) -> Unit,
    onSubtitle: (Boolean) -> Unit,
    /** 打开在线字幕搜索（选中后由调用方下载并推给电视） */
    onSearchSubtitle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by CastSession.progress.collectAsState()
    // 电视端把流交给了自带的播放器：进度/播放/倍速/音量全部失效 ——
    // 面板只留"停止投屏"和"切回内置播放器"（后者就是恢复遥控的路径）
    val external = progress?.external == true
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF152540), Color(0xFF07080B))),
            ),
    ) {
        // 吸收触摸（不给下层手势留缝）：无涟漪、无点击态
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
        ) {
            // 上下等重弹簧代替 Arrangement.Center：内容矮时居中，行数多到超高时可滚动
            //（Center + verticalScroll 会把溢出的顶部裁掉）
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CastConnected,
                    contentDescription = null,
                    tint = Color(0xFF9FC3FF),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (connecting) "正在连接 $deviceName…" else "正在投屏到 $deviceName",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title.ifBlank { "投屏播放" },
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (!connecting) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "平板可以退出，投屏不会中断",
                    color = Color(0xFF9FC3FF).copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (external) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "已交给电视自带播放器：进度与遥控暂不可用（用电视遥控器操作，或切回内置播放器）",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(30.dp))

            // 进度（拖动中只显示目标时间，松手才发一次 seek）
            val duration = progress?.durationMs ?: 0L
            var dragFraction by remember { mutableStateOf<Float?>(null) }
            val remoteFraction = if (duration > 0) {
                ((progress?.positionMs ?: 0L).toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
            val fraction = dragFraction ?: remoteFraction
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
            ) {
                Text(
                    formatCastTime((fraction * duration).toLong()),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(56.dp),
                )
                Slider(
                    value = fraction,
                    onValueChange = { dragFraction = it },
                    onValueChangeFinished = {
                        val f = dragFraction
                        dragFraction = null
                        if (f != null && duration > 0) onSeek((f * duration).toLong())
                    },
                    enabled = duration > 0 && !external,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatCastTime(duration),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(56.dp),
                )
            }
            Spacer(Modifier.height(28.dp))

            // 主控三键（与遥控器同一套语义：左退右进中间播放/暂停）
            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteKey(
                    icon = Icons.Outlined.Replay10,
                    label = "退 10 秒",
                    size = 66.dp,
                    enabled = !external,
                ) {
                    onSeek(((progress?.positionMs ?: 0L) - 10_000L).coerceAtLeast(0L))
                }
                RemoteKey(
                    icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    label = if (playing) "暂停" else "播放",
                    size = 88.dp,
                    primary = true,
                    enabled = !external,
                    onClick = onTogglePlay,
                )
                RemoteKey(
                    icon = Icons.Outlined.Forward30,
                    label = "进 30 秒",
                    size = 66.dp,
                    enabled = !external,
                ) {
                    val target = (progress?.positionMs ?: 0L) + 30_000L
                    onSeek(if (duration > 0) target.coerceAtMost(duration) else target)
                }
            }

            if (supportsExtras) {
                // 音量与倍速只在"我们自己的播放器还在播"时有意义（external 时点它们没用）
                if (!external) {
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteKey(
                            icon = Icons.Outlined.VolumeDown,
                            label = "",
                            size = 48.dp,
                            enabled = (progress?.volumeMax ?: 0) > 0,
                            onClick = onVolumeDown,
                        )
                        Text(
                            volumeLabel(progress),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        RemoteKey(
                            icon = Icons.Outlined.VolumeUp,
                            label = "",
                            size = 48.dp,
                            enabled = (progress?.volumeMax ?: 0) > 0,
                            onClick = onVolumeUp,
                        )
                    }
                }
                // 画质：档位列表与电视端画质面板同源（原盘先播、清晰度列表后台取到后才浮现）
                val qualityOptions = progress?.qualityOptions.orEmpty()
                if (qualityOptions.size > 1) {
                    Spacer(Modifier.height(24.dp))
                    SettingChips(
                        title = "画质",
                        options = qualityOptions.map { it.def.toString() to it.label },
                        selected = (progress?.quality ?: qualityOptions.first().def).toString(),
                        enabled = !external,
                        onSelect = { v -> onQuality(v.toIntOrNull() ?: 0) },
                    )
                }
                Spacer(Modifier.height(24.dp))
                // 解码方式：换它电视端会重建播放器并从当前位置接着放
                SettingChips(
                    title = "解码",
                    options = listOf(PadTv.DECODE_HW to "硬解", PadTv.DECODE_SW to "软解"),
                    selected = progress?.decode ?: PadTv.DECODE_HW,
                    enabled = !external,
                    onSelect = onDecode,
                )
                if (!external) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "倍速",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.width(10.dp))
                        Row(
                            Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CAST_SPEEDS.forEach { s ->
                                CastSpeedChip(
                                    label = "x$s",
                                    selected = (progress?.speed ?: 1f) == s,
                                    onClick = { onSpeed(s) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                // 字幕：开关跟电视端的文本轨状态走；「搜索」是平板代下载在线字幕、base64 推给电视挂轨
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "字幕",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CastSpeedChip(
                            label = "关",
                            selected = !(progress?.subtitleOn ?: true),
                            onClick = { if (!external) onSubtitle(false) },
                        )
                        CastSpeedChip(
                            label = "开",
                            selected = progress?.subtitleOn ?: true,
                            onClick = { if (!external) onSubtitle(true) },
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onSearchSubtitle, enabled = !external) {
                        Text(
                            "搜索在线字幕…",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                // 播放器：切成"电视自带"= 外派播放（遥控会失效），切回"内置"就恢复遥控
                SettingChips(
                    title = "播放器",
                    options = listOf(
                        PadTv.PLAYER_BUILTIN to "内置",
                        PadTv.PLAYER_SYSTEM to "电视自带",
                    ),
                    selected = progress?.player ?: PadTv.PLAYER_BUILTIN,
                    enabled = true,
                    onSelect = onPlayer,
                )
            }

            Spacer(Modifier.height(34.dp))
            TextButton(onClick = onStop) {
                Text("停止投屏", color = Color.White.copy(alpha = 0.75f))
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 倍速档位与本地播放器的选项一致（0.5~3.0） */
private val CAST_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

private fun volumeLabel(progress: CastProgress?): String {
    val max = progress?.volumeMax ?: -1
    val vol = progress?.volume ?: -1
    return if (max > 0 && vol >= 0) "$vol/$max" else "—"
}

/** 两个选项的横向设置行（解码方式 / 播放器），复用倍速胶囊的样式 */
@Composable
private fun SettingChips(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, label) ->
                CastSpeedChip(
                    label = label,
                    selected = selected == value,
                    onClick = { if (enabled) onSelect(value) },
                )
            }
        }
    }
}

/** 遥控器按键：圆形 + 图标（可选小标签）；主键白底黑图标，一眼认出是播放/暂停 */
@Composable
private fun RemoteKey(
    icon: ImageVector,
    label: String,
    size: Dp,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    if (primary) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.12f),
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label.ifBlank { null },
                tint = if (primary) Color(0xFF0B0B0D) else Color.White.copy(alpha = alpha),
                modifier = Modifier.size(size * 0.42f),
            )
        }
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                color = Color.White.copy(alpha = 0.65f * alpha),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun CastSpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun formatCastTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
