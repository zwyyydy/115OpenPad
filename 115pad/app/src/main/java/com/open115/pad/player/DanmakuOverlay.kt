package com.open115.pad.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * 弹幕渲染视图（实验室：弹幕）。
 *
 * 为什么自己画而不是接 danmaku-flame-master：那库要 jitpack（本工程的仓库源全是国内镜像，
 * 为一个依赖引入外网源不值得），而它的 API 远比这里需要的重 —— 播放器要的只是
 * "按播放进度把一串 (时间, 文本, 颜色, 模式) 画出去，支持暂停/倍速/seek"。
 * 自绘就三样：时间轴锚在播放进度上（外部 [syncPosition] 推进来），每帧按 elapsed 算位移，
 * 轨道调度用最经典的"最后一条右边缘完全进场才算空"。
 *
 * 时间轴：不逐帧读播放器（那要跨线程/状态采样），而是**锚点 + 自走时钟** ——
 * [setAnchor]/[seekTo]/[setPlaying]/[setSpeed] 改锚点，帧间 elapsed 按 speed 自行推进；
 * 播放器侧每 250ms 进度轮询里调一次 [syncPosition]，跳变即 seek、平稳则定期校锚
 * （缓冲期间自走时钟会超前，校锚把它拉回来）。校锚只在跳变时可见，正常播放无感。
 *
 * 画在画面 Box 里、字幕层之下；View 默认不可点，触摸事件照常穿到手势层。
 */
internal class DanmakuRenderView(context: Context) : View(context) {

    private data class Entry(val timeMs: Long, val mode: Int, val color: Int, val text: String, val width: Float)

    private class Scheduled(val entry: Entry, val channel: Int)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var source: List<DanmuComment>? = null
    private var items: List<Entry> = emptyList()
    /** items 里第一条还没进场的下标（播放推进时前移；seek/换批时重算） */
    private var nextIdx = 0
    private val active = ArrayList<Scheduled>()
    /** 每条滚动轨道到什么 elapsed 才空闲（ms） */
    private var channels = FloatArray(0)
    private var topFreeAt = 0f
    private var bottomFreeAt = 0f

    private var playing = false
    private var speed = 1f
    private var anchorElapsed = 0f
    private var anchorClock = 0L

    // 观感三参数（设置页实验室）：字号 / 不透明度 / 滚动快慢（>1 更快）
    private var curTextSizePx = 0f
    private var curOpacity = 255
    private var curScrollFactor = 1f

    private var lastPushed = Long.MIN_VALUE
    private var lastAnchorAt = 0L

    private val textSizePx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    private val lineH: Float
        get() = curTextSizePx * 1.35f

    init {
        textPaint.textSize = textSizePx
        curTextSizePx = textSizePx
        curOpacity = 255
    }

    private fun nowElapsed(): Float =
        if (!playing) anchorElapsed else anchorElapsed + (SystemClock.uptimeMillis() - anchorClock) * speed

    /** 换一批弹幕（引用不变则忽略）；null = 还没拉到，清场等。startPos = 从播放的哪里开始滚 */
    fun setComments(list: List<DanmuComment>?, startPosMs: Long) {
        if (list === source) return
        // 同一集的**再过滤**（弹幕面板里改密度/屏蔽词）不重锚时间轴：屏幕上正滚着的那批留在原地，
        // 只换待进场队列 —— 走 seekTo 会把整屏清掉重来，拖滑块时看着就是一跳一跳的。
        // 判据是"自走时钟还在播放位附近"：换集时时钟还停在上一集的秒数（进度轮询 250ms 后才校回来），
        // 差得远，两边自动落到重锚分支，不用宿主额外传"这是换集"。
        val keepTimeline = list != null && source != null &&
            kotlin.math.abs(nowElapsed() - startPosMs) <= SYNC_JUMP_MS
        source = list
        items = if (list == null) emptyList() else {
            list.map { Entry(it.timeMs, it.mode, it.color, it.text, textPaint.measureText(it.text)) }
                .sortedBy { it.timeMs }
        }
        if (keepTimeline) {
            val elapsed = nowElapsed()
            nextIdx = items.indexOfFirst { it.timeMs >= elapsed }.let { if (it < 0) items.size else it }
            invalidate()
        } else {
            seekTo(startPosMs)
        }
    }

