package com.open115.pad.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * VR 视窗渲染视图：把等距柱状的 VR 素材实时反投影成普通直线透视画面。
 *
 * 为什么用 GL 而不是 CPU：反投影本质是"每个输出像素算一次映射再采样"。
 * CPU 逐帧做的话，视角一变就要重算整屏（1080p ≈ 200 万像素）的映射表，
 * 4K 素材根本不可能实时；而 GPU 做同样的事几乎是白送的——解码器已经把帧
 * 放进纹理了，着色器只是换个采样坐标。1080p 视口跑满 60fps 毫无压力。
 *
 * ⚠️ 这个视图继承自 SurfaceView：内容由 SurfaceFlinger 合成，
 * **不参与 View 变换**，所以 `graphicsLayer` 的旋转对它不生效。
 * 播放器原本为此特意用了 TextureView（为了 90/270 旋转），
 * 但 GL 渲染必须是 SurfaceView/GLSurfaceView，二者不可兼得。
 * 解法：VR 模式下把旋转挪进着色器（`VrParams.camRot` 里带上 roll），
 * 反正本来就在做重投影，多一个旋转是零成本。
 */
class VrViewportView(context: Context) : GLSurfaceView(context) {

    private val renderer: VrRenderer

    /** Surface 就绪/重建时回调，交给 ExoPlayer 当视频输出面 */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        // Renderer 是独立类（不是内部类），拿不到 GLSurfaceView.requestRender，
        // 所以用回调把"请求重绘"传进去
        renderer = VrRenderer(requestRender = { requestRender() }) { surface ->
            post { onSurfaceReady?.invoke(surface) }
        }
        setRenderer(renderer)
        // 只在有新视频帧或视角变化时重绘，静止时不空转省电
        renderMode = RENDERMODE_WHEN_DIRTY
        setPreserveEGLContextOnPause(true)
    }

    /** 视角变化时调用：只是换 uniform，不重建任何东西 */
    fun updateParams(p: VrParams) {
        renderer.params = p
        requestRender()
    }

    /** 视口尺寸（GL 线程用），外部布局变化后需要重新计算投影 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestRender()
    }

    private class VrRenderer(
        private val requestRender: () -> Unit,
        private val onSurface: (Surface) -> Unit,
    ) : Renderer {

        @Volatile
        var params: VrParams? = null

        private val frameLock = Any()
        private var framePending = false
        private var hasFrame = false

        private var program = 0
        private var texId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var quadBuf: java.nio.FloatBuffer? = null
        private var viewW = 1
        private var viewH = 1

        private val texMatrix = FloatArray(16)
        private val frameHandler = Handler(Looper.getMainLooper())

        private var uTex = 0
        private var uTexMatrix = 0
        private var uCamRot = 0
        private var uViewSize = 0
        private var uFov = 0
        private var uHalfSphere = 0
        private var uPanniniD = 0
        private var uEyeOrigin = 0
        private var uEyeSpan = 0
        private var aPos = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            releaseSurface()

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )

            val st = SurfaceTexture(texId)
            // 在 GL 线程创建、没有 Looper，必须显式指定 Handler，
            // 否则 onFrameAvailable 无处投递（不报错，画面只是永远不刷新）
            st.setOnFrameAvailableListener({
                synchronized(frameLock) { framePending = true }
                requestRender()
            }, frameHandler)
            surfaceTexture = st
            val s = Surface(st)
            surface = s
            hasFrame = false
            onSurface(s)

            program = buildProgram(VERTEX_SRC, FRAGMENT_SRC)
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uCamRot = GLES20.glGetUniformLocation(program, "uCamRot")
            uViewSize = GLES20.glGetUniformLocation(program, "uViewSize")
            uFov = GLES20.glGetUniformLocation(program, "uFovShortRad")
            uHalfSphere = GLES20.glGetUniformLocation(program, "uHalfSphere")
            uPanniniD = GLES20.glGetUniformLocation(program, "uPanniniD")
            uEyeOrigin = GLES20.glGetUniformLocation(program, "uEyeOrigin")
            uEyeSpan = GLES20.glGetUniformLocation(program, "uEyeSpan")
            aPos = GLES20.glGetAttribLocation(program, "aPos")

            val bb = ByteBuffer.allocateDirect(QUAD.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            bb.put(QUAD).position(0)
            quadBuf = bb

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, viewW, viewH)
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return

            // 有新帧才更新纹理；拖动视角时没有新帧，直接拿上一次的纹理重绘即可
            synchronized(frameLock) {
                if (framePending) {
                    try {
                        st.updateTexImage()
                        st.getTransformMatrix(texMatrix)
                        hasFrame = true
                    } catch (_: Throwable) {
                        // 解码面刚建立/刚释放时的竞态，忽略即可
                    }
                    framePending = false
                }
            }

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val p = params ?: return
            if (!hasFrame) return

            val sbs = p.mode.layout == VrLayout.SBS
            val eyeU0 = if (sbs) (if (p.rightEye) 0.5f else 0f) else 0f
            // 上下格式里上半幅是左眼（与左右格式左半幅是左眼同理）
            val eyeV0 = if (!sbs) (if (p.rightEye) 0f else 0.5f) else 0f

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTex, 0)

            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            // camRot 在 Kotlin 侧是行主序，上传时转置成 GL 期望的列主序
            GLES20.glUniformMatrix3fv(uCamRot, 1, true, p.camRot, 0)
            GLES20.glUniform2f(uViewSize, viewW.toFloat(), viewH.toFloat())
            GLES20.glUniform1f(uFov, Math.toRadians(p.fovDeg.toDouble()).toFloat())
            GLES20.glUniform1f(uHalfSphere, if (p.mode.halfSphere) 1f else 0f)
            GLES20.glUniform1f(uPanniniD, p.panniniD)
            GLES20.glUniform2f(uEyeOrigin, eyeU0, eyeV0)
            GLES20.glUniform2f(uEyeSpan, p.mode.eyeSpanU, p.mode.eyeSpanV)

            val q = quadBuf
            if (q != null && aPos >= 0) {
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, q)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPos)
            }
        }

        fun releaseSurface() {
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
            if (texId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                texId = 0
            }
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, v)
            GLES20.glAttachShader(prog, f)
            GLES20.glLinkProgram(prog)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                throw RuntimeException("着色器链接失败: " + GLES20.glGetProgramInfoLog(prog))
            }
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            return prog
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                throw RuntimeException("着色器编译失败: " + GLES20.glGetShaderInfoLog(s))
            }
            return s
        }

        /** 全屏四边形（triangle strip），位置直接就是裁剪空间坐标 */
        private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private val VERTEX_SRC = """
            attribute vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        private val FRAGMENT_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;

            uniform samplerExternalOES uTex;
            uniform mat4 uTexMatrix;
            uniform mat3 uCamRot;
            uniform vec2 uViewSize;
            uniform float uFovShortRad;
            uniform float uHalfSphere;
            uniform float uPanniniD;
            uniform vec2 uEyeOrigin;
            uniform vec2 uEyeSpan;

            const float PI = 3.14159265358979;
            const float HALF_PI = 1.57079632679490;

            void main() {
                // gl_FragCoord 原点在左下角，正好就是"y 向上"的相机约定，
                // 不需要再翻转（CPU 参考实现里必须显式取负，这里天然对齐）
                vec2 p = (gl_FragCoord.xy / uViewSize) * 2.0 - 1.0;

                // 短边视场角固定，长边按 tan 关系推 —— 竖屏/横屏观感一致。
                // ⚠️ 必须两个方向都算出来再用：只按宽度算的话竖屏会被纵向拉爆
                float tanShort = tan(uFovShortRad * 0.5);
                float aspect = uViewSize.x / uViewSize.y;
                float tanH0 = (aspect >= 1.0) ? tanShort * aspect : tanShort;
                float tanV0 = (aspect >= 1.0) ? tanShort : tanShort / aspect;

                // ---- 屏幕平面 → 相机射线 ----
                // 默认是直线透视：边缘按 sec²θ 横向摊开（水平 FOV 110° 时边缘达 3.04×），
                // 这就是"边缘畸变明显"的来源。uPanniniD 把水平映射换成 Pannini 那族
                // "压角"映射，把边缘拉伸压下来：
                //   d=0 → 退化成直线透视（与改动前逐像素一致）
                //   d=1 → 标准 Pannini，边缘拉伸大幅下降，水平线只轻微弯
                //   d 更大 → 趋近柱面，边缘几乎不拉伸但水平线明显弯成弧
                //
                // ⚠️ 平面半宽要**按 d 重算**，让"取景范围"保持不变 —— 否则同样的平面
                //    宽度在 d 变大后对应更大的角度，画面左右边缘会直接越过半球边界出黑边
                //    （d=2 时边缘 yaw 能到 98°）。代价是中心会有轻微放大（压角本来就
                //    等价于把中心放大、边缘压回来），这是换投影的固有取舍，不是 bug。
                float D = uPanniniD;
                float halfH = atan(tanH0);
                float planeH = (D + 1.0) * sin(halfH) / (D + cos(halfH));
                float planeV = planeH * tanV0 / tanH0;
                vec2 q = vec2(p.x * planeH, p.y * planeV);

                // 反解 x = (D+1)·sinλ/(D+cosλ)：令 u = tan(λ/2)，得一元二次
                //   x(D-1)·u² - 2(D+1)·u + x(D+1) = 0
                // 取 2C/(B+√Δ) 这个根：避免 D→1 时 (B-√Δ) 的相减抵消丢精度，
                // 且天然处理了 D=1 的二次项退化和 x=0，无需任何分支。
                float A = q.x * (D - 1.0);
                float B = 2.0 * (D + 1.0);
                float C = q.x * (D + 1.0);
                float u = 2.0 * C / (B + sqrt(max(B * B - 4.0 * A * C, 0.0)));
                float lam = 2.0 * atan(u);
                // tanφ = y·(D+cosλ)/(D+1)，用 u 表示避免再算一次 cos
                float tp = q.y * ((D + 1.0) + (D - 1.0) * u * u)
                    / ((D + 1.0) * (1.0 + u * u));
                float cp = inversesqrt(1.0 + tp * tp);
                vec3 d = vec3(sin(lam) * cp, tp * cp, cos(lam) * cp);
                vec3 di = uCamRot * d;

                float yaw = atan(di.x, di.z);
                float pitch = asin(clamp(di.y, -1.0, 1.0));

                // 半球素材（VR180）：视线越过 ±90° 就出黑边。
                // 不挡的话会采样到图像另一侧的像素，画面像从边缘"穿"过去
                if (uHalfSphere > 0.5 && abs(yaw) > HALF_PI) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                float yawRange = (uHalfSphere > 0.5) ? PI : (2.0 * PI);

                vec2 uv;
                uv.x = (yaw / yawRange + 0.5) * uEyeSpan.x + uEyeOrigin.x;
                uv.y = (pitch / PI + 0.5) * uEyeSpan.y + uEyeOrigin.y;

                // SurfaceTexture 的变换矩阵作用在"显示空间"的纹理坐标上，
                // 把我们算出来的坐标转成实际纹理的采样坐标。
                //
                // ⚠️ 必须除以 t.w，**不能**除以 t.z！uTexMatrix 是 4x4 仿射矩阵，
                //    作用在 (u,v,0,1) 上时第 z 行是 (0,0,1,0)，算出来 t.z 恒等于 0，
                //    除以它会得到 NaN，纹理采样退化成一块定值（整屏变成一片均匀灰），
                //    而且不报任何错。齐次分量是 w，那个才恒等于 1。
                vec4 t = uTexMatrix * vec4(uv, 0.0, 1.0);
                gl_FragColor = texture2D(uTex, t.xy / t.w);
            }
        """.trimIndent()
    }
}
