package com.open115.pad.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.open115.pad.AppContainer
import com.open115.pad.data.ImageUrlResolver
import com.open115.pad.data.OpenApi
import com.open115.pad.data.StartPage
import com.open115.pad.data.UserInfo
import com.open115.pad.ui.components.ConfirmDialog
import com.open115.pad.ui.components.TextEntryDialog
import com.open115.pad.ui.theme.AppChip
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.AppSlider
import com.open115.pad.ui.theme.SectionHeader
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

/**
 * 设置页：分类导航 + 列表驱动的条目渲染（见 [SettingsModel]）。
 *
 * 布局按宽度分两套，共用同一份数据与同一套行渲染：
 *  - 平板（≥600dp）：左侧分类栏 + 右侧内容栏同屏。原来是一个 960dp 单列铺 29 个条目 ——
 *    在 1489dp 宽的平板上左右各空 200dp，而开关画在列的右端，标签与控件隔了约 900dp，
 *    眼睛要在屏幕两端来回跳。收进两栏后每行宽度降到 ~640dp，标签和控件重新挨在一起。
 *  - 手机（<600dp）：分类列表 → 点进二级页（返回键回列表），不再是一条无限长的滚动条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val compact = LocalConfiguration.current.screenWidthDp < 600

    var selected by rememberSaveable { mutableStateOf(SettingsCategory.PLAYER) }
    /** 手机模式下是否已点进某个分类。平板两栏同屏，不需要这个状态 */
    var phoneOpened by rememberSaveable { mutableStateOf(false) }
    var showEditAppId by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }

    val speedBoost by container.playerPrefs.speedBoost.collectAsState(initial = 2.5f)
    val seekSeconds by container.playerPrefs.seekSeconds.collectAsState(initial = 10)
    val miniProgress by container.playerPrefs.alwaysShowMiniProgress.collectAsState(initial = true)
    val playerCacheEnabled by container.playerPrefs.cacheEnabled.collectAsState(initial = true)
    val playerCacheMaxMb by container.playerPrefs.cacheMaxMb.collectAsState(initial = 1024)
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
    val startPage by container.appPrefs.startPage.collectAsState(initial = StartPage.FILES)
    val mediaCacheEnabled by container.mediaPrefs.cacheEnabled.collectAsState(initial = true)
    val mediaCacheMaxMb by container.mediaPrefs.cacheMaxMb
        .collectAsState(initial = com.open115.pad.data.media.MediaPrefs.DEFAULT_MAX_MB)

    /**
     * 落盘缓存的占用（-1 = 还在算）。
     * 清理后只 bump [cacheUsageKey] 重算，**不直接置 0** —— 个别文件删不掉时要如实显示出来。
     */
    var cacheUsageKey by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf(CacheUsage()) }
    LaunchedEffect(cacheUsageKey) {
        usage = CacheUsage(
            mediaTextBytes = container.mediaCache.sizeBytes(),
            mediaPosterBytes = ImageUrlResolver.mediaCacheSizeBytes(container.cacheDir),
            hugeBytes = ImageUrlResolver.hugeCacheSizeBytes(context.cacheDir),
            imageBytes = withContext(Dispatchers.IO) { coilCacheBytes(context) },
        )
    }
    LaunchedEffect(Unit) { clientId = container.session.currentClientId() }

    val mp = com.open115.pad.data.media.MediaPrefs
    val sections = listOf(
        playerSection(
            speedBoost = speedBoost,
            seekSeconds = seekSeconds,
            miniProgress = miniProgress,
            softwareDecode = softwareDecode,
            subtitleTextSize = subtitleTextSize,
            subtitleBottomPercent = subtitleBottomPercent,
            onSpeedBoost = { v -> scope.launch { container.playerPrefs.setSpeedBoost((v / 0.5f).roundToInt() * 0.5f) } },
            onSeekSeconds = { sec -> scope.launch { container.playerPrefs.setSeekSeconds(sec) } },
            onMiniProgress = { v -> scope.launch { container.playerPrefs.setAlwaysShowMiniProgress(v) } },
            onSoftwareDecode = { v -> scope.launch { container.playerPrefs.setSoftwareDecode(v) } },
            onSubtitleTextSize = { v -> scope.launch { container.playerPrefs.setSubtitleTextSize((v / 2).roundToInt() * 2f) } },
            onSubtitleBottomPercent = { v -> scope.launch { container.playerPrefs.setSubtitleBottomPercent((v / 5).roundToInt() * 5) } },
        ),
        uiSection(
            startPage = startPage,
            showClock = showClock,
            showBattery = showBattery,
            showNetSpeed = showNetSpeed,
            showSpecBadge = showSpecBadge,
            onStartPage = { p -> scope.launch { container.appPrefs.setStartPage(p) } },
            onShowClock = { v -> scope.launch { container.playerPrefs.setShowClock(v) } },
            onShowBattery = { v -> scope.launch { container.playerPrefs.setShowBattery(v) } },
            onShowNetSpeed = { v -> scope.launch { container.playerPrefs.setShowNetSpeed(v) } },
            onShowSpecBadge = { v -> scope.launch { container.playerPrefs.setShowSpecBadge(v) } },
        ),
        downloadSection(
            autoSubmitClipboard = autoSubmitClipboard,
            onAutoSubmitClipboard = { v -> scope.launch { container.downloadPrefs.setAutoSubmitClipboardDownload(v) } },
        ),
        storageSection(
            playerCacheEnabled = playerCacheEnabled,
            playerCacheMaxMb = playerCacheMaxMb,
            mediaCacheEnabled = mediaCacheEnabled,
            mediaCacheMaxMb = mediaCacheMaxMb,
            unlimitedPos = mp.MAX_MAX_MB + mp.STEP_MB,
            usage = usage,
            onPlayerCacheEnabled = { v -> scope.launch { container.playerPrefs.setCacheEnabled(v) } },
            onPlayerCacheMaxMb = { mb -> scope.launch { container.playerPrefs.setCacheMaxMb(mb) } },
            onMediaCacheEnabled = { v -> scope.launch { container.mediaPrefs.setCacheEnabled(v) } },
            onMediaCacheMaxMb = { mb -> scope.launch { container.mediaPrefs.setCacheMaxMb(mb) } },
            onClearMedia = {
                scope.launch {
                    container.mediaCache.clear()
                    ImageUrlResolver.clearMediaCache(container.cacheDir)
                    cacheUsageKey++
                }
            },
            onClearHuge = {
                ImageUrlResolver.clearHugeCache(context.cacheDir)
                cacheUsageKey++
            },
            onClearImage = {
                scope.launch {
                    clearCoilCache(context)
                    cacheUsageKey++
                }
            },
        ),
        labSection(
            labFilterEnabled = labFilterEnabled,
            onLabFilterEnabled = { v -> scope.launch { container.playerPrefs.setLabFilterEnabled(v) } },
        ),
        accountSection(
            clientId = clientId,
            onEditAppId = { showEditAppId = true },
            onLogout = { scope.launch { container.session.logout() } },
        ),
        aboutSection(
            version = com.open115.pad.BuildConfig.VERSION_NAME,
            onOpenDocs = { uri.openUri("https://www.yuque.com/115yun/open") },
        ),
    )
    val current = sections.first { it.category == selected }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") })
        when {
            // 手机：分类列表页（账号卡只在根页，进二级页后让位给内容）
            compact && !phoneOpened -> Column(Modifier.fillMaxSize()) {
                UserInfoCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), api = container.openApi, full = true)
                CategoryList(
                    selected = null,
                    compact = true,
                    onSelect = { selected = it; phoneOpened = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 手机：某个分类的二级页
            compact -> Column(Modifier.fillMaxSize()) {
                BackHandler { phoneOpened = false }
                PaneHeader(current.category.label) { phoneOpened = false }
                SettingsPane(current, showTitle = false, modifier = Modifier.fillMaxSize())
            }

            // 平板：左分类 + 右内容，整页收进 920dp 居中，不让行被拉到屏幕两端
            else -> Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 920.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                UserInfoCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), api = container.openApi, full = true)
                Row(Modifier.fillMaxSize()) {
                    CategoryList(
                        selected = selected,
                        compact = false,
                        onSelect = { selected = it },
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    )
                    VerticalDivider(color = AppColors.Divider)
                    SettingsPane(current, showTitle = true, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
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

// ---------------- 分类导航 ----------------

/**
 * 分类列表。平板是左侧固定栏（[compact] = false，只显示图标 + 名称），
 * 手机是整页列表（多一行说明和一个右箭头，因为点进去是一次真正的跳转）。
 */
@Composable
private fun CategoryList(
    selected: SettingsCategory?,
    compact: Boolean,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsCategory.entries.forEach { c ->
            val active = c == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) AppColors.AccentSoft else Color.Transparent)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 12.dp, vertical = if (compact) 12.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    c.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (active) AppColors.AccentDeep else AppColors.TextSecondary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        c.label,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) AppColors.AccentDeep else AppColors.TextPrimary,
                    )
                    if (compact) {
                        Text(
                            c.blurb,
                            fontSize = 12.sp,
                            color = AppColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (compact) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = AppColors.TextTertiary,
                    )
                }
            }
        }
    }
}