    fun setPlaying(v: Boolean) {
        if (playing == v) return
        if (v) {
            anchorClock = SystemClock.uptimeMillis() // anchorElapsed 保持暂停那一刻
        } else {
            anchorElapsed = nowElapsed()
        }
        playing = v
        invalidate()
    }

    fun setSpeed(v: Float) {
        if (speed == v) return
        anchorElapsed = nowElapsed()
        anchorClock = SystemClock.uptimeMillis()
        speed = v
        invalidate()
    }

    fun setAnchor(posMs: Long) {
        anchorElapsed = posMs.toFloat()
        anchorClock = SystemClock.uptimeMillis()
        lastAnchorAt = anchorClock
        lastPushed = posMs
        invalidate()
    }

    /** seek：时间轴硬切 + 轨道/已进场队列全部重算 */
    fun seekTo(posMs: Long) {
        anchorElapsed = posMs.toFloat()
        anchorClock = SystemClock.uptimeMillis()
        lastPushed = posMs
        lastAnchorAt = anchorClock
        nextIdx = items.indexOfFirst { it.timeMs >= posMs }.let { if (it < 0) items.size else it }
        active.clear()
        channels.fill(0f)
        topFreeAt = 0f
        bottomFreeAt = 0f
        invalidate()
    }

    /**
     * 观感参数（设置页实验室）：字号变化要**重新测宽**并重排轨道（已进场队列清掉重来），
     * 透明度/滚动快慢只换画笔参数下一帧就生效。
     */
    fun configure(textSizePx: Float, opacity: Float, scrollFactor: Float) {
        var reschedule = false
        if (curTextSizePx != textSizePx) {
            curTextSizePx = textSizePx
            textPaint.textSize = textSizePx
            items = items.map { it.copy(width = textPaint.measureText(it.text)) }
            reschedule = true
        }
        curOpacity = (opacity.coerceIn(0f, 1f) * 255).toInt()
        textPaint.alpha = curOpacity
        curScrollFactor = scrollFactor.coerceIn(0.25f, 4f)
        if (reschedule) seekTo(nowElapsed().toLong())
        invalidate()
    }

