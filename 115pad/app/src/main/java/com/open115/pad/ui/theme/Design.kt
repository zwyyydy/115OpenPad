package com.open115.pad.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.items as gridItems

/**
 * 全局设计系统（Design System Tokens）。
 *
 * 视觉基准：柔和微冷浅灰底 + 纯白卡片（1px 微描边 + 微弥散投影）+ 高饱和现代蓝强调色，
 * 对齐 macOS / Raycast 的轻量质感。所有页面共用本文件的 Token 与组件，
 * 严禁在页面里再写死色值 / 圆角 / 阴影。
 *
 * 尺寸安全基准：
 * - 点击热区 ≥ 44dp；功能图标 20~24dp；微胶囊高 32dp；卡片圆角 12~16dp；基础边距 8/12/16/24dp
 * - 宽屏防拉伸：列表内容用 [AdaptiveBody] 收进 max 960dp 居中容器；
 *   卡片流用 [AdaptiveCardGrid]（GridCells.Adaptive，手机 1 列 / 平板自动多列）
 */
object AppColors {
    /** 全局页面底色：柔和微冷浅灰 */
    val Bg = Color(0xFFF8FAFC)

    /** 卡片底色：纯白 */
    val Card = Color(0xFFFFFFFF)

    /** 卡片描边：rgba(0,0,0,0.05) */
    val CardBorder = Color(0x0D000000)

    /** 品牌强调色（高饱和现代蓝） */
    val Accent = Color(0xFF2563EB)

    /** 强调色深阶（选中态文字） */
    val AccentDeep = Color(0xFF1D4ED8)

    /** 强调色浅底（选中胶囊底 / 激活容器） */
    val AccentSoft = Color(0xFFEFF6FF)

    /** 一级文本 */
    val TextPrimary = Color(0xFF0F172A)

    /** 二级文本（分组标题 / 未选中胶囊） */
    val TextSecondary = Color(0xFF64748B)

    /** 三级文本（副标题 / 时间戳） */
    val TextTertiary = Color(0xFF94A3B8)

    /** 微交互浅灰底（未选中胶囊触摸显露） */
    val GraySoft = Color(0xFFF1F5F9)

    /** 分隔线 */
    val Divider = Color(0xFFE2E8F0)

    // ---- 语义徽章（浅底 + 深字） ----
    val GreenBg = Color(0xFFDCFCE7); val GreenFg = Color(0xFF16A34A)
    val RedBg = Color(0xFFFEE2E2); val RedFg = Color(0xFFDC2626)
    val BlueBg = Color(0xFFDBEAFE); val BlueFg = Color(0xFF2563EB)
    val PurpleBg = Color(0xFFF3E8FF); val PurpleFg = Color(0xFF7C3AED)
    val AmberBg = Color(0xFFFEF3C7); val AmberFg = Color(0xFFB45309)
    val SlateBg = Color(0xFFF1F5F9); val SlateFg = Color(0xFF64748B)
}

/** 卡片圆角基准：12~16dp */
val CardShape = RoundedCornerShape(16.dp)

/**
 * 纯白悬浮卡片：微弥散投影（≈ 0 4px 16px rgba(0,0,0,0.03)）+ 1px 微描边 + 16dp 圆角。
 * onClick 为空时是纯展示卡片，否则整卡可点（带涟漪）。
 * onLongClick 只在需要「长按出菜单」的地方传，单传它时点击是空操作。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = CardShape
    val base = modifier
        .shadow(
            elevation = 3.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x0A000000),
            spotColor = Color(0x16000000),
        )
        .clip(shape)
        .background(AppColors.Card)
        .border(1.dp, AppColors.CardBorder, shape)
    Column(
        modifier = when {
            onLongClick != null -> base.combinedClickable(
                onClick = onClick ?: {},
                onLongClick = onLongClick,
            )
            onClick != null -> base.clickable(onClick = onClick)
            else -> base
        }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * 轻量分段胶囊（Subtle Segmented Chip）：彻底取代粗边框 FilterChip。
 * 未选中：无边框无底色、13sp 二级灰；触摸显露浅灰底。
 * 选中：淡蓝微透明底（#EFF6FF）+ 深蓝高亮文本。高 32dp。
 */
