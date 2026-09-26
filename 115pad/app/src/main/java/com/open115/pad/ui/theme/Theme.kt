package com.open115.pad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 明亮方案（组合期构建：色值来自 [AppColors]，而后者现在是跟随主题的 @Composable 取值）。
 * 柔和微冷浅灰底（#F8FAFC）+ 纯白卡片 + 高饱和现代蓝（#2563EB）。
 *
 * [wallpaper] 激活时的玻璃范围（[glassAlpha] 跟随"卡片不透明度"设置）：
 * - surface（TopAppBar）与 surfaceContainer（NavigationBar/Rail）→ 半透明白，栏体融进壁纸；
 * - surfaceContainerHigh/Highest（AlertDialog、DropdownMenu）与 Low（ModalBottomSheet）
 *   → **保持实底**：这些是浮在页面之上的覆盖层，半透明会把两个叠着的对话框/底层内容
 *   互相透视（实测：云下载"添加任务"里再开"选择保存位置"，两个对话框文字叠印）。
 * AppColors.Card 系（自定义卡片）不经过这里，仍由各自控制。
 */
@Composable
private fun lightColors(wallpaper: Boolean, glassAlpha: Float): androidx.compose.material3.ColorScheme {
    val glassSurface = if (wallpaper) AppColors.Card.copy(alpha = glassAlpha) else AppColors.Card
    return lightColorScheme(
        primary = AppColors.Accent,
        onPrimary = Color.White,
        primaryContainer = AppColors.AccentSoft,
        onPrimaryContainer = AppColors.AccentDeep,
        secondary = Color(0xFF0284C7),
        onSecondary = Color.White,
        tertiary = AppColors.AccentDeep,
        background = AppColors.Bg,
        onBackground = AppColors.TextPrimary,
        surface = glassSurface,
        onSurface = AppColors.TextPrimary,
        surfaceVariant = AppColors.GraySoft,
        onSurfaceVariant = AppColors.TextSecondary,
        surfaceContainer = glassSurface,
        // 浮层容器实底：Low 用实底浅灰（不是半透明的 Bg token），High/Highest 用实底白
        surfaceContainerLow = LightAppPalette.bg,
        surfaceContainerHigh = AppColors.Card,
        surfaceContainerHighest = AppColors.Card,
        outline = Color(0xFFE2E8F0),
        outlineVariant = AppColors.CardBorder,
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = AppColors.RedBg,
        onErrorContainer = AppColors.RedFg,
    )
}

/**
 * 全局主题入口。
 *
 * [dark] 由设置里的「外观」三选一（明亮 / 黑暗 / 跟随系统）解析而来（MainActivity）。
 * 同时提供两样东西：M3 色彩方案 + [LocalAppPalette] 调色板——
 * 页面里 `MaterialTheme.colorScheme.*` 与 `AppColors.*` 两套取值必须一起切，缺一就会
 * 出现"底色变暗、文字还是黑的"这类半身不遂。
 *
 * 媒体库不受此开关影响：它自己套 [Open115DarkTheme]（恒暗，沉浸式看片场景）。
 */
@Composable
fun Open115Theme(
    dark: Boolean = false,
    wallpaper: Boolean = false,
    /** 遮罩强度 0..1：决定可读性——壁纸上的白色蒙版 + 页面底色浓度都随它联动 */
    wallpaperMask: Float = 0f,
    /** 列表行玻璃卡参数（设置 → 壁纸里可调） */
    glassCardAlpha: Float = 0.78f,
    glassGapDp: Int = 8,
    glassRadiusDp: Int = 14,
    content: @Composable () -> Unit,
) {
    // 壁纸激活（仅明亮模式）：页面底色保底 0.55 的浅色垫底——列表行/设置行这类
    // 直接压在底色上的文字可读；遮罩滑块拉高时垫底浓度联动加强（0.55→0.9）+
    // 壁纸上的白蒙加厚。卡片/顶栏恒不透明。0% 遮罩 = 壁纸明显可见但文字仍有底。
    val palette = (if (dark) DarkAppPalette else LightAppPalette).let {
        if (wallpaper) it.copy(bg = it.bg.copy(alpha = 0.55f + 0.35f * wallpaperMask.coerceIn(0f, 1f))) else it
    }
    CompositionLocalProvider(
        LocalAppPalette provides palette,
        LocalWallpaperGlass provides WallpaperGlassStyle(
            active = wallpaper,
            cardAlpha = glassCardAlpha,
            gapDp = glassGapDp,
            radiusDp = glassRadiusDp,
        ),
    ) {
        MaterialTheme(colorScheme = if (dark) DarkColors else lightColors(wallpaper, glassCardAlpha), content = content)
    }
}

