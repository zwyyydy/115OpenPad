package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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

    suspend fun setSubtitlesEnabled(v: Boolean) = context.playerDataStore.edit { it[KEY_SUBS_ENABLED] = v }

    suspend fun setSubtitleTextSize(v: Float) = context.playerDataStore.edit { it[KEY_SUB_TEXT_SIZE] = v }

    suspend fun setCacheEnabled(v: Boolean) = context.playerDataStore.edit { it[KEY_CACHE_ENABLED] = v }
    suspend fun setCacheMaxMb(v: Int) = context.playerDataStore.edit { it[KEY_CACHE_MB] = v }
    suspend fun setSpeedBoost(v: Float) = context.playerDataStore.edit { it[KEY_SPEED_BOOST] = v }
    suspend fun setSeekSeconds(v: Int) = context.playerDataStore.edit { it[KEY_SEEK_SECONDS] = v }

    private companion object {
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val KEY_CACHE_MB = intPreferencesKey("cache_max_mb")
        val KEY_SPEED_BOOST = floatPreferencesKey("speed_boost")
        val KEY_SEEK_SECONDS = intPreferencesKey("seek_seconds")
        val KEY_SUBS_ENABLED = booleanPreferencesKey("subs_enabled")
        val KEY_SUB_TEXT_SIZE = floatPreferencesKey("subtitle_text_size")
        val KEY_MINI_PROGRESS = booleanPreferencesKey("always_show_mini_progress")
        val KEY_SHOW_CLOCK = booleanPreferencesKey("show_clock")
        val KEY_SHOW_BATTERY = booleanPreferencesKey("show_battery")
        val KEY_SHOW_NET_SPEED = booleanPreferencesKey("show_net_speed")
        val KEY_SHOW_SPEC = booleanPreferencesKey("show_spec_badge")
    }
}
