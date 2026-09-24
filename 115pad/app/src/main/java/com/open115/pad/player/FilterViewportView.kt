package com.open115.pad.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 着色器/GL 出错时的日志标签：adb logcat -s PlayerFilter */
private const val TAG = "PlayerFilter"

/**
 * 滤镜视窗渲染视图：视频帧不直接上屏，而是先过一遍滤镜着色器。
 *
 *   ExoPlayer → Surface(SurfaceTexture) → GL 外部纹理 → 滤镜 shader → 屏幕
 *
 * 为什么自己写 GL 视图而不是用 media3 的 `setVideoEffects`：后者会把解码输出接进
 * 它自己的合成管线，而那条管线**只认片源声明的色彩信息**（PQ/BT2020）——
 * 本播放器为了让 HDR 片源在 SDR 屏上不发灰，已经让解码器把 HDR 转成 SDR 输出
 * （见 [HdrToneMapping]），声明与实况不符会让它再做一次错误的色调映射。
 * 自己画就没有这层错位：解码器吐什么，滤镜就吃什么。
 *
 * 与 [VrViewportView] 同一套路数（那条路径已实测可用），差别只在着色器：
 *  - VR 做的是等距柱状反投影，这里做的是调色；
 *  - 画面旋转在这里**烘进着色器**：GLSurfaceView 是 SurfaceView 子类，内容由
 *    SurfaceFlinger 合成，外层 graphicsLayer 的旋转对它不生效（VR 也是这么处理的）。
 *    所以宿主给的视图尺寸已经是"旋转后"的尺寸，着色器把画面转正填满即可。
 */
internal class FilterViewportView(context: Context) : GLSurfaceView(context) {

    private val renderer: FilterRenderer

    /** Surface 就绪/重建时回调，交给 ExoPlayer 当视频输出面 */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    /**
     * 着色器建不起来（驱动异常、GL 上下文丢失后重建失败等）时回调一次。
     *
     * 有这条回调，宿主才能把渲染路径退回 TextureView —— 否则用户看到的是
     * "开了滤镜画面全黑"，还找不到任何提示。
     */
    var onGlFailed: ((Throwable) -> Unit)? = null

    /**
     * 最近一次建好的输出面（GL 线程建好后 post 回主线程写入，主线程读）。
     *
     * 播放器实例被重建时（切换软/硬解）需要重新挂面：GL 视图本身不会重建，
     * `onSurfaceReady` 也就不会再触发，没有这个引用就只能拿到一块黑屏。
     */
    @Volatile
    var currentSurface: Surface? = null
        private set

    init {
        setEGLContextClientVersion(2)
        renderer = FilterRenderer(
            requestRender = { requestRender() },
            onSurface = { surface ->
                post {
                    currentSurface = surface
                    onSurfaceReady?.invoke(surface)
                }
            },
            onFailed = { e ->
                post {
                    Log.w(TAG, "滤镜着色器不可用：${e.message}")
                    onGlFailed?.invoke(e)
                }
            },
        )
        setRenderer(renderer)
        // 只在有新视频帧或参数变化时重绘，静止时不空转省电
        renderMode = RENDERMODE_WHEN_DIRTY
        setPreserveEGLContextOnPause(true)
    }

    /** 参数变化：只换 uniform，不重建任何东西（任意线程可调） */
    fun updateParams(p: FilterParams) {
        renderer.params = p
        requestRender()
    }

    /**
     * 画面几何：帧尺寸（**未旋转**的宽高，未知给 0）与旋转角（0/90/180/270，顺时针）。
     * 宿主给的视图尺寸应当已经是旋转后的尺寸。
     */
    fun updateGeometry(frameW: Int, frameH: Int, rotationDeg: Int) {
        renderer.frameW = frameW
        renderer.frameH = frameH
        renderer.rotation = rotationDeg
        requestRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestRender()
    }

