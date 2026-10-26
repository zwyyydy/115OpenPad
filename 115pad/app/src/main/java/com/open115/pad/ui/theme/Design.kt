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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.staticCompositionLocalOf
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
 * 一套完整 UI 调色板：[AppColors] 的全部 Token。
 * 明亮/黑暗各一版（[LightAppPalette] / [DarkAppPalette]），黑暗模式整体切换。
 */
data class AppPalette(
    val bg: Color, val card: Color, val cardBorder: Color,
    val accent: Color, val accentDeep: Color, val accentSoft: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color,
    val graySoft: Color, val divider: Color,
    val greenBg: Color, val greenFg: Color,
    val redBg: Color, val redFg: Color,
    val blueBg: Color, val blueFg: Color,
    val purpleBg: Color, val purpleFg: Color,
    val amberBg: Color, val amberFg: Color,
    val slateBg: Color, val slateFg: Color,
)

/** 明亮 = macOS 中性灰底 + 纯白面板 + 系统蓝 */
val LightAppPalette = AppPalette(
    // macOS 中性灰底 + 纯白面板：底色不带蓝调，卡片才立得起来
    bg = Color(0xFFF5F5F7), card = Color(0xFFFFFFFF), cardBorder = Color(0x14000000),
    // 系统蓝 #007AFF；文字/链接用深一档保证 4.5:1
    accent = Color(0xFF007AFF), accentDeep = Color(0xFF0066CC), accentSoft = Color(0xFFE9F2FF),
    // 近黑主文字 / 苹果两级灰
    textPrimary = Color(0xFF1D1D1F), textSecondary = Color(0xFF6E6E73), textTertiary = Color(0xFF8E8E93),
    graySoft = Color(0xFFF2F2F7), divider = Color(0xFFE5E5EA),
    greenBg = Color(0xFFE8F8EE), greenFg = Color(0xFF248A3D),
    redBg = Color(0xFFFFEBEA), redFg = Color(0xFFD70015),
    blueBg = Color(0xFFE9F2FF), blueFg = Color(0xFF007AFF),
    purpleBg = Color(0xFFF6EDFB), purpleFg = Color(0xFF8944AB),
    amberBg = Color(0xFFFFF4E5), amberFg = Color(0xFFB25000),
    slateBg = Color(0xFFF2F2F7), slateFg = Color(0xFF6E6E73),
)

/**
 * 黑暗：苹果深色灰阶（#1C1C1E / #2C2C2E / #3A3A3C）+ 亮字。
 * 强调蓝用系统深色档 #0A84FF；语义徽章从"浅底深字"翻成"深底亮字"。
 */
val DarkAppPalette = AppPalette(
    // 苹果深色灰阶：底 #1C1C1E / 面板 #2C2C2E / 控件 #3A3A3C
    bg = Color(0xFF1C1C1E), card = Color(0xFF2C2C2E), cardBorder = Color(0x2EFFFFFF),
    accent = Color(0xFF0A84FF), accentDeep = Color(0xFF6CB4FF), accentSoft = Color(0xFF142C4A),
    textPrimary = Color(0xFFF2F2F7), textSecondary = Color(0xFFAEAEB2), textTertiary = Color(0xFF8E8E93),
    graySoft = Color(0xFF3A3A3C), divider = Color(0xFF38383A),
    greenBg = Color(0xFF10331B), greenFg = Color(0xFF30D158),
    redBg = Color(0xFF3A1714), redFg = Color(0xFFFF6961),
    blueBg = Color(0xFF102A45), blueFg = Color(0xFF64B4FF),
    purpleBg = Color(0xFF2C1B36), purpleFg = Color(0xFFDA8FFF),
    amberBg = Color(0xFF3A2B0C), amberFg = Color(0xFFFFD60A),
    slateBg = Color(0xFF2C2C2E), slateFg = Color(0xFFAEAEB2),
)

/** 当前生效的调色板，由 Open115Theme(dark) 按设置提供；[AppColors] 的取值全走这里 */
val LocalAppPalette = staticCompositionLocalOf { LightAppPalette }

/**
 * 壁纸激活时列表行"玻璃卡"的样式参数（设置 → 界面显示 → 壁纸里可调）。
 * [active] 为 false 时列表行保持扁平整宽行。
 */
data class WallpaperGlassStyle(
    val active: Boolean,
    /** 卡片底不透明度 0..1：越低玻璃感越强 */
    val cardAlpha: Float,
    /** 行间缝隙 dp（露壁纸的宽度；每行上下各占一半） */
    val gapDp: Int,
    /** 卡片圆角 dp，0 = 直角 */
    val radiusDp: Int,
)

