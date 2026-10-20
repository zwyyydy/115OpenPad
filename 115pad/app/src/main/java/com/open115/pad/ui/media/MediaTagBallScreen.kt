package com.open115.pad.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open115.pad.data.media.MediaDao
import com.open115.pad.data.media.TagSignals
import com.open115.pad.ui.components.auroraBackdrop
import com.open115.pad.ui.theme.AdaptiveBody
import com.open115.pad.ui.theme.AppChip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * 标签球：把整个库的**演员 + 标签**撒在一个会慢慢自转的球面上，字号按"盖住多少部片"排。
 * 点任意一个词 → 它的全部作品页（[MediaWorksScreen]，跨所有媒体库，与详情页点演员/标签同一条路）。
 *
 * 三件事刻意这么做：
 *
 * ① **画质/编码标签不进球**（`1080P` / `AVC1` / `4K`…）。剔除判据直接用推荐的
 *    [TagSignals.isNoise]，不再写第二份 —— 一个两千部的库里这类标签只有二十来个，
 *    却盖住大半个库，留在球上就会变成一圈"最大的字"，真正的内容标签全被压小。
 * ② **同一个名字永远同一个颜色**（[TagBallGeometry.colorIndex] 按名字哈希取色）：
 *    按顺序上色的话，库里加一部片就会让整颗球换色。
 * ③ 每帧**只重画、不重组合**：自转角度是普通的状态，但读它的地方全在绘制阶段
 *    （`drawBehind` 里现算投影），所以一帧只走 draw，不触发 recomposition /
 *    re-layout —— 上百个词条逐帧动起来也不掉帧（这是这个页面唯一需要在意性能的地方）。
 */
