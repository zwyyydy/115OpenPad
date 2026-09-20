package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerDataStore by preferencesDataStore(name = "player")

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
    }
}