/** 壁纸玻璃卡样式，由 Open115Theme 按设置提供；FileListRow 等行组件从这里取 */
val LocalWallpaperGlass = staticCompositionLocalOf { WallpaperGlassStyle(active = false, cardAlpha = 0.78f, gapDp = 8, radiusDp = 14) }

/**
 * 全局设计系统（Design System Tokens）色 Token。
 *
 * 视觉基准：柔和微冷浅灰底 + 纯白卡片（1px 微描边 + 微弥散投影）+ 高饱和现代蓝强调色，
 * 对齐 macOS / Raycast 的轻量质感。
 *
 * 取值经 [LocalAppPalette] 跟随明亮/黑暗模式：getter 标了 @Composable，调用点
 * `AppColors.X` 写在组合上下文（含组合期默认参数）即可，无需任何改动——
 * 但**不能**在非组合上下文取值（remember{} 的计算块、draw lambda 等），先在外面取好再传进去。
 */
object AppColors {
    /** 全局页面底色：明亮=柔和微冷浅灰 / 黑暗=冷灰 */
    val Bg: Color @Composable get() = LocalAppPalette.current.bg

    /** 卡片底色：明亮=纯白 */
    val Card: Color @Composable get() = LocalAppPalette.current.card

    /** 卡片描边 */
    val CardBorder: Color @Composable get() = LocalAppPalette.current.cardBorder

    /** 品牌强调色（高饱和现代蓝；黑暗版提亮一档保证对比度） */
    val Accent: Color @Composable get() = LocalAppPalette.current.accent

    /** 强调色深阶（明亮=深蓝 / 黑暗=浅蓝，均保证在对应选中底色上可读） */
    val AccentDeep: Color @Composable get() = LocalAppPalette.current.accentDeep

    /** 强调色选中底 */
    val AccentSoft: Color @Composable get() = LocalAppPalette.current.accentSoft

    /** 一级文本 */
    val TextPrimary: Color @Composable get() = LocalAppPalette.current.textPrimary

    /** 二级文本 */
    val TextSecondary: Color @Composable get() = LocalAppPalette.current.textSecondary

    /** 三级文本 */
    val TextTertiary: Color @Composable get() = LocalAppPalette.current.textTertiary

    /** 微交互浅灰底 */
    val GraySoft: Color @Composable get() = LocalAppPalette.current.graySoft

    /** 分隔线 */
    val Divider: Color @Composable get() = LocalAppPalette.current.divider

    // ---- 语义徽章：明亮=浅底+深字，黑暗=深底+亮字 ----
    val GreenBg: Color @Composable get() = LocalAppPalette.current.greenBg
    val GreenFg: Color @Composable get() = LocalAppPalette.current.greenFg
    val RedBg: Color @Composable get() = LocalAppPalette.current.redBg
    val RedFg: Color @Composable get() = LocalAppPalette.current.redFg
    val BlueBg: Color @Composable get() = LocalAppPalette.current.blueBg
    val BlueFg: Color @Composable get() = LocalAppPalette.current.blueFg
    val PurpleBg: Color @Composable get() = LocalAppPalette.current.purpleBg
    val PurpleFg: Color @Composable get() = LocalAppPalette.current.purpleFg
    val AmberBg: Color @Composable get() = LocalAppPalette.current.amberBg
    val AmberFg: Color @Composable get() = LocalAppPalette.current.amberFg
    val SlateBg: Color @Composable get() = LocalAppPalette.current.slateBg
    val SlateFg: Color @Composable get() = LocalAppPalette.current.slateFg
}

/**
 * 圆角标尺：收敛到 4 / 8 / 12 / 16 / 24 / 50 六档。
 * 组件里别再写 6、9、10、14、18、20 这类中间值 —— 同屏出现两种相近圆角，
 * 看上去就是"没对齐"而不是"有层次"。
 */
object AppRadius {
    /** 小徽章、勾选框 */
    val xs = 4.dp

    /** 小控件底：类型角标、菜单项 */
    val sm = 8.dp

    /** 列表行、输入框、中等容器 */
    val md = 12.dp

    /** 卡片、面板 */
    val lg = 16.dp

    /** 底部弹层 */
    val xl = 24.dp
}

