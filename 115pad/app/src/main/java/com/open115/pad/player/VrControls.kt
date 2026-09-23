package com.open115.pad.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * VR 视角状态。
 *
 * 之所以不直接用 Compose 的 `mutableStateOf`：这些值每帧都变（陀螺仪 200Hz、
 * 拖动每帧都在动），走 Compose 快照系统会把重组压力从"每帧几次"抬到"每秒几百次"，
 * 而界面本身完全不需要重组——变化的只是 GL 的 uniform。
 * 所以这里用普通字段 + 锁，由调用方在改变后主动推给 GL 视图。
 */
class VrViewState {

    private val lock = Any()

    /** 相机→素材（陀螺仪基准）。陀螺仪关闭时恒为单位阵 */
    private var gyroRot = VrMath.IDENTITY.copyOf()

    /** 触摸拖动叠加在陀螺仪之上的偏移（在素材坐标系里左乘） */
    private var panRot = VrMath.IDENTITY.copyOf()

    private var fov = DEFAULT_FOV

    @Volatile var mode: VrMode = VrMode.VR180_SBS
    @Volatile var rightEye: Boolean = false
    @Volatile var gyroEnabled: Boolean = false

    /** 边缘畸变抑制强度（Pannini d）。0 = 直线透视，1 = 标准 Pannini */
    @Volatile var panniniD: Float = DEFAULT_PANNINI

    /** 取当前视角快照给 GL 线程用 */
    fun snapshot(): VrParams = synchronized(lock) {
        var m = VrMath.mul(panRot, gyroRot)
        if (mode.halfSphere) m = VrMath.clampForHalfSphere(m)
        VrParams(mode, m, rightEye, fov, panniniD)
    }

    fun setPannini(d: Float) {
        panniniD = d.coerceIn(0f, MAX_PANNINI)
    }

    /** 陀螺仪姿态更新（VrGyroController 回调，非主线程） */
    fun applyGyro(m: FloatArray) = synchronized(lock) {
        gyroRot = m
    }

    /**
     * 触摸拖动转视角。
     *
     * - 手指右移 → 画面跟着右移 → 视线左转（yaw 减小）
     * - 手指下移 → 画面跟着下移 → 视线抬起（pitch 增大）
     *
     * 采用「yaw 绕素材竖直轴、pitch 绕相机自身右轴」的组合，这样转过 90° 之后
     * 上下拖动仍然是"点头"而不是"绕世界轴打转"，符合直觉。
     * 每像素对应多少度由 FOV 反推，所以放大了之后拖动会更细腻。
     */
    fun pan(dxPx: Float, dyPx: Float, viewW: Float, viewH: Float) {
        val k = fov / minOf(viewW, viewH).coerceAtLeast(1f)
        val dYaw = -dxPx * k
        // 相机系 +Y 向上、+Z 向前，绕 +X 正向转会把前方压向 -Y，
        // 所以"抬头"要取负角度
        val dPitch = -dyPx * k
        synchronized(lock) {
            panRot = VrMath.mul(
                VrMath.rotY(dYaw),
                VrMath.mul(panRot, VrMath.rotX(dPitch)),
            )
        }
    }

    /** 双指捏合改视场角：张开手指 = 拉近 = FOV 变小 */
    fun zoomBy(scale: Float) {
        if (scale <= 0.01f || !scale.isFinite()) return
        synchronized(lock) {
            fov = (fov / scale).coerceIn(MIN_FOV, MAX_FOV)
        }
    }

    fun currentFov(): Float = synchronized(lock) { fov }

    /** 当前视线方向（度），给 HUD 用 */
    fun currentAngles(): Pair<Float, Float> = VrMath.viewAngles(snapshot().camRot)

    /** 复位：回正前方 + 恢复默认视场角。陀螺仪基准也一并重置 */
    fun resetView() {
        synchronized(lock) {
            panRot = VrMath.IDENTITY.copyOf()
            gyroRot = VrMath.IDENTITY.copyOf()
            fov = DEFAULT_FOV
        }
    }

    /** 切换模式时把视角归零，避免带着上一个素材的朝向进来 */
    fun resetForNewMode() {
        synchronized(lock) {
            panRot = VrMath.IDENTITY.copyOf()
            fov = DEFAULT_FOV
        }
    }

    companion object {
        const val DEFAULT_FOV = 90f
        const val MIN_FOV = 25f
        const val MAX_FOV = 150f

        /**
         * 边缘畸变抑制的默认值。1.0 = 标准 Pannini：水平 FOV 110° 时把边缘
         * 拉伸从 3.04× 压到 1.27×，而水平线只弯一点点——对视听习惯最友好。
         */
        const val DEFAULT_PANNINI = 1.0f
        const val MAX_PANNINI = 3.0f
    }
}

/**
 * VR 手势层。
 *
 * 刻意**不用** `detectTapGestures` + `detectTransformGestures` 叠两层：
 * 两套检测器各自等待自己的触发条件，在"先按住微微动一下再捏合"这类
 * 边界操作上会出现互相抢事件（拖动被当成捏合、捏合只生效一半）。
 * 这里用单个 `awaitEachGesture` 自己解析，按手指数分流，行为完全确定。
 *
 * 注意调用方在 VR 模式下会把 `PlayerGestureOverlay` 整个关掉，
 * 所以亮度/音量/横滑快进那套手势不会和这里打架。
 *
 * 但**双击**是例外，VR 模式下也要保留：横拖/捏合都有位移，只有"轻点两下"
 * 这种零位移操作能安全复用，所以双击三区（左退 / 中暂停 / 右进）通过
 * [onDoubleTap] 交给调用方，由调用方走与普通模式同一份 `applyDoubleTapZone`。
 *
 * @param enabled 与普通手势层一致：锁屏（防误触锁）时整体不挂事件，
 *                锁定态下拖动/捏合/点击一概不响应，只有锁按钮本身（更上层）可点。
 * @param onDoubleTap 双击回调，参数是双击点的横向比例 0~1（三区判定交给调用方）
 */