    /**
     * 播放器每 250ms 推一次进度：跳变 > [SYNC_JUMP_MS] 当 seek（进度条/双击快进/自动续播
     * 全都从这过），平稳则每 [ANCHOR_EVERY_MS] 校一次锚（拉回缓冲期间自走时钟的超前量）。
     */
    fun syncPosition(posMs: Long) {
        if (lastPushed == Long.MIN_VALUE) {
            seekTo(posMs)
            return
        }
        if (kotlin.math.abs(posMs - lastPushed) > SYNC_JUMP_MS) seekTo(posMs)
        else if (SystemClock.uptimeMillis() - lastAnchorAt > ANCHOR_EVERY_MS) setAnchor(posMs)
        lastPushed = posMs
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 排障用（弹幕区高度、轨道数、「显示区域」到底生效没）：adb logcat -s DanmuDbg
        android.util.Log.d("DanmuDbg", "size ${w}x$h 行数=${(h / lineH).toInt()} 行高=$lineH")
        // 尺寸变了轨道数就变了：清空调度状态，下一帧按新尺寸重来
        channels = FloatArray(0)
        active.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = nowElapsed()
        val w = width
        val life = LIFE_MS / curScrollFactor

        // ---- 进场调度 ----
        if (channels.isEmpty() && height > 0 && w > 0) {
            channels = FloatArray((height / lineH).toInt().coerceIn(1, MAX_CHANNELS))
        }
        while (nextIdx < items.size && items[nextIdx].timeMs <= elapsed) {
            schedule(items[nextIdx++], elapsed, w, life)
        }

        // ---- 绘制 + 过期回收 ----
        val iter = active.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            val dt = elapsed - s.entry.timeMs
            if (dt > life) {
                iter.remove()
                continue
            }
            if (dt < 0 || channels.isEmpty()) continue
            if (s.channel < 0) {
                // 固定位：-1 = 顶部第一行，-2 = 底部最后一行，水平居中
                val row = if (s.channel == -1) 0 else channels.size - 1
                drawOne(canvas, s.entry, (w - s.entry.width) / 2f, rowBaseline(row))
            } else {
                val pxPerMs = (w + s.entry.width) / life
                drawOne(canvas, s.entry, w - dt * pxPerMs, rowBaseline(s.channel))
            }
        }
        if (playing) postInvalidateOnAnimation()
    }

    private fun schedule(e: Entry, elapsed: Float, w: Int, life: Float) {
        if (channels.isEmpty()) return
        when (e.mode) {
            5 -> if (topFreeAt <= elapsed) {
                topFreeAt = elapsed + life
                active.add(Scheduled(e, -1))
            }
            4 -> if (bottomFreeAt <= elapsed) {
                bottomFreeAt = elapsed + life
                active.add(Scheduled(e, -2))
            }
            else -> {
                // 在**当前空闲**的轨道里随机挑一条，而不是固定挑最上面那条。
                //
                // 为什么必须随机：「空闲」的判据是"上一条的尾巴完全进场"，短弹幕只要 ~0.3 秒
                // 就满足 —— 于是每秒几条的普通片源会把**所有**弹幕都塞进第 0 行
                // （实测同一个视图里 25 条轨道，36 秒里最大轨道号一直是 0），
                // 画面上只有最上面一行在动，「显示区域」调多大都看不出区别。
                // 随机分配让弹幕真正铺满配置的那块区域（密集片源下与"从上往下填"观感一致）。
                val free = ArrayList<Int>(channels.size)
                for (c in channels.indices) if (channels[c] <= elapsed) free.add(c)
                if (free.isEmpty()) return // 全满：丢掉这条。宁可丢弹幕也不叠字
                val c = free[Random.nextInt(free.size)]
                channels[c] = elapsed + e.width / ((w + e.width) / life)
                active.add(Scheduled(e, c))
            }
        }
    }

    /** 行号 → 文字 baseline。行高含 1.35 倍行距，baseline = 行顶 - ascent */
    private fun rowBaseline(row: Int): Float = row * lineH - textPaint.fontMetrics.ascent

    private fun drawOne(canvas: Canvas, e: Entry, x: Float, y: Float) {
        // 先设色（setColor 会把 alpha 重写回 FF），再补上不透明度 —— 顺序反了透明度就不生效
        textPaint.color = e.color or 0xFF000000.toInt()
        textPaint.alpha = curOpacity
        canvas.drawText(e.text, x, y, textPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    private companion object {
        /** 一条弹幕从进场到出屏的时长；倍速只影响时间轴推进，不改变滚动速度 */
        const val LIFE_MS = 8000f

        /**
         * 轨道数的安全上限。**不是密度策略**：轨道数按视图高度现算（高度 ÷ 行高），
         * 这个上限只是防住"极小字号 + 极高屏幕"这种极端组合。
         *
         * 原来是 16 —— 实测在 14sp 字号、1080px 高的弹幕区里只够 25 条轨道，
         * 被砍成 16 等于「显示区域」最多只有 63% 的高度能用，满屏也铺不满。
         */
        const val MAX_CHANNELS = 64

        /** 进度轮询里超过这个跳变量判为 seek */
        const val SYNC_JUMP_MS = 1200L

        /** 校锚间隔 */
        const val ANCHOR_EVERY_MS = 5000L
    }
}

/** 弹幕覆盖层：画面 Box 内、字幕层之下；播放状态/倍速/观感三参数变化实时跟手 */
@Composable
internal fun DanmakuOverlay(
    comments: List<DanmuComment>?,
    startPosMs: Long,
    playing: Boolean,
    speed: Float,
    viewRef: AtomicReference<DanmakuRenderView?>? = null,
    textSizeSp: Float = 16f,
    opacity: Float = 1f,
    scrollFactor: Float = 1f,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            DanmakuRenderView(ctx).also { v ->
                v.setComments(comments, startPosMs)
                v.configure(spToPx(v, textSizeSp), opacity, scrollFactor)
                viewRef?.set(v)
            }
        },
        update = { v ->
            v.setComments(comments, startPosMs)
            v.setPlaying(playing)
            v.setSpeed(speed)
            v.configure(spToPx(v, textSizeSp), opacity, scrollFactor)
        },
        modifier = modifier,
    )
    DisposableEffect(viewRef) {
        onDispose { viewRef?.set(null) }
    }
}

private fun spToPx(v: View, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, v.resources.displayMetrics)

/**
 * 播放器内弹幕面板的可调参数（会话副本）。
 *
 * 面板里改**立刻上屏**、抬手/点选时落盘（同一个 DataStore，设置页那份跟着一起变）。
 * 为什么必须有本地副本：播放器偏好是进播放器时一次性读的（见 PlayerScreen 的 PlayerPrefValues），
 * 只写 DataStore 的话本次播放根本不动 —— 而这些参数全是"看着画面调"的。
 */
