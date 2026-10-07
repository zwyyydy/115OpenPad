package com.open115.pad.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 媒体库这条线的**玻璃卡**：库列表 / 扫描记录 / 观影历史三个页面共用同一套外观。
 *
 * 提到这里来的原因：扫描记录原先自己画了个"35% 灰底 + 14dp 圆角"的方块，和库卡的玻璃卡
 * 摆在一起像是两个应用的东西 —— 同一产品的几个页面，容器必须长一个样。
 *
 * - 玻璃本体是**竖向渐变**（上 10% 白 → 下 3.5% 白）：均匀半透明看着像一块塑料，
 *   上亮下暗才有"一块玻璃立在那儿"的立体感
 * - 描边同样上亮下暗：顶边是环境光反射，底边几乎没有
 * - 光晕走 [androidx.compose.ui.draw.shadow] 的 spotColor/ambientColor（API 28+ 生效，
 *   更低版本退化成普通阴影，不会报错）
 * - 按下时**整块缩一点 + 描边转主色**：深底上只靠默认水波纹几乎看不出来（水波纹还会被
 *   这层 clip 收成圆角内的一小块），缩放是"按到了"最直接的说法。缩放只走 graphicsLayer，
 *   不触发重新布局 —— 不会把 LazyColumn 里的卡片挤一下
 *
 * ★ 只在**媒体库的深色主题**下用（[com.open115.pad.ui.theme.Open115DarkTheme]）：玻璃是
 *   "白色低透明度 + 主色光晕"，浅色底上等于隐形。
 *
 * @param onClick 为 null = 这张卡整体不可点（例如扫描记录卡片，只有里面的海报可点）
 * @param onLongClick 长按（只在可点时有效；观影历史用它删单条）
 * @param dimmed 被"拎起来"的那张：整块淡下去，视觉主体交给跟着手指的幻影
 * @param highlighted 拖动落点：描边转主色，比按下态更明显、也更持久
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    elevation: Dp = 14.dp,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    dimmed: Boolean = false,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, label = "glassCardPress")
    val clickable = if (onClick != null) {
        // indication = null：反馈由上面的缩放 + 描边承担，不再叠一层水波纹
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (dimmed) 0.35f else 1f
            }
            .shadow(
                elevation = elevation,
                shape = shape,
                spotColor = accent.copy(alpha = 0.55f),
                ambientColor = accent.copy(alpha = 0.30f),
            )
            .clip(shape)
            // 填充要整块均匀：早先 0.10 → 0.035 的竖向渐隐压在近全黑的媒体库底上，
            // 上沿还看得出是卡片、往下淡得跟背景一样，整张卡就散成"上面一道亮边 + 下面一块方角暗斑"。
            // 两端收窄后四边圆角都在，一眼是一块圆角面板。
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.085f)),
                ),
            )
            .border(
                width = if (highlighted || pressed) 2.dp else 1.dp,
                brush = Brush.verticalGradient(
                    if (highlighted) {
                        listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.40f))
                    } else if (pressed) {
                        listOf(accent.copy(alpha = 0.60f), accent.copy(alpha = 0.18f))
                    } else {
                        // 底边描边别虚到底：下缘是"这块暗斑有没有圆角"的唯一线索
                        listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.11f))
                    },
                ),
                shape = shape,
            )
            .then(clickable),
    ) { content() }
}

/**
 * 元信息小胶囊：一条事实一枚，默认弱色底 + 中灰字。
 *
 * 取代"几段灰字用「 · 」拼成一行"的写法 —— 同灰度同字号的一长串，扫读要逐字读，
 * 也分不出哪个是状态、哪个是配置。[tint] 只留给需要跳出来的那一条（如「新增 N 部」）。
 */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    bold: Boolean = false,
) {
    val fg = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .background(fg.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * 极光底：三团很淡的径向渐变（主色 / 青 / 主色），给毛玻璃卡片一点"透出来的东西"。
 *
 * 媒体库这条线的三个页面（库列表 / 扫描记录 / 观影历史）共用，纯绘制、零请求，
 * 也不会像背景图那样喧宾夺主。三团的位置刻意错开（左上 / 右侧 / 下方），
 * 避免看起来是三条带子。
 *
 * 浓度是实测调出来的：原来的 0.20/0.13/0.10 铺在 #1C1C23 上基本看不见，
 * 那样这几个页面就真成了一块纯色。
 */
@Composable
fun Modifier.auroraBackdrop(): Modifier {
    val base = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val cyan = MaterialTheme.colorScheme.secondary
    return drawBehind {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(size.width * 0.16f, size.height * 0.04f),
                radius = size.width * 0.55f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(cyan.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width * 0.96f, size.height * 0.34f),
                radius = size.width * 0.50f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.42f, size.height * 1.02f),
                radius = size.width * 0.60f,
            ),
        )
    }
}
