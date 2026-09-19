package com.open115.pad.player

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 立体排布方式 */
enum class VrLayout { SBS, TB }

/**
 * 持久化里表示"用户明确关闭了 VR"的哨兵值。
 * 空串表示"从没手动设置过"——两者必须区分开，否则用户关掉之后
 * 每次开片都会被自动识别重新打开。
 */
const val VR_MODE_OFF = "off"

/**
 * VR 模式。
 *
 * - `vr180*`：半球素材（水平 180° × 垂直 180°）。yaw 只能落在 ±90° 内，
 *   越界必须出黑边——**不能环绕**，否则会采样到图像另一侧的像素，
 *   看起来就是画面从右边"穿"到左边。
 * - `vr360*`：全球素材（水平 360° × 垂直 180°）。yaw 允许环绕。
 *
 * `eyeSpan*` 是单眼图像占整帧的比例：左右格式横向各半，上下格式纵向各半。
 */
enum class VrMode(
    val label: String,
    val layout: VrLayout,
    val halfSphere: Boolean,
) {
    VR180_SBS("VR180 左右", VrLayout.SBS, true),
    VR180_TB("VR180 上下", VrLayout.TB, true),
    VR360_SBS("360 左右", VrLayout.SBS, false),
    VR360_TB("360 上下", VrLayout.TB, false),
    ;

    val eyeSpanU: Float get() = if (layout == VrLayout.SBS) 0.5f else 1f
    val eyeSpanV: Float get() = if (layout == VrLayout.TB) 0.5f else 1f
}

/**
 * 按分辨率猜 VR 模式，猜不出来返回 null（= 普通视频）。
 *
 * ⚠️ **这个猜测天然有歧义，必须配手动开关兜底**：
 * `3840x1920`（宽高比正好 2）既能解释成「VR180 左右」（每眼 1920x1920 正方形），
 * 也能解释成「360 单目」（整幅 2:1 等距柱状）。光看分辨率**区分不了**，
 * 只有在 MP4 里带 `st3d`/`sv3d` 球面元数据时才能确定，而大量素材导出时根本不写。
 *
 * 这里默认按 VR180 左右处理（VR180 素材远比 360 单目常见），用户不满意可手动切。
 */
object VrDetector {
    fun guess(width: Int, height: Int): VrMode? {
        if (width <= 0 || height <= 0) return null
        return when {
            height == width * 2 -> VrMode.VR180_TB   // 每眼正方形
            height == width * 4 -> VrMode.VR360_TB   // 每眼 2:1
            width == height * 2 -> VrMode.VR180_SBS  // 每眼正方形（默认解释）
            width == height * 4 -> VrMode.VR360_SBS  // 每眼 2:1
            else -> null
        }
    }
}

/**
 * 送进 GL 线程的参数快照。
 *
 * 整体替换、不可变：UI 线程写、GL 线程读，逐字段写会读到"半新半旧"的组合
 * （比如新的 fov 配旧的 yaw），表现为偶发跳帧。整体引用替换没有这个问题。
 */
data class VrParams(
    val mode: VrMode,
    /** 相机→素材坐标系的旋转矩阵，行主序 9 元素；着色器里用它把射线转到球面上 */
    val camRot: FloatArray,
    val rightEye: Boolean,
    /** 短边视场角（度）。竖屏时按宽度算，横屏时按高度算 */
    val fovDeg: Float,
    /**
     * 边缘畸变抑制强度（Pannini 投影的 d 参数）。
     * - `0` = 直线透视：直线保持挺直，但边缘按 sec²θ 横向摊开（110° 时边缘达 3.04×）
     * - `1` = 标准 Pannini：边缘拉伸压到 1.27×，代价是水平线极轻微弯曲
     * - 更大 = 更接近柱面：完全不拉伸，但水平线明显弯成弧
     *
     * 纵向刻意不跟着 d 变（平面 y 尺度固定），所以拖这个值只会改变**边缘畸变**，
     * 画面中心的大小和视野始终不动 —— 否则滑杆会表现得像"缩放"，很难判断该停在哪。
     */
    val panniniD: Float,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 3x3 旋转矩阵工具（行主序 float[9]，与 `VrParams.camRot` 一致）。
 *
 * 约定：矩阵 `M` 表示「相机坐标系 → 素材坐标系」，即 `d_material = M · d_camera`。
 * 相机坐标系里 `+Z` 是视线方向，`+Y` 向上；素材坐标系里 yaw=atan2(x,z)、pitch=asin(y)。
 * 视角改变等价于**左乘**（在素材坐标系里转）。
 */
object VrMath {

    val IDENTITY = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /** 绕 Y 轴（偏航）：正角度把视线转向 +X */
    fun rotY(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return floatArrayOf(
            c, 0f, s,
            0f, 1f, 0f,
            -s, 0f, c,
        )
    }

    /** 绕 X 轴（俯仰）：正角度把视线抬高（+Y） */
    fun rotX(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return floatArrayOf(
            1f, 0f, 0f,
            0f, c, -s,
            0f, s, c,
        )
    }

    /** 绕 Z 轴（翻滚）：设备倾斜时让地平线跟着倾斜 */
    fun rotZ(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return floatArrayOf(
            c, -s, 0f,
            s, c, 0f,
            0f, 0f, 1f,
        )
    }

    /** a · b（都是行主序 3x3） */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (i in 0 until 3) {
            val i3 = i * 3
            for (j in 0 until 3) {
                o[i3 + j] = a[i3] * b[j] + a[i3 + 1] * b[3 + j] + a[i3 + 2] * b[6 + j]
            }
        }
        return o
    }

    /** 转置。正交矩阵的转置就是逆，用来把「设备→世界」翻成「世界→设备」 */
    fun transpose(m: FloatArray): FloatArray = floatArrayOf(
        m[0], m[3], m[6],
        m[1], m[4], m[7],
        m[2], m[5], m[8],
    )

    /** 从矩阵里取出视线方向（相机 +Z 在素材坐标系里的朝向），返回 (yaw, pitch) 度 */
    fun viewAngles(camRot: FloatArray): Pair<Float, Float> {
        // 相机 +Z 对应矩阵第三列
        val x = camRot[2]
        val y = camRot[5]
        val z = camRot[8]
        val yaw = Math.toDegrees(atan2(x.toDouble(), z.toDouble())).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(y.coerceIn(-1f, 1f).toDouble())).toFloat()
        return yaw to pitch
    }

    /**
     * 半球素材下把视线夹回 ±90°。
     * 不做这个夹取的话，视线越过半球边缘会采到图像另一侧的像素——
     * 表现是画面从右边"穿"到左边，非常突兀。夹到边缘至少是稳定的。
     */
    fun clampForHalfSphere(camRot: FloatArray): FloatArray {
        val (yaw, pitch) = viewAngles(camRot)
        val cy = yaw.coerceIn(-90f, 90f)
        val cp = pitch.coerceIn(-90f, 90f)
        if (abs(cy - yaw) < 0.01f && abs(cp - pitch) < 0.01f) return camRot
        return mul(mul(rotY(cy - yaw), rotX(cp - pitch)), camRot)
    }
}