@Composable
internal fun VrGestureLayer(
    modifier: Modifier,
    enabled: Boolean,
    state: VrViewState,
    onViewChanged: () -> Unit,
    onToggleController: () -> Unit,
    onDoubleTap: (Float) -> Unit,
    onHud: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lastTapAt = remember { longArrayOf(0L) }
    var pendingTap = remember { arrayOfNulls<Job>(1) }

    var layer = modifier
    if (enabled) {
        layer = layer.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                var lastPos = down.position
                var acc = Offset.Zero
                var moved = false
                var pinchPrev = 0f
                var centroidPrev = Offset.Zero
                // 上一帧是否处于双指状态：双指 → 单指的过渡帧要做基线重置，
                // 否则 lastPos/pinchPrev 还是双指开始前的旧值，放开手指的一瞬间
                // 会算出一次巨大的 pan/zoom 跳变（用户看到的"画面抖一下"）
                var wasTwoFingers = false

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break

                    if (pressed.size >= 2) {
                        // 双指：捏合改 FOV，同时允许两指整体拖动转视角
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val dist = (a - b).getDistance()
                        if (pinchPrev > 0f && dist > 0f) {
                            state.zoomBy(dist / pinchPrev)
                            // 显示**实际**视场角，而不是 fov 字段本身：Pannini 只保证
                            // 它作用的那个轴角度不变，另一个轴会被压小 —— 横屏 16:9 下
                            // fov=90 实际是 121°×67°，直接显示 90 会虚报 20° 以上。
                            // 比例必须用**视窗**尺寸：竖屏选了 3:4 / 1:1 档位时视窗比屏幕窄，
                            // 拿屏幕比例算出来的读数会和画面不符。
                            val (hFov, vFov) = VrProjection.actualFov(
                                state.currentFov(),
                                size.width.toFloat() / size.height.coerceAtLeast(1),
                                state.panniniD,
                            )
                            onHud("视场角 ${hFov.toInt()}°×${vFov.toInt()}°")
                            onViewChanged()
                        }
                        pinchPrev = dist

                        val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                        if (centroidPrev != Offset.Zero) {
                            val d = centroid - centroidPrev
                            if (abs(d.x) > 0.5f || abs(d.y) > 0.5f) {
                                state.pan(d.x, d.y, size.width.toFloat(), size.height.toFloat())
                                onViewChanged()
                            }
                        }
                        centroidPrev = centroid
                        moved = true
                        wasTwoFingers = true
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else {
                        val ch = pressed[0]
                        // 双指 → 单指的过渡帧：这里的 ch.position 相对上一帧没有基准
                        // （lastPos 还停在双指开始前），直接按 pan 处理会跳一次视角。
                        // 该帧只重置基线、不生效；下一帧起才是正常的单指拖动。
                        if (wasTwoFingers) {
                            wasTwoFingers = false
                            lastPos = ch.position
                            acc = Offset.Zero
                            moved = true // 放开手指不触发单击/双击判定
                            pinchPrev = 0f
                            centroidPrev = Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        val d = ch.position - lastPos
                        acc += d
                        if (!moved && (abs(acc.x) > slop || abs(acc.y) > slop)) moved = true
                        if (moved) {
                            state.pan(d.x, d.y, size.width.toFloat(), size.height.toFloat())
                            val (yaw, pitch) = state.currentAngles()
                            onHud("左右 ${yaw.toInt()}° · 上下 ${pitch.toInt()}°")
                            onViewChanged()
                            ch.consume()
                        }
                        lastPos = ch.position
                        pinchPrev = 0f
                        centroidPrev = Offset.Zero
                    }
                }

                if (moved) {
                    onHud(null)
                } else {
                    // 单击 / 双击：双击要等一个窗口才能定性，所以单击延后触发
                    val now = System.currentTimeMillis()
                    val prev = pendingTap[0]
                    if (now - lastTapAt[0] < DOUBLE_TAP_MS && prev?.isActive == true) {
                        prev.cancel()
                        pendingTap[0] = null
                        lastTapAt[0] = 0L
                        // 用当前这一击的位置判区（与 Compose 的 onDoubleTap 语义一致：
                        // 取的是第二击），两击之间手指可能已经挪了位置
                        onDoubleTap(down.position.x / size.width.toFloat().coerceAtLeast(1f))
                    } else {
                        lastTapAt[0] = now
                        pendingTap[0] = scope.launch {
                            delay(DOUBLE_TAP_MS)
                            onToggleController()
                        }
                    }
                }
            }
        }
    }
    Box(layer)
}

/** VR 手势提示（顶部居中）：拖动显示角度，捏合显示视场角 */
@Composable
internal fun VrHud(text: String?, modifier: Modifier) {
    if (text == null) return
    Box(modifier) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

private const val DOUBLE_TAP_MS = 280L