    private class FilterRenderer(
        private val requestRender: () -> Unit,
        private val onSurface: (Surface) -> Unit,
        private val onFailed: (Throwable) -> Unit,
    ) : Renderer {

        @Volatile
        var params: FilterParams = FilterParams()

        @Volatile
        var frameW: Int = 0

        @Volatile
        var frameH: Int = 0

        @Volatile
        var rotation: Int = 0

        private val frameLock = Any()
        private var framePending = false
        private var hasFrame = false
        private var failed = false

        private var program = 0
        private var texId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var quadBuf: java.nio.FloatBuffer? = null

        private val texMatrix = FloatArray(16)
        private val frameHandler = Handler(Looper.getMainLooper())

        private var aPos = 0
        private var uTexMatrix = 0
        private var uTexelSize = 0
        private var uRotation = 0
        private var uBrightness = 0
        private var uContrast = 0
        private var uSaturation = 0
        private var uTemperature = 0
        private var uTint = 0
        private var uSmooth = 0
        private var uVignette = 0
        private var uShadowTint = 0
        private var uHighlightTint = 0
        private var uGamma = 0
        private var uFade = 0
        private var uMono = 0

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

            try {
                program = buildProgram(VERTEX_SRC, FRAGMENT_SRC)
            } catch (e: Throwable) {
                // 建不起来就停在这：让宿主回退到原渲染路径，而不是每帧抛异常把 App 带崩
                failed = true
                onFailed(e)
                return
            }
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")
            uRotation = GLES20.glGetUniformLocation(program, "uRotation")
            uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
            uContrast = GLES20.glGetUniformLocation(program, "uContrast")
            uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
            uTemperature = GLES20.glGetUniformLocation(program, "uTemperature")
            uTint = GLES20.glGetUniformLocation(program, "uTint")
            uSmooth = GLES20.glGetUniformLocation(program, "uSmooth")
            uVignette = GLES20.glGetUniformLocation(program, "uVignette")
            uShadowTint = GLES20.glGetUniformLocation(program, "uShadowTint")
            uHighlightTint = GLES20.glGetUniformLocation(program, "uHighlightTint")
            uGamma = GLES20.glGetUniformLocation(program, "uGamma")
            uFade = GLES20.glGetUniformLocation(program, "uFade")
            uMono = GLES20.glGetUniformLocation(program, "uMono")
            aPos = GLES20.glGetAttribLocation(program, "aPos")

            val bb = ByteBuffer.allocateDirect(QUAD.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            bb.put(QUAD).position(0)
            quadBuf = bb
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return

            // 有新帧才更新纹理；参数变化时没有新帧，直接拿上一次的纹理重绘即可
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

            if (failed || program == 0 || !hasFrame) return

            val p = params
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

            // 磨皮取样步长：偏移是加在 vUv（屏幕空间）上的，而旋转 90/270 后屏幕上的
            // 横竖与画面的横竖对调，步长分量也要跟着换 —— 不换的话模糊会被沿一个方向拉长。
            // 帧尺寸未知时按 1080p 给（只看磨皮强弱的观感，不影响画面位置）
            val fw = if (frameW > 0) frameW else 1920
            val fh = if (frameH > 0) frameH else 1080
            val (dw, dh) = if (rotation % 180 == 90) fh to fw else fw to fh
            GLES20.glUniform2f(uTexelSize, 1f / dw, 1f / dh)
            GLES20.glUniform1f(uRotation, (rotation % 360 / 90).toFloat())

            GLES20.glUniform1f(uBrightness, p.brightness)
            GLES20.glUniform1f(uContrast, p.contrast)
            GLES20.glUniform1f(uSaturation, p.saturation)
            GLES20.glUniform1f(uTemperature, p.temperature)
            GLES20.glUniform1f(uTint, p.tint)
            GLES20.glUniform1f(uSmooth, p.smooth)
            GLES20.glUniform1f(uVignette, p.vignette)
            GLES20.glUniform3f(uShadowTint, p.shadowTintR, p.shadowTintG, p.shadowTintB)
            GLES20.glUniform3f(
                uHighlightTint,
                p.highlightTintR, p.highlightTintG, p.highlightTintB,
            )
            GLES20.glUniform1f(uGamma, p.gamma)
            GLES20.glUniform1f(uFade, p.fade)
            GLES20.glUniform1f(uMono, p.mono)

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
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("着色器链接失败: $log")
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
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("着色器编译失败: $log")
            }
            return s
        }

        /** 全屏四边形（triangle strip），位置直接就是裁剪空间坐标 */
        private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private val VERTEX_SRC = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                // y 向上（和 SurfaceTexture 变换矩阵同一套坐标约定），
                // aPos.y = -1 是屏幕下边，对应 uv.y = 0
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        /**
         * 调色着色器：移植自参考项目 dkfilter，只加了"画面旋转"与取样步长两处。
         * 处理顺序：磨皮 → 亮度 → 对比度 → 饱和度 → 色温/色调 → 分离色调 → gamma
         *          → 褪色 → 单色 → 暗角
         */
        private val FRAGMENT_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;

