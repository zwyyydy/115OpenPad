package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.playerDataStore by preferencesDataStore(name = "player")

/** 本地源续播表最多记这么多条，超了丢一批，避免无限涨 */
private const val LOCAL_RESUME_MAX = 100

/** 本地源续播表是 `uri → 毫秒` 的 JSON 串；解析失败一律当空表（宁可从头播也不要崩） */
private val localResumeJson = Json { ignoreUnknownKeys = true }

private fun decodeLocalResume(raw: String?): Map<String, Long> = runCatching {
    localResumeJson.decodeFromString<Map<String, Long>>(raw ?: "{}")
}.getOrDefault(emptyMap())

/** 播放器偏好：缓存、手势参数 */
class PlayerPrefs(private val context: Context) {

    val cacheEnabled: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_CACHE_ENABLED] ?: true }
    val cacheMaxMb: Flow<Int> = context.playerDataStore.data.map { it[KEY_CACHE_MB] ?: 1024 }
    val speedBoost: Flow<Float> = context.playerDataStore.data.map { it[KEY_SPEED_BOOST] ?: 2.5f }
    val seekSeconds: Flow<Int> = context.playerDataStore.data.map { it[KEY_SEEK_SECONDS] ?: 10 }
    val subtitlesEnabled: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_SUBS_ENABLED] ?: true }

    /** 外挂字幕字号（sp）：设置页可调，播放器实时生效 */
    val subtitleTextSize: Flow<Float> = context.playerDataStore.data.map { it[KEY_SUB_TEXT_SIZE] ?: 18f }

    /** 字幕垂直位置：距画面底部占画面高度的百分比（0 = 贴底），越大越靠上 */
    val subtitleBottomPercent: Flow<Int> =
        context.playerDataStore.data.map { it[KEY_SUB_BOTTOM_PERCENT] ?: 0 }

    /**
     * 解码方式：true = 软件（CPU）解码优先。
     * 硬件解码在个别片源/设备上会花屏、黑屏或直接起播失败，这时切软解绕过；
     * 默认 false = 硬解优先（省电、发热低）。
     */
    val softwareDecode: Flow<Boolean> =
        context.playerDataStore.data.map { it[KEY_SOFTWARE_DECODE] ?: false }

    // ---- 播放器右上角状态栏（四项独立开关，默认全开）----
    val showClock: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_SHOW_CLOCK] ?: true }
    val showBattery: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_SHOW_BATTERY] ?: true }
    val showNetSpeed: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_SHOW_NET_SPEED] ?: true }
    val showSpecBadge: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_SHOW_SPEC] ?: true }

    suspend fun setShowClock(v: Boolean) = context.playerDataStore.edit { it[KEY_SHOW_CLOCK] = v }
    suspend fun setShowBattery(v: Boolean) = context.playerDataStore.edit { it[KEY_SHOW_BATTERY] = v }
    suspend fun setShowNetSpeed(v: Boolean) = context.playerDataStore.edit { it[KEY_SHOW_NET_SPEED] = v }
    suspend fun setShowSpecBadge(v: Boolean) = context.playerDataStore.edit { it[KEY_SHOW_SPEC] = v }

    /** 常驻底部迷你进度条（控制栏隐藏时显示）；关闭后全屏观看不留任何进度元素 */
    val alwaysShowMiniProgress: Flow<Boolean> =
        context.playerDataStore.data.map { it[KEY_MINI_PROGRESS] ?: true }

    suspend fun setAlwaysShowMiniProgress(v: Boolean) =
        context.playerDataStore.edit { it[KEY_MINI_PROGRESS] = v }

    /**
     * 是否优先用「原盘」档位：直接拿下载直链播原始文件，不经 115 转码。
     * 画质上限最高（逐字节原文件、保留全部音轨/字幕轨），但没有自适应码率、
     * 带宽占用高，所以默认关，只有用户在画质菜单里主动选过才记住。
     */
    val preferOriginal: Flow<Boolean> =
        context.playerDataStore.data.map { it[KEY_PREFER_ORIGINAL] ?: false }

    suspend fun setPreferOriginal(v: Boolean) =
        context.playerDataStore.edit { it[KEY_PREFER_ORIGINAL] = v }

    suspend fun setSubtitlesEnabled(v: Boolean) = context.playerDataStore.edit { it[KEY_SUBS_ENABLED] = v }

    suspend fun setSubtitleTextSize(v: Float) = context.playerDataStore.edit { it[KEY_SUB_TEXT_SIZE] = v }

    suspend fun setSubtitleBottomPercent(v: Int) =
        context.playerDataStore.edit { it[KEY_SUB_BOTTOM_PERCENT] = v }

    suspend fun setSoftwareDecode(v: Boolean) =
        context.playerDataStore.edit { it[KEY_SOFTWARE_DECODE] = v }

    suspend fun setCacheEnabled(v: Boolean) = context.playerDataStore.edit { it[KEY_CACHE_ENABLED] = v }
    suspend fun setCacheMaxMb(v: Int) = context.playerDataStore.edit { it[KEY_CACHE_MB] = v }
    suspend fun setSpeedBoost(v: Float) = context.playerDataStore.edit { it[KEY_SPEED_BOOST] = v }
    suspend fun setSeekSeconds(v: Int) = context.playerDataStore.edit { it[KEY_SEEK_SECONDS] = v }

    // ---- VR 视角（180/360 立体素材实时反投影成平面观看）----

    /**
     * 手动指定的 VR 模式名（`VrMode.name`）。空串 = 没手动指定过，
     * 由分辨率自动猜；也用来记住"用户明确关掉过"，避免每次开片都弹出来。
     */
    val vrMode: Flow<String> = context.playerDataStore.data.map { it[KEY_VR_MODE] ?: "" }

    /** 取右眼（默认左眼。极少数素材左右眼装反，或者主眼是右眼） */
    val vrRightEye: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_VR_RIGHT_EYE] ?: false }

    /** 是否启用陀螺仪跟随（默认关：不是所有场景都想举着手机看） */
    val vrGyro: Flow<Boolean> = context.playerDataStore.data.map { it[KEY_VR_GYRO] ?: false }

    /** 上次的视场角，下次进 VR 沿用 */
    val vrFov: Flow<Float> = context.playerDataStore.data.map { it[KEY_VR_FOV] ?: 90f }

    /** 边缘畸变抑制强度（Pannini d）。0 = 直线透视，1 = 标准 Pannini */
    val vrPanniniD: Flow<Float> =
        context.playerDataStore.data.map { it[KEY_VR_PANNINI] ?: 1.0f }

    /**
     * 竖屏 VR 反投影视窗档位名（`VrWindow.name`）。**只在竖屏生效**，横屏恒满屏。
     * 空串 = 没设过（按满屏走），认不出来的名字也一律当满屏 —— 所以这里不需要
     * 给默认值兜底成某个具体档位，加档位/改名都不会让旧数据落到奇怪的档上。
     */
    val vrWindow: Flow<String> =
        context.playerDataStore.data.map { it[KEY_VR_WINDOW] ?: "" }

    suspend fun setVrMode(v: String) = context.playerDataStore.edit { it[KEY_VR_MODE] = v }
    suspend fun setVrRightEye(v: Boolean) = context.playerDataStore.edit { it[KEY_VR_RIGHT_EYE] = v }
    suspend fun setVrGyro(v: Boolean) = context.playerDataStore.edit { it[KEY_VR_GYRO] = v }
    suspend fun setVrFov(v: Float) = context.playerDataStore.edit { it[KEY_VR_FOV] = v }
    suspend fun setVrPanniniD(v: Float) = context.playerDataStore.edit { it[KEY_VR_PANNINI] = v }
    suspend fun setVrWindow(v: String) = context.playerDataStore.edit { it[KEY_VR_WINDOW] = v }

    // ---- 实验室：视频滤镜 ----

    /**
     * 滤镜总开关（设置页「实验室」）。关着的时候播放器连滤镜按钮都不出现，
     * 渲染也完全走原来的 TextureView 路径。
     */
    val labFilterEnabled: Flow<Boolean> =
        context.playerDataStore.data.map { it[KEY_LAB_FILTER_ENABLED] ?: false }

    /**
     * 滤镜参数串（16 个 float 逗号分隔，见 `FilterParams.toPrefString`）。
     *
     * 这里刻意只当**不透明字符串**存：编解码归播放器那边管（`FilterParams`），
     * data 层不需要认识滤镜有几个参数 —— 以后加参数只改一处，存储层不用动。
     *
     * 参数是**全局**的（不分条目）：滤镜是口味，不是素材属性，
     * 用户挑好「电影感」不该每换一集再调一次。（VR 模式按条目记是因为那跟素材本身有关。）
     */
    val labFilterParams: Flow<String> =
        context.playerDataStore.data.map { it[KEY_LAB_FILTER_PARAMS] ?: "" }

    suspend fun setLabFilterEnabled(v: Boolean) =
        context.playerDataStore.edit { it[KEY_LAB_FILTER_ENABLED] = v }

    suspend fun setLabFilterParams(v: String) =
        context.playerDataStore.edit { it[KEY_LAB_FILTER_PARAMS] = v }

    /**
     * 自定义滤镜模板列表（JSON 串：[{name, params}]）。
     *
     * 与 [labFilterParams] 同款"不透明字符串"分工：编解码归播放器侧
     * （`CustomFilterPresetsCodec`），存储层不认识模板长什么样。
     */
    val labFilterCustomPresets: Flow<String> =
        context.playerDataStore.data.map { it[KEY_LAB_FILTER_CUSTOM_PRESETS] ?: "" }

    suspend fun setLabFilterCustomPresets(v: String) =
        context.playerDataStore.edit { it[KEY_LAB_FILTER_CUSTOM_PRESETS] = v }

    // ---- 实验室：弹幕 ----

    /** 弹幕总开关（设置页「实验室」）。关着时播放器连弹幕按钮都不出现。 */
    val labDanmuEnabled: Flow<Boolean> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_ENABLED] ?: false }

    /**
     * 弹幕源地址：任何**兼容弹弹play 协议**的 HTTP 服务（自托管 danmu_api、
     * 弹弹play 官方、局域网实例都行），**默认留空**，要用弹幕就自己填一个。
     * 写入时去尾斜杠，拼路径的地方就不用二回了。
     */
    val labDanmuApi: Flow<String> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_API] ?: DANMU_API_DEFAULT }

    /** 弹幕铺满屏幕上部多少（百分比：25/50/75/100），全局口味，不分条目 */
    val labDanmuArea: Flow<Int> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_AREA] ?: 50 }

    /** 弹幕字号（sp） */
    val labDanmuTextSize: Flow<Float> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_TEXT_SIZE] ?: 16f }

    /** 弹幕不透明度（0.3~1.0） */
    val labDanmuOpacity: Flow<Float> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_OPACITY] ?: 1f }

    /** 弹幕滚动速度倍率（0.5~2.0，>1 更快） */
    val labDanmuScroll: Flow<Float> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_SCROLL] ?: 1f }

    /** 屏蔽词原始串（逗号/顿号/换行分隔，见 DanmuClient.parseBlocklist） */
    val labDanmuBlocklist: Flow<String> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_BLOCKLIST] ?: "" }

    /** 弹幕密度（25/50/75/100）：按时间轴均匀抽稀，实际只显示这个比例的弹幕 */
    val labDanmuDensity: Flow<Int> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_DENSITY] ?: 100 }

    /** 弹幕缓存时效（天，1~30）：期内同一部片命中本机缓存不再走网络 */
    val labDanmuCacheDays: Flow<Int> =
        context.playerDataStore.data.map { it[KEY_LAB_DANMU_CACHE_DAYS] ?: 7 }

    suspend fun setLabDanmuEnabled(v: Boolean) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_ENABLED] = v }

    suspend fun setLabDanmuApi(v: String) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_API] = v.trim().trimEnd('/') }

    suspend fun setLabDanmuArea(v: Int) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_AREA] = v }

    suspend fun setLabDanmuTextSize(v: Float) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_TEXT_SIZE] = v }

    suspend fun setLabDanmuOpacity(v: Float) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_OPACITY] = v }

    suspend fun setLabDanmuScroll(v: Float) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_SCROLL] = v }

    suspend fun setLabDanmuBlocklist(v: String) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_BLOCKLIST] = v }

    suspend fun setLabDanmuDensity(v: Int) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_DENSITY] = v }

    suspend fun setLabDanmuCacheDays(v: Int) =
        context.playerDataStore.edit { it[KEY_LAB_DANMU_CACHE_DAYS] = v }

    // ---- 本地/外部源的续播位置 ----
    // 云盘片走 115 的观看记录；本地文件（已下载的、外部应用传进来的）没有那个接口，
    // 只能在本机记一份。不做的话 40 分钟的下载片每次进都从头开始，体验像半成品。

    /** 本地文件的续播位置（毫秒）；没有记录返回 0 */
    suspend fun localResumeOf(uri: String): Long =
        decodeLocalResume(context.playerDataStore.data.first()[KEY_LOCAL_RESUME])[uri] ?: 0L

    suspend fun setLocalResume(uri: String, ms: Long) {
        context.playerDataStore.edit { prefs ->
            val next = decodeLocalResume(prefs[KEY_LOCAL_RESUME]) + (uri to ms)
            prefs[KEY_LOCAL_RESUME] = localResumeJson.encodeToString(
                if (next.size <= LOCAL_RESUME_MAX) next
                else next.entries.toList().takeLast(LOCAL_RESUME_MAX).associate { it.key to it.value },
            )
        }
    }

    private companion object {
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val KEY_CACHE_MB = intPreferencesKey("cache_max_mb")
        val KEY_SPEED_BOOST = floatPreferencesKey("speed_boost")
        val KEY_SEEK_SECONDS = intPreferencesKey("seek_seconds")
        val KEY_SUBS_ENABLED = booleanPreferencesKey("subs_enabled")
        val KEY_SUB_TEXT_SIZE = floatPreferencesKey("subtitle_text_size")
        val KEY_SUB_BOTTOM_PERCENT = intPreferencesKey("subtitle_bottom_percent")
        val KEY_SOFTWARE_DECODE = booleanPreferencesKey("software_decode")
        val KEY_MINI_PROGRESS = booleanPreferencesKey("always_show_mini_progress")
        val KEY_SHOW_CLOCK = booleanPreferencesKey("show_clock")
        val KEY_SHOW_BATTERY = booleanPreferencesKey("show_battery")
        val KEY_SHOW_NET_SPEED = booleanPreferencesKey("show_net_speed")
        val KEY_SHOW_SPEC = booleanPreferencesKey("show_spec_badge")
        val KEY_PREFER_ORIGINAL = booleanPreferencesKey("prefer_original")
        val KEY_VR_MODE = stringPreferencesKey("vr_mode")
        val KEY_VR_RIGHT_EYE = booleanPreferencesKey("vr_right_eye")
        val KEY_VR_GYRO = booleanPreferencesKey("vr_gyro")
        val KEY_VR_FOV = floatPreferencesKey("vr_fov")
        val KEY_VR_PANNINI = floatPreferencesKey("vr_pannini_d")
        val KEY_VR_WINDOW = stringPreferencesKey("vr_window")
        val KEY_LAB_FILTER_ENABLED = booleanPreferencesKey("lab_filter_enabled")
        val KEY_LAB_FILTER_PARAMS = stringPreferencesKey("lab_filter_params")
        val KEY_LAB_FILTER_CUSTOM_PRESETS = stringPreferencesKey("lab_filter_custom_presets")
        val KEY_LAB_DANMU_ENABLED = booleanPreferencesKey("lab_danmu_enabled")
        val KEY_LAB_DANMU_API = stringPreferencesKey("lab_danmu_api")
        val KEY_LAB_DANMU_AREA = intPreferencesKey("lab_danmu_area")
        val KEY_LAB_DANMU_TEXT_SIZE = floatPreferencesKey("lab_danmu_text_size")
        val KEY_LAB_DANMU_OPACITY = floatPreferencesKey("lab_danmu_opacity")
        val KEY_LAB_DANMU_SCROLL = floatPreferencesKey("lab_danmu_scroll")
        val KEY_LAB_DANMU_BLOCKLIST = stringPreferencesKey("lab_danmu_blocklist")
        val KEY_LAB_DANMU_DENSITY = intPreferencesKey("lab_danmu_density")
        val KEY_LAB_DANMU_CACHE_DAYS = intPreferencesKey("lab_danmu_cache_days")

        /** 弹幕源默认地址：留空 = 未配置（弹幕源自托管，不预置公共地址） */
        const val DANMU_API_DEFAULT = ""
        val KEY_LOCAL_RESUME = stringPreferencesKey("local_resume")
    }
}