@Composable
fun MediaTagBallScreen(
    dao: MediaDao,
    /** 点了一个词：交给外面压一层"同演员/同标签的作品"浮层 */
    onOpenWorks: (WorksKind, String) -> Unit,
    onBack: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 600
    // 全库原料：一次查两张表。画质/编码在这一层就被剔掉，后面对谁都看不见它们
    val candidates by produceState<List<BallCandidate>?>(null) {
        val tags = dao.tagCloudCounts()
        val actors = dao.actorCloudCounts()
        value = buildList {
            tags.forEach { row ->
                if (row.name.isNotBlank() && !TagSignals.isNoise(row.name)) {
                    add(BallCandidate(row.name, WorksKind.Tag, row.tagCount))
                }
            }
            actors.forEach { row -> add(BallCandidate(row.name, WorksKind.Actor, row.tagCount)) }
        }
    }

    /** 只看标签 / 只看演员 / 两个都看（null） */
    var only by remember { mutableStateOf<WorksKind?>(null) }

    val shown = remember(candidates, only, compact) {
        val all = candidates.orEmpty()
        val filtered = if (only == null) all else all.filter { it.kind == only }
        TagBallPick.scatter(TagBallPick.pick(filtered, if (compact) BALL_LIMIT_COMPACT else BALL_LIMIT_WIDE))
    }
    // 词条（含字号/颜色/球面坐标）：在 shown 上再走一遍纯计算，没有请求也没有 IO
    val words = remember(shown, compact) {
        val maxCount = shown.maxOfOrNull { it.count } ?: 1
        val points = TagBallGeometry.spherePoints(shown.size)
        shown.mapIndexed { i, c ->
            val palette = if (c.kind == WorksKind.Actor) ACTOR_PALETTE else TAG_PALETTE
            BallWord(
                name = c.name,
                label = TagBallPick.labelOf(c.name),
                kind = c.kind,
                fontSizeSp = TagBallGeometry.fontSizeSp(c.count, maxCount, compact),
                color = palette[TagBallGeometry.colorIndex(c.name, palette.size)],
                point = points[i],
            )
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().auroraBackdrop()) {
        AdaptiveBody(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.padding(start = 4.dp).weight(1f)) {
                        Text(
                            "标签球",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 1,
                        )
                        Text(
                            // listOfNotNull + joinToString 拼：字符串拼接里的 if 会吞掉后面几段（踩过的坑）
                            listOfNotNull(
                                candidates?.count { it.kind == WorksKind.Actor }
                                    ?.takeIf { it > 0 }?.let { "演员 $it 位" },
                                candidates?.count { it.kind == WorksKind.Tag }
                                    ?.takeIf { it > 0 }?.let { "标签 $it 条" },
                                // 说清"为什么只有这些" —— 不然用户会奇怪库里明明有 4K/1080P 的标签
                                "已剔除画质 / 编码类标签",
                                "点一个看它的全部作品",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            // 同作品页：不限行会在窄屏逐字换行，把顶栏撑成几十行高
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // 筛选：两族的颜色不只是筛选键，也是**图例**（球上分不出哪个是演员、哪个是标签）
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppChip("全部", selected = only == null, onClick = { only = null })
                    AppChip(
                        "标签",
                        selected = only == WorksKind.Tag,
                        onClick = { only = if (only == WorksKind.Tag) null else WorksKind.Tag },
                        leading = { PaletteDot(TAG_PALETTE.first()) },
                    )
                    AppChip(
                        "演员",
                        selected = only == WorksKind.Actor,
                        onClick = { only = if (only == WorksKind.Actor) null else WorksKind.Actor },
                        leading = { PaletteDot(ACTOR_PALETTE.first()) },
                    )
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        candidates == null -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                        }

                        words.isEmpty() -> Box(
                            Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (candidates.orEmpty().isEmpty()) {
                                    "这个库里还没有能上球的演员 / 标签" +
                                        "（只在一部片里出现过的、以及画质 / 编码类的不算）"
                                } else {
                                    "这一族是空的，换另一个看看"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }

                        else -> TagBallSphere(
                            words = words,
                            onOpen = { onOpenWorks(it.kind, it.name) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Text(
                    if (words.isEmpty()) "拖动旋转 · 松手还会慢慢转" else
                        "球上 ${words.size} 条 · 拖动旋转 · 松手还会慢慢转",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

// ---------------- 球体本体 ----------------

/** 球上能容纳多少条：平板多些才像一团云，手机球小、字多了会糊成一片 */
private const val BALL_LIMIT_WIDE = 110
private const val BALL_LIMIT_COMPACT = 64

/**
 * 球半径 = 容器**半**短边的这个比例，即球直径占短边的 ~78%。
 *
 * ★ 这里传进来的 `cx / cy` 已经是半宽半高，别再乘一次 0.42 —— 那样球只有屏宽的 42%，
 *   竖屏上会缩成中间一小团（实测踩过）。词的半宽最多到 0.42×半宽，加上这个半径正好铺满又不顶边。
 */
private const val RADIUS_RATIO = 0.78f

/** 焦距 = 半径的倍数（见 TagBallGeometry.project） */
private const val FOCAL_RATIO = 2.8f

/** 自转角速度（弧度/秒）：慢到"看得出在动"、又不至于让人追着一个字读 */
private const val AUTO_SPIN = 0.13f

/** 拖动 1 像素转多少弧度 */
private const val DRAG_TO_RAD = 0.0055f

/** 俯仰角上下限（弧度）：不让球翻过来 —— 翻到"这里"的时候整个球面会拧成一团 */
private const val PITCH_LIMIT = 1.05f

/** 松手后惯性衰减的时间常数（秒） */
private const val INERTIA_TAU = 0.45f

/**
 * 标签用冷色、演员用暖色：球上那么多字，光看内容分不出"这是人是物"。
 * 配色挑的都是压在这个近黑底上仍然清楚、又不会亮到刺眼的中高饱和色。
 */
private val TAG_PALETTE = listOf(
    Color(0xFF9B8CFF), Color(0xFF6EA8FE), Color(0xFF4FD1C5),
    Color(0xFF5BC8F5), Color(0xFF7BE495), Color(0xFFA3E635),
)
private val ACTOR_PALETTE = listOf(
    Color(0xFFFFB020), Color(0xFFFF8A5B), Color(0xFFF472B6),
    Color(0xFFFCD34D), Color(0xFFFF6B6B), Color(0xFFFFA45B),
)

/** 球上的一个词条：查询用的原名、显示的文本、字号颜色、以及它挂在球面上的哪一点 */
private data class BallWord(
    val name: String,
    val label: String,
    val kind: WorksKind,
    val fontSizeSp: Float,
    val color: Color,
    val point: TagBallGeometry.Point,
)

@Composable
private fun PaletteDot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

/**
 * 每帧的投影结果：**画和点击共用同一份**。
 *
 * 点击要挑"最靠前的那一个"（球上前后重叠是常态，点下去得选中看得见的那条），
 * 所以排序后的 [order] 同时也是命中测试的扫描顺序（从后往前扫 = 从近到远）。
 */
private class BallFrame(val n: Int) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val scale = FloatArray(n)

    /** 深度打包成可排序的 long：高 32 位是深度键（0 最远、65534 最近）、低 32 位是下标 */
    val order = LongArray(n)

    /** 第一帧画出来之前里头全是 0，别拿它做命中测试 */
    var drawn = false

    /**
     * 命中测试：从最近的往回扫，返回第一个罩住 [local]（相对容器左上角）的词条下标。
     * 文字框就是"绘制时那个矩形"，所以用绘制时存下来的中心与缩放现算。
     */
    fun pick(local: Offset, layouts: List<TextLayoutResult>): Int {
        if (!drawn) return -1
        for (k in order.indices.reversed()) {
            val i = (order[k] and 0xFFFFFFFFL).toInt()
            if (i >= layouts.size) continue
            val halfW = layouts[i].size.width / 2f * scale[i]
            val halfH = layouts[i].size.height / 2f * scale[i]
            if (abs(local.x - x[i]) <= halfW && abs(local.y - y[i]) <= halfH) return i
        }
        return -1
    }
}

/**
 * 球面视图：自转 + 拖动 + 点击。文字**不跟着转**（只按深度缩放淡出），所以背面的字也还是正的。
 *
 * 绘制顺序按深度从远到近：近处的字压住远处的 —— 不做这个排序的话，背面那个"大标签"
 * 会盖在正面的小字上，看着像穿模。
 */
@Composable
private fun TagBallSphere(
    words: List<BallWord>,
    onOpen: (BallWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val yaw = remember { mutableFloatStateOf(0.35f) }
    val pitch = remember { mutableFloatStateOf(-0.26f) }
    // 拖拽惯性（弧度/秒）：松手后按 INERTIA_TAU 衰减回 0，剩下的匀速自转由 AUTO_SPIN 负责
    val spin = remember { mutableFloatStateOf(0f) }
    val dragging = remember { mutableStateOf(false) }
    val frame = remember(words) { BallFrame(words.size) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                if (dt > 0f) {
                    if (dragging.value) {
                        // 拖拽中只走匀速自转：手指已经按 1:1 把角度转过去了，
                        // 这里再把惯性速度叠上去会变成"越拖越快"（松手才是惯性该出场的时候）
                        yaw.value += AUTO_SPIN * dt
                    } else {
                        spin.value *= exp((-dt / INERTIA_TAU).toDouble()).toFloat()
                        yaw.value += (AUTO_SPIN + spin.value) * dt
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier) {
        val maxTextWidth = with(LocalDensity.current) { (maxWidth * 0.42f).toPx().toInt() }
        // 只量一次：TextMeasurer 自己带 LRU，逐帧重量的代价是没有的
        val layouts = remember(words, maxTextWidth) {
            words.map { w ->
                measurer.measure(
                    text = w.label,
                    style = TextStyle(fontSize = w.fontSizeSp.sp, fontWeight = FontWeight.SemiBold),
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = maxTextWidth),
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(words) {
                    // 拖动与点击是**一个**手势判定者，不是两个（detectTapGestures + detectDragGestures
                    // 挂同一个节点上会互相取消：那边抬手就 consume 掉按下，这边就再也等不到触摸阈值。
                    // 播放器手势层踩过的同一个坑，见 player 的注释）。
                    // 判据只有一条：手指动过触摸阈值 = 拖动，没动过就抬手 = 点击。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragged = false
                        var lastPos = down.position
                        var lastAt = down.uptimeMillis
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                if (!dragged) {
                                    val hit = frame.pick(down.position, layouts)
                                    if (hit >= 0) onOpen(words[hit])
                                }
                                break
                            }
                            if (!dragged &&
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                dragged = true
                                dragging.value = true
                                // 从"越过阈值这一刻"重新起算：把阈值内的那几像素也补上会是落手一蹦
                                lastPos = change.position
                                lastAt = change.uptimeMillis
                            }
                            if (dragged) {
                                change.consume()
                                val delta = change.position - lastPos
                                lastPos = change.position
                                val dt = ((change.uptimeMillis - lastAt) / 1000f)
                                    .coerceIn(1f / 240f, 0.1f)
                                lastAt = change.uptimeMillis
                                yaw.value += delta.x * DRAG_TO_RAD
                                pitch.value = (pitch.value + delta.y * DRAG_TO_RAD)
                                    .coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
                                // 瞬时角速度平滑一下再存：不滤波的话最后一帧的抖动会把转速定死
                                val instant = delta.x * DRAG_TO_RAD / dt
                                spin.value = spin.value * 0.65f + instant * 0.35f
                            }
                        }
                        dragging.value = false
                    }
                }
                .drawBehind {
                    val n = words.size
                    if (n == 0) return@drawBehind
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = min(cx, cy) * RADIUS_RATIO
                    val focal = radius * FOCAL_RATIO
                    val yawNow = yaw.value
                    val pitchNow = pitch.value
                    for (i in 0 until n) {
                        val q = TagBallGeometry.project(
                            TagBallGeometry.rotate(words[i].point, yawNow, pitchNow),
                            radius,
                            focal,
                        )
                        frame.x[i] = cx + q.x
                        frame.y[i] = cy + q.y
                        frame.scale[i] = q.scale
                        frame.order[i] = (((q.depth + 1f) * 32767f).toLong() shl 32) or i.toLong()
                    }
                    frame.drawn = true
                    java.util.Arrays.sort(frame.order)
                    for (key in frame.order) {
                        val i = (key and 0xFFFFFFFFL).toInt()
                        val depth = (key ushr 32) / 32767f - 1f
                        val layout = layouts[i]
                        val s = frame.scale[i]
                        val w = layout.size.width.toFloat()
                        val h = layout.size.height.toFloat()
                        // 以文字框中心为锚点摆放：先移到落点、按深度缩放、再把文字自身的左上角让出来
                        withTransform({
                            translate(frame.x[i], frame.y[i])
                            scale(s, s, pivot = Offset.Zero)
                            translate(-w / 2f, -h / 2f)
                        }) {
                            drawText(
                                textLayoutResult = layout,
                                color = words[i].color,
                                alpha = TagBallGeometry.alphaOf(depth),
                            )
                        }
                    }
                },
        )
    }
}