internal data class DanmuTuning(
    /** 弹幕铺满屏幕上部多少（百分比：25/50/75/100） */
    val area: Int = 50,
    /** 字号（sp） */
    val textSize: Float = 16f,
    /** 不透明度（0.3~1） */
    val opacity: Float = 1f,
    /** 滚动速度倍率（0.5~2，越大越快） */
    val scroll: Float = 1f,
    /** 密度（25/50/75/100，按时间轴均匀抽稀） */
    val density: Int = 100,
    /** 屏蔽词原始串（逗号/顿号/换行分隔，见 [DanmuClient.parseBlocklist]） */
    val blocklist: String = "",
    /** 缓存时效（天）。只影响**下一次**拉取 —— 不进拉取的 keys，免得拖一下重拉一次 */
    val cacheDays: Int = 7,
)

/** 显示区域档位（百分比） */
private val DanmuAreas = listOf("1/4 屏" to 25, "半屏" to 50, "3/4 屏" to 75, "满屏" to 100)

/** 密度档位（百分比） */
private val DanmuDensities = listOf("25%" to 25, "50%" to 50, "75%" to 75, "100%" to 100)

/**
 * 弹幕面板（实验室：弹幕）：播放器里直接改弹幕配置，不必退出播放去设置页 ——
 * 观感参数是"看着画面调"的，出去改一次再回来还得重新缓冲。
 *
 * 与 [FilterMenu]/[VrModeMenu] 同一套外观与行为：贴底挂在控制排顶部、跟进度条/按钮排
 * 同生共死（一起 4 秒淡出）、自带收起键，打开时两侧圆钮列让位。
 *
 * 面板只画 + 回调：不碰 DataStore，也不做抽稀 —— 应用与落盘的时机（拖动中实时上屏、
 * 抬手才写盘）由宿主决定（同 [FilterMenu] 的 onParam/onParamCommit）。
 * 屏蔽词与源地址要用软键盘，只放「…」入口、真正的输入框还是弹层（见 [PlayerTextEditDialog]）。
 */