/** 手机二级页的顶栏：返回箭头 + 分类名（返回键与箭头同效） */
@Composable
private fun PaneHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "返回",
            modifier = Modifier.size(20.dp),
            tint = AppColors.TextSecondary,
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

// ---------------- 内容栏 ----------------

@Composable
private fun SettingsPane(section: SettingsSection, showTitle: Boolean, modifier: Modifier = Modifier) {
    Box(modifier) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (showTitle) {
                Text(
                    section.category.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                )
            }
            Text(
                section.category.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            section.groups.forEach { g ->
                g.label?.let { SectionHeader(it) }
                g.items.forEach { SettingItemRow(it) }
            }
        }
    }
}

@Composable
private fun SettingItemRow(item: SettingItem) {
    when (item) {
        is SwitchItem -> SwitchRow(item)
        is SliderItem -> SliderRow(item)
        is ChoiceItem<*> -> ChoiceRow(item)
        is ActionItem -> ActionRow(item)
        is InfoItem -> ItemShell(item)
    }
}

/**
 * 行外壳：标题（+ 可选小字）+ 一行短说明（+ 可选 ⓘ 展开的长解释）+ 右侧控件 + 说明下方的内容槽。
 * 五种行共用，保证行高、留白、折叠行为完全一致。
 *
 * 说明默认只占一行（[SettingItem.detail] 非空时截断）—— 原来最长的几条换两行还带括号，
 * 一屏只看得到三四项，这是"乱"的最直接来源。
 */