/** 字号标尺：11/12 的正文在 1000dp+ 的宽屏上偏小，二级信息统一提到 12sp */
object AppType {
    /** 徽章、列表元信息、时间戳 */
    val caption = 12.sp

    /** 胶囊、按钮、分组标题 */
    val label = 13.sp

    /** 正文 */
    val body = 15.sp

    /** 卡片标题 */
    val title = 16.sp

    /** 页面标题 */
    val page = 22.sp
}

/** 卡片圆角基准：12dp（和列表行同一档，macOS 的分组面板不会比行更圆） */
val CardShape = RoundedCornerShape(AppRadius.md)

/**
 * M3 组件共用的圆角标尺。默认 M3 的对话框/弹层圆角是 28dp、按钮是胶囊，
 * 放在这套偏紧凑的灰白界面里显得"圆过头"；统一收到 6/8/12/16/24。
 */
val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

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
    // 壁纸激活时卡片同列表行一起玻璃化（不透明度跟随"卡片不透明度"设置）；
    // 媒体库等恒暗场景由 Open115DarkTheme 把 LocalWallpaperGlass 重置为未激活
    val glass = LocalWallpaperGlass.current
    val cardBg = if (glass.active) AppColors.Card.copy(alpha = glass.cardAlpha) else AppColors.Card
    val base = modifier
        .shadow(
            // 柔和的弥散投影：macOS 面板靠"白面 + 极淡的落影 + 发丝边"分层，
            // 不用重阴影（重了像 Material 的浮起卡片）
            elevation = 6.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x08000000),
            spotColor = Color(0x1C000000),
        )
        .clip(shape)
        .background(cardBg)
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

/** 彩色微徽章（Badges）：12sp 胶囊标签，用于状态 / 统计 / 元信息 */
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
            .clip(RoundedCornerShape(AppRadius.xs))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = fg,
        fontSize = AppType.caption,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 状态语义：决定小圆点的颜色，文字一律走二级灰（只有失败/进行中允许上色） */
enum class StatusTone { Success, Info, Warning, Error, Neutral }

/**
 * 轻量状态行：6dp 圆点 + 12sp 文字，用来取代"每行一个彩色胶囊"。
 * 一屏几十张卡时，常驻的绿底/黄底胶囊比内容本身还抢眼；圆点既能表达状态，
 * 又不会在列表里连成色带。
 */
@Composable
fun StatusDot(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val dot = when (tone) {
        StatusTone.Success -> AppColors.GreenFg
        StatusTone.Info -> AppColors.BlueFg
        StatusTone.Warning -> AppColors.AmberFg
        StatusTone.Error -> AppColors.RedFg
        StatusTone.Neutral -> AppColors.TextTertiary
    }
    val fg = when (tone) {
        StatusTone.Error -> AppColors.RedFg
        StatusTone.Info -> AppColors.BlueFg
        else -> AppColors.TextSecondary
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = AppType.caption,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 自绘开关：44×24 轨道 + 18dp 白色圆钮，和 AppCheckbox / AppSlider 同一套语言。
 * 原生 M3 Switch 比它大一倍，在一块全是 32dp 高控件的页面里会显得突兀。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackW = 44.dp
    val trackH = 24.dp
    val thumb = 18.dp
    val knob by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) trackW - thumb - 3.dp else 3.dp,
        animationSpec = tween(160),
        label = "switchKnob",
    )
    val track by androidx.compose.animation.animateColorAsState(
        // 关态轨道：Divider 在灰底上几乎看不见，用三级文字色压一半透明度，明暗两套都成立
        targetValue = if (checked) AppColors.Accent else AppColors.TextTertiary.copy(alpha = 0.5f),
        animationSpec = tween(160),
        label = "switchTrack",
    )
    Box(
        modifier
            .size(trackW, trackH)
            .clip(RoundedCornerShape(50))
            .background(track)
            .clickable(
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(thumb)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
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
    val shape = RoundedCornerShape(AppRadius.xs)
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

/** 按文件名 / 类型推断彩色视觉（双层质感：淡色圆角底 + 深色图标）；取 AppColors 需在组合期 */
@Composable
fun kindVisualOf(name: String, isDir: Boolean): KindVisual {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        // 文件夹也走调色板：深色模式下一块奶油白太扎眼
        isDir -> KindVisual(Icons.Outlined.Folder, AppColors.AmberFg, AppColors.AmberBg)
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
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(v.bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(v.icon, contentDescription = null, tint = v.tint, modifier = Modifier.size(size * 0.55f))
    }
}