@Composable
internal fun DanmuMenu(
    tuning: DanmuTuning,
    /** 状态行：当前匹配到哪部/哪集、多少条（宿主按匹配状态 + 过滤后条数拼好） */
    statusText: String,
    /** 会话开关（"这次看不看"）；总开关在设置页实验室 */
    displayOn: Boolean,
    /** 已设的屏蔽词个数（0 = 没设过）：标在入口上，免得忘了自己屏蔽过什么 */
    blockCount: Int,
    /** 拖动/点选中：立刻上屏 */
    onTuning: (DanmuTuning) -> Unit,
    /** 该落盘了（滑块抬手、点完档位） */
    onTuningCommit: () -> Unit,
    onToggleDisplay: () -> Unit,
    onRematch: () -> Unit,
    onEditBlocklist: () -> Unit,
    onEditApi: () -> Unit,
    /** 收起面板（两侧圆钮列随之回来） */
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    statusText,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "收起弹幕面板",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 低分辨率横屏下这几排都可能超宽，允许横向滚动兜底（同 VR / 滤镜菜单）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                VrChip(if (displayOn) "显示弹幕 ✓" else "显示弹幕（关）", displayOn) { onToggleDisplay() }
                VrChip("重新匹配…", false) { onRematch() }
                VrChip(if (blockCount > 0) "屏蔽词 $blockCount" else "屏蔽词…", false) {
                    onEditBlocklist()
                }
                VrChip("弹幕源…", false) { onEditApi() }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                DanmuGroupLabel("显示区域")
                DanmuAreas.forEach { (label, v) ->
                    VrChip(label, tuning.area == v) {
                        onTuning(tuning.copy(area = v))
                        onTuningCommit()
                    }
                }
                Spacer(Modifier.width(12.dp))
                DanmuGroupLabel("密度")
                DanmuDensities.forEach { (label, v) ->
                    VrChip(label, tuning.density == v) {
                        onTuning(tuning.copy(density = v))
                        onTuningCommit()
                    }
                }
            }
            // 两列两行：跟滤镜面板同规格，平板横屏下不至于挡掉大半画面
            Row(Modifier.fillMaxWidth()) {
                DanmuSliderCell(
                    label = "字号",
                    valueText = "${tuning.textSize.toInt()} sp",
                    value = tuning.textSize,
                    range = 12f..28f,
                    steps = 7,
                    onChange = { v -> onTuning(tuning.copy(textSize = v)) },
                    onCommit = onTuningCommit,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                DanmuSliderCell(
                    label = "不透明度",
                    valueText = "${(tuning.opacity * 100).toInt()}%",
                    value = tuning.opacity,
                    range = 0.3f..1f,
                    steps = 6,
                    onChange = { v -> onTuning(tuning.copy(opacity = v)) },
                    onCommit = onTuningCommit,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                DanmuSliderCell(
                    label = "速度",
                    valueText = "%.2f×".format(tuning.scroll),
                    value = tuning.scroll,
                    range = 0.5f..2f,
                    steps = 5,
                    onChange = { v -> onTuning(tuning.copy(scroll = v)) },
                    onCommit = onTuningCommit,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                DanmuSliderCell(
                    label = "缓存时效",
                    valueText = "${tuning.cacheDays} 天",
                    value = tuning.cacheDays.toFloat(),
                    range = 1f..30f,
                    steps = 29,
                    onChange = { v -> onTuning(tuning.copy(cacheDays = v.toInt().coerceIn(1, 30))) },
                    onCommit = onTuningCommit,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** 面板里的分组小标题（"显示区域" / "密度"） */
@Composable
private fun DanmuGroupLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier.padding(start = 6.dp, end = 4.dp),
    )
}

/** 面板里的一格滑块：标题 + 数值 + 滑杆（样式与滤镜面板的滑块格一致） */
@Composable
private fun DanmuSliderCell(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    /** 拖动中：立刻上屏（不落盘） */
    onChange: (Float) -> Unit,
    /** 抬手：落盘 */
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                valueText,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 播放器面板里的文本编辑弹层（弹幕屏蔽词 / 弹幕源地址 / 滤镜模板命名）。保存即应用，落盘归宿主。
 *
 * 为什么不把输入框塞进面板：播放器是横屏、控制排 4 秒无操作就整体淡出，
 * 输入框在面板里会被"自动淡出 + 横向滚动 + 软键盘"三头夹击；弹层不受淡出影响。
 */
@Composable
internal fun PlayerTextEditDialog(
    title: String,
    label: String,
    hint: String,
    initial: String,
    /** 单行（地址）/ 多行（屏蔽词） */
    singleLine: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 手动匹配弹幕的对话框：**关键词可编辑**（自动清洗出的剧名只是初值 —— 文件名不标准时
 * 用户自己改成正经剧名再搜），搜出候选 → 点开一部 → 选集 → 回调绑定。
 *
 * 两段式导航：候选列表 ↔ 某一部下的分集（带返回）。搜索走 [onSearch]（宿主接
 * DanmuClient.search，io 与异常都归它），选中经 [onPick] 回宿主落缓存。
 */
@Composable
internal fun DanmuMatchDialog(
    initialKeyword: String,
    onSearch: suspend (String) -> List<DanmuAnime>,
    onPick: (DanmuAnime, DanmuEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf(initialKeyword) }
    var searching by remember { mutableStateOf(false) }
    var animes by remember { mutableStateOf<List<DanmuAnime>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<DanmuAnime?>(null) }
    val scope = rememberCoroutineScope()

    fun doSearch() {
        if (keyword.isBlank() || searching) return
        scope.launch {
            searching = true
            opened = null
            animes = onSearch(keyword)
            searched = true
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匹配弹幕") },
        text = {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        singleLine = true,
                        label = { Text("剧名 / 关键词（可编辑）") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { doSearch() }) { Text("搜索") }
                }
                Spacer(Modifier.height(6.dp))
                val current = opened
                when {
                    searching -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("搜索中…", style = MaterialTheme.typography.bodySmall)
                    }
                    current != null -> Column {
                        TextButton(onClick = { opened = null }) { Text("← 返回候选列表") }
                        Text(
                            current.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            itemsIndexed(current.episodes) { _, ep ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(current, ep) }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                ) {
                                    Text(ep.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                    animes.isNotEmpty() -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(animes) { _, a ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { opened = a }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            ) {
                                Text(a.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(
                                    "${a.episodes.size} 集",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    searched -> Text(
                        "没搜到。换个关键词试试（文件名里的画质/字幕组等杂词已经去掉，但仍可能不准）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        "自动匹配用的关键词已填在上面，不对就改一下再搜",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