@Composable
private fun ItemShell(
    item: SettingItem,
    titleColor: Color = AppColors.TextPrimary,
    /** 标题后的小字（滑块的当前值） */
    titleTrailing: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                    if (titleTrailing != null) {
                        Text(
                            titleTrailing,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Accent,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (item.subtitle.isNotEmpty() || item.detail != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.subtitle.isNotEmpty()) {
                            Text(
                                item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary,
                                maxLines = if (item.detail != null && !expanded) 1 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                                // fill = false：说明短的时候 ⓘ 紧跟在文字后面，不会被推到行尾
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (item.detail != null) DetailToggle(expanded) { expanded = !expanded }
                    }
                }
            }
            trailing?.invoke()
        }
        if (expanded) {
            item.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        extra?.invoke()
    }
}

/** 长解释的展开开关。放在说明文字后面而不是行尾，读的时候视线不用横跨整行 */
@Composable
private fun DetailToggle(expanded: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.Info,
            contentDescription = if (expanded) "收起说明" else "详细说明",
            modifier = Modifier.size(16.dp),
            tint = AppColors.TextTertiary,
        )
    }
}

@Composable
private fun SwitchRow(item: SwitchItem) {
    ItemShell(
        item,
        // 整行可点也能切：不必瞄准右边那个开关（原来只有开关本身响应）
        onClick = { item.onChange(!item.checked) },
        trailing = { Switch(checked = item.checked, onCheckedChange = item.onChange) },
    )
}

