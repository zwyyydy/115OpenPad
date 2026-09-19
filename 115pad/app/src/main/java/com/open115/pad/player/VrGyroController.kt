package com.open115.pad.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * 陀螺仪视角控制：把手机姿态映射成「相机→素材坐标系」的旋转矩阵。
 *
 * 用 `TYPE_GAME_ROTATION_VECTOR`（不带磁力计）而不是 `ROTATION_VECTOR`：
 * 前者不吃地磁、不受周围铁磁物体和手机壳干扰，低头/靠近金属时不会突然跳变；
 * 代价是没有绝对方位参考，yaw 会缓慢漂移——所以必须有「复位视角」。
 * 这个传感器不需要任何权限。
 *
 * ## 坐标系推导（含 2026-09-19 的方向修正）
 * 设 `R` 是把「设备坐标 → 世界坐标」的旋转矩阵（传感器直接给的就是它）。
 * 设备 +Z 指向屏幕外（朝用户），所以**视线是设备的 -Z**。
 *
 * 相机系（= 素材系，右手系）：`+Z` 视线、`+Y` 上、`+X` 右。
 * 设备系在"竖直握持、屏幕朝用户、面朝南"时：
 * ```
 * device X（屏幕右）→ 世界 东(+X)
 * device Y（屏幕上）→ 世界 天(+Z)
 * device Z（屏幕外）→ 世界 南(-Y，指向用户)   ← 视线是 -device Z = 北
 * ```
 *
 * 令 `F` = 「相机系 → 设备系」的固定换基矩阵，`R0` = 复位时的姿态，
 * 则 `uCamRot = refT · R · F`，其中 `refT` 取 `Fᵀ·R0ᵀ = F·R0ᵀ`
 * （F 是对角 ±1，故 Fᵀ = F）。这样复位瞬间
 * `uCamRot_0 = F·R0ᵀ · R0 · F = F·F = I`，视线正好落在素材中心。
 *
 * ### 🚨 F 取 `diag(-1, -1, +1)` 而不是 `diag(-1, 1, -1)`
 * 这是把 8 个对角 ±1 矩阵全部穷举后**唯一**同时满足下列三项的解
 * （`scripts/gyro_solve.py` 可复现）：
 *   1. `det(F) = +1` —— 必须是合法旋转。det = -1 会把画面左右镜像，
 *      看起来"能用"但字是反的，且滚转方向也是错的。
 *   2. 左转头 → 素材系 yaw 为负 → 视野左移（内容右移），与拖拽手感一致。
 *   3. 抬头 → 素材系 pitch 为负 → 视野上移。
 *
 * 旧实现用 `diag(-1, 1, -1)` 且 `refT = R0ᵀ`：它的**转向其实是对的**，
 * 但复位时 `uCamRot_0 = F ≠ I`，整体差 180°。对 VR180 半球素材
 * （`abs(yaw) > 90°` 在着色器里被判为黑边）意味着**一进 VR 视角就贴在半球背面**，
 * 左右一转就穿出边界 —— 体感就是"陀螺仪方向整个反了"。
 * 把 `R0ᵀ` 换成 `F·R0ᵀ` 即可让复位回到中心，同时保住方向。
 */
class VrGyroController(private val context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 设备上没有可用的旋转向量传感器（很多模拟器就是这样） */
    val available: Boolean get() = sensor != null

    private val raw = FloatArray(9)
    private val remapped = FloatArray(9)
    private val rot = FloatArray(9)
    private val refT = FloatArray(9).also { System.arraycopy(VrMath.IDENTITY, 0, it, 0, 9) }
    private var haveRef = false

    /** 屏幕方向，由宿主 Activity 同步进来 */
    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    /** 姿态更新回调：参数是相机→素材坐标系的旋转矩阵（行主序 9 元素） */
    var onRotate: ((FloatArray) -> Unit)? = null

    fun start() {
        val s = sensor ?: return
        haveRef = false
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** 把当前姿态设为新的基准：调用后画面回到素材正中央 */
    fun recenter() {
        haveRef = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) {
            return
        }
        SensorManager.getRotationMatrixFromVector(raw, event.values)

        // 按屏幕方向重映射。不重映射的话手机横过来视角会跟着歪 90°——
        // 因为传感器的设备坐标系是固定的，而界面会跟着系统方向转
        val (ax, ay) = axisPair(displayRotation)
        if (!SensorManager.remapCoordinateSystem(raw, ax, ay, remapped)) {
            System.arraycopy(raw, 0, remapped, 0, 9)
        }

        if (!haveRef) {
            System.arraycopy(remapped, 0, rot, 0, 9)
            // refT = F · R0ᵀ。乘 F 是为了让复位时 uCamRot = F·F = I（视线正对素材中心），
            // 少了这一步整体就差 180°，VR180 半球会直接贴在背面出黑边。
            val t = VrMath.mul(DEVICE_TO_CAM, VrMath.transpose(rot))
            System.arraycopy(t, 0, refT, 0, 9)
            haveRef = true
            onRotate?.invoke(VrMath.IDENTITY)
            return
        }

        // uCamRot = refT · R · F
        onRotate?.invoke(VrMath.mul(VrMath.mul(refT, remapped), DEVICE_TO_CAM))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun axisPair(rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    private companion object {
        /**
         * 相机系 → 设备系的固定换基。
         *
         * 设备 +Z 朝屏幕外、视线是设备 -Z；相机 +Z 就是视线 ⇒ Z 必须翻。
         * 翻了 Z 之后 det 变成 -1（成了镜像），所以还要再翻一个轴把它掰回
         * 纯旋转——遍历 8 个对角 ±1 矩阵，**唯一**同时满足
         * 「det=+1」+「左转 yaw 为负」+「抬头 pitch 为负」的就是翻 X 和 Y。
         */
        val DEVICE_TO_CAM = floatArrayOf(
            -1f, 0f, 0f,
            0f, -1f, 0f,
            0f, 0f, 1f,
        )
    }
}
