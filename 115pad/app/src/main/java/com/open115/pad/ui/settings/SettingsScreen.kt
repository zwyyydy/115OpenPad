package com.open115.pad.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.open115.pad.AppContainer
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.UserInfo
import com.open115.pad.player.PlayerCache
import com.open115.pad.ui.components.ConfirmDialog
import com.open115.pad.ui.components.TextEntryDialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 探活（AppRoot 启动）也会写入，故放开为 internal
internal object UserInfoCache {
    var data: UserInfo? = null
    var at: Long = 0
    fun take(): UserInfo? = data?.takeIf { System.currentTimeMillis() - at < 5 * 60 * 1000 }
    fun put(v: UserInfo) {
        data = v
        at = System.currentTimeMillis()
    }
}

/** 用户信息卡片：文件页侧栏与设置页共用 */
@Composable
fun UserInfoCard(modifier: Modifier = Modifier, api: OpenApi? = null, full: Boolean = false) {
    val resolvedApi = api ?: androidx.compose.ui.platform.LocalContext.current.let { ctx ->
        (ctx.applicationContext as com.open115.pad.App115).container.openApi
    }
    var info by remember { mutableStateOf(UserInfoCache.data) }
    var loading by remember { mutableStateOf(UserInfoCache.take() == null) }

    LaunchedEffect(Unit) {
        if (UserInfoCache.take() != null) {
            info = UserInfoCache.take()
        } else {
            loading = true
            runCatching { com.open115.pad.data.parseUserInfo(resolvedApi.userInfo()) }
                .onSuccess { u ->
                    UserInfoCache.put(u)
                    info = u
                }
            loading = false
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        when {
            loading && info == null -> Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(24.dp)) }

            info != null -> {
                val u = info!!
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubcomposeAsyncImage(
                            model = u.avatar,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            loading = {},
                            error = {},
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(u.userName ?: "", style = MaterialTheme.typography.titleMedium)
                            Text(
                                u.vip?.levelName ?: "普通用户",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val total = u.space?.total?.size ?: 0
                    val used = u.space?.used?.size ?: 0
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (used.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已用 ${u.space?.used?.sizeFormat ?: "?"} / ${u.space?.total?.sizeFormat ?: "?"}" +
                                (if (full) "（剩余 ${u.space?.remain?.sizeFormat ?: "?"}）" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("无法加载用户信息", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var showLogout by remember { mutableStateOf(false) }
    var showEditAppId by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }

    val cacheEnabled by container.playerPrefs.cacheEnabled.collectAsState(initial = true)
    val cacheMaxMb by container.playerPrefs.cacheMaxMb.collectAsState(initial = 1024)
    val speedBoost by container.playerPrefs.speedBoost.collectAsState(initial = 2.5f)
    val seekSeconds by container.playerPrefs.seekSeconds.collectAsState(initial = 10)
    val miniProgress by container.playerPrefs.alwaysShowMiniProgress.collectAsState(initial = true)
    val showClock by container.playerPrefs.showClock.collectAsState(initial = true)
    val showBattery by container.playerPrefs.showBattery.collectAsState(initial = true)
    val showNetSpeed by container.playerPrefs.showNetSpeed.collectAsState(initial = true)
    val showSpecBadge by container.playerPrefs.showSpecBadge.collectAsState(initial = true)
    val subtitleTextSize by container.playerPrefs.subtitleTextSize.collectAsState(initial = 18f)
    val subtitleBottomPercent by container.playerPrefs.subtitleBottomPercent.collectAsState(initial = 0)
    val softwareDecode by container.playerPrefs.softwareDecode.collectAsState(initial = false)
    val autoSubmitClipboard by container.downloadPrefs.autoSubmitClipboardDownload
        .collectAsState(initial = false)
    var cacheSizeMb by remember { mutableStateOf(-1L) }
    /** 大图落盘缓存（cacheDir/huge_img）占用，-1 = 还在算 */
    var hugeCacheMb by remember { mutableStateOf(-1L) }

    LaunchedEffect(Unit) {
        clientId = container.session.currentClientId()
        cacheSizeMb = PlayerCache.sizeBytes(context) / (1024 * 1024)
        hugeCacheMb = ImageUrlResolver.hugeCacheSizeBytes(context.cacheDir) / (1024 * 1024)
    }

    // 宽屏防拉伸：设置内容收进 960dp 居中容器
    com.open115.pad.ui.theme.AdaptiveBody(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(title = { Text("设置") })
        UserInfoCard(Modifier.padding(16.dp), api = container.openApi, full = true)

        SectionTitle("账号")
        SettingRow(
            title = "退出登录",
            subtitle = "清除本机保存的授权令牌",
            onClick = { showLogout = true },
        )

        SectionTitle("开发者")
        SettingRow(
            title = "AppID (client_id)",
            subtitle = clientId.ifBlank { "未设置" },
            onClick = { showEditAppId = true },
        )

        SectionTitle("云下载")
        SettingSwitch(
            title = "剪贴板链接自动提交",
            subtitle = "复制下载链接后切回本应用：开启则直接静默提交云下载；" +
                "关闭则先弹出确认（磁力 / 电驴 / 直链 / 网盘分享均可识别）",
            checked = autoSubmitClipboard,
            onChange = { v -> scope.launch { container.downloadPrefs.setAutoSubmitClipboardDownload(v) } },
        )
        SettingRow(
            title = "外部唤起下载",
            subtitle = "支持 mypad://download?url=…、pan115://…、magnet: 链接，以及其它应用「分享到」纯文本；" +
                "外部唤起会直接提交，无需二次确认",
            onClick = null,
        )

        SectionTitle("播放器")
        SettingSwitch(
            title = "播放缓存",
            subtitle = "已播内容缓存到本机，回退进度零等待；达到上限自动清理最旧缓存",
            checked = cacheEnabled,
            onChange = { v -> scope.launch { container.playerPrefs.setCacheEnabled(v) } },
        )
        SliderRow(
            title = "缓存上限",
            valueText = "${cacheMaxMb} MB",
            value = cacheMaxMb.toFloat(),
            range = 256f..4096f,
            steps = 14,
        ) { v ->
            scope.launch { container.playerPrefs.setCacheMaxMb((v / 256).toInt() * 256) }
        }
        SliderRow(
            title = "长按倍速",
            valueText = String.format(java.util.Locale.CHINA, "x%.1f", speedBoost),
            value = speedBoost,
            range = 1.5f..4f,
            steps = 4,
        ) { v ->
            scope.launch { container.playerPrefs.setSpeedBoost((v / 0.5f).roundToInt() * 0.5f) }
        }
        val seekOptions = listOf(5, 10, 15, 30)
        val seekIndex = seekOptions.indexOf(seekSeconds).coerceAtLeast(0).toFloat()
        SliderRow(
            title = "双击快进 / 快退",
            valueText = "${seekSeconds} 秒",
            value = seekIndex,
            range = 0f..3f,
            steps = 2,
        ) { v ->
            val sec = seekOptions[v.toInt().coerceIn(0, seekOptions.lastIndex)]
            scope.launch { container.playerPrefs.setSeekSeconds(sec) }
        }
        SliderRow(
            title = "外挂字幕字号",
            valueText = "%d sp".format(subtitleTextSize.roundToInt()),
            value = subtitleTextSize,
            range = 12f..50f,
            steps = 19,
        ) { v ->
            scope.launch { container.playerPrefs.setSubtitleTextSize((v / 2).roundToInt() * 2f) }
        }
        SliderRow(
            title = "外挂字幕位置",
            valueText = if (subtitleBottomPercent <= 0) "贴底（默认）" else "上移 ${subtitleBottomPercent}%",
            value = subtitleBottomPercent.toFloat(),
            range = 0f..50f,
            steps = 9,
        ) { v ->
            scope.launch { container.playerPrefs.setSubtitleBottomPercent((v / 5).roundToInt() * 5) }
        }
        SettingSwitch(
            title = "软件解码",
            subtitle = "默认用硬件解码（省电、发热低）。出现花屏、黑屏、画面撕裂或直接起播失败时打开，" +
                "改用 CPU 软解；个别编码没有软解时会自动回退硬解。改完重进播放器生效",
            checked = softwareDecode,
            onChange = { v -> scope.launch { container.playerPrefs.setSoftwareDecode(v) } },
        )
        SettingSwitch(
            title = "常驻迷你进度条",
            subtitle = "控制栏隐藏时，播放器最底部保留一条 3dp 细进度条（无滑块）；" +
                "唤出控制栏会自动让位给大进度条。关闭后全屏不留任何进度元素",
            checked = miniProgress,
            onChange = { v -> scope.launch { container.playerPrefs.setAlwaysShowMiniProgress(v) } },
        )
        SettingRow(
            title = "清除视频缓存",
            subtitle = if (cacheSizeMb >= 0) "当前占用 ${cacheSizeMb} MB，点击清除" else "计算中…",
            onClick = {
                PlayerCache.clear(context)
                cacheSizeMb = 0
            },
        )

        SectionTitle("图片")
        SettingRow(
            title = "清除大图缓存",
            subtitle = if (hugeCacheMb >= 0) {
                "超大图的原图落盘（分块解码用），当前占用 ${hugeCacheMb} MB，点击清除；" +
                    "超过 200MB 会自动淘汰最久未用的"
            } else "计算中…",
            onClick = {
                ImageUrlResolver.clearHugeCache(context.cacheDir)
                // 重新读一次而不是直接置 0：个别文件删不掉时能如实显示出来
                hugeCacheMb = ImageUrlResolver.hugeCacheSizeBytes(context.cacheDir) / (1024 * 1024)
            },
        )

        SectionTitle("界面显示")
        Text(
            "全屏播放时右上角状态栏的显示内容（四项可独立开关）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SettingSwitch(
            title = "显示实时时间",
            subtitle = "右上角显示当前系统时钟（每分钟同步）",
            checked = showClock,
            onChange = { v -> scope.launch { container.playerPrefs.setShowClock(v) } },
        )
        SettingSwitch(
            title = "显示电池电量",
            subtitle = "右上角显示设备剩余电量（微型电池图标 + 百分比）",
            checked = showBattery,
            onChange = { v -> scope.launch { container.playerPrefs.setShowBattery(v) } },
        )
        SettingSwitch(
            title = "显示实时网速",
            subtitle = "右上角显示播放器当前加载缓冲速率（每秒刷新）",
            checked = showNetSpeed,
            onChange = { v -> scope.launch { container.playerPrefs.setShowNetSpeed(v) } },
        )
        SettingSwitch(
            title = "显示规格标签",
            subtitle = "展示视频画质规格胶囊（如 4K HDR · HEVC）",
            checked = showSpecBadge,
            onChange = { v -> scope.launch { container.playerPrefs.setShowSpecBadge(v) } },
        )

        SectionTitle("关于")
        SettingRow(
            title = "115 OpenPad v0.1.0",
            subtitle = "基于 115 开放平台 API 的第三方客户端，为大屏/平板优化",
            onClick = null,
        )
        SettingRow(
            title = "开放平台文档",
            subtitle = "yuque.com/115yun/open",
            onClick = { uri.openUri("https://www.yuque.com/115yun/open") },
        )
        Text(
            "说明：本应用仅供个人学习交流使用，请遵守《115生活开放平台开发者协议》。\n" +
                "上传、BT 任务暂未实现；部分清晰度（4K/原画）需要 115 会员；\n" +
                "接口存在频控，操作过快可能被暂时限制。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
    }

    if (showLogout) {
        ConfirmDialog(
            title = "退出登录",
            text = "将清除本机保存的 access_token 与 refresh_token，需要重新扫码授权。",
            confirmText = "退出",
            onConfirm = {
                showLogout = false
                scope.launch { container.session.logout() }
            },
            onDismiss = { showLogout = false },
        )
    }
    if (showEditAppId) {
        TextEntryDialog(
            title = "修改 AppID",
            label = "AppID (client_id)",
            initial = clientId,
            onConfirm = { newId ->
                showEditAppId = false
                scope.launch { container.session.saveClientId(newId) }
            },
            onDismiss = { showEditAppId = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    // 分组次级灰标题：拉开模块间距，替代生硬分隔线
    com.open115.pad.ui.theme.SectionHeader(text, Modifier.padding(top = 14.dp))
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // 细轨 4dp + 白芯拇指 + 拖动数值气泡
        com.open115.pad.ui.theme.AppSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            valueText = valueText,
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