@Composable
private fun SliderRow(item: SliderItem) {
    ItemShell(
        item,
        titleTrailing = item.valueText,
        extra = {
            AppSlider(
                value = item.value,
                onValueChange = item.onChange,
                valueRange = item.range,
                steps = item.steps,
                valueText = item.valueText,
            )
        },
    )
}

@Composable
private fun ChoiceRow(item: ChoiceItem<*>) {
    ItemShell(
        item,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.options.forEach { (label, value) ->
                    // onClick 必须显式传：AppChip 最后一个参数是 leading 的 composable 槽，
                    // 尾随 lambda 会被绑到那里去（onClick 反而报"没传值"）
                    AppChip(
                        label = label,
                        selected = value == item.selected,
                        onClick = { item.onSelectAny(value) },
                    )
                }
            }
        },
    )
}

/** 泛型擦除后 onSelect 收不到具体类型，这里统一转成 Any 再交回去（选项来自同一份列表，类型是安全的） */
@Suppress("UNCHECKED_CAST")
private fun ChoiceItem<*>.onSelectAny(value: Any?) {
    (this as ChoiceItem<Any?>).onSelect(value)
}

@Composable
private fun ActionRow(item: ActionItem) {
    var asking by remember(item.id) { mutableStateOf(false) }
    ItemShell(
        item,
        titleColor = if (item.danger) AppColors.RedFg else AppColors.TextPrimary,
        onClick = { if (item.confirm != null) asking = true else item.onClick() },
        // 动作行给个右箭头：否则它和只读信息行长得一模一样，看不出哪个能点
        trailing = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = AppColors.TextTertiary,
            )
        },
    )
    val confirm = item.confirm
    if (asking && confirm != null) {
        ConfirmDialog(
            title = item.title,
            text = confirm,
            confirmText = item.confirmText,
            onConfirm = {
                asking = false
                item.onClick()
            },
            onDismiss = { asking = false },
        )
    }
}

// ---------------- 各分类的条目声明 ----------------

private fun playerSection(
    speedBoost: Float,
    seekSeconds: Int,
    miniProgress: Boolean,
    softwareDecode: Boolean,
    subtitleTextSize: Float,
    subtitleBottomPercent: Int,
    onSpeedBoost: (Float) -> Unit,
    onSeekSeconds: (Int) -> Unit,
    onMiniProgress: (Boolean) -> Unit,
    onSoftwareDecode: (Boolean) -> Unit,
    onSubtitleTextSize: (Float) -> Unit,
    onSubtitleBottomPercent: (Float) -> Unit,
): SettingsSection = section(SettingsCategory.PLAYER) {
    group("播放")
    switch(
        id = "software_decode",
        title = "软件解码",
        subtitle = "花屏 / 黑屏 / 撕裂 / 起播失败时打开，改完重进播放器生效",
        detail = "默认用硬件解码（省电、发热低）。打开后改用 CPU 软解；个别编码没有软解时会自动回退硬解。",
        checked = softwareDecode,
        onChange = onSoftwareDecode,
    )
    switch(
        id = "mini_progress",
        title = "常驻迷你进度条",
        subtitle = "控制栏隐藏时，底部保留一条 3dp 细进度条",
        detail = "唤出控制栏会自动让位给大进度条。关闭后全屏不留任何进度元素。",
        checked = miniProgress,
        onChange = onMiniProgress,
    )

    group("手势")
    slider(
        id = "speed_boost",
        title = "长按倍速",
        valueText = String.format(java.util.Locale.CHINA, "x%.1f", speedBoost),
        value = speedBoost,
        range = 1.5f..4f,
        steps = 4,
        subtitle = "长按画面时按这个倍数播放，松手恢复",
        onChange = onSpeedBoost,
    )
    // 4 档离散值：滑块走的是下标，回调里再映射回秒数
    val seekOptions = listOf(5, 10, 15, 30)
    val seekIndex = seekOptions.indexOf(seekSeconds).coerceAtLeast(0).toFloat()
    slider(
        id = "seek_seconds",
        title = "双击快进 / 快退",
        valueText = "${seekSeconds} 秒",
        value = seekIndex,
        range = 0f..3f,
        steps = 2,
        subtitle = "双击左 / 右半屏跳转的步长",
        onChange = { v -> onSeekSeconds(seekOptions[v.toInt().coerceIn(0, seekOptions.lastIndex)]) },
    )

    group("外挂字幕")
    slider(
        id = "subtitle_size",
        title = "字号",
        valueText = "%d sp".format(subtitleTextSize.roundToInt()),
        value = subtitleTextSize,
        range = 12f..50f,
        steps = 19,
        onChange = onSubtitleTextSize,
    )
    slider(
        id = "subtitle_pos",
        title = "位置",
        valueText = if (subtitleBottomPercent <= 0) "贴底（默认）" else "上移 ${subtitleBottomPercent}%",
        value = subtitleBottomPercent.toFloat(),
        range = 0f..50f,
        steps = 9,
        subtitle = "画面底部有内容挡住字幕时往上抬",
        onChange = onSubtitleBottomPercent,
    )
}