            varying vec2 vUv;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTexMatrix;
            uniform vec2 uTexelSize;
            uniform float uRotation;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uSmooth;
            uniform float uVignette;
            uniform vec3 uShadowTint;
            uniform vec3 uHighlightTint;
            uniform float uGamma;
            uniform float uFade;
            uniform float uMono;

            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            // 画面旋转：把"屏幕上的坐标"换算回"未旋转画面里的坐标"。
            // uRotation 以 90° 为单位（0/1/2/3），正值 = 顺时针，与宿主的旋转按钮同义。
            // 推导：旋转 90° 后画面的左上角出现在屏幕右上角 ⇒ 屏幕 (1,1) 取源 (0,1)。
            vec2 unrotate(vec2 o) {
                if (uRotation < 0.5) return o;
                if (uRotation < 1.5) return vec2(1.0 - o.y, o.x);
                if (uRotation < 2.5) return vec2(1.0 - o.x, 1.0 - o.y);
                return vec2(o.y, 1.0 - o.x);
            }

            // SurfaceTexture 的变换矩阵作用在"显示空间"的纹理坐标上，把屏幕坐标转成
            // 实际纹理的采样坐标。
            //
            // ⚠️ 必须除以 t.w，**不能**除以 t.z！uTexMatrix 是 4x4 仿射矩阵，作用在
            //    (u,v,0,1) 上时第 z 行是 (0,0,1,0)，算出来 t.z 恒等于 0，除以它会得到
            //    NaN，纹理采样退化成一块定值（整屏一片均匀灰），而且不报任何错。
            vec3 sampleAt(vec2 o) {
                vec4 t = uTexMatrix * vec4(unrotate(o), 0.0, 1.0);
                return texture2D(sTexture, t.xy / t.w).rgb;
            }

            void main() {
                vec3 c = sampleAt(vUv);

                // ---- 磨皮：3x3 模糊，用局部亮度差做边缘保护 ----
                if (uSmooth > 0.001) {
                    vec2 t = uTexelSize * 1.6;
                    vec3 b = c * 0.25;
                    b += sampleAt(vUv + vec2( t.x,  t.y)) * 0.125;
                    b += sampleAt(vUv + vec2(-t.x,  t.y)) * 0.125;
                    b += sampleAt(vUv + vec2( t.x, -t.y)) * 0.125;
                    b += sampleAt(vUv + vec2(-t.x, -t.y)) * 0.125;
                    b += sampleAt(vUv + vec2( t.x, 0.0)) * 0.0625;
                    b += sampleAt(vUv + vec2(-t.x, 0.0)) * 0.0625;
                    b += sampleAt(vUv + vec2(0.0,  t.y)) * 0.0625;
                    b += sampleAt(vUv + vec2(0.0, -t.y)) * 0.0625;
                    float edge = clamp(abs(luma(c) - luma(b)) * 6.0, 0.0, 1.0);
                    c = mix(c, b, uSmooth * (1.0 - edge * 0.75));
                }

                // ---- 基础影调 ----
                c += uBrightness;
                c = (c - 0.5) * uContrast + 0.5;

                float l = clamp(luma(c), 0.0, 1.0);
                c = mix(vec3(l), c, uSaturation);

                // ---- 色温 / 色调 ----
                c.r *= (1.0 + uTemperature);
                c.b *= (1.0 - uTemperature);
                c.g *= (1.0 + uTint);

                // ---- 分离色调：暗部/亮部分别染色 ----
                float shW = 1.0 - smoothstep(0.0, 0.55, l);
                float hlW = smoothstep(0.45, 1.0, l);
                c += uShadowTint * shW;
                c += uHighlightTint * hlW;

                // ---- gamma ----
                c = pow(max(c, vec3(0.0)), vec3(uGamma));

                // ---- 褪色（提黑压对比，胶片感） ----
                c = mix(c, c * 0.86 + 0.09, uFade);

                // ---- 单色 ----
                c = mix(c, vec3(luma(c)), uMono);

                // ---- 暗角 ----
                if (uVignette > 0.001) {
                    vec2 d = vUv - 0.5;
                    float dist = length(d) * 1.414;
                    c *= 1.0 - uVignette * smoothstep(0.25, 1.05, dist);
                }

                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
