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
import androidx.compose.material3.FilterChip
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
import com.open115.pad.data.StartPage
import com.open115.pad.data.UserInfo
import com.open115.pad.player.PlayerCache
import com.open115.pad.ui.components.ConfirmDialog
import com.open115.pad.ui.components.TextEntryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val labFilterEnabled by container.playerPrefs.labFilterEnabled.collectAsState(initial = false)
    val autoSubmitClipboard by container.downloadPrefs.autoSubmitClipboardDownload
        .collectAsState(initial = false)
    var cacheSizeMb by remember { mutableStateOf(-1L) }
    /** 大图落盘缓存（cacheDir/huge_img）占用，-1 = 还在算 */
    var hugeCacheMb by remember { mutableStateOf(-1L) }

    // ---- 媒体库缓存（海报 + .nfo）----
    val mediaCacheEnabled by container.mediaPrefs.cacheEnabled.collectAsState(initial = true)
    val mediaCacheMaxMb by container.mediaPrefs.cacheMaxMb
        .collectAsState(initial = com.open115.pad.data.media.MediaPrefs.DEFAULT_MAX_MB)
    // 启动首页（界面显示 →「启动首页」）：存的是枚举名，见 data/AppPrefs.kt
    val startPage by container.appPrefs.startPage.collectAsState(initial = StartPage.FILES)
    /** 媒体库缓存占用（字节）：简介文本池 / 海报，-1 = 还在算 */
    var mediaTextBytes by remember { mutableStateOf(-1L) }
    var mediaPosterBytes by remember { mutableStateOf(-1L) }

    LaunchedEffect(Unit) {
        clientId = container.session.currentClientId()
        cacheSizeMb = withContext(Dispatchers.IO) {
            PlayerCache.sizeBytes(context) / (1024 * 1024)
        }
        hugeCacheMb = ImageUrlResolver.hugeCacheSizeBytes(context.cacheDir) / (1024 * 1024)
        mediaTextBytes = container.mediaCache.sizeBytes()
        // 海报在 container.cacheDir（= <cache>/images/media_img），和 huge_img **不是同一个根**
        // —— huge_img 在 ImageGalleryDialog 里用的是裸 context.cacheDir。两者别抄串。
        mediaPosterBytes = ImageUrlResolver.mediaCacheSizeBytes(container.cacheDir)
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
            subtitle = "已播内容缓存到本机，本次播放内回退进度零等待；达到上限自动清理最旧缓存。" +
                "播放地址每次进入都会更换签名，上次播放的缓存对新地址无效，退出播放器时自动清空",
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
                scope.launch {
                    withContext(Dispatchers.IO) { PlayerCache.clear(context) }
                    cacheSizeMb = 0
                }
            },
        )

        SectionTitle("实验室")
        Text(
            "实验功能，可能不稳定；不想要随时关掉，关掉即回到原样。以后新的尝鲜功能也放这里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SettingSwitch(
            title = "视频滤镜",
            subtitle = "给播放画面加风格滤镜（电影感 / 复古胶片 / 黑白 …）：开启后播放器右侧多一个「滤镜」" +
                "按钮，可选预设、也能自己拖亮度/对比度/饱和度等参数。" +
                "画面由 GPU 多过一道着色器，4K 高码率片源会略微增加耗电；" +
                "选「原图」或关掉本开关时仍走原来的渲染路径，零额外开销。" +
                "VR 视角下滤镜不生效（那条路径的画面由 VR 视窗自己画）",
            checked = labFilterEnabled,
            onChange = { v -> scope.launch { container.playerPrefs.setLabFilterEnabled(v) } },
        )

        SectionTitle("媒体库")
        SettingSwitch(
            title = "媒体库缓存",
            subtitle = "把海报与简介（.nfo）缓存到本机，减少 115 接口调用：" +
                "已缓存的海报进海报墙不再解析直链，重扫一个库也不再重复下载 .nfo；" +
                "关闭后不读写磁盘缓存（已存的不动，要腾空间点下面的清空）",
            checked = mediaCacheEnabled,
            onChange = { v -> scope.launch { container.mediaPrefs.setCacheEnabled(v) } },
        )
        // 缓存上限滑块：最右端点是「不限制」挡（内部存 0），行程比 MAX_MAX_MB 多一步
        val mp = com.open115.pad.data.media.MediaPrefs
        val unlimitedPos = mp.MAX_MAX_MB + mp.STEP_MB
        SliderRow(
            title = "缓存上限",
            valueText = if (mediaCacheMaxMb == mp.UNLIMITED_MB) "不限制" else "$mediaCacheMaxMb MB",
            value = (if (mediaCacheMaxMb == mp.UNLIMITED_MB) unlimitedPos else mediaCacheMaxMb).toFloat(),
            range = mp.MIN_MAX_MB.toFloat()..unlimitedPos.toFloat(),
            // Slider 的 steps 是"两端点之间的刻度数"，所以要减 1
            steps = mediaCacheSliderSteps(),
        ) { v ->
            scope.launch {
                val mb = (v / mp.STEP_MB).toInt().toLong() * mp.STEP_MB
                container.mediaPrefs.setCacheMaxMb(if (mb >= unlimitedPos) mp.UNLIMITED_MB else mb)
            }
        }
        SettingRow(
            title = "缓存占用",
            subtitle = when {
                mediaTextBytes < 0 || mediaPosterBytes < 0 -> "计算中…"
                mediaCacheMaxMb == mp.UNLIMITED_MB ->
                    "海报 ${fmtBytes(mediaPosterBytes)} · 简介 ${fmtBytes(mediaTextBytes)}（上限不限制）"
                else -> "海报 ${fmtBytes(mediaPosterBytes)} · 简介 ${fmtBytes(mediaTextBytes)}" +
                    "（上限主要限制海报，简介按上限的 5% 另算）"
            },
            onClick = null,
        )
        SettingRow(
            title = "清空媒体库缓存",
            subtitle = "清掉已缓存的海报与简介。下次进海报墙会重新解析直链并下载",
            onClick = {
                scope.launch {
                    container.mediaCache.clear()
                    ImageUrlResolver.clearMediaCache(container.cacheDir)
                    // 重新读一次而不是直接置 0：个别文件删不掉时能如实显示出来
                    mediaTextBytes = container.mediaCache.sizeBytes()
                    mediaPosterBytes = ImageUrlResolver.mediaCacheSizeBytes(container.cacheDir)
                }
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
        ChoiceRow(
            title = "启动首页",
            subtitle = "程序启动后默认显示哪一页；下次启动应用生效（媒体库页会收起左侧导航栏）",
            options = listOf("文件" to StartPage.FILES, "媒体库" to StartPage.MEDIA),
            selected = startPage,
            onSelect = { scope.launch { container.appPrefs.setStartPage(it) } },
        )
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
            title = "115 OpenPad v${com.open115.pad.BuildConfig.VERSION_NAME}",
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
                "BT 原生任务暂未实现；部分清晰度（4K/原画）需要 115 会员；\n" +
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

/** 媒体库缓存上限滑块的刻度数：Slider 的 steps 是"两端点之间的刻度数"，所以要减 1；最右端点是「不限制」挡 */
private fun mediaCacheSliderSteps(): Int {
    val mp = com.open115.pad.data.media.MediaPrefs
    return ((mp.MAX_MAX_MB + mp.STEP_MB - mp.MIN_MAX_MB) / mp.STEP_MB).toInt() - 1
}

/**
 * 占用显示：不足 1MB 用 KB、不足 10MB 保留一位小数。
 *
 * 不能一律整数 MB —— 媒体库缓存起步只有几十 KB（简介是文本），整数截断会显示成
 * "0 MB"，看起来像没缓存住；而海报动辄几百 MB，也不需要小数。
 */
private fun fmtBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> "%.0f KB".format(kb)
        kb < 10 * 1024 -> "%.1f MB".format(kb / 1024)
        else -> "%.0f MB".format(kb / 1024)
    }
}

/**
 * 二选一的设置行：标题 + 说明在左，右侧一排胶囊（选中态高亮）。
 *
 * 用 [FilterChip] 而不是 Switch ——「文件 / 媒体库」是两个平级选项，没有开/关语义，
 * 开关形状会让人以为是在启用/停用某个功能。
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
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
        options.forEach { (label, value) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
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