/**
 * 媒体库专用的深色方案。
 *
 * **不全局改深色**：客户端整体是浅色轻量质感（见上面 LightColors 的注释）。只有媒体库这一条线
 * 是"沉浸式看片"的场景 —— 列表 / 海报墙 / 详情页三屏连成一体，而详情页是 fanart 铺满 +
 * 取色底色，天然是暗的；列表和墙跟着暗才不割裂（用户原话：「和详情页协调一些」）。
 *
 * 三个页面只用了 `MaterialTheme.colorScheme.*`，所以**在路由层套一层这个就够了，不用逐个控件改**。
 *
 * ★ 底色是**深灰**（#1C1C23）不是近黑：第一版用了 #0E0E12，整页压得太重、像块黑板
 *   （用户：「这个颜色又太深了」）。现在底色比卡片低一档、卡片又比底色亮一档，
 *   靠这层明度差把层次做出来，而不是靠描边。
 *
 * `primary` 比浅色方案浅一档：`#2563EB` 在深灰底上太闷，搜索框聚焦线、可点文本都看不清。
 * `surfaceVariant` 也不能太亮 —— 透明 LOGO 海报（示例影片那几张）会直接露出它，亮了就是一块白方块。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF0E1524),
    primaryContainer = Color(0xFF2A3A5C),
    onPrimaryContainer = Color(0xFFD3E0FF),
    secondary = Color(0xFF8AD8F8),
    onSecondary = Color(0xFF0A2634),
    tertiary = Color(0xFFA9C8FF),
    background = Color(0xFF1C1C23),
    onBackground = Color(0xFFE9E9EF),
    surface = Color(0xFF26262E),
    onSurface = Color(0xFFE9E9EF),
    surfaceVariant = Color(0xFF34343E),
    onSurfaceVariant = Color(0xFFABABB8),
    surfaceContainer = Color(0xFF26262E),
    surfaceContainerLow = Color(0xFF202027),
    surfaceContainerHigh = Color(0xFF31313A),
    // ★ 这一档必须显式设：Material3 的 `Card` 容器色用的是 surfaceContainerHighest，
    //   不设就回落到 M3 基线的默认值（#36343B，**带紫调**），和这套冷灰调色板不是一家人。
    surfaceContainerHighest = Color(0xFF33333D),
    outline = Color(0xFF3F3F4A),
    outlineVariant = Color(0xFF2E2E38),
    error = Color(0xFFFF7B7B),
    onError = Color(0xFF2E0C0C),
    errorContainer = Color(0xFF43201F),
    onErrorContainer = Color(0xFFFFD2CF),
)

/** 媒体库这条线专用：套深色方案 + 暗调色板，并把壁纸玻璃重置为未激活（媒体库不受壁纸影响），路由层包一次即可 */
@Composable
fun Open115DarkTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppPalette provides DarkAppPalette,
        LocalWallpaperGlass provides WallpaperGlassStyle(active = false, cardAlpha = 1f, gapDp = 8, radiusDp = 14),
    ) {
        MaterialTheme(colorScheme = DarkColors, content = content)
    }
}