private fun uiSection(
    startPage: StartPage,
    showClock: Boolean,
    showBattery: Boolean,
    showNetSpeed: Boolean,
    showSpecBadge: Boolean,
    onStartPage: (StartPage) -> Unit,
    onShowClock: (Boolean) -> Unit,
    onShowBattery: (Boolean) -> Unit,
    onShowNetSpeed: (Boolean) -> Unit,
    onShowSpecBadge: (Boolean) -> Unit,
): SettingsSection = section(SettingsCategory.UI) {
    choice(
        id = "start_page",
        title = "启动首页",
        subtitle = "程序启动后默认显示哪一页，下次启动生效",
        detail = "选「媒体库」时启动会直接落到媒体库页（它会收起左侧导航栏）。",
        options = listOf("文件" to StartPage.FILES, "媒体库" to StartPage.MEDIA),
        selected = startPage,
        onSelect = onStartPage,
    )

    group("全屏播放时右上角")
    switch("hud_clock", "显示实时时间", "当前系统时钟，每分钟同步", checked = showClock, onChange = onShowClock)
    switch("hud_battery", "显示电池电量", "微型电池图标 + 百分比", checked = showBattery, onChange = onShowBattery)
    switch("hud_netspeed", "显示实时网速", "当前加载缓冲速率，每秒刷新", checked = showNetSpeed, onChange = onShowNetSpeed)
    switch("hud_spec", "显示规格标签", "画质规格胶囊，如 4K HDR · HEVC", checked = showSpecBadge, onChange = onShowSpecBadge)
}

private fun downloadSection(
    autoSubmitClipboard: Boolean,
    onAutoSubmitClipboard: (Boolean) -> Unit,
): SettingsSection = section(SettingsCategory.DOWNLOAD) {
    switch(
        id = "clipboard_auto",
        title = "剪贴板链接自动提交",
        subtitle = "复制下载链接后切回本应用即静默提交云下载",
        detail = "关闭后改为先弹确认。磁力 / 电驴 / 直链 / 网盘分享均可识别。",
        checked = autoSubmitClipboard,
        onChange = onAutoSubmitClipboard,
    )
    info(
        id = "external_intent",
        title = "外部唤起下载",
        subtitle = "支持 mypad://download?url=… 、pan115://… 、magnet: 与「分享到」",
        detail = "外部唤起会直接提交，无需二次确认。",
    )
}