@Composable
fun AppChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val bg = if (selected) AppColors.AccentSoft else Color.Transparent
    val fg = if (selected) AppColors.AccentDeep else AppColors.TextSecondary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading?.invoke(this)
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 彩色微徽章（Badges）：11sp 胶囊标签，用于状态 / 统计 / 元信息 */
@Composable
fun StatusBadge(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = fg,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 自定义圆角 Checkbox：品牌蓝底 + 纯白勾，视觉 20dp、点击热区 32dp。
 * 取代系统原生复选框。
 */
@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(shape)
                .then(
                    if (checked) Modifier.background(AppColors.Accent, shape)
                    else Modifier
                        .background(Color.White, shape)
                        .border(1.5.dp, Color(0xFFCBD5E1), shape),
                )
                .clickable(onClick = onCheckedChange),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 细轨滑块：4dp 圆角轨道 + 白芯圆点拇指（带微投影）；
 * 拖动时拇指上方浮出数值气泡（valueText 由调用方给出）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    var dragging by remember { mutableStateOf(false) }
    val range = valueRange.endInclusive - valueRange.start
    val fraction = if (range > 0f) ((value - valueRange.start) / range).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val maxWpx = with(density) { maxWidth.toPx() }
        var bubbleWidth by remember { mutableStateOf(0) }
        Column {
            // 数值气泡：拖动时浮出，横向跟随拇指（按比例映射并防出界）
            Box(
                Modifier.fillMaxWidth().height(26.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = dragging,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(120)),
                ) {
                    val xPx = (fraction * maxWpx)
                        .coerceIn(0f, (maxWpx - bubbleWidth).coerceAtLeast(0f))
                    Text(
                        valueText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .offset(x = with(density) { xPx.toDp() })
                            .background(AppColors.TextPrimary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Slider(
                value = value,
                onValueChange = {
                    dragging = true
                    onValueChange(it)
                },
                onValueChangeFinished = { dragging = false },
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.Accent,
                    activeTrackColor = AppColors.Accent,
                    inactiveTrackColor = Color(0xFFE2E8F0),
                ),
                thumb = {
                    Box(
                        Modifier
                            .size(22.dp)
                            .shadow(3.dp, CircleShape)
                            .background(AppColors.Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 白色内芯
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(Color.White, CircleShape),
                        )
                    }
                },
                track = { state ->
                    val f = if (range > 0f)
                        ((state.value - valueRange.start) / range).coerceIn(0f, 1f)
                    else 0f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFE2E8F0)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(AppColors.Accent),
                        )
                    }
                },
            )
        }
    }
}

/** 分组次级灰标题（账号 / 播放器…）：13sp 加粗二级灰 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        color = AppColors.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    )
}

/**
 * 宽屏防拉伸容器：内容收进 max [maxWidth] 的居中列（默认 960dp），
 * 手机窄屏自然占满，平板宽屏不把条目甩到屏幕两端。
 */
@Composable
fun AdaptiveBody(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 960.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxSize(),
            content = content,
        )
    }
}

/**
 * 自适应卡片网格：宽屏多列、窄屏单列（由 [minCardWidth] 决定，推荐 380dp），
 * 外层收进 max [maxWidth] 居中。用于规则卡片 / 云下载任务卡等。
 */
@Composable
fun <T> AdaptiveCardGrid(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    minCardWidth: Dp = 380.dp,
    maxWidth: Dp = 1080.dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    onLoadMore: (() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        if (onLoadMore != null) {
            androidx.compose.runtime.LaunchedEffect(gridState, items.size) {
                androidx.compose.runtime.snapshotFlow {
                    gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                }.collect { last ->
                    if (last != null && items.isNotEmpty() && last >= items.size - 6) onLoadMore()
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCardWidth),
            state = gridState,
            modifier = Modifier.widthIn(max = maxWidth).fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(items, key = key) { item ->
                itemContent(item)
            }
        }
    }
}

// ---------------- 文件类型多彩视觉 ----------------

/** 文件类型的彩色视觉（图标 + 淡色底），列表 / 回收站 / 下载卡片共用 */
data class KindVisual(val icon: ImageVector, val tint: Color, val bg: Color)

private val videoExtsToken = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "webm", "m4v", "mpg", "mpeg", "rmvb", "m2ts",
)
private val imageExtsToken = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "avif", "tiff")

/** 按文件名 / 类型推断彩色视觉（双层质感：淡色圆角底 + 深色图标） */
fun kindVisualOf(name: String, isDir: Boolean): KindVisual {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        isDir -> KindVisual(Icons.Outlined.Folder, Color(0xFFB45309), Color(0xFFFEF3C7))
        ext in videoExtsToken -> KindVisual(Icons.Outlined.OndemandVideo, AppColors.BlueFg, AppColors.BlueBg)
        ext in imageExtsToken -> KindVisual(Icons.Outlined.Image, AppColors.GreenFg, AppColors.GreenBg)
        ext in setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "ape", "wma") ->
            KindVisual(Icons.Outlined.MusicNote, AppColors.PurpleFg, AppColors.PurpleBg)
        ext in setOf("zip", "rar", "7z", "tar", "gz") ->
            KindVisual(Icons.Outlined.FolderZip, AppColors.AmberFg, AppColors.AmberBg)
        ext == "apk" -> KindVisual(Icons.Outlined.Android, AppColors.GreenFg, AppColors.GreenBg)
        ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "epub", "mobi") ->
            KindVisual(Icons.Outlined.Description, AppColors.BlueFg, AppColors.BlueBg)
        ext in setOf("torrent") ->
            KindVisual(Icons.Outlined.Link, AppColors.AmberFg, AppColors.AmberBg)
        else -> KindVisual(Icons.Outlined.Description, AppColors.SlateFg, AppColors.SlateBg)
    }
}

/** 类型彩色角标（36dp 圆角淡色底 + 深色图标）：列表行 / 回收站 / 下载卡共用 */
@Composable
fun KindBadge(name: String, isDir: Boolean, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val v = kindVisualOf(name, isDir)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(v.bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(v.icon, contentDescription = null, tint = v.tint, modifier = Modifier.size(size * 0.55f))
    }
}