private fun storageSection(
    playerCacheEnabled: Boolean,
    playerCacheMaxMb: Int,
    mediaCacheEnabled: Boolean,
    mediaCacheMaxMb: Long,
    unlimitedPos: Long,
    usage: CacheUsage,
    onPlayerCacheEnabled: (Boolean) -> Unit,
    onPlayerCacheMaxMb: (Int) -> Unit,
    onMediaCacheEnabled: (Boolean) -> Unit,
    onMediaCacheMaxMb: (Long) -> Unit,
    onClearMedia: () -> Unit,
    onClearHuge: () -> Unit,
    onClearImage: () -> Unit,
): SettingsSection {
    val mp = com.open115.pad.data.media.MediaPrefs
    return section(SettingsCategory.STORAGE) {
        // 播放缓存没有「占用 / 清除」行是刻意的：它只在单次播放会话内有效，退出播放器就清空，
        // 占用永远接近 0，显示出来只会让人以为没生效。开关与上限留着（会话内的回退、切档预载要用）
        group("播放")
        switch(
            id = "player_cache",
            title = "播放缓存",
            subtitle = "已播内容缓存到本机，本次播放内回退进度零等待",
            detail = "播放地址每次进入都会更换签名，上次播放的缓存对新地址无效 —— " +
                "所以退出播放器时会整个清空，只服务本次播放。达到上限自动清理最旧的分片。",
            checked = playerCacheEnabled,
            onChange = onPlayerCacheEnabled,
        )
        slider(
            id = "player_cache_max",
            title = "缓存上限",
            valueText = "$playerCacheMaxMb MB",
            value = playerCacheMaxMb.toFloat(),
            range = 256f..4096f,
            steps = 14,
            subtitle = "单次播放最多占用多少本机空间",
            onChange = { v -> onPlayerCacheMaxMb((v / 256).toInt() * 256) },
        )

        group("媒体库")
        switch(
            id = "media_cache",
            title = "媒体库缓存",
            subtitle = "海报与简介（.nfo）缓存到本机，减少 115 接口调用",
            detail = "已缓存的海报进海报墙不再解析直链，重扫一个库也不再重复下载 .nfo。" +
                "关闭后不读写磁盘缓存（已存的不动，要腾空间点下面的清空）。",
            checked = mediaCacheEnabled,
            onChange = onMediaCacheEnabled,
        )
        // 最右端点是「不限制」挡（内部存 0），行程比 MAX_MAX_MB 多一步
        slider(
            id = "media_cache_max",
            title = "缓存上限",
            valueText = if (mediaCacheMaxMb == mp.UNLIMITED_MB) "不限制" else "$mediaCacheMaxMb MB",
            value = (if (mediaCacheMaxMb == mp.UNLIMITED_MB) unlimitedPos else mediaCacheMaxMb).toFloat(),
            range = mp.MIN_MAX_MB.toFloat()..unlimitedPos.toFloat(),
            // Slider 的 steps 是"两端点之间的刻度数"，所以要减 1
            steps = mediaCacheSliderSteps(),
            subtitle = "上限主要限制海报，简介按上限的 5% 另算",
            onChange = { v ->
                val mb = (v / mp.STEP_MB).toInt().toLong() * mp.STEP_MB
                onMediaCacheMaxMb(if (mb >= unlimitedPos) mp.UNLIMITED_MB else mb)
            },
        )
        action(
            id = "media_clear",
            title = "清空媒体库缓存",
            subtitle = if (usage.ready) {
                "海报 ${fmtBytes(usage.mediaPosterBytes)} · 简介 ${fmtBytes(usage.mediaTextBytes)}"
            } else "计算中…",
            confirm = "将清掉已缓存的海报与简介。下次进海报墙会重新解析直链并下载。",
            onClick = onClearMedia,
        )

        group("图片")
        action(
            id = "huge_clear",
            title = "清除超大图缓存",
            subtitle = if (usage.ready) "超过 10MB 的原图落盘（分块解码用），当前 ${fmtBytes(usage.hugeBytes)}" else "计算中…",
            detail = "浏览超大图时会先把原图存到本机，翻回来不必重下。超过 200MB 自动淘汰最久未用的。",
            confirm = "将清掉已落盘的超大图原图。下次打开会重新下载。",
            onClick = onClearHuge,
        )
        action(
            id = "image_clear",
            title = "清除图片缓存",
            subtitle = if (usage.ready) "画廊原图与缩略图，当前 ${fmtBytes(usage.imageBytes)}" else "计算中…",
            detail = "画廊浏览过的图片按文件标识缓存在本机，关掉画廊再进来、重启应用都能直接命中。" +
                "上限由图片库自己按可用空间分配（最多 250MB），超了自动淘汰最久未用的。",
            confirm = "将清掉画廊已缓存的图片。下次浏览会重新下载。",
            onClick = onClearImage,
        )
    }
}

private fun labSection(
    labFilterEnabled: Boolean,
    onLabFilterEnabled: (Boolean) -> Unit,
): SettingsSection = section(SettingsCategory.LAB) {
    switch(
        id = "video_filter",
        title = "视频滤镜",
        subtitle = "播放器右侧多一个「滤镜」按钮：8 个预设 + 6 个参数滑块",
        detail = "画面由 GPU 多过一道着色器，4K 高码率片源会略微增加耗电；选「原图」或关掉本开关时" +
            "仍走原来的渲染路径，零额外开销。VR 视角下滤镜不生效（那条路径的画面由 VR 视窗自己画）。",
        checked = labFilterEnabled,
        onChange = onLabFilterEnabled,
    )
}

private fun accountSection(
    clientId: String,
    onEditAppId: () -> Unit,
    onLogout: () -> Unit,
): SettingsSection = section(SettingsCategory.ACCOUNT) {
    action(
        id = "app_id",
        title = "AppID (client_id)",
        subtitle = clientId.ifBlank { "未设置" },
        detail = "115 开放平台申请到的应用 ID，授权登录时用它换取令牌。",
        onClick = onEditAppId,
    )
    // 退出登录是整页最不可逆的操作，所以：放在账号分类最底、红色标题、点了先确认
    action(
        id = "logout",
        title = "退出登录",
        subtitle = "清除本机保存的授权令牌",
        detail = "会清掉 access_token 与 refresh_token，需要重新扫码授权。" +
            "同时会清空目录/海报/图片等本机缓存，避免换账号后看到上一个账号的内容。",
        danger = true,
        confirm = "将清除本机保存的 access_token 与 refresh_token，需要重新扫码授权。",
        confirmText = "退出",
        onClick = onLogout,
    )
}

private fun aboutSection(version: String, onOpenDocs: () -> Unit): SettingsSection =
    section(SettingsCategory.ABOUT) {
        info(
            id = "version",
            title = "115 OpenPad v$version",
            subtitle = "基于 115 开放平台 API 的第三方客户端，为大屏 / 平板优化",
        )
        action(
            id = "docs",
            title = "开放平台文档",
            subtitle = "yuque.com/115yun/open",
            onClick = onOpenDocs,
        )
        info(
            id = "disclaimer",
            title = "说明",
            subtitle = "仅供个人学习交流使用，请遵守《115生活开放平台开发者协议》",
            detail = "BT 原生任务暂未实现；部分清晰度（4K / 原画）需要 115 会员；" +
                "接口存在频控，操作过快可能被暂时限制。",
        )
    }

// ---------------- 缓存占用 ----------------

/**
 * 落盘缓存的占用快照。[mediaTextBytes] / [mediaPosterBytes] / [hugeBytes] / [imageBytes] 为 -1 表示还在算。
 *
 * 没有「播放缓存」这一项是刻意的：它只在单次播放会话内有效、退出播放器就清空，
 * 显示出来永远是 0，只会让人以为没生效。
 */
private data class CacheUsage(
    val mediaTextBytes: Long = -1,
    val mediaPosterBytes: Long = -1,
    val hugeBytes: Long = -1,
    val imageBytes: Long = -1,
) {
    val ready: Boolean get() = mediaTextBytes >= 0 && mediaPosterBytes >= 0 && hugeBytes >= 0 && imageBytes >= 0
}

/** Coil 自己的图片磁盘缓存（画廊原图/缩略图落在这里，见 ImageGalleryDialog 的 diskKeyOf） */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
private fun coilCacheBytes(context: Context): Long =
    coil.Coil.imageLoader(context).diskCache?.size ?: 0L

/**
 * 清空 Coil 的图片缓存。磁盘清理是阻塞 IO；内存缓存里是已解码位图，按 Coil 的约定放主线程清。
 * 与登出时走的是同一套（见 App115）。
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
private suspend fun clearCoilCache(context: Context) {
    withContext(Dispatchers.IO) { coil.Coil.imageLoader(context).diskCache?.clear() }
    withContext(Dispatchers.Main) { coil.Coil.imageLoader(context).memoryCache?.clear() }
}

/** 媒体库缓存上限滑块的刻度数：steps 是"两端点之间的刻度数"所以要减 1；最右端点是「不限制」挡 */
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
